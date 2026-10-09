/*
 * Copyright (C) 2026 Velocity Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.btcvelocity.admission;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The IP-to-ASN database in memory, with the safeguards the address lists have.
 *
 * <p>It is the {@link ListStore} and the {@link ListQuarantine} of the ASN file, for one file:
 * <ul>
 *   <li><b>open by construction</b>: with no file, or an unreadable one, nothing is known and
 *       {@link #lookup} answers empty for every address; an absent database never refuses
 *       anybody (it only leaves the journal without autonomous systems);</li>
 *   <li><b>judged before trusted</b>: every parsed file passes {@link AsnGuards}, against the
 *       version in use, or against the embedded baseline when there is none;</li>
 *   <li><b>last good version kept</b>: a file that fails is thrown away and the one in memory (and
 *       on disk) stays;</li>
 *   <li><b>quarantine</b>: when {@code asn.deny-asns} is not empty, a changed new version waits next
 *       to the one in use, watched by {@link #shadow()}, before it may refuse anybody, exactly as
 *       an address list does; when nothing is denied, a new version cannot refuse anybody and is
 *       put in use at once.</li>
 * </ul>
 * Readers see an immutable snapshot swapped in one write.
 */
public final class AsnStore implements AsnLookup {

  /** The most distinct players remembered per candidate; the count is a lower bound beyond it. */
  static final int MAX_TRACKED_PLAYERS = ListQuarantine.MAX_TRACKED_PLAYERS;

  /** The suffix of the file a candidate waits in, next to the file in use. */
  static final String CANDIDATE_SUFFIX = ListQuarantine.FILE_SUFFIX;

  /**
   * The database as currently loaded.
   *
   * @param database the ranges
   * @param validLines how many rows the file held
   * @param invalidLines how many lines were rejected
   * @param loadedAt when it was read
   */
  public record Loaded(AsnDatabase database, int validLines, int invalidLines, Instant loadedAt) {
  }

  /**
   * A new version in quarantine.
   *
   * @param loaded the version, parsed
   * @param file where its text waits
   * @param releaseAt when it may be promoted
   */
  public record Pending(Loaded loaded, Path file, Instant releaseAt) {
  }

  /**
   * The version waiting, with how many players it would have refused in addition so far.
   *
   * @param pending the version
   * @param extraPlayers the players counted
   */
  public record Status(Pending pending, int extraPlayers) {
  }

  private record Entry(Pending pending, Set<UUID> extraPlayers) {
  }

  private final Path directory;
  private final Logger logger;
  private final Clock clock;
  private final AsnSettings settings;
  private final boolean useBaseline;
  private final boolean guardKeywordSystems;
  private final Object writeLock = new Object();
  private volatile @Nullable Loaded loaded;
  private volatile @Nullable Entry waiting;
  private volatile @Nullable Wide wide;

  /**
   * Creates an empty store that judges a first load against the embedded baseline.
   *
   * @param directory where the database file is kept (the list directory)
   * @param logger where problems are reported
   * @param clock the source of "loaded at"
   * @param settings the {@code asn} section: the ceiling on rows and the denied systems
   */
  public AsnStore(final Path directory, final Logger logger, final Clock clock,
      final AsnSettings settings) {
    this(directory, logger, clock, settings, true);
  }

  /**
   * Creates an empty store.
   *
   * @param directory where the database file is kept (the list directory)
   * @param logger where problems are reported
   * @param clock the source of "loaded at"
   * @param settings the {@code asn} section: the ceiling on rows and the denied systems
   * @param useBaseline whether a database with no previous version is judged against
   *     {@link AsnGuards#EMBEDDED}
   */
  public AsnStore(final Path directory, final Logger logger, final Clock clock,
      final AsnSettings settings, final boolean useBaseline) {
    this(directory, logger, clock, settings, useBaseline, false);
  }

  /**
   * Creates an empty store.
   *
   * @param directory where the database file is kept (the list directory)
   * @param logger where problems are reported
   * @param clock the source of "loaded at"
   * @param settings the {@code asn} section: the ceiling on rows and the denied systems
   * @param useBaseline whether a database with no previous version is judged against
   *     {@link AsnGuards#EMBEDDED}
   * @param guardKeywordSystems whether the systems named by {@code asn.observe-keywords} are held
   *     to the same coverage limits as the denied ones, and to the quarantine: true when the rate
   *     bound groups hosting systems into one bucket, where a system the file stretches over the
   *     internet would starve everybody in it
   */
  public AsnStore(final Path directory, final Logger logger, final Clock clock,
      final AsnSettings settings, final boolean useBaseline, final boolean guardKeywordSystems) {
    this.directory = directory;
    this.logger = logger;
    this.clock = clock;
    this.settings = settings;
    this.useBaseline = useBaseline;
    this.guardKeywordSystems = guardKeywordSystems;
  }

  /**
   * The settings the store judges files with.
   *
   * @return the {@code asn} section
   */
  public AsnSettings settings() {
    return this.settings;
  }

