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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.btcvelocity.proxy.security.GeyserListenerAddress;
import com.btcvelocity.proxy.security.TrustedProxies;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.config.VelocityConfiguration;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;
import org.junit.jupiter.api.Test;

/**
 * Whether a channel speaks the PROXY protocol is decided per listener: the Geyser listener always
 * does, the main one follows {@code haproxy-protocol}. Which sources may send the header is
 * decided earlier, on the server channel (see {@link ListenerSourceFilterTest}).
 */
class ServerChannelInitializerTest {

  private static final String PEER = "127.0.0.1";

  private static PeerChannel open(final String listener, final boolean mainFlag) {
    final VelocityServer server = mock(VelocityServer.class);
    final VelocityConfiguration configuration = mock(VelocityConfiguration.class);
    when(server.getConfiguration()).thenReturn(configuration);
    when(configuration.isProxyProtocol()).thenReturn(mainFlag);
    when(configuration.getPacketLimiterConfig())
        .thenReturn(VelocityConfiguration.PacketLimiterConfig.DEFAULT);
    final PeerChannel channel = new PeerChannel(PEER);
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
  void eachListenerHasItsOwnTrustedSources() {
    final VelocityConfiguration configuration = mock(VelocityConfiguration.class);
    final TrustedProxies main = TrustedProxies.parse(TrustedProxies.DEFAULT_ENTRIES);
    final TrustedProxies geyser = TrustedProxies.parse(GeyserListenerAddress.DEFAULT_TRUSTED_PROXIES);
    when(configuration.getProxyProtocolTrustedProxies()).thenReturn(main);
    when(configuration.getGeyserListenerTrustedProxies()).thenReturn(geyser);

    assertSame(main, ServerChannelInitializer.trustedSources(configuration, null));
    assertSame(geyser, ServerChannelInitializer.trustedSources(configuration, "geyser"));
  }

  @Test
  void theGeyserListenerGetsAProxyProtocolDecoderEvenWithTheFlagOff() {
    final PeerChannel channel = open("geyser", false);

    assertNotNull(channel.pipeline().get(HAProxyMessageDecoder.class));
    assertNotNull(channel.pipeline().get(MinecraftConnection.class));
    assertTrue(channel.isOpen());
  }

  @Test
  void theMainListenerWithTheFlagOffHasNoDecoder() {
    final PeerChannel channel = open(null, false);

    assertNull(channel.pipeline().get(HAProxyMessageDecoder.class));
    assertNotNull(channel.pipeline().get(MinecraftConnection.class));
    assertTrue(channel.isOpen());
  }

  @Test
  void theMainListenerWithTheFlagOnGetsADecoder() {
    final PeerChannel channel = open(null, true);

    assertNotNull(channel.pipeline().get(HAProxyMessageDecoder.class));
    assertTrue(channel.isOpen());
  }
}
