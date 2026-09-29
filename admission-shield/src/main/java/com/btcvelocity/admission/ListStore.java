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
import org.slf4j.Logger;

/**
 * The lists in memory, read from the module's list directory.
 *
 * <p>The store is <em>open by construction</em>: with no file, or only unreadable ones, it knows
 * no address and {@link #classify} answers empty for everything. An absent list must never turn
 * into a refusal.
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
  private volatile List<Loaded> loaded = List.of();

  /**
   * Creates an empty store.
   *
   * @param directory where the list files are kept
   * @param logger where problems are reported
   * @param clock the source of "loaded at"
   */
  public ListStore(final Path directory, final Logger logger, final Clock clock) {
    this.directory = directory;
    this.logger = logger;
    this.clock = clock;
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
    if (result.validLines() == 0) {
      this.logger.error("List '{}' in {} holds no valid entry ({} bad lines). Keeping the"
          + " previous one.", source.id(), file, result.invalidLines());
      return false;
    }
    if (result.invalidLines() > 0) {
      this.logger.warn("List '{}': {} valid entries, {} invalid lines ignored (first: {}).",
          source.id(), result.validLines(), result.invalidLines(), result.invalidSamples());
    }
    final Loaded entry = new Loaded(source, result.set(), result.validLines(),
        result.invalidLines(), this.clock.instant());
    synchronized (this.writeLock) {
      final List<Loaded> next = new ArrayList<>(this.loaded);
      // Replaced in place: the order lists were first loaded in (the configuration order)
      // decides which list names an address that several of them hold.
      final int at = indexOf(next, source.id());
      if (at >= 0) {
        next.set(at, entry);
      } else {
        next.add(entry);
      }
      this.loaded = List.copyOf(next);
    }
    this.logger.info("List '{}' ({}): {} entries loaded from {}.", source.id(),
        source.ipClass().key(), result.validLines(), file);
    return true;
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
    for (Loaded list : this.loaded) {
      if (list.set().contains(address)) {
        return Optional.of(new Match(list.source().ipClass(), list.source().id()));
      }
    }
    return Optional.empty();
  }
}
