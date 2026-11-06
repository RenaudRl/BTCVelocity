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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.natives.compression.JavaVelocityCompressor;
import com.velocitypowered.natives.compression.VelocityCompressor;
import com.velocitypowered.natives.util.BufferPreference;
import com.velocitypowered.proxy.network.limiter.SimpleBytesPerSecondLimiter;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.ProtocolUtils.Direction;
import com.velocitypowered.proxy.protocol.netty.MinecraftCompressDecoder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import com.velocitypowered.proxy.util.except.QuietDecoderException;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;

/**
 * Replays decompression bombs through the compression decoder with the upstream packet limiter
 * alone, with the BTC guard alone, and with both (the production wiring), to pin what each
 * defence stops. Every packet is a valid deflate stream of zeros, so its ratio is close to
 * deflate's maximum of about 1000:1. A packet that makes the decoder throw ends the connection in
 * production, so the replay stops there and reports it as rejected.
 */
class DecompressionBombReplayTest {

  private static final int THRESHOLD = 256;
  private static final int KIB = 1 << 10;
  private static final int MIB = 1 << 20;
  private static final String GUARD_CLOSE_MESSAGE = "Decompression bomb limits exceeded";
  private static final int BTC_MAX_SIZE = 8 * MIB;
  private static final int BTC_MAX_RATIO = 100;
  private static final int BTC_MAX_VIOLATIONS = 3;
  private static final int BTC_RATIO_FLOOR = 2 * MIB;
  private static final int UPSTREAM_BYTES_PER_SECOND = 5_242_880;
  private static final int UPSTREAM_WINDOW_SECONDS = 7;

  /** Which defences the decoder carries. Its own size and claim checks are always on. */
  private enum Defense {
    /** The packet limiter the proxy installs for serverbound traffic. */
    UPSTREAM(true, false),
    /** The BTC guard, without a limiter. */
    BTC(false, true),
    /** The production wiring: limiter and guard. */
    BOTH(true, true);

    private final boolean limiter;
    private final boolean guard;

    Defense(final boolean limiter, final boolean guard) {
      this.limiter = limiter;
      this.guard = guard;
    }
  }

  /** What reached the end of the pipeline for a series of packets. */
  private record Outcome(int passed, int dropped, boolean rejected) {
  }

  /** An outcome with the inflations performed, the failure that ended it and the passed sizes. */
  private record Run(Outcome outcome, int inflations, DecoderException failure,
                     List<Integer> passedSizes) {
  }

  /** The Java compressor, counting the calls to {@code inflate}. */
  private static final class CountingCompressor implements VelocityCompressor {
    private final VelocityCompressor delegate =
        JavaVelocityCompressor.FACTORY.create(Deflater.DEFAULT_COMPRESSION);
    private int inflations;

    @Override
    public void inflate(final ByteBuf source, final ByteBuf destination,
                        final int uncompressedSize) throws DataFormatException {
      inflations++;
      delegate.inflate(source, destination, uncompressedSize);
    }

    @Override
    public void deflate(final ByteBuf source, final ByteBuf destination)
        throws DataFormatException {
      delegate.deflate(source, destination);
    }

    @Override
    public BufferPreference preferredBufferType() {
      return delegate.preferredBufferType();
    }

    @Override
    public void close() {
      delegate.close();
    }
  }

  private static Outcome replay(final Direction direction, final Defense defense,
                                final int btcMaxSize, final byte[]... frames) {
    return run(direction, defense, btcMaxSize, frames).outcome();
  }

  private static Outcome replay(final Direction direction, final Defense defense,
                                final byte[]... frames) {
    return replay(direction, defense, BTC_MAX_SIZE, frames);
  }