  /**
   * The directory holding the database file.
   *
   * @return the list directory
   */
  public Path directory() {
    return this.directory;
  }

  /**
   * The file in use.
   *
   * @return the path of {@value AsnSettings#FILE_NAME} in the list directory
   */
  public Path file() {
    return this.directory.resolve(AsnSettings.FILE_NAME);
  }

  /**
   * What is loaded now.
   *
   * @return the database in use, or empty while none is
   */
  public Optional<Loaded> current() {
    return Optional.ofNullable(this.loaded);
  }

  /**
   * Whether the database in use is loaded.
   *
   * @return {@code false} while the module cannot name any autonomous system
   */
  public boolean isLoaded() {
    return this.loaded != null;
  }

  @Override
  public Optional<AsnDatabase.Info> lookup(final InetAddress address) {
    final Loaded current = this.loaded;
    return current == null ? Optional.empty() : lookupIn(current.database(), address);
  }

  /** The systems found above a share in one database, kept until the database or the share moves. */
  private record Wide(AsnDatabase database, double percent, Set<Long> systems) {
  }

  @Override
  public boolean coversMoreThan(final long asNumber, final double percent) {
    final Loaded current = this.loaded;
    if (current == null) {
      return false;
    }
    Wide wide = this.wide;
    if (wide == null || wide.database() != current.database() || wide.percent() != percent) {
      wide = new Wide(current.database(), percent,
          current.database().systemsCoveringMoreThan(percent));
      this.wide = wide;
    }
    return wide.systems().contains(asNumber);
  }

  private static Optional<AsnDatabase.Info> lookupIn(final AsnDatabase database,
      final InetAddress address) {
    return AddressPolicy.classifiable(address).flatMap(database::lookup);
  }

  /**
   * Whether a parsed file may be trusted: the absolute guards, then the version in use if there is
   * one, otherwise the embedded baseline. The same judgement at start (a file on disk), after a
   * download, and for a file that has no previous version because the directory is not kept
   * between two starts.
   *
   * @param candidate what the file was parsed into
   * @return why it is refused, or {@code null} if it passes
   */
  public @Nullable String rejectionOf(final AsnParser.Result candidate) {
    final Set<Long> guarded = guardedSystems(candidate.database());
    final String absolute = AsnGuards.absoluteRejection(candidate, this.settings.maxRows(), guarded);
    if (absolute != null) {
      return absolute;
    }
    final Loaded previous = this.loaded;
    if (previous != null) {
      final Set<Long> both = new HashSet<>(guarded);
      both.addAll(guardedSystems(previous.database()));
      return AsnGuards.relativeRejection(candidate.database(), previous.database(), both);
    }
    return this.useBaseline ? AsnGuards.EMBEDDED.rejection(candidate.database()) : null;
  }

  /**
   * The systems whose coverage the guards bound in this version of the file: the denied ones and,
   * when the rate bound groups by system, those named by a keyword.
   */
  private Set<Long> guardedSystems(final AsnDatabase database) {
    if (!this.guardKeywordSystems || this.settings.observeKeywords().isEmpty()) {
      return this.settings.denyAsns();
    }
    final Set<Long> guarded = new HashSet<>(this.settings.denyAsns());
    guarded.addAll(database.systemsWhere(info -> this.settings.isHosting(info)));
    return guarded;
  }

  /**
   * Whether a new version of the file could make the module refuse somebody, and so is worth
   * watching in quarantine: a system is denied, or systems named by a keyword are grouped into one
   * bucket of the rate bound.
   *
   * @return {@code true} if a wrong file could cost players a connection
   */
  public boolean canRefuseAnybody() {
    return !this.settings.denyAsns().isEmpty()
        || this.guardKeywordSystems && !this.settings.observeKeywords().isEmpty();
  }

  /**
   * Reads the file from disk at startup, warning if it is missing. On any failure nothing is
   * loaded, and the module stays open.
   */
  public void loadAtStart() {
    final Path file = file();
    if (Files.notExists(file)) {
      this.logger.warn("The IP-to-ASN database ({}) has no file yet: autonomous systems are NOT"
          + " named, and asn.deny-asns refuses nothing, until the first download succeeds.", file);
      return;
    }
    reload();
  }

  /**
   * Reads the file from disk and swaps it in. On any failure the version in memory is kept.
   *
   * @return {@code true} if the database was replaced
   */
  public boolean reload() {
    final Path file = file();
    final AsnParser.Result result;
    try {
      result = AsnParser.parseGzip(file, this.settings.maxRows());
    } catch (IOException | RuntimeException | OutOfMemoryError unreadable) {
      this.logger.error("The IP-to-ASN database could not be read from {}: {}. Keeping the"
          + " previous one.", file, unreadable.toString());
      return false;
    }
    final String rejection = rejectionOf(result);
    if (rejection != null) {
      this.logger.error("The IP-to-ASN database in {} was rejected ({}). Keeping the previous one,"
          + " if any.", file, rejection);
      return false;
    }
    if (result.invalidLines() > 0) {
      this.logger.warn("IP-to-ASN database: {} valid rows, {} invalid lines ignored (first: {}).",
          result.validLines(), result.invalidLines(), result.printableSamples());
    }
    install(new Loaded(result.database(), result.validLines(), result.invalidLines(),
        this.clock.instant()));
    this.logger.info("IP-to-ASN database: {} rows loaded from {} ({} IPv4 ranges, {} IPv6 ranges,"
        + " {} systems, about {} MiB).", result.validLines(), file,
        result.database().ipv4RangeCount(), result.database().ipv6RangeCount(),
        result.database().systemCount(), result.database().approximateBytes() >> 20);
    return true;
  }

