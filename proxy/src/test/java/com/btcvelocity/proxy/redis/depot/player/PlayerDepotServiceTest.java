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

package com.btcvelocity.proxy.redis.depot.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.btcvelocity.proxy.redis.VelocityRedis;
import com.btcvelocity.proxy.redis.depot.Depot;
import com.btcvelocity.proxy.redis.provider.LettuceProvider;
import com.google.gson.Gson;
import com.velocitypowered.api.proxy.player.PlayerSettings;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.config.VelocityConfiguration;
import com.velocitypowered.proxy.connection.backend.VelocityServerConnection;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.scheduler.VelocityScheduler;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlayerDepotServiceTest {

  private static final String SELF = "proxy-a";
  private static final String OTHER = "proxy-b";

  private final FakeDepot depot = new FakeDepot();
  private final VelocityRedis redis = mock(VelocityRedis.class);
  private final VelocityServer server = mock(VelocityServer.class);
  private final VelocityConfiguration configuration = mock(VelocityConfiguration.class);
  private final List<ConnectedPlayer> online = new CopyOnWriteArrayList<>();
  private PlayerDepotService service;

  @BeforeEach
  @SuppressWarnings({"unchecked", "rawtypes"})
  void setUp() {
    final LettuceProvider provider = mock(LettuceProvider.class);
    when(provider.createDepot(PlayerEntry.class)).thenReturn((Depot) depot);
    when(redis.getProvider()).thenReturn(provider);
    when(redis.getServer()).thenReturn(server);
    when(redis.getProxyId()).thenReturn(SELF);
    when(server.getScheduler()).thenReturn(mock(VelocityScheduler.class, RETURNS_DEEP_STUBS));
    when(server.getConfiguration()).thenReturn(configuration);
    when(server.getAllPlayers()).thenAnswer(inv -> List.copyOf(online));
    when(server.getPlayer(any(UUID.class))).thenAnswer(inv -> online.stream()
        .filter(p -> p.getUniqueId().equals(inv.getArgument(0))).findFirst());
    service = new PlayerDepotService(redis);
  }

  @AfterEach
  void tearDown() {
    when(redis.isShutdown()).thenReturn(true);
    service.teardown();
  }

  @Test
  void lifecycleHooksReturnAtOnceWhileRedisHangsAndLoginFailsOpen() throws Exception {
    final ConnectedPlayer alice = player("alice", null);
    final PlayerSettings settings = mock(PlayerSettings.class);
    depot.hang = new CountDownLatch(1);

    final long start = System.nanoTime();
    final CompletableFuture<Boolean> login = service.onPlayerConnect(alice);
    service.onPlayerSwitchServer(alice, "lobby");
    service.onPlayerSettingsChange(alice, settings);
    service.onPlayerDisconnect(alice);
    assertTrue(millisSince(start) < 100, "hooks must not wait on Redis");

    assertTrue(login.get(5, TimeUnit.SECONDS), "a silent Redis must not lock players out");
    final long waited = millisSince(start);
    assertTrue(waited >= PlayerDepotService.CONNECT_CHECK_TIMEOUT.toMillis() - 50, "waited " + waited);
    assertTrue(waited < PlayerDepotService.CONNECT_CHECK_TIMEOUT.toMillis() + 1000, "waited " + waited);

    depot.hang.countDown();
  }

  @Test
  void writesForOnePlayerApplyInIssueOrder() throws Exception {
    final ConnectedPlayer alice = player("alice", null);
    final ConnectedPlayer bob = player("bob", null);
    online.add(bob);
    depot.hang = new CountDownLatch(1);

    // Everything is issued while Redis hangs, then released at once.
    service.onPlayerConnect(alice);
    service.onPlayerSwitchServer(alice, "lobby");
    service.onPlayerSwitchServer(alice, "survival");
    service.onPlayerDisconnect(alice);
    service.onPlayerConnect(bob);
    service.onPlayerSwitchServer(bob, "lobby");
    service.onPlayerSwitchServer(bob, "survival");
    depot.hang.countDown();
    awaitWorker();

    assertNull(depot.map.get(alice.getUniqueId()), "disconnect issued last must win");
    assertEquals("survival", depot.map.get(bob.getUniqueId()).getServerName());
  }

  @Test
  void duplicateOnAnotherProxyIsRefusedWhenNotKickingExisting() throws Exception {
    final ConnectedPlayer alice = player("alice", null);
    depot.put(entry(alice.getUniqueId(), "alice", OTHER, "lobby"));

    assertFalse(service.onPlayerConnect(alice).get(5, TimeUnit.SECONDS));
    verify(alice).disconnect0(any(Component.class), anyBoolean());
    assertEquals(OTHER, depot.map.get(alice.getUniqueId()).getProxyId());
  }

  @Test
  void leftoverEntryOfThisProxyDoesNotRefuseAReconnect() throws Exception {
    final ConnectedPlayer alice = player("alice", null);
    depot.put(entry(alice.getUniqueId(), "alice", SELF, "lobby"));

    assertTrue(service.onPlayerConnect(alice).get(5, TimeUnit.SECONDS));
    verify(alice, never()).disconnect0(any(Component.class), anyBoolean());
  }

  @Test
  void refreshRepairsThisProxyAndBuildsTheSnapshot() {
    final ConnectedPlayer missing = player("missing", "lobby");
    final ConnectedPlayer drifted = player("drifted", "survival");
    online.add(missing);
    online.add(drifted);
    depot.put(entry(drifted.getUniqueId(), "drifted", SELF, "lobby"));
    final UUID ghost = UUID.randomUUID();
    depot.put(entry(ghost, "ghost", SELF, "lobby"));
    final UUID remote = UUID.randomUUID();
    depot.put(entry(remote, "remote", OTHER, "LOBBY"));

    assertEquals(OptionalInt.empty(), service.getCachedPlayersOnServer("lobby"));
    service.refreshNow();

    assertEquals("lobby", depot.map.get(missing.getUniqueId()).getServerName());
    assertEquals("survival", depot.map.get(drifted.getUniqueId()).getServerName());
    assertNull(depot.map.get(ghost));
    assertEquals(OTHER, depot.map.get(remote).getProxyId());
    // The snapshot is the view read before the repair: drifted + ghost + remote on lobby.
    assertEquals(OptionalInt.of(3), service.getCachedPlayersOnServer("Lobby"));
    assertEquals(3, service.getTotalPlayerCount());
  }

  @Test
  void snapshotOlderThanTheMaxAgeIsNotServed() {
    final PlayerDepotService.Snapshot snapshot = PlayerDepotService.Snapshot.of(
        List.of(entry(UUID.randomUUID(), "a", SELF, "lobby")), 0L);
    final long maxAge = PlayerDepotService.SNAPSHOT_MAX_AGE.toNanos();

    assertEquals(OptionalInt.of(1), snapshot.countOn("LOBBY", maxAge));
    assertEquals(OptionalInt.of(0), snapshot.countOn("survival", maxAge));
    assertEquals(OptionalInt.empty(), snapshot.countOn("lobby", maxAge + 1));
  }

  private void awaitWorker() throws Exception {
    // The worker is FIFO: once a later write has landed, every earlier one has too.
    final ConnectedPlayer probe = player("probe", null);
    service.onPlayerConnect(probe);
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!depot.map.containsKey(probe.getUniqueId())) {
      assertTrue(System.nanoTime() < deadline, "worker did not drain");
      Thread.sleep(5);
    }
  }

  private static long millisSince(final long start) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
  }

  private static ConnectedPlayer player(final String name, final String serverName) {
    final ConnectedPlayer player = mock(ConnectedPlayer.class);
    when(player.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes(name.getBytes()));
    when(player.getUsername()).thenReturn(name);
    when(player.getQueuePriorities()).thenReturn(Map.of());
    when(player.getRemoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 25565));
    when(player.getPlayerSettings()).thenReturn(mock(PlayerSettings.class));
    if (serverName == null) {
      when(player.getCurrentServer()).thenReturn(Optional.empty());
    } else {
      final VelocityServerConnection connection = mock(VelocityServerConnection.class);
      when(connection.getServerInfo())
          .thenReturn(new ServerInfo(serverName, new InetSocketAddress("127.0.0.1", 30000)));
      when(player.getCurrentServer()).thenReturn(Optional.of(connection));
    }
    return player;
  }

  private PlayerEntry entry(final UUID id, final String name, final String proxyId,
                            final String serverName) {
    final String json = new Gson().toJson(Map.of("uniqueId", id.toString(), "username", name,
        "proxyId", proxyId, "serverName", serverName, "queuePriority", Map.of()));
    final PlayerEntry entry = new Gson().fromJson(json, PlayerEntry.class);
    entry.setDepot(depot);
    return entry;
  }

  /**
   * In-memory depot. {@code hang} makes every call wait, like a paused Redis.
   */
  private static final class FakeDepot implements Depot<UUID, PlayerEntry> {

    final Map<UUID, PlayerEntry> map = new ConcurrentHashMap<>();
    volatile CountDownLatch hang;

    void put(final PlayerEntry entry) {
      map.put(entry.getUniqueId(), entry);
    }

    private void maybeHang() {
      final CountDownLatch latch = hang;
      if (latch != null) {
        try {
          latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }

    @Override
    public boolean contains(final UUID key) {
      maybeHang();
      return map.containsKey(key);
    }

    @Override
    public PlayerEntry get(final UUID key) {
      maybeHang();
      return map.get(key);
    }

    @Override
    public void upsert(final PlayerEntry value) {
      maybeHang();
      value.setDepot(this);
      map.put(value.getUniqueId(), value);
    }

    @Override
    public PlayerEntry remove(final UUID key) {
      maybeHang();
      return map.remove(key);
    }

    @Override
    public Collection<PlayerEntry> values() {
      maybeHang();
      return new ArrayList<>(map.values());
    }

    @Override
    public Collection<String> keys() {
      maybeHang();
      return map.keySet().stream().map(UUID::toString).toList();
    }
  }
}
