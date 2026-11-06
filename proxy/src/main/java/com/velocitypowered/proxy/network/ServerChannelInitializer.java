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

package com.velocitypowered.proxy.network;

import static com.velocitypowered.proxy.network.Connections.FRAME_DECODER;
import static com.velocitypowered.proxy.network.Connections.FRAME_ENCODER;
import static com.velocitypowered.proxy.network.Connections.LEGACY_PING_DECODER;
import static com.velocitypowered.proxy.network.Connections.LEGACY_PING_ENCODER;
import static com.velocitypowered.proxy.network.Connections.MINECRAFT_DECODER;
import static com.velocitypowered.proxy.network.Connections.MINECRAFT_ENCODER;
import static com.velocitypowered.proxy.network.Connections.READ_TIMEOUT;

import com.btcvelocity.api.connection.ListenerOrigin;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.config.VelocityConfiguration;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.client.HandshakeSessionHandler;
import com.velocitypowered.proxy.network.limiter.SimpleBytesPerSecondLimiter;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.netty.LegacyPingDecoder;
import com.velocitypowered.proxy.protocol.netty.LegacyPingEncoder;
import com.velocitypowered.proxy.protocol.netty.MinecraftDecoder;
import com.velocitypowered.proxy.protocol.netty.MinecraftEncoder;
import com.velocitypowered.proxy.protocol.netty.MinecraftVarintFrameDecoder;
import com.velocitypowered.proxy.protocol.netty.MinecraftVarintLengthEncoder;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;
import io.netty.handler.timeout.ReadTimeoutHandler;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Server channel initializer.
 */
public class ServerChannelInitializer extends ChannelInitializer<Channel> {

  private static final Logger LOGGER = LogManager.getLogger(ServerChannelInitializer.class);
  private static final AtomicBoolean UNTRUSTED_WARNED = new AtomicBoolean();

  private final VelocityServer server;

  public ServerChannelInitializer(final VelocityServer server) {
    this.server = server;
  }

  /**
   * Whether a channel must speak the PROXY protocol: the Geyser listener always does (Geyser sends
   * it with {@code use-proxy-protocol}), the main one follows {@code haproxy-protocol}.
   *
   * @param listener the name of the listener that accepted the channel, {@code null} for the main one
   * @param mainListenerFlag the {@code haproxy-protocol} setting
   * @return {@code true} if a PROXY header is expected
   */
  static boolean expectsProxyProtocol(final @Nullable String listener, final boolean mainListenerFlag) {
    return ListenerOrigin.GEYSER.equals(listener) || mainListenerFlag;
  }

  @Override
  protected void initChannel(final Channel ch) {
    final boolean proxyProtocol = expectsProxyProtocol(ch.attr(Connections.LISTENER).get(),
        this.server.getConfiguration().isProxyProtocol());
    // An untrusted peer could forge the client address in its PROXY header: drop it unread.
    if (proxyProtocol && !(ch.remoteAddress() instanceof InetSocketAddress peer
        && this.server.getConfiguration().getProxyProtocolTrustedProxies()
            .contains(peer.getAddress()))) {
      // First refusal at warn so a misconfigured relay is visible; later ones at debug (log flood).
      if (UNTRUSTED_WARNED.compareAndSet(false, true)) {
        LOGGER.warn("Refusing PROXY protocol connection from untrusted source {} (add your relay to "
            + "haproxy-protocol-trusted-proxies; further refusals are logged at debug)", ch.remoteAddress());
      } else {
        LOGGER.debug("Refusing PROXY protocol connection from untrusted source {}", ch.remoteAddress());
      }
      ch.close();
      return;
    }

    ch.pipeline()
        .addLast(LEGACY_PING_DECODER, new LegacyPingDecoder())
        .addLast(FRAME_DECODER, new MinecraftVarintFrameDecoder(ProtocolUtils.Direction.SERVERBOUND))
        .addLast(READ_TIMEOUT, new ReadTimeoutHandler(this.server.getConfiguration().getReadTimeout(), TimeUnit.MILLISECONDS))
        .addLast(LEGACY_PING_ENCODER, LegacyPingEncoder.INSTANCE)
        .addLast(FRAME_ENCODER, MinecraftVarintLengthEncoder.INSTANCE)
        .addLast(MINECRAFT_DECODER, new MinecraftDecoder(ProtocolUtils.Direction.SERVERBOUND))
        .addLast(MINECRAFT_ENCODER, new MinecraftEncoder(ProtocolUtils.Direction.CLIENTBOUND));

    final MinecraftConnection connection = new MinecraftConnection(ch, this.server, UUID.randomUUID());
    connection.setActiveSessionHandler(StateRegistry.HANDSHAKE,
        new HandshakeSessionHandler(connection, this.server));
    ch.pipeline().addLast(Connections.HANDLER, connection);

    VelocityConfiguration.PacketLimiterConfig packetLimiterConfig =
        server.getConfiguration().getPacketLimiterConfig();
    int configuredInterval = packetLimiterConfig.interval();
    int configuredPacketsPerSecond = packetLimiterConfig.pps();
    int configuredBytes = packetLimiterConfig.bytes();

    if (configuredInterval > 0 && (configuredBytes > 0 || configuredPacketsPerSecond > 0)) {
      ch.pipeline().get(MinecraftVarintFrameDecoder.class).setPacketLimiter(
          new SimpleBytesPerSecondLimiter(configuredPacketsPerSecond, configuredBytes, configuredInterval)
      );
    }
    if (proxyProtocol) {
      ch.pipeline().addFirst(new HAProxyMessageDecoder());
    }
  }
}
