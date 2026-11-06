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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.btcvelocity.proxy.security.TrustedProxies;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.config.VelocityConfiguration;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Whether a channel speaks the PROXY protocol is decided per listener: the Geyser listener always
 * does, the main one follows {@code haproxy-protocol}; the trusted sources filter both.
 */
class ServerChannelInitializerTest {

  private static final String TRUSTED_RELAY = "10.1.2.3";
  private static final String STRANGER = "203.0.113.9";

  /** A channel whose TCP peer is the given address, as a socket would report it. */
  private static final class PeerChannel extends EmbeddedChannel {

    private final SocketAddress peer;

    PeerChannel(final String peer) {
      super();
      this.peer = new InetSocketAddress(InetAddress.ofLiteral(peer), 40000);
    }

    @Override
    protected SocketAddress remoteAddress0() {
      return this.peer;
    }
  }

  private static PeerChannel open(final String peer, final String listener, final boolean mainFlag) {
    final VelocityServer server = mock(VelocityServer.class);
    final VelocityConfiguration configuration = mock(VelocityConfiguration.class);
    when(server.getConfiguration()).thenReturn(configuration);
    when(configuration.isProxyProtocol()).thenReturn(mainFlag);
    when(configuration.getProxyProtocolTrustedProxies())
        .thenReturn(TrustedProxies.parse(List.of("10.0.0.0/8")));
    when(configuration.getPacketLimiterConfig())
        .thenReturn(VelocityConfiguration.PacketLimiterConfig.DEFAULT);
    final PeerChannel channel = new PeerChannel(peer);
    if (listener != null) {
      channel.attr(Connections.LISTENER).set(listener);
    }
    new ServerChannelInitializer(server).initChannel(channel);
    return channel;
  }

  @Test
  void theGeyserListenerExpectsProxyProtocolWhateverTheMainFlagSays() {
    assertTrue(ServerChannelInitializer.expectsProxyProtocol("geyser", false));
    assertTrue(ServerChannelInitializer.expectsProxyProtocol("geyser", true));
  }

  @Test
  void theMainListenerFollowsTheHaproxyFlag() {
    assertFalse(ServerChannelInitializer.expectsProxyProtocol(null, false));
    assertTrue(ServerChannelInitializer.expectsProxyProtocol(null, true));
  }

  @Test
  void aTrustedRelayOnTheGeyserListenerGetsAProxyProtocolDecoderEvenWithTheFlagOff() {
    final PeerChannel channel = open(TRUSTED_RELAY, "geyser", false);

    assertNotNull(channel.pipeline().get(HAProxyMessageDecoder.class));
    assertNotNull(channel.pipeline().get(MinecraftConnection.class));
    assertTrue(channel.isOpen());
  }

  @Test
  void aStrangerOnTheGeyserListenerIsClosedUnreadEvenWithTheFlagOff() {
    final PeerChannel channel = open(STRANGER, "geyser", false);

    assertFalse(channel.isOpen(), "a header from outside the trusted list must not be believed");
    assertNull(channel.pipeline().get(MinecraftConnection.class));
    assertNull(channel.pipeline().get(HAProxyMessageDecoder.class));
  }

  @Test
  void theMainListenerWithTheFlagOffIsUnchangedForAnyPeer() {
    for (String peer : List.of(TRUSTED_RELAY, STRANGER)) {
      final PeerChannel channel = open(peer, null, false);

      assertNull(channel.pipeline().get(HAProxyMessageDecoder.class));
      assertNotNull(channel.pipeline().get(MinecraftConnection.class));
      assertTrue(channel.isOpen());
    }
  }

  @Test
  void theMainListenerWithTheFlagOnIsUnchanged() {
    final PeerChannel relay = open(TRUSTED_RELAY, null, true);
    final PeerChannel stranger = open(STRANGER, null, true);

    assertNotNull(relay.pipeline().get(HAProxyMessageDecoder.class));
    assertFalse(stranger.isOpen());
  }

  @Test
  void aConnectionKnowsTheListenerThatAcceptedIt() {
    final PeerChannel geyser = open(TRUSTED_RELAY, "geyser", false);
    final PeerChannel main = open(TRUSTED_RELAY, null, false);

    assertEquals("geyser", geyser.pipeline().get(MinecraftConnection.class).getListenerName().orElseThrow());
    assertTrue(main.pipeline().get(MinecraftConnection.class).getListenerName().isEmpty());
  }
}
