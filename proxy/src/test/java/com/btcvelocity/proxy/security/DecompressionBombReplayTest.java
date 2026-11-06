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

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.velocitypowered.natives.compression.JavaVelocityCompressor;
import com.velocitypowered.natives.compression.VelocityCompressor;
import com.velocitypowered.proxy.network.limiter.SimpleBytesPerSecondLimiter;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.ProtocolUtils.Direction;
import com.velocitypowered.proxy.protocol.netty.MinecraftCompressDecoder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;

/**
 * Replays the same decompression bombs through the upstream decoder alone, through the BTC
 * handlers around the upstream decoder, and through both together (the production wiring), to
 * record what each defence stops. Every packet is a valid deflate stream of zeros, so its ratio is
 * close to deflate's maximum of about 1000:1.
 */
class DecompressionBombReplayTest {

  private static final int THRESHOLD = 256;
  private static final int MIB = 1 << 20;
  private static final int BTC_MAX_SIZE = 8 * MIB;
  private static final int BTC_MAX_RATIO = 100;
  private static final int BTC_MAX_VIOLATIONS = 3;
  private static final int BTC_RATIO_FLOOR = 2 * MIB;
  private static final int UPSTREAM_BYTES_PER_SECOND = 5_242_880;
  private static final int UPSTREAM_WINDOW_SECONDS = 7;

  /** Which defences surround the decoder. The decoder's own size and claim checks are always on. */
  private enum Defense {
    /** The decoder with the packet limiter the proxy installs for serverbound traffic. */
    UPSTREAM(true, false),
    /** The decoder without a limiter, wrapped by the BTC monitor and handler. */
    BTC(false, true),
    /** The production wiring: limiter and BTC handlers. */
    BOTH(true, true);

    private final boolean limiter;
    private final boolean btcHandlers;

    Defense(final boolean limiter, final boolean btcHandlers) {
      this.limiter = limiter;
      this.btcHandlers = btcHandlers;
    }
  }

  /** What reached the end of the pipeline for a series of packets. */
  private record Outcome(int passed, int dropped, boolean rejected, boolean closed) {
  }

  private static Outcome replay(final Direction direction, final Defense defense,
                                final int btcMaxSize, final byte[]... frames) {
    final VelocityCompressor compressor =
        JavaVelocityCompressor.FACTORY.create(Deflater.DEFAULT_COMPRESSION);
    final MinecraftCompressDecoder decoder =
        new MinecraftCompressDecoder(THRESHOLD, compressor, direction);
    if (defense.limiter && direction == Direction.SERVERBOUND) {
      decoder.setPacketLimiter(new SimpleBytesPerSecondLimiter(
          -1, UPSTREAM_BYTES_PER_SECOND, UPSTREAM_WINDOW_SECONDS));
    }
    final EmbeddedChannel channel = defense.btcHandlers
        ? new EmbeddedChannel(
            new CompressionRatioMonitor(btcMaxSize, BTC_MAX_RATIO, BTC_MAX_VIOLATIONS),
            decoder,
            new DecompressionBombHandler(btcMaxSize, BTC_MAX_RATIO, BTC_MAX_VIOLATIONS,
                BTC_RATIO_FLOOR))
        : new EmbeddedChannel(decoder);

    int fed = 0;
    boolean rejected = false;
    for (final byte[] frame : frames) {
      if (!channel.isOpen()) {
        break;
      }
      fed++;
      try {
        channel.writeInbound(Unpooled.wrappedBuffer(frame));
      } catch (final Exception e) {
        rejected = true;
        break;
      }
    }
    int passed = 0;
    for (ByteBuf out = channel.readInbound(); out != null; out = channel.readInbound()) {
      passed++;
      out.release();
    }
    final boolean closed = !channel.isOpen();
    channel.finishAndReleaseAll();
    return new Outcome(passed, fed - passed - (rejected ? 1 : 0), rejected, closed);
  }

  private static Outcome replay(final Direction direction, final Defense defense,
                                final byte[]... frames) {
    return replay(direction, defense, BTC_MAX_SIZE, frames);
  }

  /** A compressed frame as the compression decoder receives it: claimed size, then the deflate. */
  private static byte[] frame(final int claimedSize, final int actualSize) {
    final VelocityCompressor compressor =
        JavaVelocityCompressor.FACTORY.create(Deflater.DEFAULT_COMPRESSION);
    final ByteBuf raw = Unpooled.buffer(actualSize);
    final ByteBuf frame = Unpooled.buffer();
    try {
      raw.writeZero(actualSize);
      ProtocolUtils.writeVarInt(frame, claimedSize);
      compressor.deflate(raw, frame);
      final byte[] bytes = new byte[frame.readableBytes()];
      frame.readBytes(bytes);
      return bytes;
    } catch (final DataFormatException e) {
      throw new IllegalStateException(e);
    } finally {
      raw.release();
      frame.release();
      compressor.close();
    }
  }

  private static byte[] bomb(final int size) {
    return frame(size, size);
  }

