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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;

/**
 * Holds a new version of a list aside for a while before it may refuse anybody.
 *
 * <p>The guards on a single download cannot see a poisoning that grows by steps, each within the
 * limits (coverage doubling at every refresh, say). Quarantine adds time: a new version that
 * passed the guards is kept next to the one in use, in memory and in {@code <file>.candidate}.
 * While it waits, the version in use keeps deciding, and the new one only <em>watches</em>: each
 * authenticated player it would refuse <em>in addition</em> is counted (once) and journaled as
 * {@code would_refuse}. When the time is up the version is promoted if that count is within the
 * limit, and rejected otherwise: the poisoned list is then thrown away and the staff is told.
 *
 * <p>The state is in memory: a restart forgets it, deletes the candidate file, and the next
 * download starts a new quarantine. The version in use is never touched while the candidate waits.
 */
public final class ListQuarantine {

  /** The most distinct players remembered per candidate; the count is a lower bound beyond it. */
  static final int MAX_TRACKED_PLAYERS = 1_000;

  /** The suffix of the file a candidate waits in, next to the file in use. */
  static final String FILE_SUFFIX = ".candidate";

  /**
   * One new version in quarantine.
   *
   * @param list the version, parsed
   * @param file where its text waits
   * @param releaseAt when it may be promoted
   */
  public record Pending(ListStore.Loaded list, Path file, Instant releaseAt) {
  }

  /**
   * How a quarantine ended.
   *
   * @param source the list
   * @param promoted whether the new version now is the one in use
   * @param extraPlayers how many authenticated players it would have refused in addition
   * @param maxExtraPlayers the limit it was judged against
   * @param validLines the entries of the new version
   */
  public record Outcome(
      ListSource source, boolean promoted, int extraPlayers, int maxExtraPlayers, int validLines) {

    /**
     * The sentence for the staff online.
     *
     * @return the message
     */
    public String staffMessage() {
      return "[Admission] Liste " + this.source.id() + " : la nouvelle version ("
          + this.validLines + " entrées) est REJETÉE après quarantaine, "
          + this.extraPlayers + " joueur(s) authentifié(s) auraient été refusés en plus (seuil "
          + this.maxExtraPlayers + "). L'ancienne version reste en service."
          + " Détail : lignes would_refuse du journal.";
    }
  }

  private record Entry(Pending pending, Set<UUID> extraPlayers) {
  }

  private final ListStore store;
  private final Path directory;
  private final Logger logger;
  private final Clock clock;
  private final Map<String, Entry> entries = new ConcurrentHashMap<>();

  ListQuarantine(final ListStore store, final Path directory, final Logger logger,
      final Clock clock) {
    this.store = store;
    this.directory = directory;
    this.logger = logger;
    this.clock = clock;
  }

  /**
   * Puts a new version in quarantine, replacing any that was waiting for the same list.
   *
   * @param source the list
   * @param result the new version, already judged by the guards
   * @param candidateFile where its text was put
   * @param hold how long it waits
   */
  public void hold(final ListSource source, final ListParser.Result result,
      final Path candidateFile, final Duration hold) {
    final Instant now = this.clock.instant();
    final ListStore.Loaded list = new ListStore.Loaded(source, result.set(), result.validLines(),
        result.invalidLines(), now);
    this.entries.put(source.id(),
        new Entry(new Pending(list, candidateFile, now.plus(hold)), ConcurrentHashMap.newKeySet()));
    this.logger.info("List '{}': a new version ({} entries) waits in quarantine until {}. The"
        + " version in use keeps deciding; what the new one would refuse in addition is only"
        + " journaled (would_refuse).", source.id(), result.validLines(), now.plus(hold));
  }

  /**
   * Whether a new version of this list is waiting.
   *
   * @param sourceId the id of the list
   * @return {@code true} if one is
   */
  public boolean holds(final String sourceId) {
    return this.entries.containsKey(sourceId);
  }

  /**
   * Whether any version is waiting.
   *
   * @return {@code true} if at least one is
   */
  public boolean hasAny() {
    return !this.entries.isEmpty();
  }

