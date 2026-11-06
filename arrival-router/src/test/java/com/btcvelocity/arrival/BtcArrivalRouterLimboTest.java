/*
 * Copyright (C) 2026 Velocity Contributors
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

package com.btcvelocity.arrival;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

class BtcArrivalRouterLimboTest {

  private final ProxyServer proxy = mock(ProxyServer.class);
  private final List<RegisteredServer> registered = new ArrayList<>();
  private final RegisteredServer limbo = this.server("limbo");
  private final BtcArrivalRouterPlugin router = new BtcArrivalRouterPlugin(
      this.proxy, NOPLogger.NOP_LOGGER, ArrivalPolicy.parse("Lobby-*,*"), "limbo", null);

  BtcArrivalRouterLimboTest() {
    doReturn(this.registered).when(this.proxy).getAllServers();
  }

  private RegisteredServer server(String name) {
    var server = mock(RegisteredServer.class);
    when(server.getServerInfo()).thenReturn(new ServerInfo(name, new InetSocketAddress(25599)));
    doReturn(Optional.of(server)).when(this.proxy).getServer(name);
    this.registered.add(server);
    return server;
  }

  private static Player player() {
    var player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(UUID.randomUUID());
    when(player.getUsername()).thenReturn("tester");
    return player;
  }

  private static KickedFromServerEvent kick(Player player, RegisteredServer from) {
    return new KickedFromServerEvent(player, from, Component.empty(), true,
        KickedFromServerEvent.DisconnectPlayer.create(Component.empty()));
  }

  @Test
  void aPlayerWithNowhereToGoArrivesInTheLimbo() {
    // Arrange — only the limbo is registered; the "*" pattern must still not pick it as a lobby
    var event = new PlayerChooseInitialServerEvent(player(), null);

    // Act
    this.router.onChooseInitialServer(event);

    // Assert
    assertSame(this.limbo, event.getInitialServer().orElseThrow());
  }

  @Test
  void aPlayerDroppedWithNothingLeftIsHeldInTheLimbo() {
    // Arrange
    var lobby = this.server("Lobby-1");
    var event = kick(player(), lobby);

    // Act
    this.router.onKickedFromServer(event);

    // Assert
    var redirect = assertInstanceOf(KickedFromServerEvent.RedirectPlayer.class, event.getResult());
    assertSame(this.limbo, redirect.getServer());
  }

  @Test
  void aPlayerDroppedByTheLimboItselfIsNotSentBackToIt() {
    // Witness: the limbo going down must end in a disconnect, not a loop onto itself.
    var event = kick(player(), this.limbo);

    this.router.onKickedFromServer(event);

    assertInstanceOf(KickedFromServerEvent.DisconnectPlayer.class, event.getResult());
  }

  @Test
  void maintenanceHoldsArrivalsInTheLimboEvenWithALobbyUp() {
    // Arrange
    this.server("Lobby-1");
    this.router.setMaintenance(true);
    var event = new PlayerChooseInitialServerEvent(player(), null);

    // Act
    this.router.onChooseInitialServer(event);

    // Assert
    assertSame(this.limbo, event.getInitialServer().orElseThrow());
  }

  @Test
  void staffWithTheBypassPermissionPassMaintenance() {
    // Witness: maintenance that also locks staff out would make it impossible to work.
    var lobby = this.server("Lobby-1");
    this.router.setMaintenance(true);
    var staff = player();
    when(staff.hasPermission(BtcArrivalRouterPlugin.MAINTENANCE_BYPASS_PERMISSION)).thenReturn(true);
    var event = new PlayerChooseInitialServerEvent(staff, null);

    this.router.onChooseInitialServer(event);

    assertSame(lobby, event.getInitialServer().orElseThrow());
  }

  @Test
  void theSweepKeepsMaintenancePlayersUntilItEnds() {
    // Arrange
    var waiting = player();
    var request = mock(ConnectionRequestBuilder.class);
    when(waiting.createConnectionRequest(org.mockito.ArgumentMatchers.any())).thenReturn(request);
    doReturn(List.of(waiting)).when(this.limbo).getPlayersConnected();
    this.server("Lobby-1");
    this.router.setMaintenance(true);

    // Act + Assert — held while maintenance is on, released once it is off
    this.router.releaseFromLimbo();
    verify(request, never()).fireAndForget();
    this.router.setMaintenance(false);
    this.router.releaseFromLimbo();
    verify(request).fireAndForget();
  }

  @Test
  void theSweepReleasesLimboPlayersOnceADestinationExists() {
    // Arrange
    var waiting = player();
    var request = mock(ConnectionRequestBuilder.class);
    when(waiting.createConnectionRequest(org.mockito.ArgumentMatchers.any())).thenReturn(request);
    doReturn(List.of(waiting)).when(this.limbo).getPlayersConnected();

    // Act — nothing to go to yet, then a lobby appears
    this.router.releaseFromLimbo();
    verify(request, never()).fireAndForget();
    var lobby = this.server("Lobby-1");
    this.router.releaseFromLimbo();

    // Assert — sent once, and not again on the next sweep (it is now an attempted server)
    verify(waiting).createConnectionRequest(lobby);
    this.router.releaseFromLimbo();
    verify(request).fireAndForget();
    assertEquals(2, this.registered.size());
  }
}
