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

package com.velocitypowered.proxy.protocol.netty;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.connection.MinecraftSessionHandler;
import com.velocitypowered.proxy.protocol.MinecraftPacket;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.packet.chat.ChatAcknowledgementPacket;
import com.velocitypowered.proxy.util.except.QuietDecoderException;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.DefaultByteBufHolder;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests that PLAY packets parked toward a peer in the CONFIG state are bounded by count and by
 * bytes, and that the overflow reaches the connection as an exception (which closes it) instead of
 * growing the queue.
 */
class PlayPacketQueueOutboundHandlerTest {

  /** Stands in for MinecraftConnection, the tail handler that closes on an exception. */
  private static final class Tail extends ChannelInboundHandlerAdapter {
    private final List<Throwable> caught = new ArrayList<>();

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
      caught.add(cause);
      ctx.close();
    }
  }

  /** A PLAY-only packet unknown to the CONFIG registry, carrying a buffer. */
  private static final class Blob extends DefaultByteBufHolder implements MinecraftPacket {
    Blob(final int size) {
      super(Unpooled.wrappedBuffer(new byte[size]));
    }

    @Override
    public void decode(final ByteBuf buf, final ProtocolUtils.Direction direction,
        final ProtocolVersion protocolVersion) {
    }

    @Override
    public void encode(final ByteBuf buf, final ProtocolUtils.Direction direction,
        final ProtocolVersion protocolVersion) {
    }

    @Override
    public boolean handle(final MinecraftSessionHandler handler) {
      return false;
    }
  }

  private static EmbeddedChannel channel(final Tail tail, final int maxPackets, final int maxBytes) {
    return new EmbeddedChannel(new PlayPacketQueueOutboundHandler(
        ProtocolVersion.MAXIMUM_VERSION, ProtocolUtils.Direction.CLIENTBOUND, maxPackets, maxBytes), tail);
  }

  @Test
  void parksUpToTheDefaultLimitThenClosesTheConnection() {
    Tail tail = new Tail();
    int limit = PlayPacketQueueOutboundHandler.DEFAULT_MAXIMUM_PACKETS;
    EmbeddedChannel channel = channel(tail, limit, PlayPacketQueueOutboundHandler.DEFAULT_MAXIMUM_SIZE);
    try {
      assertDoesNotThrow(() -> {
        for (int i = 0; i < limit; i++) {
          channel.writeOneOutbound(new ChatAcknowledgementPacket(i));
        }
      });
      assertTrue(tail.caught.isEmpty(), "no overflow yet");
      assertTrue(channel.isActive());

      channel.writeOneOutbound(new ChatAcknowledgementPacket(limit));

      assertEquals(1, tail.caught.size());
      assertInstanceOf(QuietDecoderException.class, tail.caught.get(0));
      assertFalse(channel.isActive(), "overflow must close the connection");
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  @Test
  void bytesAreBoundedToo() {
    Tail tail = new Tail();
    EmbeddedChannel channel = channel(tail, 100, 100);
    try {
      channel.writeOneOutbound(new Blob(60));
      assertTrue(tail.caught.isEmpty());

      Blob refused = new Blob(60);
      channel.writeOneOutbound(refused);

      assertEquals(1, tail.caught.size());
      assertFalse(channel.isActive(), "overflow must close the connection");
      assertEquals(0, refused.refCnt(), "the refused packet is released");
    } finally {
      channel.finishAndReleaseAll();
    }
  }
}
