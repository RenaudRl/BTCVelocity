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

package com.velocitypowered.proxy.protocol.netty;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.packet.chat.ChatAcknowledgementPacket;
import com.velocitypowered.proxy.util.except.QuietDecoderException;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

/**
 * Tests that packets parked while the client is in the CONFIG state are bounded by count, not only
 * by bytes: an already decoded packet that is not a ByteBufHolder weighs 0 bytes.
 */
class PlayPacketQueueInboundHandlerTest {

  // Must match PlayPacketQueueInboundHandler's default packet bound.
  private static final int PACKET_LIMIT = 4096;

  private static EmbeddedChannel channel() {
    return new EmbeddedChannel(new PlayPacketQueueInboundHandler(
        ProtocolVersion.MAXIMUM_VERSION, ProtocolUtils.Direction.SERVERBOUND));
  }

  @Test
  void queuesUpToTheLimitThenRejectsDecodedPackets() {
    EmbeddedChannel channel = channel();
    try {
      // A PLAY-only packet with no buffer: zero bytes against the byte bound.
      assertDoesNotThrow(() -> {
        for (int i = 0; i < PACKET_LIMIT; i++) {
          channel.writeInbound(new ChatAcknowledgementPacket(i));
        }
      });
      assertThrows(QuietDecoderException.class,
          () -> channel.writeInbound(new ChatAcknowledgementPacket(PACKET_LIMIT)));
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  @Test
  void floodOfDecodedPacketsIsRejected() {
    EmbeddedChannel channel = channel();
    try {
      assertThrows(QuietDecoderException.class, () -> {
        for (int i = 0; i < 100_000; i++) {
          channel.writeInbound(new ChatAcknowledgementPacket(i));
        }
      });
    } finally {
      channel.finishAndReleaseAll();
    }
  }
}
