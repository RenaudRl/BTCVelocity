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

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

/**
 * A Netty {@link ChannelInboundHandlerAdapter} that sits <em>before</em> the compression decoder
 * in the pipeline and records the compressed size of every inbound {@link ByteBuf}.
 *
 * <p>The recorded compressed size is later queried by
 * {@link DecompressionBombHandler} (which sits after the decompressor) to compute the actual
 * compression ratio and detect decompression-bomb attacks.</p>
 *
 * <p>In addition to recording compressed sizes, this monitor performs a lightweight pre-check:
 * if the compressed payload is suspiciously small (below
 * {@link #MIN_SUSPICIOUS_COMPRESSED_SIZE} bytes) while compression is active, a warning is logged.
 * This catches the trivial case of a 1-byte compressed payload that expands into megabytes.</p>
 *
 * <p>The monitor also maintains its own violation counter and can independently close a
 * connection if the violation threshold is exceeded. In normal pipeline wiring, the
 * {@link DecompressionBombHandler} is the primary enforcer and uses
 * {@link #incrementViolations()} to record ratio-based violations through this monitor so that
 * there is a single, authoritative violation count per connection.</p>
 *
 * <p>All mutable state uses thread-safe types ({@link AtomicInteger}, {@code volatile}) to ensure
 * correctness even if the handler is queried from outside the channel's event-loop thread.</p>
 */
public class CompressionRatioMonitor extends ChannelInboundHandlerAdapter {

  private static final Logger LOGGER = LogManager.getLogger(CompressionRatioMonitor.class);

  /**
   * The default maximum decompressed size (8&nbsp;MiB = 8&thinsp;388&thinsp;608 bytes).
   */
  public static final int DEFAULT_MAX_DECOMPRESSED_SIZE = 8 * 1024 * 1024;

  /**
   * The default maximum compression ratio (100:1).
   */
  public static final int DEFAULT_MAX_COMPRESSION_RATIO = 100;

  /**
   * The default number of violations before the connection is closed.
   */
  public static final int DEFAULT_MAX_VIOLATIONS = 3;

  /**
   * Compressed payloads smaller than this (in bytes) are considered suspicious if they later
   * decompress into large buffers. Used only for early warning logging.
   */
  private static final int MIN_SUSPICIOUS_COMPRESSED_SIZE = 16;

  private final int maxDecompressedSize;
  private final int maxCompressionRatio;
  private final int maxViolations;

  /**
   * The compressed size of the most recent inbound {@code ByteBuf}. Marked {@code volatile}
   * because it is read by {@link DecompressionBombHandler} which, although typically on the same
   * event-loop thread, may be queried from other contexts.
   */
  private volatile int lastCompressedSize;

  /**
   * Per-connection violation counter. Thread-safe via {@link AtomicInteger}.
   */
  private final AtomicInteger violations = new AtomicInteger(0);

  /**
   * Creates a new {@code CompressionRatioMonitor} with the specified limits.
   *
   * @param maxDecompressedSize the maximum allowed decompressed payload size, in bytes
   * @param maxCompressionRatio the maximum allowed compression ratio (decompressed:compressed)
   * @param maxViolations       the number of violations before the connection is closed
   */
  public CompressionRatioMonitor(final int maxDecompressedSize,
                                 final int maxCompressionRatio,
                                 final int maxViolations) {
    this.maxDecompressedSize = maxDecompressedSize;
    this.maxCompressionRatio = maxCompressionRatio;
    this.maxViolations = maxViolations;
  }

  /**
   * Creates a new {@code CompressionRatioMonitor} with default limits.
   */
  public CompressionRatioMonitor() {
    this(DEFAULT_MAX_DECOMPRESSED_SIZE, DEFAULT_MAX_COMPRESSION_RATIO, DEFAULT_MAX_VIOLATIONS);
  }

  @Override
  public void channelRead(final @NotNull ChannelHandlerContext ctx, final @NotNull Object msg) {
    if (msg instanceof ByteBuf buf) {
      // Record the compressed size before the decompressor processes this buffer.
      lastCompressedSize = buf.readableBytes();

      // Early warning: a very small compressed payload is a classic bomb signature.
      // We do not block it here (the ratio check after decompression will catch it),
      // but we log a heads-up so operators can correlate with downstream violations.
      if (lastCompressedSize > 0 && lastCompressedSize < MIN_SUSPICIOUS_COMPRESSED_SIZE) {
        LOGGER.debug("[CompressionRatioMonitor] Suspiciously small compressed payload "
                + "of {} bytes from {} — potential decompression bomb",
            lastCompressedSize, ctx.channel().remoteAddress());
      }
    }

    // Forward the message downstream to the compression decoder.
    ctx.fireChannelRead(msg);
  }

  /**
   * Gets the compressed size of the most recent inbound {@code ByteBuf}.
   *
   * <p>This value is read by {@link DecompressionBombHandler} to compute the compression ratio
   * after decompression.</p>
   *
   * @return the compressed size in bytes, or {@code 0} if no buffer has been observed yet
   */
  public int getLastCompressedSize() {
    return lastCompressedSize;
  }

  /**
   * Atomically increments the per-connection violation counter and returns the new value.
   *
   * <p>This method is intended to be called by {@link DecompressionBombHandler} when it detects
   * a compression-ratio or size violation, so that a single authoritative counter is
   * maintained.</p>
   *
   * @return the violation count after incrementing
   */
  public int incrementViolations() {
    return violations.incrementAndGet();
  }

  /**
   * Gets the current violation count for this connection.
   *
   * @return the number of violations recorded so far
   */
  public int getViolationCount() {
    return violations.get();
  }

  /**
   * Atomically increments the violation counter, logs a warning, and closes the connection if
   * the violation threshold has been reached.
   *
   * <p>This allows the monitor to independently enforce the violation policy — for example,
   * when a packet is dropped by the downstream {@link DecompressionBombHandler} and that handler
   * delegates violation tracking to this monitor.</p>
   *
   * @param ctx           the channel handler context
   * @param remoteAddress the remote socket address, for logging
   * @param reason        a human-readable description of the violation
   * @return {@code true} if the connection was closed as a result of this violation
   */
  public boolean enforceViolation(final ChannelHandlerContext ctx, final SocketAddress remoteAddress,
                                  final String reason) {
    final int count = violations.incrementAndGet();
    LOGGER.warn("[CompressionRatioMonitor] {} — violation {}/{} from {}",
        reason, count, maxViolations, remoteAddress);

    if (count >= maxViolations) {
      LOGGER.warn("[CompressionRatioMonitor] Closing connection from {} after {} violations",
          remoteAddress, count);
      ctx.close();
      return true;
    }
    return false;
  }

  /**
   * Drops the specified message (releases its reference count) without forwarding it downstream.
   *
   * @param msg the message to drop
   */
  public static void drop(final Object msg) {
    ReferenceCountUtil.release(msg);
  }

  /**
   * Gets the configured maximum decompressed size.
   *
   * @return the maximum decompressed size in bytes
   */
  public int getMaxDecompressedSize() {
    return maxDecompressedSize;
  }

  /**
   * Gets the configured maximum compression ratio.
   *
   * @return the maximum compression ratio (decompressed:compressed)
   */
  public int getMaxCompressionRatio() {
    return maxCompressionRatio;
  }

  /**
   * Gets the configured maximum number of violations before connection close.
   *
   * @return the maximum violation count
   */
  public int getMaxViolations() {
    return maxViolations;
  }
}