  private static Run run(final Direction direction, final Defense defense,
                         final int btcMaxSize, final byte[]... frames) {
    final CountingCompressor compressor = new CountingCompressor();
    final MinecraftCompressDecoder decoder =
        new MinecraftCompressDecoder(THRESHOLD, compressor, direction);
    if (defense.limiter && direction == Direction.SERVERBOUND) {
      decoder.setPacketLimiter(new SimpleBytesPerSecondLimiter(
          -1, UPSTREAM_BYTES_PER_SECOND, UPSTREAM_WINDOW_SECONDS));
    }
    if (defense.guard) {
      decoder.setDecompressionGuard(new DecompressionGuard(
          btcMaxSize, BTC_MAX_RATIO, BTC_MAX_VIOLATIONS, BTC_RATIO_FLOOR));
    }
    final EmbeddedChannel channel = new EmbeddedChannel(decoder);

    int fed = 0;
    DecoderException failure = null;
    for (final byte[] frame : frames) {
      if (!channel.isOpen()) {
        break;
      }
      fed++;
      try {
        channel.writeInbound(Unpooled.wrappedBuffer(frame));
      } catch (final DecoderException e) {
        failure = e;
        break;
      }
    }
    final List<Integer> passedSizes = new ArrayList<>();
    for (ByteBuf out = channel.readInbound(); out != null; out = channel.readInbound()) {
      passedSizes.add(out.readableBytes());
      out.release();
    }
    channel.finishAndReleaseAll();
    final int passed = passedSizes.size();
    final boolean rejected = failure != null;
    // A dropped packet is never inflated: only the packets that passed, and at most the one the
    // decoder rejected after inflating it, may have reached the compressor.
    assertTrue(compressor.inflations >= passed
            && compressor.inflations <= passed + (rejected ? 1 : 0),
        () -> "inflations " + compressor.inflations + " for " + passedSizes.size() + " passed");
    return new Run(new Outcome(passed, fed - passed - (rejected ? 1 : 0), rejected),
        compressor.inflations, failure, passedSizes);
  }

  private static Run run(final Direction direction, final Defense defense,
                         final byte[]... frames) {
    return run(direction, defense, BTC_MAX_SIZE, frames);
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

    assertEquals(new Outcome(1, 0, false), replay(Direction.SERVERBOUND, Defense.UPSTREAM, bomb));
    assertEquals(new Outcome(0, 1, false), replay(Direction.SERVERBOUND, Defense.BTC, bomb));
    assertEquals(new Outcome(0, 1, false), replay(Direction.SERVERBOUND, Defense.BOTH, bomb));
  }

  @Test
  void largestClientboundBombPassesUpstreamAndIsDroppedByBtc() {
    final byte[] bomb = bomb(8 * MIB);

    assertEquals(new Outcome(1, 0, false), replay(Direction.CLIENTBOUND, Defense.UPSTREAM, bomb));
    assertEquals(new Outcome(0, 1, false), replay(Direction.CLIENTBOUND, Defense.BTC, bomb));
    assertEquals(new Outcome(0, 1, false), replay(Direction.CLIENTBOUND, Defense.BOTH, bomb));
  }

  @Test
  void thirdBombEndsTheConnectionOnlyWithBtc() {
    final byte[][] bombs = repeat(bomb(2 * MIB), 3);

    assertEquals(new Outcome(3, 0, false), replay(Direction.SERVERBOUND, Defense.UPSTREAM, bombs));
    assertEquals(new Outcome(0, 2, true), replay(Direction.SERVERBOUND, Defense.BTC, bombs));
    assertEquals(new Outcome(0, 2, true), replay(Direction.SERVERBOUND, Defense.BOTH, bombs));
  }

  @Test
  void sustainedBombsBelowTheRatioFloorAreStoppedOnlyByTheUpstreamLimiter() {
    final byte[][] bombs = repeat(bomb(MIB), 40);

    assertEquals(new Outcome(35, 0, true), replay(Direction.SERVERBOUND, Defense.UPSTREAM, bombs));
    assertEquals(new Outcome(40, 0, false), replay(Direction.SERVERBOUND, Defense.BTC, bombs));
    assertEquals(new Outcome(35, 0, true), replay(Direction.SERVERBOUND, Defense.BOTH, bombs));
  }

  @Test
  void sustainedClientboundBombsAreStoppedByNeitherDefense() {
    final byte[][] bombs = repeat(bomb(MIB), 40);

    assertEquals(new Outcome(40, 0, false), replay(Direction.CLIENTBOUND, Defense.UPSTREAM, bombs));
    assertEquals(new Outcome(40, 0, false), replay(Direction.CLIENTBOUND, Defense.BTC, bombs));
    assertEquals(new Outcome(40, 0, false), replay(Direction.CLIENTBOUND, Defense.BOTH, bombs));
  }

  @Test
  void configuredSizeCapBelowTheDecoderCapIsEnforcedOnlyByBtc() {
    final byte[] packet = bomb(3 * MIB / 2);

    assertEquals(new Outcome(1, 0, false), replay(Direction.SERVERBOUND, Defense.UPSTREAM, MIB, packet));
    assertEquals(new Outcome(0, 1, false), replay(Direction.SERVERBOUND, Defense.BTC, MIB, packet));
    assertEquals(new Outcome(0, 1, false), replay(Direction.SERVERBOUND, Defense.BOTH, MIB, packet));
  }

