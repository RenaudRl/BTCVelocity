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

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.protocol.MinecraftPacket;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.util.except.QuietDecoderException;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.Queue;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

/**
 * Queues up any pending PLAY packets while the client is in the CONFIG state.
 *
 * <p>Much of the Velocity API (i.e., chat messages) utilize PLAY packets; however, the client is
 * incapable of receiving these packets during the CONFIG state. Certain events such as the
 * ServerPreConnectEvent may be called during this time, and we need to ensure that any API that
 * uses these packets will work as expected.
 *
 * <p>This handler will queue up any packets that are sent to the client during this time, and send
 * them once the client has (re)entered the PLAY state.
 */
public class PlayPacketQueueOutboundHandler extends ChannelDuplexHandler {

  private static final Logger LOGGER = LogManager.getLogger(PlayPacketQueueOutboundHandler.class);

  /** The smallest packet bound a system property can set: below it, a normal login would trip. */
  static final int MINIMUM_PACKETS = 64;
  /** The smallest byte bound a system property can set. */
  static final int MINIMUM_SIZE = 1024 * 1024;
  /**
   * What every parked packet weighs in the byte bound, whatever it holds: most packets are not
   * {@link ByteBufHolder}s, so without it the byte bound would see nothing of them.
   */
  static final int PACKET_WEIGHT = 1024;

  // Mirrors the inbound bounds: packets parked for a client stuck in CONFIG must not grow forever.
  static final int DEFAULT_MAXIMUM_PACKETS =
      bounded("velocity.maximum-play-queue-outbound-packets", 4096, MINIMUM_PACKETS);
  static final int DEFAULT_MAXIMUM_SIZE =
      bounded("velocity.maximum-play-queue-outbound-size", 128 * 1024 * 1024, MINIMUM_SIZE); // 128MiB

  /** A system property, never below its minimum (0 or a negative value would close every login). */
  static int bounded(final String name, final int fallback, final int minimum) {
    return Math.max(minimum, Integer.getInteger(name, fallback));
  }

  /** A parked packet with the promise of whoever wrote it, answered when it is sent or dropped. */
  private record Parked(MinecraftPacket packet, ChannelPromise promise) {
  }

  private final StateRegistry.PacketRegistry.ProtocolRegistry registry;
  private final int maximumPackets;
  private final long maximumSize;

  private final Queue<Parked> queue = new ArrayDeque<>();
  private long queueSize = 0;

  /**
   * Provides registries for "client" &amp; server bound packets.
   *
   * @param version the protocol version
   * @param direction the direction of packet flow (typically {@code CLIENTBOUND})
   */
  public PlayPacketQueueOutboundHandler(final ProtocolVersion version, final ProtocolUtils.Direction direction) {
    this(version, direction, DEFAULT_MAXIMUM_PACKETS, DEFAULT_MAXIMUM_SIZE);
  }

  PlayPacketQueueOutboundHandler(final ProtocolVersion version, final ProtocolUtils.Direction direction,
      final int maximumPackets, final long maximumSize) {
    this.registry = StateRegistry.CONFIG.getProtocolRegistry(direction, version);
    this.maximumPackets = maximumPackets;
    this.maximumSize = maximumSize;
  }

  @Override
  public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) throws Exception {
    if (!(msg instanceof final MinecraftPacket packet)) {
      ctx.write(msg, promise);
      return;
    }

    // If the packet exists in the CONFIG state, we want to always
    // ensure that it gets sent out to the client
    if (this.registry.containsPacket(packet)) {
      ctx.write(msg, promise);
      return;
    }

    // Otherwise, queue the packet, unless the peer has stayed in CONFIG long enough to overflow us.
    final long length = PACKET_WEIGHT
        + (packet instanceof final ByteBufHolder holder ? holder.content().readableBytes() : 0);
    final boolean tooMany = this.queue.size() >= this.maximumPackets;
    if (tooMany || this.queueSize + length > this.maximumSize) {
      ReferenceCountUtil.release(packet);
      final QuietDecoderException overflow = new QuietDecoderException(tooMany
          ? "Outbound queue too big (more than " + this.maximumPackets + " packets)"
          : "Outbound queue too big (greater than " + this.maximumSize + " bytes)");
      promise.tryFailure(overflow);
      LOGGER.warn("Closing the connection of {}: its PLAY queue toward a client still in CONFIG is full"
          + " ({} packets queued, {} bytes counted; limits {} packets, {} bytes)",
          playerOf(ctx), this.queue.size(), this.queueSize, this.maximumPackets, this.maximumSize);
      // Reaches MinecraftConnection#exceptionCaught, which closes the connection quietly.
      ctx.fireExceptionCaught(overflow);
      return;
    }
    this.queueSize += length;
    this.queue.offer(new Parked(packet, promise));
  }

  /** The username only: the address of the player has no place in this log line. */
  private static String playerOf(final ChannelHandlerContext ctx) {
    final MinecraftConnection connection = ctx.pipeline().get(MinecraftConnection.class);
    return connection != null && connection.getAssociation() instanceof final ConnectedPlayer player
        ? player.getUsername() : "an unidentified player";
  }

  @Override
  public void channelInactive(final @NotNull ChannelHandlerContext ctx) throws Exception {
    this.releaseQueue(ctx, false);

    super.channelInactive(ctx);
  }

  @Override
  public void handlerRemoved(final ChannelHandlerContext ctx) {
    this.releaseQueue(ctx, ctx.channel().isActive());
  }

  private void releaseQueue(final ChannelHandlerContext ctx, final boolean active) {
    // Send out all the queued packets
    Parked parked;
    while ((parked = this.queue.poll()) != null) {
      if (active) {
        ctx.write(parked.packet(), parked.promise());
      } else {
        ReferenceCountUtil.release(parked.packet());
        parked.promise().tryFailure(new ClosedChannelException());
      }
    }

    this.queueSize = 0;

    if (active) {
      ctx.flush();
    }
  }
}