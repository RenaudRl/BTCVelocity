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

package com.btcvelocity.proxy.security;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.velocitypowered.natives.compression.JavaVelocityCompressor;
import com.velocitypowered.proxy.network.limiter.SimpleBytesPerSecondLimiter;
import com.velocitypowered.proxy.protocol.ProtocolUtils.Direction;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.netty.MinecraftCompressDecoder;
import com.velocitypowered.proxy.protocol.netty.MinecraftVarintFrameDecoder;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;

/**
 * Both decoders that apply the packet limiter raise {@link PacketLimitExceededException}, so the
 * connection can say why it closes. Each test feeds frames until the limit trips.
 */
class PacketLimitDecoderTest {

  private static final int THRESHOLD = 256;
  private static final int WINDOW_SECONDS = 1;
  private static final int FRAMES = 50;

  @Test
  void theFrameDecoderRaisesItOnPacketsPerSecond() {
    final MinecraftVarintFrameDecoder decoder = new MinecraftVarintFrameDecoder(Direction.SERVERBOUND);
    decoder.setState(StateRegistry.PLAY);
    decoder.setPacketLimiter(new SimpleBytesPerSecondLimiter(1, -1, WINDOW_SECONDS));
    final EmbeddedChannel channel = new EmbeddedChannel(decoder);

    assertThrows(PacketLimitExceededException.class, () -> {
      for (int i = 0; i < FRAMES; i++) {
        channel.writeInbound(Unpooled.wrappedBuffer(new byte[] {2, 0x10, 0x20}));
      }
    });
    channel.finishAndReleaseAll();
  }

  @Test
  void theCompressionDecoderRaisesItOnDecompressedBytes() {
    final MinecraftCompressDecoder decoder = new MinecraftCompressDecoder(THRESHOLD,
        JavaVelocityCompressor.FACTORY.create(Deflater.DEFAULT_COMPRESSION), Direction.SERVERBOUND);
    decoder.setPacketLimiter(new SimpleBytesPerSecondLimiter(-1, 1, WINDOW_SECONDS));
    final EmbeddedChannel channel = new EmbeddedChannel(decoder);

    // A data length of 0 marks a packet below the compression threshold, sent as it is.
    assertThrows(PacketLimitExceededException.class, () -> {
      for (int i = 0; i < FRAMES; i++) {
        channel.writeInbound(Unpooled.wrappedBuffer(new byte[] {0, 0x10, 0x20, 0x30}));
      }
    });
    channel.finishAndReleaseAll();
  }
}
