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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.btcvelocity.proxy.redis.VelocityRedis;
import com.btcvelocity.proxy.redis.depot.player.PlayerDepotService;
import com.btcvelocity.proxy.redis.depot.player.PlayerEntry;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class RedisClusterPlayerServiceNamesTest {

  private static final String THIS_PROXY = "proxy-a";

  private final VelocityServer server = mock(VelocityServer.class);
  private final PlayerDepotService players = mock(PlayerDepotService.class);
  private final RedisClusterPlayerService service;

  RedisClusterPlayerServiceNamesTest() {
    final VelocityRedis redis = mock(VelocityRedis.class);
    when(redis.getPlayerService()).thenReturn(players);
    when(redis.getProxyId()).thenReturn(THIS_PROXY);
    service = new RedisClusterPlayerService(server, redis);
  }

  @Test
  void thisProxyLiveAndTheOtherProxiesFromTheSnapshotWithoutReadingRedis() {
    final ConnectedPlayer alice = connected("Alice");
    when(server.getAllPlayers()).thenReturn(List.of(alice));
    // Carol is a stale entry of this proxy in the snapshot: only the live list counts for it.
    final List<PlayerEntry> snapshot = List.of(
        entry("Carol", THIS_PROXY), entry("Bob", "proxy-b"), entry("Dave", "PROXY-C"));
    when(players.getCachedEntries()).thenReturn(Optional.of(snapshot));

    assertEquals(Set.of("Alice", "Bob", "Dave"),
        service.getPlayerNames().stream().collect(Collectors.toSet()));
    verify(players, never()).getAll();
  }

  @Test
  void aPlayerSeenLiveAndInTheSnapshotIsListedOnce() {
    final ConnectedPlayer alice = connected("Alice");
    final List<PlayerEntry> snapshot = List.of(entry("Alice", "proxy-b"));
    when(server.getAllPlayers()).thenReturn(List.of(alice));
    when(players.getCachedEntries()).thenReturn(Optional.of(snapshot));

    assertEquals(List.of("Alice"), List.copyOf(service.getPlayerNames()));
  }

  @Test
  void deadSnapshotListsThisProxysPlayersOnly() {
    final ConnectedPlayer alice = connected("Alice");
    when(server.getAllPlayers()).thenReturn(List.of(alice));
    when(players.getCachedEntries()).thenReturn(Optional.empty());

    assertEquals(List.of("Alice"), List.copyOf(service.getPlayerNames()));
    verify(players, never()).getAll();
  }

  private static ConnectedPlayer connected(final String name) {
    final ConnectedPlayer player = mock(ConnectedPlayer.class);
    when(player.getUsername()).thenReturn(name);
    return player;
  }

  private static PlayerEntry entry(final String name, final String proxyId) {
    final PlayerEntry entry = mock(PlayerEntry.class);
    when(entry.getUsername()).thenReturn(name);
    when(entry.getProxyId()).thenReturn(proxyId);
    return entry;
  }
}
