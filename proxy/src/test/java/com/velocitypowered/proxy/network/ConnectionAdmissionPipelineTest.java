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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.btcvelocity.proxy.network.ClientChannelHooks;
import com.btcvelocity.proxy.security.AdmissionStats;
import com.btcvelocity.proxy.security.ConnectionAdmission;
import com.btcvelocity.proxy.security.HandshakeHosts;
import com.btcvelocity.proxy.security.TrustedProxies;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.config.VelocityConfiguration;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.util.ratelimit.Ratelimiters;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.haproxy.HAProxyCommand;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.codec.haproxy.HAProxyProtocolVersion;
import io.netty.handler.codec.haproxy.HAProxyProxiedProtocol;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The connection limit and the handshake deadline where they act: on a new channel, before its
 * pipeline exists (direct peer) or when the PROXY header names the client.
 */
class ConnectionAdmissionPipelineTest {

  private static final long DEADLINE_MS = 30;

  private final AdmissionStats stats = new AdmissionStats();

  private VelocityServer server(final int limit, final List<String> exempt, final long deadlineMs) {
    final VelocityServer server = mock(VelocityServer.class);
    final VelocityConfiguration configuration = mock(VelocityConfiguration.class);
    when(server.getConfiguration()).thenReturn(configuration);
    when(configuration.getPacketLimiterConfig())
        .thenReturn(VelocityConfiguration.PacketLimiterConfig.DEFAULT);
    when(server.getConnectionAdmission()).thenReturn(new ConnectionAdmission(
        HandshakeHosts.of(HandshakeHosts.Mode.OFF, List.of(), List.of()),
        Ratelimiters.createWindowed(limit, 60_000, 1000), TrustedProxies.parse(exempt), deadlineMs,
        stats));
    return server;
  }

  private static PeerChannel open(final VelocityServer server, final String peer,
      final boolean proxyProtocol) {
    final PeerChannel channel = new PeerChannel(peer);
    channel.attr(Connections.PROXY_PROTOCOL).set(proxyProtocol);
    new ServerChannelInitializer(server, new ClientChannelHooks()).initChannel(channel);
    return channel;
  }

  private static HAProxyMessage header(final String client) {
    return new HAProxyMessage(HAProxyProtocolVersion.V2, HAProxyCommand.PROXY,
        HAProxyProxiedProtocol.TCP4, client, "127.0.0.1", 4242, 25565);
  }

  /** Offers a child to the listener's server channel; {@code true} if it goes on to the acceptor. */
  private static boolean accepted(final EmbeddedChannel serverChannel, final PeerChannel child) {
    serverChannel.writeInbound(child);
    return serverChannel.readInbound() != null;
  }

  @Test
  void aDirectPeerOverTheLimitIsClosedOnTheServerChannelBeforeAnyPipelineIsBuilt() {
    final EmbeddedChannel serverChannel =
        new EmbeddedChannel(new ListenerSourceFilter(server(2, List.of(), 0), null));
    final PeerChannel third = new PeerChannel("203.0.113.9");
    final PeerChannel other = new PeerChannel("203.0.113.10");

    assertTrue(accepted(serverChannel, new PeerChannel("203.0.113.9")));
    assertTrue(accepted(serverChannel, new PeerChannel("203.0.113.9")));
    assertFalse(accepted(serverChannel, third));
    assertTrue(accepted(serverChannel, other), "another source has its own count");

    assertFalse(third.isOpen());
    assertNull(third.pipeline().get(MinecraftConnection.class), "nothing was built for it");
    assertEquals(1, stats.count(AdmissionStats.Result.RATE_LIMITED));
  }

  /**
   * Floodgate wraps the original initializer and then adds a handler before "minecraft-decoder"
   * without a try/catch: a channel that reached it without a pipeline would throw, and Netty would
   * log the stack of each refused connection.
   */
  @Test
  void aPluginThatWrapsTheInitializerNeverSeesAChannelOverTheLimit() {
    final VelocityServer server = server(1, List.of(), 0);
    final ServerChannelInitializer real = new ServerChannelInitializer(server, new ClientChannelHooks());
    final EmbeddedChannel serverChannel = new EmbeddedChannel(new ListenerSourceFilter(server, null));

    for (int i = 0; i < 20; i++) {
      final PeerChannel child = new PeerChannel("203.0.113.9");
      if (accepted(serverChannel, child)) {
        assertDoesNotThrow(() -> {
          real.initChannel(child);
          child.pipeline().addBefore(Connections.MINECRAFT_DECODER, "floodgate-like",
              new ChannelInboundHandlerAdapter());
        });
      }
    }

    assertEquals(19, stats.count(AdmissionStats.Result.RATE_LIMITED));
  }