  private static byte[][] repeat(final byte[] frame, final int times) {
    final byte[][] frames = new byte[times][];
    Arrays.fill(frames, frame);
    return frames;
  }

  @Test
  void largestServerboundBombPassesUpstreamAndIsDroppedByBtc() {
    final byte[] bomb = bomb(2 * MIB);

    assertEquals(new Outcome(1, 0, false, false), replay(Direction.SERVERBOUND, Defense.UPSTREAM, bomb));
    assertEquals(new Outcome(0, 1, false, false), replay(Direction.SERVERBOUND, Defense.BTC, bomb));
    assertEquals(new Outcome(0, 1, false, false), replay(Direction.SERVERBOUND, Defense.BOTH, bomb));
  }

  @Test
  void largestClientboundBombPassesUpstreamAndIsDroppedByBtc() {
    final byte[] bomb = bomb(8 * MIB);

    assertEquals(new Outcome(1, 0, false, false), replay(Direction.CLIENTBOUND, Defense.UPSTREAM, bomb));
    assertEquals(new Outcome(0, 1, false, false), replay(Direction.CLIENTBOUND, Defense.BTC, bomb));
    assertEquals(new Outcome(0, 1, false, false), replay(Direction.CLIENTBOUND, Defense.BOTH, bomb));
  }

  @Test
  void repeatedBombsCloseTheConnectionOnlyWithBtc() {
    final byte[][] bombs = repeat(bomb(2 * MIB), 3);

    assertEquals(new Outcome(3, 0, false, false), replay(Direction.SERVERBOUND, Defense.UPSTREAM, bombs));
    assertEquals(new Outcome(0, 3, false, true), replay(Direction.SERVERBOUND, Defense.BTC, bombs));
    assertEquals(new Outcome(0, 3, false, true), replay(Direction.SERVERBOUND, Defense.BOTH, bombs));
  }

  @Test
  void sustainedBombsBelowTheRatioFloorAreStoppedOnlyByTheUpstreamLimiter() {
    final byte[][] bombs = repeat(bomb(MIB), 40);

    assertEquals(new Outcome(35, 0, true, false), replay(Direction.SERVERBOUND, Defense.UPSTREAM, bombs));
    assertEquals(new Outcome(40, 0, false, false), replay(Direction.SERVERBOUND, Defense.BTC, bombs));
    assertEquals(new Outcome(35, 0, true, false), replay(Direction.SERVERBOUND, Defense.BOTH, bombs));
  }

  @Test
  void sustainedClientboundBombsAreStoppedByNeitherDefense() {
    final byte[][] bombs = repeat(bomb(MIB), 40);

    assertEquals(new Outcome(40, 0, false, false), replay(Direction.CLIENTBOUND, Defense.UPSTREAM, bombs));
    assertEquals(new Outcome(40, 0, false, false), replay(Direction.CLIENTBOUND, Defense.BTC, bombs));
    assertEquals(new Outcome(40, 0, false, false), replay(Direction.CLIENTBOUND, Defense.BOTH, bombs));
  }

  @Test
  void configuredSizeCapBelowTheDecoderCapIsEnforcedOnlyByBtc() {
    final byte[] packet = bomb(3 * MIB / 2);

    assertEquals(new Outcome(1, 0, false, false), replay(Direction.SERVERBOUND, Defense.UPSTREAM, MIB, packet));
    assertEquals(new Outcome(0, 1, false, false), replay(Direction.SERVERBOUND, Defense.BTC, MIB, packet));
    assertEquals(new Outcome(0, 1, false, false), replay(Direction.SERVERBOUND, Defense.BOTH, MIB, packet));
  }

  @Test
  void claimsAboveTheDecoderCapAreRejectedByTheDecoderWhateverTheDefense() {
    final byte[] serverbound = bomb(2 * MIB + 1);
    final byte[] clientbound = bomb(8 * MIB + 1);

    for (final Defense defense : Defense.values()) {
      assertEquals(new Outcome(0, 0, true, false), replay(Direction.SERVERBOUND, defense, serverbound));
      assertEquals(new Outcome(0, 0, true, false), replay(Direction.CLIENTBOUND, defense, clientbound));
    }
  }

  @Test
  void underReportedClaimIsRejectedByTheDecoderWhateverTheDefense() {
    final byte[] lying = frame(1024, MIB);

    for (final Defense defense : Defense.values()) {
      assertEquals(new Outcome(0, 0, true, false), replay(Direction.SERVERBOUND, defense, lying));
    }
  }

  @Test
  void highlyCompressibleSmallPacketsAreNeverTreatedAsBombs() {
    final byte[] packet = bomb(256 * 1024);

    for (final Defense defense : Defense.values()) {
      assertEquals(new Outcome(1, 0, false, false), replay(Direction.SERVERBOUND, defense, packet));
      assertEquals(new Outcome(1, 0, false, false), replay(Direction.CLIENTBOUND, defense, packet));
    }
  }
}