  /**
   * One version waiting, with how many players it would have refused in addition so far.
   *
   * @param pending the version
   * @param extraPlayers the players counted
   */
  public record Status(Pending pending, int extraPlayers) {
  }

  /**
   * The versions waiting.
   *
   * @return one status per list
   */
  public List<Status> waiting() {
    final List<Status> waiting = new ArrayList<>();
    for (Entry entry : this.entries.values()) {
      waiting.add(new Status(entry.pending(), entry.extraPlayers().size()));
    }
    return waiting;
  }

  /**
   * The lists as they would be with every waiting version in use: what the new versions would
   * decide. Empty for every address while nothing waits.
   *
   * @return the classifier of the waiting versions
   */
  public IpClassifier shadow() {
    return this::shadowMatch;
  }

  private Optional<IpClassifier.Match> shadowMatch(final InetAddress address) {
    if (this.entries.isEmpty()) {
      return Optional.empty();
    }
    final Optional<InetAddress> lookedUp = AddressPolicy.classifiable(address);
    if (lookedUp.isEmpty()) {
      return Optional.empty();
    }
    for (ListStore.Loaded inUse : this.store.snapshot()) {
      final Entry waiting = this.entries.get(inUse.source().id());
      final CidrSet set = waiting != null ? waiting.pending().list().set() : inUse.set();
      if (set.contains(lookedUp.get())) {
        return Optional.of(new IpClassifier.Match(inUse.source().ipClass(), inUse.source().id()));
      }
    }
    return Optional.empty();
  }

  /**
   * Counts an authenticated player the waiting version of a list would refuse in addition.
   *
   * @param sourceId the list
   * @param player the player's UUID
   * @return {@code true} the first time this player is counted for this version
   */
  public boolean noteWouldRefuse(final String sourceId, final UUID player) {
    final Entry entry = this.entries.get(sourceId);
    if (entry == null || entry.extraPlayers().size() >= MAX_TRACKED_PLAYERS) {
      return false;
    }
    return entry.extraPlayers().add(player);
  }

  /**
   * Ends the quarantines whose time is up: promotes the version or throws it away.
   *
   * @param maxExtraPlayers the most players a version may have refused in addition and still be
   *     promoted
   * @return what became of each version that was due
   */
  public List<Outcome> settleDue(final int maxExtraPlayers) {
    final Instant now = this.clock.instant();
    final List<Outcome> outcomes = new ArrayList<>();
    for (Map.Entry<String, Entry> waiting : List.copyOf(this.entries.entrySet())) {
      final Entry entry = waiting.getValue();
      if (now.isBefore(entry.pending().releaseAt())
          || !this.entries.remove(waiting.getKey(), entry)) {
        continue;
      }
      outcomes.add(settle(entry, maxExtraPlayers));
    }
    return outcomes;
  }

  private Outcome settle(final Entry entry, final int maxExtraPlayers) {
    final ListStore.Loaded list = entry.pending().list();
    final int extra = entry.extraPlayers().size();
    final boolean withinLimit = extra <= maxExtraPlayers;
    boolean promoted = false;
    if (withinLimit) {
      promoted = promote(entry.pending());
    } else {
      this.logger.error("List '{}': the new version ({} entries) is REJECTED after quarantine:"
          + " it would have refused {} authenticated player(s) in addition (limit {}). The"
          + " version in use stays.", list.source().id(), list.validLines(), extra,
          maxExtraPlayers);
    }
    if (!promoted) {
      deleteQuietly(entry.pending().file());
    }
    return new Outcome(list.source(), promoted, extra, maxExtraPlayers, list.validLines());
  }

  private boolean promote(final Pending pending) {
    final ListSource source = pending.list().source();
    try {
      ListRefresher.moveInPlace(pending.file(), this.directory.resolve(source.fileName()));
    } catch (IOException failure) {
      this.logger.error("List '{}': the quarantined version could not be put in place: {}. The"
          + " version in use stays.", source.id(), failure.toString());
      return false;
    }
    this.store.install(new ListStore.Loaded(source, pending.list().set(),
        pending.list().validLines(), pending.list().invalidLines(), this.clock.instant()));
    this.logger.info("List '{}': the new version ({} entries) left quarantine and is now in use.",
        source.id(), pending.list().validLines());
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
