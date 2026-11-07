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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.btcvelocity.api.connection.ListenerOrigin;
import com.btcvelocity.proxy.security.AdmissionStats;
import com.btcvelocity.proxy.security.ConnectionAdmission;
import com.btcvelocity.proxy.security.HandshakeHosts;
import com.velocitypowered.api.network.HandshakeIntent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.config.VelocityConfiguration;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.event.VelocityEventManager;
import com.velocitypowered.proxy.protocol.packet.HandshakePacket;
import com.velocitypowered.proxy.protocol.packet.LegacyPingPacket;
import com.velocitypowered.proxy.protocol.packet.legacyping.LegacyMinecraftPingVersion;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * The handshake host list at the place where it acts: a refused host closes the connection before
 * the establish event, an observed one goes on, the Geyser listener is not judged.
 */
class HandshakeHostAdmissionTest {

  private final AdmissionStats stats = new AdmissionStats();
  private final VelocityEventManager events = mock(VelocityEventManager.class);

  private HandshakeSessionHandler handler(final HandshakeHosts.Mode mode,
      final MinecraftConnection connection) {
    final VelocityServer server = mock(VelocityServer.class);
    final VelocityConfiguration configuration = mock(VelocityConfiguration.class);
    when(server.getConfiguration()).thenReturn(configuration);
    when(configuration.getMaximumVersion()).thenReturn(Optional.empty());
    when(server.getEventManager()).thenReturn(events);
    // never completes: the test stops where the establish event is fired
    when(events.fire(any())).thenReturn(new CompletableFuture<>());
    when(server.getConnectionAdmission()).thenReturn(new ConnectionAdmission(
        HandshakeHosts.of(mode, List.of("play.borntocraft.net"), List.of("lobby.borntocraft.net")),
        stats));
    return new HandshakeSessionHandler(connection, server);
  }

  private static HandshakePacket handshake(final String host) {
    final HandshakePacket packet = mock(HandshakePacket.class);
    when(packet.getServerAddress()).thenReturn(host);
    when(packet.getNextStatus()).thenReturn(1);
    when(packet.getIntent()).thenReturn(HandshakeIntent.STATUS);
    when(packet.getProtocolVersion()).thenReturn(ProtocolVersion.MAXIMUM_VERSION);
    return packet;
  }

  private static MinecraftConnection connection(final String listener) {
    final MinecraftConnection connection = mock(MinecraftConnection.class);
    when(connection.getListenerName()).thenReturn(Optional.ofNullable(listener));
    return connection;
  }

  @Test
  void enforceClosesAnUnlistedHostBeforeTheEstablishEvent() {
    final MinecraftConnection connection = connection(null);

    handler(HandshakeHosts.Mode.ENFORCE, connection).handle(handshake("203.0.113.9"));

    verify(connection).close(true);
    verify(events, never()).fire(any());
    assertEquals(1, stats.count(AdmissionStats.Result.HOST_REFUSED));
  }

  @Test
  void enforceLetsAListedHostGoOn() {
    final MinecraftConnection connection = connection(null);

    handler(HandshakeHosts.Mode.ENFORCE, connection).handle(handshake("play.borntocraft.net."));

    verify(connection, never()).close(anyBoolean());
    verify(events).fire(any());
  }

  @Test
  void aForcedHostWithTheForgeMarkerGoesOn() {
    final MinecraftConnection connection = connection(null);

    handler(HandshakeHosts.Mode.ENFORCE, connection)
        .handle(handshake("Lobby.borntocraft.net\0FML\0"));

    verify(connection, never()).close(anyBoolean());
    verify(events).fire(any());
    assertEquals(0, stats.count(AdmissionStats.Result.HOST_REFUSED));
  }

  @Test
  void observeCountsAnUnlistedHostAndLetsItGoOn() {
    final MinecraftConnection connection = connection(null);

    handler(HandshakeHosts.Mode.OBSERVE, connection).handle(handshake("203.0.113.9"));

    verify(connection, never()).close(anyBoolean());
    verify(events).fire(any());
    assertEquals(1, stats.count(AdmissionStats.Result.HOST_OBSERVED));
  }

  @Test
  void offJudgesNothing() {
    final MinecraftConnection connection = connection(null);

    handler(HandshakeHosts.Mode.OFF, connection).handle(handshake("203.0.113.9"));

    verify(connection, never()).close(anyBoolean());
    verify(events).fire(any());
    assertEquals(0, stats.count(AdmissionStats.Result.HOST_OBSERVED));
  }

  @Test
  void enforceClosesALegacyPingWithoutAnswerAndCountsIt() {
    final MinecraftConnection noHost = connection(null);
    final MinecraftConnection otherHost = connection(null);

    handler(HandshakeHosts.Mode.ENFORCE, noHost)
        .handle(new LegacyPingPacket(LegacyMinecraftPingVersion.MINECRAFT_1_4));
    handler(HandshakeHosts.Mode.ENFORCE, otherHost).handle(new LegacyPingPacket(
        LegacyMinecraftPingVersion.MINECRAFT_1_6, InetSocketAddress.createUnresolved("203.0.113.9", 25565)));

    verify(noHost).close(true);
    verify(otherHost).close(true);
    verify(noHost, never()).setActiveSessionHandler(any(), any());
    verify(otherHost, never()).setActiveSessionHandler(any(), any());
    assertEquals(2, stats.count(AdmissionStats.Result.HOST_REFUSED));
  }

  @Test
  void theGeyserListenerLegacyPingIsNotJudged() {
    final MinecraftConnection connection = connection(ListenerOrigin.GEYSER);

    try {
      handler(HandshakeHosts.Mode.ENFORCE, connection)
          .handle(new LegacyPingPacket(LegacyMinecraftPingVersion.MINECRAFT_1_4));
    } catch (RuntimeException statusHandlerOnAMock) {
      // the status handler runs on mocks after the check: only the verdict matters here
    }

    verify(connection, never()).close(anyBoolean());
    assertEquals(0, stats.count(AdmissionStats.Result.HOST_REFUSED));
  }

  @Test
  void theHandshakeIsMarkedReceivedWhateverTheVerdictSoTheDeadlineLeavesItAlone() {
    final HandshakeSessionHandler refused =
        handler(HandshakeHosts.Mode.ENFORCE, connection(null));
    final HandshakeSessionHandler admitted = handler(HandshakeHosts.Mode.OFF, connection(null));
    assertEquals(false, admitted.isHandshakeReceived());

    refused.handle(handshake("203.0.113.9"));
    admitted.handle(handshake("203.0.113.9"));

    assertEquals(true, refused.isHandshakeReceived());
    assertEquals(true, admitted.isHandshakeReceived());
  }

  @Test
  void theGeyserListenerIsNotJudgedEvenInEnforceMode() {
    final MinecraftConnection connection = connection(ListenerOrigin.GEYSER);

    handler(HandshakeHosts.Mode.ENFORCE, connection).handle(handshake("127.0.0.1"));

    verify(connection, never()).close(anyBoolean());
    verify(events).fire(any());
    assertEquals(0, stats.count(AdmissionStats.Result.HOST_REFUSED));
  }
}
