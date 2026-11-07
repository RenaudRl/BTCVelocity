/*
 * Copyright (C) 2026 Velocity Contributors
 *
 * The Velocity API is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package com.velocitypowered.proxy.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.proxy.config.BackendServerConfig;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A reload only touches the servers that come from velocity.toml: a server registered through the
 * plugin API (for instance by btc-cloudnet-registry) must survive it.
 */
class ServerMapReloadTest {

  private static final String LOBBY = "127.0.0.1:30066";
  private static final String GAMES = "127.0.0.1:30067";

  private static ServerInfo apiServer(final String name) {
    return new ServerInfo(name, new InetSocketAddress(InetAddress.getLoopbackAddress(), 31000));
  }

  private static Map<String, BackendServerConfig> toml(final String... nameAndAddress) {
    var builder = new java.util.LinkedHashMap<String, BackendServerConfig>();
    for (int i = 0; i < nameAndAddress.length; i += 2) {
      builder.put(nameAndAddress[i], new BackendServerConfig(nameAndAddress[i + 1]));
    }
    return Map.copyOf(builder);
  }

  @Test
  void apiRegisteredServerSurvivesReload() {
    ServerMap map = new ServerMap(null);
    Map<String, BackendServerConfig> config = toml("lobby", LOBBY);
    map.applyConfiguredServers(Map.of(), config);
    map.register(apiServer("cloudnet-1"));

    map.applyConfiguredServers(config, config);

    assertTrue(map.getServer("cloudnet-1").isPresent());
    assertTrue(map.getServer("lobby").isPresent());
  }

  @Test
  void serverRemovedFromTomlIsUnregistered() {
    ServerMap map = new ServerMap(null);
    Map<String, BackendServerConfig> before = toml("lobby", LOBBY, "games", GAMES);
    map.applyConfiguredServers(Map.of(), before);
    map.register(apiServer("cloudnet-1"));

    map.applyConfiguredServers(before, toml("lobby", LOBBY));

    assertFalse(map.getServer("games").isPresent());
    assertTrue(map.getServer("lobby").isPresent());
    assertTrue(map.getServer("cloudnet-1").isPresent());
  }

  @Test
  void removedServerPlayersAreReturnedForEvacuation() {
    ServerMap map = new ServerMap(null);
    Map<String, BackendServerConfig> before = toml("lobby", LOBBY, "games", GAMES);
    map.applyConfiguredServers(Map.of(), before);
    ConnectedPlayer player = mock(ConnectedPlayer.class);
    when(player.getUniqueId()).thenReturn(UUID.randomUUID());
    map.getServer("games").orElseThrow().addPlayer(player);

    Collection<ConnectedPlayer> evacuate = map.applyConfiguredServers(before, toml("lobby", LOBBY));

    assertEquals(1, evacuate.size());
    assertSame(player, evacuate.iterator().next());
  }

  @Test
  void changedAddressReplacesTheServerAndEvacuates() {
    ServerMap map = new ServerMap(null);
    Map<String, BackendServerConfig> before = toml("lobby", LOBBY);
    map.applyConfiguredServers(Map.of(), before);
    ConnectedPlayer player = mock(ConnectedPlayer.class);
    when(player.getUniqueId()).thenReturn(UUID.randomUUID());
    map.getServer("lobby").orElseThrow().addPlayer(player);

    Collection<ConnectedPlayer> evacuate = map.applyConfiguredServers(before, toml("lobby", GAMES));

    assertEquals(1, evacuate.size());
    assertEquals(30067, map.getServer("lobby").orElseThrow().getServerInfo().getAddress().getPort());
  }

  @Test
  void tomlServerWithAnApiServerNameTakesOverLikeUpstream() {
    ServerMap map = new ServerMap(null);
    map.register(apiServer("lobby"));

    Map<String, BackendServerConfig> config = toml("lobby", LOBBY);
    map.applyConfiguredServers(Map.of(), config);

    assertEquals(30066, map.getServer("lobby").orElseThrow().getServerInfo().getAddress().getPort());
  }

  @Test
  void emptyTomlRemovesEveryTomlServerAndKeepsApiOnes() {
    ServerMap map = new ServerMap(null);
    Map<String, BackendServerConfig> before = toml("lobby", LOBBY);
    map.applyConfiguredServers(Map.of(), before);
    map.register(apiServer("cloudnet-1"));

    map.applyConfiguredServers(before, Map.of());

    assertFalse(map.getServer("lobby").isPresent());
    assertTrue(map.getServer("cloudnet-1").isPresent());
  }
}
