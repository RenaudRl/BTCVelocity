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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The lists in memory, read from the module's list directory.
 *
 * <p>The store is <em>open by construction</em>: with no file, or only unreadable ones, it knows
 * no address and {@link #classify} answers empty for everything. An absent list must never turn
 * into a refusal. Neither does a non-public address ever get a class, nor a tunnelled one its
 * tunnel's class (see {@link AddressPolicy}).
 *
 * <p>Readers see an immutable snapshot swapped in one write, so a lookup never waits for a reload
 * and never sees a half-loaded list.
 */
public final class ListStore implements IpClassifier {

  /**
   * One list as currently loaded.
   *
   * @param source the list's configuration
   * @param set its networks
   * @param validLines how many entries it held
   * @param invalidLines how many lines were rejected
   * @param loadedAt when it was read
   */
  public record Loaded(
      ListSource source, CidrSet set, int validLines, int invalidLines, Instant loadedAt) {
  }

  private final Path directory;
  private final Logger logger;
  private final Clock clock;
  private final Object writeLock = new Object();
  private final int maxEntries;
  private final boolean useBaseline;
  private final ListQuarantine quarantine;
  private volatile List<Loaded> loaded = List.of();

  /**
   * Creates an empty store that judges a first load against the embedded baseline.
   *
   * @param directory where the list files are kept
   * @param logger where problems are reported
   * @param clock the source of "loaded at"
   * @param maxEntries the ceiling on the entries of one list; a longer one is not loaded
   */
  public ListStore(final Path directory, final Logger logger, final Clock clock,
      final int maxEntries) {
    this(directory, logger, clock, maxEntries, true);
  }

  /**
   * Creates an empty store.
   *
   * @param directory where the list files are kept
   * @param logger where problems are reported
   * @param clock the source of "loaded at"
   * @param maxEntries the ceiling on the entries of one list; a longer one is not loaded
   * @param useBaseline whether a list with no previous version is judged against the embedded
   *     {@link ListBaseline} of its source
   */
  public ListStore(final Path directory, final Logger logger, final Clock clock,
      final int maxEntries, final boolean useBaseline) {
    this.directory = directory;
    this.logger = logger;
    this.clock = clock;
    this.maxEntries = maxEntries;
    this.useBaseline = useBaseline;
    this.quarantine = new ListQuarantine(this, directory, logger, clock);
  }

  /**
   * Whether a parsed list may be trusted: the absolute guards, then the version in use if there is
   * one, otherwise the embedded baseline of the source. The same judgement at start (a file on
   * disk), after a download, and for a list that has no previous version because the directory
   * is not kept between two starts.
   *
   * @param source the list
   * @param candidate what it was parsed into
   * @return why it is refused, or {@code null} if it passes
   */
  public @Nullable String rejectionOf(final ListSource source, final ListParser.Result candidate) {
    final String absolute = ListGuards.absoluteRejection(candidate, this.maxEntries);
    if (absolute != null) {
      return absolute;
    }
    final Loaded previous = loadedFor(source.id()).orElse(null);
    if (previous != null) {
      return ListGuards.relativeRejection(candidate, previous);
    }
    if (!this.useBaseline) {
      return null;
    }
    final Optional<ListBaseline> baseline = ListBaseline.forSource(source.id());
    if (baseline.isEmpty()) {
      this.logger.warn("List '{}' has no embedded baseline (not a default list): its first load"
          + " is only judged by the absolute guards.", source.id());
      return null;
    }
    return baseline.get().rejection(candidate);
  }

  /**
   * The directory holding the list files.
   *
   * @return the list directory
   */
  public Path directory() {
    return this.directory;
  }

  /**
   * The ceiling on the entries of one list.
   *
   * @return the most entries a list may hold and still be loaded
   */
  public int maxEntries() {
    return this.maxEntries;
  }

  /**
   * Loads every list at startup, warning about each one that is missing.
   *
   * @param sources the configured lists
   */
  public void loadAll(final List<ListSource> sources) {
    for (ListSource source : sources) {
      final Path file = this.directory.resolve(source.fileName());
      if (Files.notExists(file)) {
        this.logger.warn("List '{}' ({}) has no file yet ({}): its class is NOT filtered until"
            + " the first download succeeds.", source.id(), source.ipClass().key(), file);
        continue;
      }
      reload(source);
    }
  }

  /**
   * Reads one list from disk and swaps it in. On any failure the list already in memory is kept.
   *
   * @param source the list to (re)load
   * @return {@code true} if the list was replaced
   */
  public boolean reload(final ListSource source) {
    final Path file = this.directory.resolve(source.fileName());
    final ListParser.Result result;
    try {
      result = ListParser.parse(file);
    } catch (IOException unreadable) {
      this.logger.error("List '{}' could not be read from {}: {}. Keeping the previous one.",
          source.id(), file, unreadable.toString());
      return false;
    }
    final String rejection = rejectionOf(source, result);
    if (rejection != null) {
      this.logger.error("List '{}' in {} was rejected ({}). Keeping the previous one, if any.",
          source.id(), file, rejection);
      return false;
    }
    if (result.invalidLines() > 0) {
      this.logger.warn("List '{}': {} valid entries, {} invalid lines ignored (first: {}).",
          source.id(), result.validLines(), result.invalidLines(), result.invalidSamples());
    }
    install(new Loaded(source, result.set(), result.validLines(), result.invalidLines(),
        this.clock.instant()));
    this.logger.info("List '{}' ({}): {} entries loaded from {}.", source.id(),
        source.ipClass().key(), result.validLines(), file);
    return true;
  }

  /** Swaps one list in, in the position of the version it replaces. */
  void install(final Loaded entry) {
    synchronized (this.writeLock) {
      final List<Loaded> next = new ArrayList<>(this.loaded);
      // Replaced in place: the order lists were first loaded in (the configuration order)
      // decides which list names an address that several of them hold.
      final int at = indexOf(next, entry.source().id());
      if (at >= 0) {
        next.set(at, entry);
      } else {
        next.add(entry);
      }
      this.loaded = List.copyOf(next);
    }
  }

  /**
   * The new versions held aside before they may refuse anybody.
   *
   * @return the quarantine
   */
  public ListQuarantine quarantine() {
    return this.quarantine;
  }

  /**
   * What is loaded now.
   *
   * @return the lists in memory
   */
  public List<Loaded> snapshot() {
    return this.loaded;
  }

  private static int indexOf(final List<Loaded> lists, final String id) {
    for (int i = 0; i < lists.size(); i++) {
      if (lists.get(i).source().id().equals(id)) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Whether at least one list is in memory.
   *
   * @return {@code false} while the module cannot judge anything
   */
  public boolean hasAnyList() {
    return !this.loaded.isEmpty();
  }

  @Override
  public Optional<Match> classify(final InetAddress address) {
    final Optional<InetAddress> lookedUp = AddressPolicy.classifiable(address);
    if (lookedUp.isEmpty()) {
      return Optional.empty();
    }
    for (Loaded list : this.loaded) {
      if (list.set().contains(lookedUp.get())) {
        return Optional.of(new Match(list.source().ipClass(), list.source().id()));
      }
    }
    return Optional.empty();
  }

  /**
   * The list currently in use for a source.
   *
   * @param sourceId the id of the source
   * @return the loaded list, or empty if none is loaded for it
   */
  public Optional<Loaded> loadedFor(final String sourceId) {
    final int at = indexOf(this.loaded, sourceId);
    return at < 0 ? Optional.empty() : Optional.of(this.loaded.get(at));
  }
}
