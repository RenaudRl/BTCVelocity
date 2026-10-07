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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;

/**
 * Keeps the list files fresh: download to a temporary file, check it, then replace atomically.
 *
 * <p>The file in use is never written to. A download that fails, times out, comes back empty or
 * comes back as something that is not a list (an HTML error page, say) is thrown away and the
 * last valid list stays in place, in the file and in memory. The proxy therefore never runs on
 * a truncated list, and never loses a good one to a bad day at the host.
 *
 * <p>Privacy: this class fetches public lists by URL. No player address is part of any request.
 */
public final class ListRefresher {

  private static final String PART_SUFFIX = ".part";

  private final ListStore store;
  private final ListFetcher fetcher;
  private final Logger logger;
  private final AtomicBoolean running = new AtomicBoolean();

  /**
   * Creates a refresher.
   *
   * @param store the store to reload after a list was replaced; also names the directory and the
   *     ceiling on the entries of one list
   * @param fetcher how lists are downloaded
   * @param logger where outcomes are reported
   */
  public ListRefresher(final ListStore store, final ListFetcher fetcher, final Logger logger) {
    this.store = store;
    this.fetcher = fetcher;
    this.logger = logger;
  }

  /**
   * Refreshes every list. One list failing does not stop the others.
   *
   * @param sources the lists to refresh
   * @return how many were replaced
   */
  public int refreshAll(final List<ListSource> sources) {
    if (!this.running.compareAndSet(false, true)) {
      this.logger.warn("A list refresh is still running; this one is skipped.");
      return 0;
    }
    try {
      int replaced = 0;
      for (ListSource source : sources) {
        if (refreshLocked(source)) {
          replaced++;
        }
      }
      return replaced;
    } finally {
      this.running.set(false);
    }
  }

  /**
   * Refreshes one list. Skipped, with a warning, if another refresh is already running: two
   * downloads must never race to replace the same files.
   *
   * @param source the list
   * @return {@code true} if a newer valid list replaced the previous one
   */
  public boolean refresh(final ListSource source) {
    if (!this.running.compareAndSet(false, true)) {
      this.logger.warn("A list refresh is still running; the refresh of '{}' is skipped.",
          source.id());
      return false;
    }
    try {
      return refreshLocked(source);
    } finally {
      this.running.set(false);
    }
  }

  private boolean refreshLocked(final ListSource source) {
    final Path directory = this.store.directory();
    final Path target = directory.resolve(source.fileName());
    Path part = null;
    try {
      Files.createDirectories(directory);
      // Unique to this attempt: nothing else can be writing, reading or deleting it.
      part = Files.createTempFile(directory, source.fileName() + ".", PART_SUFFIX);
      this.fetcher.fetch(source.url(), part);
      final ListParser.Result parsed = ListParser.parse(part);
      final String rejection = this.store.rejectionOf(source, parsed);
      if (rejection != null) {
        this.logger.error("List '{}' downloaded from {} was rejected ({}); keeping the last valid"
            + " list.", source.id(), source.url(), rejection);
        return false;
      }
      moveInPlace(part, target);
    } catch (IOException | RuntimeException failure) {
      this.logger.error("List '{}' could not be refreshed from {}: {}. Keeping the last valid"
          + " list.", source.id(), source.url(), failure.toString());
      return false;
    } finally {
      if (part != null) {
        deleteQuietly(part);
      }
    }
    return this.store.reload(source);
  }

  /**
   * How long to wait before this list's next download, judged from the age of its file: none when
   * the file is missing or older than the interval, the rest of the interval otherwise.
   *
   * @param directory the list directory
   * @param source the list
   * @param interval the refresh interval
   * @param clock the source of "now"
   * @return the wait; zero when a download is due now
   */
  public static Duration delayBeforeFirstRefresh(
      final Path directory, final ListSource source, final Duration interval, final Clock clock) {
    final Path file = directory.resolve(source.fileName());
    try {
      final Instant modified = Files.getLastModifiedTime(file).toInstant();
      final Duration age = Duration.between(modified, clock.instant());
      if (age.isNegative()) {
        return interval;
      }
      return age.compareTo(interval) >= 0 ? Duration.ZERO : interval.minus(age);
    } catch (IOException missing) {
      return Duration.ZERO;
    }
  }

  private static void moveInPlace(final Path part, final Path target) throws IOException {
    try {
      Files.move(part, target, StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException notAtomic) {
      Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  /**
   * Deletes the temporary files a killed process may have left behind. Called at startup, before
   * any refresh can run: each attempt names its own file, so none is ever reused.
   *
   * @return how many were deleted
   */
  public int purgeLeftovers() {
    final Path directory = this.store.directory();
    if (Files.notExists(directory)) {
      return 0;
    }
    int deleted = 0;
    try (DirectoryStream<Path> leftovers =
        Files.newDirectoryStream(directory, "*" + PART_SUFFIX)) {
      for (Path leftover : leftovers) {
        if (deleteQuietly(leftover)) {
          deleted++;
        }
      }
    } catch (IOException unreadable) {
      this.logger.warn("Could not look for leftover downloads in {}: {}", directory,
          unreadable.toString());
    }
    return deleted;
  }

  private static boolean deleteQuietly(final Path file) {
    try {
      return Files.deleteIfExists(file);
    } catch (IOException ignored) {
      // Purged at the next start; it is never read, the name is unique to one attempt.
      return false;
    }
  }
}
