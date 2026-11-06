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

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.btcvelocity.api.cluster.ClusterPlayerService;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

/** The router balances on the cluster count and keeps working when the cluster fails. */
class BtcArrivalRouterClusterCountTest {

  private final ProxyServer proxy = mock(ProxyServer.class);
  private final ClusterPlayerService cluster = mock(ClusterPlayerService.class);
  private final List<RegisteredServer> registered = new ArrayList<>();
  private final BtcArrivalRouterPlugin router = new BtcArrivalRouterPlugin(
      this.proxy, NOPLogger.NOP_LOGGER, ArrivalPolicy.parse("Lobby-*"), null, null);

  BtcArrivalRouterClusterCountTest() {
    doReturn(this.registered).when(this.proxy).getAllServers();
    when(this.proxy.getClusterPlayerService()).thenReturn(this.cluster);
  }

  /** A server with {@code local} players on this proxy. */
  private RegisteredServer server(String name, int local) {
    var server = mock(RegisteredServer.class);
    when(server.getServerInfo()).thenReturn(new ServerInfo(name, new InetSocketAddress(25599)));
    doReturn(java.util.Collections.nCopies(local, mock(Player.class))).when(server).getPlayersConnected();
    doReturn(Optional.of(server)).when(this.proxy).getServer(name);
    this.registered.add(server);
    return server;
  }

  private RegisteredServer arrivalOf() {
    var player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(UUID.randomUUID());
    var event = new PlayerChooseInitialServerEvent(player, null);
    this.router.onChooseInitialServer(event);
    return event.getInitialServer().orElseThrow();
  }

  @Test
  void theLeastPopulatedByTheClusterWinsOverTheLeastPopulatedHere() {
    // Arrange — this proxy sees Lobby-1 empty, but the other proxies fill it
    this.server("Lobby-1", 0);
    var quiet = this.server("Lobby-2", 5);
    when(this.cluster.getPlayersOnServerCount("Lobby-1")).thenReturn(30);
    when(this.cluster.getPlayersOnServerCount("Lobby-2")).thenReturn(6);

    // Act + Assert — a local count would have picked Lobby-1
    assertSame(quiet, this.arrivalOf());
  }

  @Test
  void aFailingClusterFallsBackToTheLocalCountWithoutLosingTheArrival() {
    // Arrange
    this.server("Lobby-1", 4);
    var emptyHere = this.server("Lobby-2", 1);
    when(this.cluster.getPlayersOnServerCount("Lobby-1")).thenThrow(new IllegalStateException("redis"));
    when(this.cluster.getPlayersOnServerCount("Lobby-2")).thenThrow(new IllegalStateException("redis"));

    // Act + Assert — the player still arrives, on the least populated as this proxy sees it
    assertSame(emptyHere, this.arrivalOf());
  }

  @Test
  void aServerOutsideThePolicyIsNotCounted() {
    // Arrange — a server the policy does not cover costs no cluster call
    this.server("Lobby-1", 0);
    this.server("Survival-1", 0);

    // Act
    this.arrivalOf();

    // Assert
    verify(this.cluster, never()).getPlayersOnServerCount("Survival-1");
  }
}
