/*
 * Copyright (C) 2018-2026 Velocity Contributors
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

package com.btcvelocity.proxy.security;

import java.net.SocketAddress;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * The size and compression-ratio limits of one connection, applied by the compression decoder
 * to every packet <em>before</em> it inflates it, so a packet that breaks them costs nothing.
 *
 * <p>This is the configurable half of the decompression-bomb defence ({@code [security]} in
 * {@code velocity.toml}). The decoder's own caps (2&nbsp;MiB serverbound, 8&nbsp;MiB clientbound,
 * claimed size checked against the real one) and its bytes-per-second packet limiter are the
 * other half; neither measures the ratio, and the limiter only protects serverbound traffic.</p>
 *
 * <p>A packet that breaks a limit is dropped and counted; the {@code maxViolations}-th one closes
 * the connection. An instance belongs to one channel and is only used from its event loop.</p>
 */
public final class DecompressionGuard {

  private static final Logger LOGGER = LogManager.getLogger(DecompressionGuard.class);

  /** What the decoder must do with the packet it just asked about. */
  public enum Verdict {
    /** The packet is within every limit. */
    ACCEPT,
    /** The packet broke a limit: discard it and keep the connection. */
    DROP,
    /** The packet broke a limit and was the last one tolerated: close the connection. */
    CLOSE
  }

  private final int maxDecompressedSize;
  private final int maxCompressionRatio;
  private final int maxViolations;
  private final int ratioCheckMinDecompressedSize;

  private int violations;

  /**
   * Creates the guard of one connection.
   *
   * @param maxDecompressedSize           the largest decompressed packet allowed, in bytes
   * @param maxCompressionRatio           the largest decompressed:compressed ratio allowed
   * @param maxViolations                 the number of violations after which the connection closes
   * @param ratioCheckMinDecompressedSize the decompressed size, in bytes, from which the ratio is
   *                                      checked; a high ratio on a smaller packet is ordinary
   *                                      traffic (chunk, light and movement data compress far past
   *                                      100:1 while staying tens of kilobytes)
   */
  public DecompressionGuard(final int maxDecompressedSize, final int maxCompressionRatio,
                            final int maxViolations, final int ratioCheckMinDecompressedSize) {
    this.maxDecompressedSize = maxDecompressedSize;
    this.maxCompressionRatio = maxCompressionRatio;
    this.maxViolations = maxViolations;
    this.ratioCheckMinDecompressedSize = ratioCheckMinDecompressedSize;
  }

  /**
   * Checks one packet against the limits and counts it as a violation if it breaks one.
   *
   * @param compressedSize   the size of the frame as received, in bytes
   * @param decompressedSize the size the packet decompresses to (its claimed size), in bytes
   * @param remoteAddress    the peer, for the log line (masked there)
   * @return what to do with the packet
   */
  public Verdict inspect(final int compressedSize, final int decompressedSize,
                         final SocketAddress remoteAddress) {
    final String reason = violationOf(compressedSize, decompressedSize);
    if (reason == null) {
      return Verdict.ACCEPT;
    }

    violations++;
    LOGGER.warn("[DecompressionBomb] {} — violation {}/{} from {}",
        reason, violations, maxViolations, LogAddress.of(remoteAddress));
    if (violations < maxViolations) {
      return Verdict.DROP;
    }

    LOGGER.warn("[DecompressionBomb] Closing connection from {} after {} decompression-bomb violations",
        LogAddress.of(remoteAddress), violations);
    return Verdict.CLOSE;
  }

  private @Nullable String violationOf(final int compressedSize, final int decompressedSize) {
    if (decompressedSize > maxDecompressedSize) {
      return "Decompressed size %,d bytes exceeds maximum %,d bytes"
          .formatted(decompressedSize, maxDecompressedSize);
    }
    if (decompressedSize >= ratioCheckMinDecompressedSize && compressedSize > 0) {
      final double ratio = (double) decompressedSize / (double) compressedSize;
      if (ratio > maxCompressionRatio) {
        return "Compression ratio %.1f:1 (decompressed %,d / compressed %,d) exceeds maximum %d:1"
            .formatted(ratio, decompressedSize, compressedSize, maxCompressionRatio);
      }
    }
    return null;
  }
}
