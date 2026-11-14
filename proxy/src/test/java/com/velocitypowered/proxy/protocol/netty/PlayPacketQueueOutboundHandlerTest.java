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
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
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
    // each packet weighs its fixed share plus its content: 1024 + 60 each
    EmbeddedChannel channel = channel(tail, 100, 2000);
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

  /** Most packets hold no ByteBufHolder: they must weigh something, or the byte bound is blind. */
  @Test
  void aPacketWithoutABufferStillWeighsInTheByteBound() {
    Tail tail = new Tail();
    EmbeddedChannel channel = channel(tail, 100, 3 * PlayPacketQueueOutboundHandler.PACKET_WEIGHT);
    try {
      for (int i = 0; i < 3; i++) {
        channel.writeOneOutbound(new ChatAcknowledgementPacket(i));
      }
      assertTrue(tail.caught.isEmpty(), "three packets fit");

      channel.writeOneOutbound(new ChatAcknowledgementPacket(3));

      assertEquals(1, tail.caught.size());
      assertTrue(tail.caught.get(0).getMessage().contains("bytes"), tail.caught.get(0).getMessage());
      assertFalse(channel.isActive());
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  /** The overflow is named in the log before the connection closes, with the depth reached. */
  @Test
  void theOverflowWarnsWithTheDepthBeforeClosing() throws InterruptedException {
    final List<LogEvent> events = new CopyOnWriteArrayList<>();
    final AbstractAppender appender = new AbstractAppender("outbound-overflow", null, null, true,
        Property.EMPTY_ARRAY) {
      @Override
      public void append(final LogEvent event) {
        events.add(event.toImmutable());
      }
    };
    appender.start();
    final org.apache.logging.log4j.core.Logger logger = (org.apache.logging.log4j.core.Logger)
        LogManager.getLogger(PlayPacketQueueOutboundHandler.class);
    logger.addAppender(appender);
    Tail tail = new Tail();
    EmbeddedChannel channel = channel(tail, 2, 1024 * 1024);
    try {
      channel.writeOneOutbound(new ChatAcknowledgementPacket(0));
      channel.writeOneOutbound(new ChatAcknowledgementPacket(1));
      channel.writeOneOutbound(new ChatAcknowledgementPacket(2));

      final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (events.isEmpty() && System.nanoTime() < deadline) {
        Thread.sleep(20);
      }
      assertEquals(1, events.size());
      assertEquals(Level.WARN, events.get(0).getLevel());
      final String message = events.get(0).getMessage().getFormattedMessage();
      assertTrue(message.contains("2 packets"), message);
    } finally {
      logger.removeAppender(appender);
      appender.stop();
      channel.finishAndReleaseAll();
    }
  }

  /** A packet parked with its promise gets the promise's answer, not a dropped one. */
  @Test
  void aParkedPacketsPromiseCompletesWhenItIsSent() {
    Tail tail = new Tail();
    PlayPacketQueueOutboundHandler handler = new PlayPacketQueueOutboundHandler(
        ProtocolVersion.MAXIMUM_VERSION, ProtocolUtils.Direction.CLIENTBOUND, 10, 1024 * 1024);
    EmbeddedChannel channel = new EmbeddedChannel(handler, tail);
    try {
      ChannelPromise promise = channel.newPromise();
      channel.writeOneOutbound(new ChatAcknowledgementPacket(0), promise);
      assertFalse(promise.isDone(), "still parked");

      channel.pipeline().remove(handler);

      assertTrue(promise.isSuccess(), "sent once the handler leaves");
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  @Test
  void aParkedPacketsPromiseFailsWhenTheChannelDies() {
    Tail tail = new Tail();
    EmbeddedChannel channel = channel(tail, 10, 1024 * 1024);
    ChannelPromise promise = channel.newPromise();
    Blob blob = new Blob(10);
    channel.writeOneOutbound(blob, promise);
    assertFalse(promise.isDone());

    channel.close();

    assertTrue(promise.isDone() && !promise.isSuccess(), "the writer is told the packet never left");
    assertEquals(0, blob.refCnt(), "and the packet is released");
    channel.finishAndReleaseAll();
  }

  @Test
  void theSystemPropertiesAreClampedToAReasonableMinimum() {
    final String name = "velocity.test-queue-bound";
    try {
      assertEquals(4096, PlayPacketQueueOutboundHandler.bounded(name, 4096, 64));
      System.setProperty(name, "0");
      assertEquals(64, PlayPacketQueueOutboundHandler.bounded(name, 4096, 64));
      System.setProperty(name, "-5");
      assertEquals(64, PlayPacketQueueOutboundHandler.bounded(name, 4096, 64));
      System.setProperty(name, "500");
      assertEquals(500, PlayPacketQueueOutboundHandler.bounded(name, 4096, 64));
    } finally {
      System.clearProperty(name);
    }
  }
}