  @Test
  void withTheProxyProtocolTheClientAddressInTheHeaderIsTheOneCounted() {
    final VelocityServer server = server(1, List.of(), 0);
    // all three come from the same relay, loopback: the relay must not be what is limited
    final PeerChannel a1 = open(server, "127.0.0.1", true);
    final PeerChannel a2 = open(server, "127.0.0.1", true);
    final PeerChannel b = open(server, "127.0.0.1", true);

    a1.writeInbound(header("203.0.113.9"));
    a2.writeInbound(header("203.0.113.9"));
    b.writeInbound(header("203.0.113.10"));

    assertTrue(a1.isOpen());
    assertFalse(a2.isOpen(), "second connection of the same client");
    assertTrue(b.isOpen(), "another client behind the same relay");
    assertEquals(1, stats.count(AdmissionStats.Result.RATE_LIMITED));
  }

  @Test
  void aPeerBehindTheProxyProtocolIsNotCountedBeforeItsHeader() {
    final VelocityServer server = server(1, List.of(), 0);

    open(server, "127.0.0.1", true);
    final PeerChannel second = open(server, "127.0.0.1", true);

    assertTrue(second.isOpen(), "the relay address is not the client");
    assertEquals(0, stats.count(AdmissionStats.Result.RATE_LIMITED));
  }

  @Test
  void anExemptSourceIsNotLimitedOnEitherPath() {
    final VelocityServer server = server(1, List.of("203.0.113.0/24"), 0);
    final EmbeddedChannel serverChannel = new EmbeddedChannel(new ListenerSourceFilter(server, null));

    for (int i = 0; i < 5; i++) {
      assertTrue(accepted(serverChannel, new PeerChannel("203.0.113.9")));
    }
    final PeerChannel viaHeader = open(server, "127.0.0.1", true);
    final PeerChannel viaHeader2 = open(server, "127.0.0.1", true);
    viaHeader.writeInbound(header("203.0.113.50"));
    viaHeader2.writeInbound(header("203.0.113.50"));
    assertTrue(viaHeader.isOpen());
    assertTrue(viaHeader2.isOpen());
  }

  @Test
  void aLimitOfZeroLimitsNothing() {
    final EmbeddedChannel serverChannel =
        new EmbeddedChannel(new ListenerSourceFilter(server(0, List.of(), 0), null));

    for (int i = 0; i < 20; i++) {
      assertTrue(accepted(serverChannel, new PeerChannel("203.0.113.9")));
    }
  }

  @Test
  void aConnectionThatSendsNoHandshakeIsClosedAtTheDeadline() throws InterruptedException {
    final PeerChannel silent = open(server(0, List.of(), DEADLINE_MS), "203.0.113.9", false);
    assertNotNull(silent.pipeline().get(MinecraftConnection.class));

    assertTrue(silent.isOpen(), "not before the deadline");
    Thread.sleep(DEADLINE_MS * 3);
    silent.runScheduledPendingTasks();

    assertFalse(silent.isOpen());
    assertEquals(1, stats.count(AdmissionStats.Result.HANDSHAKE_TIMEOUT));
  }

  @Test
  void aConnectionThatHasSentItsHandshakeIsLeftAlone() throws InterruptedException {
    final ConnectionAdmission admission = server(0, List.of(), DEADLINE_MS).getConnectionAdmission();
    final PeerChannel talker = new PeerChannel("203.0.113.9");

    ServerChannelInitializer.scheduleHandshakeDeadline(talker, admission, () -> true);
    Thread.sleep(DEADLINE_MS * 3);
    talker.runScheduledPendingTasks();

    assertTrue(talker.isOpen());
    assertEquals(0, stats.count(AdmissionStats.Result.HANDSHAKE_TIMEOUT));
  }

  @Test
  void aConnectionClosedEarlierIsNotCountedAsLate() throws InterruptedException {
    final PeerChannel gone = open(server(0, List.of(), DEADLINE_MS), "203.0.113.9", false);

    gone.close();
    Thread.sleep(DEADLINE_MS * 3);
    gone.runScheduledPendingTasks();

    assertEquals(0, stats.count(AdmissionStats.Result.HANDSHAKE_TIMEOUT));
  }

  @Test
  void noDeadlineMeansNothingIsScheduled() throws InterruptedException {
    final PeerChannel silent = open(server(0, List.of(), 0), "203.0.113.9", false);

    Thread.sleep(DEADLINE_MS * 3);
    silent.runScheduledPendingTasks();

    assertTrue(silent.isOpen());
  }
}
