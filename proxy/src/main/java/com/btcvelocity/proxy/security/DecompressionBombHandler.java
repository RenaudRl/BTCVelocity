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
 * A Netty {@link ChannelInboundHandlerAdapter} that protects against decompression-bomb attacks
 * by monitoring the size and compression ratio of every {@link ByteBuf} that emerges from the
 * decompression stage of the pipeline.
 *
 * <p>A decompression bomb is a small compressed payload that expands into an enormous uncompressed
 * payload, consuming memory and CPU on the proxy. This handler sits <em>after</em> the compression
 * decoder in the Netty pipeline and inspects each decompressed buffer before it reaches the
 * Minecraft packet decoder.</p>
 *
 * <p>Two checks are performed on every inbound {@code ByteBuf}:</p>
 * <ul>
 *   <li><b>Absolute size</b> &mdash; if the decompressed size exceeds
 *       {@link #maxDecompressedSize}, a violation is recorded. This is the primary defence: a
 *       bomb is dangerous because of the memory it produces, and this cap bounds it directly.</li>
 *   <li><b>Compression ratio</b> &mdash; if the ratio of decompressed size to compressed size
 *       exceeds {@link #maxCompressionRatio}, a violation is recorded. The compressed size is
 *       obtained from the {@link CompressionRatioMonitor} that sits earlier in the pipeline.
 *       <b>The ratio check is only applied once the decompressed output reaches
 *       {@link #ratioCheckMinDecompressedSize}</b>: a decompression bomb is defined by a
 *       <em>large</em> output produced from a tiny input, so a high ratio on a small payload is
 *       not a bomb. Legitimate Minecraft traffic — chunk, light and entity-movement packets full
 *       of repeated data, sent in bursts when a player moves fast — routinely compresses well
 *       beyond 100:1 while staying only tens of kilobytes in size, and must not be flagged.</li>
 * </ul>
 *
 * <p>Each violation drops the offending packet (the buffer is released and not forwarded
 * downstream), increments a per-connection violation counter, and logs a warning that includes the
 * remote address. Once the violation count reaches {@link #maxViolations}, the connection is
 * closed immediately.</p>
 *
 * <p>All mutable state is kept in thread-safe types ({@link AtomicInteger}) because, although a
 * single Netty channel is serviced by one event-loop thread, the handler may be inspected or
 * queried by other parts of the pipeline at any time.</p>
 */
public class DecompressionBombHandler extends ChannelInboundHandlerAdapter {

  private static final Logger LOGGER = LogManager.getLogger(DecompressionBombHandler.class);

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
   * The default decompressed-size floor below which the compression-ratio check is skipped
   * (2&nbsp;MiB = 2&thinsp;097&thinsp;152 bytes). This matches the usual maximum size of a single
   * uncompressed Minecraft packet, so all normal traffic — however compressible — falls below it,
   * while a genuine bomb producing multiple megabytes still lands in the enforced band between
   * this floor and {@link #maxDecompressedSize}.
   */
  public static final int DEFAULT_RATIO_CHECK_MIN_DECOMPRESSED_SIZE = 2 * 1024 * 1024;

  private final int maxDecompressedSize;
  private final int maxCompressionRatio;
  private final int maxViolations;
  private final int ratioCheckMinDecompressedSize;

  /**
   * Per-connection violation counter. Thread-safe via {@link AtomicInteger}.
   */
  private final AtomicInteger violations = new AtomicInteger(0);

  /**
   * Creates a new {@code DecompressionBombHandler} with the specified limits and the default
   * ratio-check floor ({@link #DEFAULT_RATIO_CHECK_MIN_DECOMPRESSED_SIZE}).
   *
   * @param maxDecompressedSize the maximum allowed decompressed payload size, in bytes
   * @param maxCompressionRatio the maximum allowed compression ratio (decompressed:compressed)
   * @param maxViolations       the number of violations before the connection is closed
   */
  public DecompressionBombHandler(final int maxDecompressedSize,
                                  final int maxCompressionRatio,
                                  final int maxViolations) {
    this(maxDecompressedSize, maxCompressionRatio, maxViolations,
        DEFAULT_RATIO_CHECK_MIN_DECOMPRESSED_SIZE);
  }

  /**
   * Creates a new {@code DecompressionBombHandler} with the specified limits.
   *
   * @param maxDecompressedSize           the maximum allowed decompressed payload size, in bytes
   * @param maxCompressionRatio           the maximum allowed compression ratio
   *                                      (decompressed:compressed)
   * @param maxViolations                 the number of violations before the connection is closed
   * @param ratioCheckMinDecompressedSize the decompressed size, in bytes, at or above which the
   *                                      compression-ratio check is applied; smaller payloads are
   *                                      exempt from the ratio check regardless of how well they
   *                                      compressed
   */
  public DecompressionBombHandler(final int maxDecompressedSize,
                                  final int maxCompressionRatio,
                                  final int maxViolations,
                                  final int ratioCheckMinDecompressedSize) {
    this.maxDecompressedSize = maxDecompressedSize;
    this.maxCompressionRatio = maxCompressionRatio;
    this.maxViolations = maxViolations;
    this.ratioCheckMinDecompressedSize = ratioCheckMinDecompressedSize;
  }

  /**
   * Creates a new {@code DecompressionBombHandler} with default limits.
   */
  public DecompressionBombHandler() {
    this(DEFAULT_MAX_DECOMPRESSED_SIZE, DEFAULT_MAX_COMPRESSION_RATIO, DEFAULT_MAX_VIOLATIONS,
        DEFAULT_RATIO_CHECK_MIN_DECOMPRESSED_SIZE);
  }

  @Override
  public void channelRead(final @NotNull ChannelHandlerContext ctx, final @NotNull Object msg) {
    if (!(msg instanceof ByteBuf buf)) {
      // Non-ByteBuf messages (e.g. HAProxyMessage) pass through untouched.
      ctx.fireChannelRead(msg);
      return;
    }

    final int decompressedSize = buf.readableBytes();
    final SocketAddress remoteAddress = ctx.channel().remoteAddress();

    // --- Check 1: absolute decompressed size ----------------------------------------
    if (decompressedSize > maxDecompressedSize) {
      recordViolation(ctx, buf, remoteAddress,
          "Decompressed size %,d bytes exceeds maximum %,d bytes",
          decompressedSize, maxDecompressedSize);
      return;
    }

    // --- Check 2: compression ratio --------------------------------------------------
    // Only enforced once the decompressed output is itself large: a decompression bomb is
    // defined by a LARGE payload produced from a tiny input. A high ratio on a small packet is
    // normal Minecraft traffic (repeated chunk/light/movement data compresses far past 100:1
    // while staying tens of KiB) and must not be treated as an attack. The absolute-size cap
    // above remains the primary defence for every packet.
    if (decompressedSize >= ratioCheckMinDecompressedSize) {
      final CompressionRatioMonitor monitor = ctx.pipeline().get(CompressionRatioMonitor.class);
      if (monitor != null) {
        final int compressedSize = monitor.getLastCompressedSize();
        if (compressedSize > 0) {
          final double ratio = (double) decompressedSize / (double) compressedSize;
          if (ratio > maxCompressionRatio) {
            recordViolation(ctx, buf, remoteAddress,
                "Compression ratio %.1f:1 (decompressed %,d / compressed %,d) exceeds maximum %d:1",
                ratio, decompressedSize, compressedSize, maxCompressionRatio);
            return;
          }
        }
      }
    }

    // All checks passed — forward the buffer downstream.
    ctx.fireChannelRead(msg);
  }

  /**
   * Records a violation: releases the offending buffer, increments the counter, logs a warning,
   * and closes the connection if the violation threshold has been reached.
   *
   * @param ctx           the channel handler context
   * @param buf           the offending buffer (will be released)
   * @param remoteAddress the remote socket address, for logging
   * @param format        a {@link String#format(String, Object...)} log message template
   * @param args          arguments for the log message template
   */
  private void recordViolation(final ChannelHandlerContext ctx, final ByteBuf buf,
                               final SocketAddress remoteAddress, final String format,
                               final Object... args) {
    // Drop the packet — release the buffer and do NOT forward it downstream.
    ReferenceCountUtil.release(buf);

    final int count = violations.incrementAndGet();
    LOGGER.warn("[DecompressionBomb] {} — violation {}/{} from {}",
        String.format(format, args), count, maxViolations, remoteAddress);

    if (count >= maxViolations) {
      LOGGER.warn("[DecompressionBomb] Closing connection from {} after {} decompression-bomb violations",
          remoteAddress, count);
      ctx.close();
    }
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

  /**
   * Gets the decompressed-size floor at or above which the compression-ratio check is applied.
   *
   * @return the ratio-check floor, in bytes
   */
  public int getRatioCheckMinDecompressedSize() {
    return ratioCheckMinDecompressedSize;
  }
}
