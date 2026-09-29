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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class BoundedCopyTest {

  /** A body that trickles in one byte per read, each read costing one second on the clock. */
  private static InputStream trickle(final int length, final AtomicLong clock) {
    return new InputStream() {
      private int left = length;

      @Override
      public int read() {
        if (this.left == 0) {
          return -1;
        }
        this.left--;
        clock.addAndGet(1_000_000_000L);
        return 'x';
      }

      @Override
      public int read(final byte[] buffer, final int offset, final int count) {
        final int next = read();
        if (next < 0) {
          return -1;
        }
        buffer[offset] = (byte) next;
        return 1;
      }
    };
  }

  @Test
  void aBodyWithinBoundsIsCopiedWhole() throws IOException {
    final byte[] body = "203.0.113.0/24\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    final ByteArrayOutputStream out = new ByteArrayOutputStream();

    BoundedCopy.copy(new ByteArrayInputStream(body), out, body.length, Long.MAX_VALUE,
        () -> 0L);

    assertArrayEquals(body, out.toByteArray());
  }

  @Test
  void aBodyLargerThanTheLimitIsAbandoned() {
    final IOException failure = assertThrows(IOException.class, () -> BoundedCopy.copy(
        new ByteArrayInputStream(new byte[101]), new ByteArrayOutputStream(), 100,
        Long.MAX_VALUE, () -> 0L));

    assertTrue(failure.getMessage().contains("larger than 100"));
  }

  @Test
  void aBodyThatKeepsTricklingPastTheDeadlineIsAbandoned() {
    // Arrange - every read succeeds, so no per-read timeout would ever fire; 30 s allowed
    final AtomicLong clock = new AtomicLong();
    final long deadline = 30_000_000_000L;

    // Act
    final IOException failure = assertThrows(IOException.class, () -> BoundedCopy.copy(
        trickle(1_000, clock), new ByteArrayOutputStream(), 1_000_000, deadline, clock::get));

    // Assert
    assertTrue(failure.getMessage().contains("overall time limit"));
    assertTrue(clock.get() <= 31_000_000_000L, "stopped at the deadline, not at the end");
  }
}