  @Test
  void claimsAboveTheDecoderCapAreRejectedByTheDecoderWhateverTheDefense() {
    final byte[] serverbound = bomb(2 * MIB + 1);
    final byte[] clientbound = bomb(8 * MIB + 1);

    for (final Defense defense : Defense.values()) {
      final Run up = run(Direction.SERVERBOUND, defense, serverbound);
      final Run down = run(Direction.CLIENTBOUND, defense, clientbound);
      assertEquals(new Outcome(0, 0, true), up.outcome());
      assertEquals(new Outcome(0, 0, true), down.outcome());
      assertDecoderSizeCheck(up);
      assertDecoderSizeCheck(down);
      assertEquals(0, up.inflations());
      assertEquals(0, down.inflations());
    }
  }

  /**
   * The decoder's own checks throw a {@link QuietDecoderException} (a {@code
   * CorruptedFrameException} only with {@code -Dvelocity.packet-decode-logging=true}); either way
   * it is not the guard's close.
   */
  private static void assertDecoderSizeCheck(final Run run) {
    assertInstanceOf(DecoderException.class, run.failure());
    assertNotEquals(GUARD_CLOSE_MESSAGE, run.failure().getMessage());
  }

  @Test
  void underReportedClaimIsRejectedByTheDecoderWhateverTheDefense() {
    final byte[] lying = frame(1024, MIB);

    for (final Defense defense : Defense.values()) {
      final Run run = run(Direction.SERVERBOUND, defense, lying);
      assertEquals(new Outcome(0, 0, true), run.outcome());
      assertDecoderSizeCheck(run);
    }
  }

  @Test
  void bombsDroppedByTheGuardNeverReachTheInflater() {
    final Run run = run(Direction.SERVERBOUND, Defense.BOTH, bomb(2 * MIB));

    assertEquals(new Outcome(0, 1, false), run.outcome());
    assertEquals(0, run.inflations());
  }

  @Test
  void acceptedPacketIsInflatedOnce() {
    final Run run = run(Direction.SERVERBOUND, Defense.BOTH, bomb(256 * 1024));

    assertEquals(new Outcome(1, 0, false), run.outcome());
    assertEquals(1, run.inflations());
  }

  @Test
  void underReportedBombsAreDroppedThenCloseOnTheThirdWithoutInflating() {
    final Run run = run(Direction.SERVERBOUND, Defense.BOTH, repeat(frame(2 * MIB, KIB), 3));

    assertEquals(new Outcome(0, 2, true), run.outcome());
    assertEquals(0, run.inflations());
    assertInstanceOf(QuietDecoderException.class, run.failure());
    assertEquals(GUARD_CLOSE_MESSAGE, run.failure().getMessage());
  }

  @Test
  void oneMibClaimBelowTheRatioFloorIsNotJudgedByTheGuardButRejectedAfterInflating() {
    // 1 MiB is under the 2 MiB ratio floor and the 8 MiB size cap: the guard accepts it, and only
    // the decoder's claimed-versus-real size check rejects it, after one inflation.
    final Run run = run(Direction.SERVERBOUND, Defense.BOTH, frame(MIB, KIB));

    assertEquals(new Outcome(0, 0, true), run.outcome());
    assertEquals(1, run.inflations());
    assertDecoderSizeCheck(run);
  }

  @Test
  void validPacketAfterADropIsDecodedIntact() {
    final Run run = run(Direction.SERVERBOUND, Defense.BOTH, frame(2 * MIB, KIB), bomb(KIB));

    assertEquals(new Outcome(1, 1, false), run.outcome());
    assertEquals(List.of(KIB), run.passedSizes());
    assertEquals(1, run.inflations());
  }

  @Test
  void highlyCompressibleSmallPacketsAreNeverTreatedAsBombs() {
    final byte[] packet = bomb(256 * 1024);

    for (final Defense defense : Defense.values()) {
      assertEquals(new Outcome(1, 0, false), replay(Direction.SERVERBOUND, defense, packet));
      assertEquals(new Outcome(1, 0, false), replay(Direction.CLIENTBOUND, defense, packet));
    }
  }
}
