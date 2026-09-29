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
import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.LongSupplier;

/**
 * Copies a download while bounding both its size and its total duration.
 *
 * <p>An HTTP client's timeout covers the connection and the headers, not a body that then trickles
 * in one byte a minute. The deadline here covers the whole copy: it is checked after every read,
 * and the caller arms a watchdog that closes the stream so that a read blocked on a silent server
 * fails too.
 */
final class BoundedCopy {

  private static final int BUFFER_SIZE = 64 * 1024;

  private BoundedCopy() {
  }

  /**
   * Copies {@code in} to {@code out}.
   *
   * @param in the source
   * @param out the destination
   * @param maxBytes the most that may be copied
   * @param deadlineNanos when the copy must be over, on the {@code nanoClock} scale
   * @param nanoClock the clock, {@code System::nanoTime} in production
   * @throws IOException if the size or the deadline is exceeded, or a stream fails
   */
  static void copy(final InputStream in, final OutputStream out, final long maxBytes,
      final long deadlineNanos, final LongSupplier nanoClock) throws IOException {
    final byte[] buffer = new byte[BUFFER_SIZE];
    long total = 0;
    int read;
    while ((read = in.read(buffer)) != -1) {
      if (nanoClock.getAsLong() - deadlineNanos > 0) {
        throw new IOException("download exceeded its overall time limit, abandoned");
      }
      total += read;
      if (total > maxBytes) {
        throw new IOException("list larger than " + maxBytes + " bytes, download abandoned");
      }
      out.write(buffer, 0, read);
    }
  }
}
