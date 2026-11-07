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

package com.velocitypowered.proxy.connection.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.btcvelocity.proxy.cluster.ProxyDrain;
import com.btcvelocity.proxy.security.AdmissionStats;
import com.btcvelocity.proxy.security.ConnectionAdmission;
import com.btcvelocity.proxy.security.HandshakeHosts;
import com.velocitypowered.api.network.HandshakeIntent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.config.VelocityConfiguration;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.event.VelocityEventManager;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.packet.HandshakePacket;
import com.velocitypowered.proxy.util.ratelimit.Ratelimiter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * A draining proxy refuses new logins at the handshake and keeps answering the server list.
 */
class HandshakeDrainTest {

  private final AdmissionStats stats = new AdmissionStats();
  private final ProxyDrain drain = mock(ProxyDrain.class);
  private final MinecraftConnection connection = mock(MinecraftConnection.class);
  private final VelocityServer server = mock(VelocityServer.class);

  private HandshakeSessionHandler handler(final boolean draining) {
    final VelocityConfiguration configuration = mock(VelocityConfiguration.class);
    when(server.getConfiguration()).thenReturn(configuration);
    when(configuration.getMaximumVersion()).thenReturn(Optional.empty());
    when(configuration.isAcceptTransfers()).thenReturn(true);
    final VelocityEventManager events = mock(VelocityEventManager.class);
    // The establish event goes through untouched, like with no plugin listening.
    when(events.fire(any())).thenAnswer(inv -> CompletableFuture.completedFuture(inv.getArgument(0)));
    when(server.getEventManager()).thenReturn(events);
    when(server.getConnectionAdmission()).thenReturn(
        new ConnectionAdmission(HandshakeHosts.of(HandshakeHosts.Mode.OFF, List.of(),
            List.of()), stats));
    when(server.getAdmissionStats()).thenReturn(stats);
    when(server.getProxyDrain()).thenReturn(drain);
    when(drain.isDraining()).thenReturn(draining);
    when(connection.getListenerName()).thenReturn(Optional.empty());
    when(connection.getRemoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 40000));
    return new HandshakeSessionHandler(connection, server);
  }

  private static HandshakePacket handshake(final HandshakeIntent intent, final int nextStatus) {
    final HandshakePacket packet = mock(HandshakePacket.class);
    when(packet.getServerAddress()).thenReturn("play.borntocraft.net");
    when(packet.getNextStatus()).thenReturn(nextStatus);
    when(packet.getIntent()).thenReturn(intent);
    when(packet.getProtocolVersion()).thenReturn(ProtocolVersion.MAXIMUM_VERSION);
    return packet;
  }

  @Test
  void aLoginIsRefusedWithTheDrainMessageAndCounted() {
    handler(true).handle(handshake(HandshakeIntent.LOGIN, StateRegistry.LOGIN_ID));

    verify(connection).closeWith(any());
    verify(connection, never()).setActiveSessionHandler(eq(StateRegistry.LOGIN), any());
    assertEquals(1, stats.count(AdmissionStats.Result.DRAINING));
  }

  @Test
  void aTransferFromAnotherProxyIsRefusedToo() {
    handler(true).handle(handshake(HandshakeIntent.TRANSFER, StateRegistry.TRANSFER_ID));

    verify(connection, never()).setActiveSessionHandler(eq(StateRegistry.LOGIN), any());
    assertEquals(1, stats.count(AdmissionStats.Result.DRAINING));
  }

  @Test
  void theServerListStillAnswersWhileDraining() {
    handler(true).handle(handshake(HandshakeIntent.STATUS, StateRegistry.STATUS_ID));

    verify(connection).setActiveSessionHandler(eq(StateRegistry.STATUS), any());
    assertEquals(0, stats.count(AdmissionStats.Result.DRAINING));
  }

  @Test
  @SuppressWarnings("unchecked")
  void aLoginGoesOnToTheNextCheckWhenTheProxyIsNotDraining() {
    final Ratelimiter<InetAddress> limiter = mock(Ratelimiter.class);
    when(server.getIpAttemptLimiter()).thenReturn(limiter);

    handler(false).handle(handshake(HandshakeIntent.LOGIN, StateRegistry.LOGIN_ID));

    verify(limiter).attempt(any());
    assertEquals(0, stats.count(AdmissionStats.Result.DRAINING));
  }
}