  /** Swaps a version in. */
  void install(final Loaded entry) {
    synchronized (this.writeLock) {
      this.loaded = entry;
    }
  }

  /**
   * Puts a new version in quarantine, replacing any that was waiting.
   *
   * @param result the new version, already judged by the guards
   * @param candidateFile where its text was put
   * @param hold how long it waits
   */
  public void hold(final AsnParser.Result result, final Path candidateFile, final Duration hold) {
    final Instant now = this.clock.instant();
    final Loaded version = new Loaded(result.database(), result.validLines(),
        result.invalidLines(), now);
    this.waiting = new Entry(new Pending(version, candidateFile, now.plus(hold)),
        ConcurrentHashMap.newKeySet());
    this.logger.info("IP-to-ASN database: a new version ({} rows) waits in quarantine until {}."
        + " The version in use keeps deciding; what the new one would refuse in addition is only"
        + " journaled (would_refuse).", result.validLines(), now.plus(hold));
  }

  /**
   * Whether a new version is waiting.
   *
   * @return {@code true} if one is
   */
  public boolean holds() {
    return this.waiting != null;
  }

  /**
   * The version waiting.
   *
   * @return its status, or empty when none waits
   */
  public Optional<Status> waitingStatus() {
    final Entry entry = this.waiting;
    return entry == null ? Optional.empty()
        : Optional.of(new Status(entry.pending(), entry.extraPlayers().size()));
  }

  /**
   * The database as it would be with the waiting version in use: what the new version would
   * decide. Empty for every address while nothing waits.
   *
   * @return the lookup of the waiting version
   */
  public AsnLookup shadow() {
    return address -> {
      final Entry entry = this.waiting;
      return entry == null ? Optional.empty()
          : lookupIn(entry.pending().loaded().database(), address);
    };
  }

  /**
   * Counts an authenticated player the waiting version would refuse in addition.
   *
   * @param player the player's UUID
   * @return {@code true} the first time this player is counted for this version
   */
  public boolean noteWouldRefuse(final UUID player) {
    final Entry entry = this.waiting;
    if (entry == null || entry.extraPlayers().size() >= MAX_TRACKED_PLAYERS) {
      return false;
    }
    return entry.extraPlayers().add(player);
  }

  /**
   * Ends the quarantine if its time is up: promotes the version or throws it away.
   *
   * @param maxExtraPlayers the most players the version may have refused in addition and still be
   *     promoted
   * @return what became of the version, or empty when none was due
   */
  public Optional<ListQuarantine.Outcome> settleDue(final int maxExtraPlayers) {
    final Entry entry = this.waiting;
    if (entry == null || this.clock.instant().isBefore(entry.pending().releaseAt())) {
      return Optional.empty();
    }
    synchronized (this.writeLock) {
      if (this.waiting != entry) {
        return Optional.empty();
      }
      this.waiting = null;
    }
    return Optional.of(settle(entry, maxExtraPlayers));
  }

  private ListQuarantine.Outcome settle(final Entry entry, final int maxExtraPlayers) {
    final Loaded version = entry.pending().loaded();
    final int extra = entry.extraPlayers().size();
    boolean promoted = false;
    if (extra <= maxExtraPlayers) {
      promoted = promote(entry.pending());
    } else {
      this.logger.error("IP-to-ASN database: the new version ({} rows) is REJECTED after"
          + " quarantine: it would have refused {} authenticated player(s) in addition (limit"
          + " {}). The version in use stays.", version.validLines(), extra, maxExtraPlayers);
    }
    if (!promoted) {
      deleteQuietly(entry.pending().file());
    }
    return new ListQuarantine.Outcome(this.settings.source(), promoted, extra, maxExtraPlayers,
        version.validLines());
  }

  private boolean promote(final Pending pending) {
    try {
      ListRefresher.moveInPlace(pending.file(), file());
    } catch (IOException failure) {
      this.logger.error("IP-to-ASN database: the quarantined version could not be put in place:"
          + " {}. The version in use stays.", failure.toString());
      return false;
    }
    final Loaded version = pending.loaded();
    install(new Loaded(version.database(), version.validLines(), version.invalidLines(),
        this.clock.instant()));
    this.logger.info("IP-to-ASN database: the new version ({} rows) left quarantine and is now in"
        + " use.", version.validLines());
    return true;
  }

  private static void deleteQuietly(final Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException ignored) {
      // Purged at the next start; a candidate file is never read.
    }
  }
}
