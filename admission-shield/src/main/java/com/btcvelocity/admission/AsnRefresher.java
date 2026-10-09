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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;

/**
 * Keeps the IP-to-ASN file fresh with the steps {@link ListRefresher} takes for an address list:
 * download to a temporary file, parse it, judge it ({@link AsnStore#rejectionOf}), then replace the
 * file atomically, or hold it in quarantine.
 *
 * <p>The file in use is never written to. A download that fails, times out, comes back empty,
 * comes back as something that is not a gzip table (an HTML error page, say), or is judged
 * implausible is thrown away and the last good version stays, in the file and in memory. A new
 * version waits in quarantine only when it can refuse somebody: the version in use is replaced, and
 * the module can refuse somebody on a system ({@link AsnStore#canRefuseAnybody}). Otherwise it is
 * put in use at once.
 *
 * <p>Privacy: this class fetches one public URL. No player address is part of any request.
 */
public final class AsnRefresher {

  private static final String PART_SUFFIX = ".part";

  private final AsnStore store;
  private final ListFetcher fetcher;
  private final Logger logger;
  private final Duration quarantine;
  private final AtomicBoolean running = new AtomicBoolean();

  /**
   * Creates a refresher.
   *
   * @param store the store to replace the database in; also names the directory and the settings
   * @param fetcher how the file is downloaded
   * @param logger where outcomes are reported
   * @param quarantine how long a changed version of a database in use is held aside when systems
   *     are denied; zero puts it in use at once
   */
  public AsnRefresher(final AsnStore store, final ListFetcher fetcher, final Logger logger,
      final Duration quarantine) {
    this.store = store;
    this.fetcher = fetcher;
    this.logger = logger;
    this.quarantine = quarantine;
  }

  /**
   * Downloads the file again. Skipped, with a warning, if another refresh is running, or if a new
   * version already waits in quarantine.
   *
   * @return {@code true} if a newer valid file replaced the one in use
   */
  public boolean refresh() {
    if (!this.running.compareAndSet(false, true)) {
      this.logger.warn("An IP-to-ASN refresh is still running; this one is skipped.");
      return false;
    }
    try {
      return refreshLocked();
    } finally {
      this.running.set(false);
    }
  }

  private boolean refreshLocked() {
    final Path directory = this.store.directory();
    final Path target = this.store.file();
    final AsnSettings settings = this.store.settings();
    if (this.store.holds()) {
      this.logger.info("The IP-to-ASN database has a new version in quarantine: not downloaded"
          + " again until it is settled.");
      return false;
    }
    Path part = null;
    try {
      Files.createDirectories(directory);
      // Unique to this attempt: nothing else can be writing, reading or deleting it.
      part = Files.createTempFile(directory, AsnSettings.FILE_NAME + ".", PART_SUFFIX);
      this.fetcher.fetch(settings.url(), part);
      final AsnParser.Result parsed = AsnParser.parseGzip(part, settings.maxRows());
      final String rejection = this.store.rejectionOf(parsed);
      if (rejection != null) {
        this.logger.error("The IP-to-ASN database downloaded from {} was rejected ({}); keeping"
            + " the last valid one.", settings.url(), rejection);
        return false;
      }
      if (shouldWait(part, target)) {
        final Path candidate = directory.resolve(AsnSettings.FILE_NAME + AsnStore.CANDIDATE_SUFFIX);
        ListRefresher.moveInPlace(part, candidate);
        this.store.hold(parsed, candidate, this.quarantine);
        return false;
      }
      ListRefresher.moveInPlace(part, target);
    } catch (IOException | RuntimeException failure) {
      this.logger.error("The IP-to-ASN database could not be refreshed from {}: {}. Keeping the"
          + " last valid one.", settings.url(), failure.toString());
      return false;
    } finally {
      if (part != null) {
        deleteQuietly(part);
      }
    }
    return this.store.reload();
  }

  /** A changed version waits when it could refuse somebody; an unchanged one never does. */
  private boolean shouldWait(final Path part, final Path target) {
    if (this.quarantine.isZero() || !this.store.canRefuseAnybody() || !this.store.isLoaded()) {
      return false;
    }
    try {
      return Files.mismatch(part, target) != -1;
    } catch (IOException missingOrUnreadable) {
      return true;
    }
  }

  private static void deleteQuietly(final Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException ignored) {
      // Purged at the next start; it is never read, the name is unique to one attempt.
    }
  }
}
