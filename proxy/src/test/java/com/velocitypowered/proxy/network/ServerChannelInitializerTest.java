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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.btcvelocity.proxy.network.ClientChannelHooks;
import com.btcvelocity.proxy.security.GeyserListenerAddress;
import com.btcvelocity.proxy.security.TrustedProxies;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.config.VelocityConfiguration;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import io.netty.handler.codec.haproxy.HAProxyCommand;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;
import io.netty.handler.codec.haproxy.HAProxyProtocolVersion;
import io.netty.handler.codec.haproxy.HAProxyProxiedProtocol;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Whether a channel speaks the PROXY protocol is decided per listener: the Geyser listener always
 * does, the main one follows {@code haproxy-protocol}. Which sources may send the header is
 * decided earlier, on the server channel (see {@link ListenerSourceFilterTest}).
 */
class ServerChannelInitializerTest {

  private static final String PEER = "127.0.0.1";

  private static PeerChannel open(final String listener, final boolean mainFlag) {
    return open(listener, mainFlag, new ClientChannelHooks());
  }

  private static PeerChannel open(final String listener, final boolean mainFlag,
      final ClientChannelHooks hooks) {
    final VelocityServer server = mock(VelocityServer.class);
    final VelocityConfiguration configuration = mock(VelocityConfiguration.class);
    when(server.getConfiguration()).thenReturn(configuration);
    when(server.getConnectionAdmission()).thenReturn(NoAdmission.checks());
    when(configuration.isProxyProtocol()).thenReturn(mainFlag);
    when(configuration.getPacketLimiterConfig())
        .thenReturn(VelocityConfiguration.PacketLimiterConfig.DEFAULT);
    final PeerChannel channel = new PeerChannel(PEER);
    if (listener != null) {
      channel.attr(Connections.LISTENER).set(listener);
    }
    // what the source filter records when it admits the child
    channel.attr(Connections.PROXY_PROTOCOL)
        .set(ServerChannelInitializer.expectsProxyProtocol(listener, mainFlag));
    new ServerChannelInitializer(server, hooks).initChannel(channel);
    return channel;
  }

  @Test
  void aChannelTheFilterNeverSawGetsNoProxyProtocolDecoder() {
    final VelocityServer server = mock(VelocityServer.class);
    final VelocityConfiguration configuration = mock(VelocityConfiguration.class);
    when(server.getConfiguration()).thenReturn(configuration);
    when(server.getConnectionAdmission()).thenReturn(NoAdmission.checks());
    when(configuration.isProxyProtocol()).thenReturn(true);
    when(configuration.getPacketLimiterConfig())
        .thenReturn(VelocityConfiguration.PacketLimiterConfig.DEFAULT);
    final PeerChannel channel = new PeerChannel(PEER);
    channel.attr(Connections.LISTENER).set("geyser");

    new ServerChannelInitializer(server, new ClientChannelHooks()).initChannel(channel);

    assertNull(channel.pipeline().get(HAProxyMessageDecoder.class));
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
  void aTcpHeaderSetsTheClientAddressAndAnyOtherProtocolClosesTheConnection() {
    final PeerChannel tcp = open("geyser", false);
    final PeerChannel unix = open("geyser", false);

    tcp.writeInbound(new HAProxyMessage(HAProxyProtocolVersion.V2, HAProxyCommand.PROXY,
        HAProxyProxiedProtocol.TCP4, "203.0.113.7", "127.0.0.1", 4242, 25566));
    unix.writeInbound(new HAProxyMessage(HAProxyProtocolVersion.V2, HAProxyCommand.PROXY,
        HAProxyProxiedProtocol.UNIX_STREAM, "evil.example.com", "/run/x", 0, 0));

    assertEquals(new InetSocketAddress(InetAddress.ofLiteral("203.0.113.7"), 4242),
        tcp.pipeline().get(MinecraftConnection.class).getRemoteAddress());
    assertTrue(tcp.isOpen());
    assertFalse(unix.isOpen(), "a header that is not TCP over IPv4 or IPv6 must close the connection");
  }

  @Test
  void aHookIsCalledOncePerChannelOnceTheWholePipelineIsBuilt() {
    final ClientChannelHooks hooks = new ClientChannelHooks();
    final List<String> seen = new ArrayList<>();
    hooks.add(channel -> seen.add("first:" + codecAndConnectionAreThere(channel)));
    hooks.add(channel -> seen.add("second:" + codecAndConnectionAreThere(channel)));

    open("geyser", false, hooks);
    open(null, true, hooks);

    assertEquals(List.of("first:true", "second:true", "first:true", "second:true"), seen);
  }

  @Test
  void aHookCanPutAHandlerAheadOfTheMinecraftCodec() {
    final ClientChannelHooks hooks = new ClientChannelHooks();
    final ChannelHandler tap = new ChannelInboundHandlerAdapter();
    // the names ClientChannelHook documents: a plugin hardcodes them
    hooks.add(channel -> channel.pipeline().addBefore("minecraft-decoder", "tap", tap));

    final PeerChannel channel = open(null, false, hooks);

    final List<String> names = channel.pipeline().names();
    assertEquals(names.indexOf(Connections.MINECRAFT_DECODER) - 1, names.indexOf("tap"));
  }

  @Test
  void aFailingHookNeitherClosesTheChannelNorStopsTheNextHook() {
    final ClientChannelHooks hooks = new ClientChannelHooks();
    final List<String> seen = new ArrayList<>();
    hooks.add(channel -> {
      throw new IllegalStateException("plugin bug");
    });
    hooks.add(channel -> seen.add("next"));

    final PeerChannel channel = open(null, false, hooks);

    assertEquals(List.of("next"), seen);
    assertTrue(channel.isOpen());
    assertNotNull(channel.pipeline().get(MinecraftConnection.class));
  }

  private static boolean codecAndConnectionAreThere(final Channel channel) {
    return channel.pipeline().get(Connections.MINECRAFT_DECODER) != null
        && channel.pipeline().get(Connections.MINECRAFT_ENCODER) != null
        && channel.pipeline().get(MinecraftConnection.class) != null;
  }

  @Test
  void theMainListenerWithTheFlagOnGetsADecoder() {
    final PeerChannel channel = open(null, true);

    assertNotNull(channel.pipeline().get(HAProxyMessageDecoder.class));
    assertTrue(channel.isOpen());
  }
}
