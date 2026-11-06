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

package com.btcvelocity.proxy.cluster.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.btcvelocity.proxy.redis.VelocityRedis;
import com.btcvelocity.proxy.redis.depot.player.PlayerDepotService;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.server.VelocityRegisteredServer;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class RedisClusterPlayerServiceCountTest {

  private final VelocityServer server = mock(VelocityServer.class);
  private final PlayerDepotService players = mock(PlayerDepotService.class);
  private final RedisClusterPlayerService service;

  RedisClusterPlayerServiceCountTest() {
    final VelocityRedis redis = mock(VelocityRedis.class);
    when(redis.getPlayerService()).thenReturn(players);
    final VelocityRegisteredServer lobby = mock(VelocityRegisteredServer.class);
    when(lobby.getPlayersConnected())
        .thenReturn(List.of(mock(ConnectedPlayer.class), mock(ConnectedPlayer.class)));
    when(server.getServer("lobby")).thenReturn(Optional.of(lobby));
    when(server.getServer("gone")).thenReturn(Optional.empty());
    service = new RedisClusterPlayerService(server, redis);
  }

  @Test
  void otherProxiesFromTheSnapshotPlusThisProxyLive() {
    when(players.getCachedPlayersOnOtherProxies("lobby")).thenReturn(OptionalInt.of(17));
    when(players.getCachedPlayerCountOnOtherProxies()).thenReturn(30);
    when(server.getLocalPlayerCount()).thenReturn(4);

    assertEquals(17 + 2, service.getPlayersOnServerCount("lobby"));
    assertEquals(30 + 4, service.getTotalPlayerCount());
  }

  @Test
  void staleSnapshotFallsBackToThisProxysPlayers() {
    when(players.getCachedPlayersOnOtherProxies("lobby")).thenReturn(OptionalInt.empty());
    when(players.getCachedPlayersOnOtherProxies("gone")).thenReturn(OptionalInt.empty());

    assertEquals(2, service.getPlayersOnServerCount("lobby"));
    assertEquals(0, service.getPlayersOnServerCount("gone"));
  }

  @Test
  void deadSnapshotListsThisProxysPlayers() {
    when(players.getCachedEntries()).thenReturn(Optional.empty());
    when(server.getAllPlayers()).thenReturn(List.of(mock(ConnectedPlayer.class)));

    assertEquals(1, service.getAllPlayers().size());
  }
}
