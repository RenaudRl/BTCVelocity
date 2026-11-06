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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.btcvelocity.proxy.redis.VelocityRedis;
import com.btcvelocity.proxy.redis.data.VelocityKick;
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
import java.util.HashMap;
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
import net.kyori.adventure.text.TranslatableComponent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;

class PlayerDepotServiceTest {

  private static final String SELF = "proxy-a";
  private static final String OTHER = "proxy-b";
  private static final String UNCHECKED = "velocity.error.login-check-unavailable";
  private static final String DUPLICATE = "velocity.error.already-connected-proxy.remote";

  private final FakeDepot depot = new FakeDepot();
  private final VelocityRedis redis = mock(VelocityRedis.class);
  private final VelocityServer server = mock(VelocityServer.class);
  private final VelocityConfiguration configuration =
      mock(VelocityConfiguration.class, RETURNS_DEEP_STUBS);
  private final List<ConnectedPlayer> online = new CopyOnWriteArrayList<>();
  private volatile boolean shutdown;
  private PlayerDepotService service;

  @BeforeEach
  @SuppressWarnings({"unchecked", "rawtypes"})
  void setUp() {
    final LettuceProvider provider = mock(LettuceProvider.class);
    when(provider.createDepot(PlayerEntry.class)).thenReturn((Depot) depot);
    when(redis.getProvider()).thenReturn(provider);
    when(redis.getServer()).thenReturn(server);
    when(redis.getProxyId()).thenReturn(SELF);
    // Stubbed once: re-stubbing while the worker calls the mock races in Mockito.
    when(redis.isShutdown()).thenAnswer(inv -> shutdown);
    when(server.getScheduler()).thenReturn(mock(VelocityScheduler.class, RETURNS_DEEP_STUBS));
    when(server.getConfiguration()).thenReturn(configuration);
    when(server.getAllPlayers()).thenAnswer(inv -> List.copyOf(online));
    when(server.getPlayer(any(UUID.class))).thenAnswer(inv -> online.stream()
        .filter(p -> p.getUniqueId().equals(inv.getArgument(0))).findFirst());
    service = new PlayerDepotService(redis);
  }

  @AfterEach
  void tearDown() {
    depot.release();
    shutdown = true;
    service.teardown();
  }

  @Test
  void hooksReturnAtOnceAndASilentRedisRefusesTheLoginByDefault() throws Exception {
    final ConnectedPlayer alice = join("alice");
    depot.hang = new CountDownLatch(1);
    depot.claimHang = new CountDownLatch(1);

    final long start = System.nanoTime();
    final CompletableFuture<Boolean> login = service.onPlayerConnect(alice);
    service.onPlayerSwitchServer(alice, "lobby");
    service.onPlayerSettingsChange(alice, mock(PlayerSettings.class));
    service.onPlayerDisconnect(alice);
    assertTrue(millisSince(start) < 200, "hooks must not wait on Redis");

    assertFalse(login.get(5, TimeUnit.SECONDS), "deny is the default");
    assertTrue(millisSince(start) >= PlayerDepotService.CONNECT_CHECK_TIMEOUT.toMillis() - 50);
    verify(alice).disconnect0(argThat(key(UNCHECKED)), anyBoolean());
  }

  @Test
  void allowLetsTheLoginInWhenRedisIsSilent() throws Exception {
    when(configuration.getRedis().isLoginAllowedWhenUnchecked()).thenReturn(true);
    final ConnectedPlayer alice = join("alice");
    depot.claimHang = new CountDownLatch(1);

    assertTrue(service.onPlayerConnect(alice).get(5, TimeUnit.SECONDS));
    verify(alice, never()).disconnect0(any(Component.class), anyBoolean());
  }

  @Test
  void aFullWriteQueueNeverLetsADuplicateIn() throws Exception {
    final ConnectedPlayer bob = join("bob");
    depot.put(entry(bob.getUniqueId(), "bob", SELF, "lobby"));
    depot.hang = new CountDownLatch(1); // the worker is stuck on Redis...
    for (int i = 0; i < PlayerDepotService.WRITE_QUEUE_CAPACITY + 100; i++) {
      service.onPlayerSwitchServer(bob, "lobby-" + i); // ...and its queue overflows
    }
    final ConnectedPlayer alice = join("alice");
    depot.put(entry(alice.getUniqueId(), "alice", OTHER, "survival"));

    assertFalse(service.onPlayerConnect(alice).get(5, TimeUnit.SECONDS));
    verify(alice).disconnect0(argThat(key(DUPLICATE)), anyBoolean());
    assertEquals(OTHER, depot.map.get(alice.getUniqueId()).getProxyId());
  }

  @Test
  void writesForOnePlayerApplyInIssueOrder() throws Exception {
    final ConnectedPlayer alice = join("alice");
    final ConnectedPlayer bob = join("bob");
    depot.put(entry(alice.getUniqueId(), "alice", SELF, null));
    depot.put(entry(bob.getUniqueId(), "bob", SELF, null));
    depot.hang = new CountDownLatch(1);

    // Everything is issued while Redis hangs, then released at once.
    service.onPlayerSwitchServer(alice, "lobby");
    service.onPlayerSwitchServer(alice, "survival");
    service.onPlayerDisconnect(alice);
    service.onPlayerSwitchServer(bob, "lobby");
    service.onPlayerSwitchServer(bob, "survival");
    depot.hang.countDown();
    awaitWorker();

    assertNull(depot.map.get(alice.getUniqueId()), "disconnect issued last must win");
    assertEquals("survival", depot.map.get(bob.getUniqueId()).getServerName());
  }

  @Test
  void duplicateOnAnotherProxyIsRefusedWhenNotKickingExisting() throws Exception {
    final ConnectedPlayer alice = join("alice");
    depot.put(entry(alice.getUniqueId(), "alice", OTHER, "lobby"));

    assertFalse(service.onPlayerConnect(alice).get(5, TimeUnit.SECONDS));
    verify(alice).disconnect0(argThat(key(DUPLICATE)), anyBoolean());
    assertEquals(OTHER, depot.map.get(alice.getUniqueId()).getProxyId());
  }

  @Test
  void kickExistingTakesTheEntryOverAndKicksTheOtherSession() throws Exception {
    when(configuration.isKickExistingPlayers()).thenReturn(true);
    final ConnectedPlayer alice = join("alice");
    depot.put(entry(alice.getUniqueId(), "alice", OTHER, "lobby"));

    assertTrue(service.onPlayerConnect(alice).get(5, TimeUnit.SECONDS));
    verify(redis).publish(any(VelocityKick.class));
    assertEquals(SELF, depot.map.get(alice.getUniqueId()).getProxyId());
  }

  @Test
  void leftoverEntryOfThisProxyDoesNotRefuseAReconnect() throws Exception {
    final ConnectedPlayer alice = join("alice");
    depot.put(entry(alice.getUniqueId(), "alice", SELF, "lobby"));

    assertTrue(service.onPlayerConnect(alice).get(5, TimeUnit.SECONDS));
    verify(alice, never()).disconnect0(any(Component.class), anyBoolean());
  }

  @Test
  void aStaleLoginNeitherKicksNorWrites() throws Exception {
    final ConnectedPlayer gone = player("alice", null);
    join("alice");
    depot.put(entry(gone.getUniqueId(), "alice", OTHER, "lobby"));

    assertFalse(service.onPlayerConnect(gone).get(5, TimeUnit.SECONDS));
    verify(gone, never()).disconnect0(any(Component.class), anyBoolean());
    assertEquals(OTHER, depot.map.get(gone.getUniqueId()).getProxyId());
  }

  @Test
  void aDisconnectNeverRemovesAnEntryAnotherProxyClaimed() throws Exception {
    final ConnectedPlayer alice = player("alice", null);
    depot.put(entry(alice.getUniqueId(), "alice", OTHER, "lobby")); // moved on to proxy-b

    service.onPlayerDisconnect(alice);
    awaitWorker();

    assertEquals(OTHER, depot.map.get(alice.getUniqueId()).getProxyId());
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
  void releaseAllRemovesOnlyThisProxysEntriesAndStopsWriting() throws Exception {
    final ConnectedPlayer alice = join("alice");
    depot.put(entry(alice.getUniqueId(), "alice", SELF, "lobby"));
    final UUID remote = UUID.randomUUID();
    depot.put(entry(remote, "remote", OTHER, "lobby"));

    service.releaseAll();
    service.onPlayerSwitchServer(alice, "survival");
    service.refreshNow();

    assertEquals(List.of(remote), List.copyOf(depot.map.keySet()));
    assertFalse(service.onPlayerConnect(alice).get(5, TimeUnit.SECONDS));
  }

  @Test
  void snapshotOlderThanTheMaxAgeIsNotServed() {
    final PlayerDepotService.Snapshot snapshot = PlayerDepotService.Snapshot.of(
        List.of(entry(UUID.randomUUID(), "a", SELF, "lobby")), 0L);
    final long maxAge = PlayerDepotService.SNAPSHOT_MAX_AGE.toNanos();

    assertEquals(OptionalInt.of(1), snapshot.countOn("LOBBY", maxAge));
    assertEquals(OptionalInt.of(0), snapshot.countOn("survival", maxAge));
    assertEquals(OptionalInt.empty(), snapshot.countOn("lobby", maxAge + 1));
    assertTrue(snapshot.isStale(maxAge + 1));
    assertTrue(service.getCachedEntries().isEmpty(), "no snapshot yet");
  }

  private void awaitWorker() throws Exception {
    // The worker is FIFO: once a later write has landed, every earlier one has too.
    final ConnectedPlayer probe = join("probe");
    depot.put(entry(probe.getUniqueId(), "probe", SELF, "before"));
    service.onPlayerSwitchServer(probe, "after");
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!"after".equals(depot.map.get(probe.getUniqueId()).getServerName())) {
      assertTrue(System.nanoTime() < deadline, "worker did not drain");
      Thread.sleep(5);
    }
  }

  private static ArgumentMatcher<Component> key(final String key) {
    return component -> component instanceof TranslatableComponent translatable
        && translatable.key().equals(key);
  }

  private static long millisSince(final long start) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
  }

  private ConnectedPlayer join(final String name) {
    final ConnectedPlayer player = player(name, null);
    online.add(player);
    return player;
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
    final Map<String, Object> fields = new HashMap<>(Map.of("uniqueId", id.toString(),
        "username", name, "proxyId", proxyId, "queuePriority", Map.of()));
    if (serverName != null) {
      fields.put("serverName", serverName);
    }
    final PlayerEntry entry = new Gson().fromJson(new Gson().toJson(fields), PlayerEntry.class);
    entry.setDepot(depot);
    return entry;
  }

  /**
   * In-memory depot with the same owner rules as the Lua scripts. {@code hang} makes the sync
   * calls wait (the worker), {@code claimHang} the async login claim, like a paused Redis.
   */
  private static final class FakeDepot implements Depot<UUID, PlayerEntry> {

    final Map<UUID, PlayerEntry> map = new ConcurrentHashMap<>();
    volatile CountDownLatch hang;
    volatile CountDownLatch claimHang;

    void put(final PlayerEntry entry) {
      map.put(entry.getUniqueId(), entry);
    }

    void release() {
      for (CountDownLatch latch : new CountDownLatch[] {hang, claimHang}) {
        if (latch != null) {
          latch.countDown();
        }
      }
    }

    private static void await(final CountDownLatch latch) {
      if (latch != null) {
        try {
          latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }

    private static boolean owns(final PlayerEntry entry, final String proxyId) {
      return entry.getProxyId().equalsIgnoreCase(proxyId);
    }

    @Override
    public boolean contains(final UUID key) {
      await(hang);
      return map.containsKey(key);
    }

    @Override
    public PlayerEntry get(final UUID key) {
      await(hang);
      return map.get(key);
    }

    @Override
    public void upsert(final PlayerEntry value) {
      await(hang);
      value.setDepot(this);
      map.put(value.getUniqueId(), value);
    }

    @Override
    public PlayerEntry remove(final UUID key) {
      await(hang);
      return map.remove(key);
    }

    @Override
    public CompletableFuture<PlayerEntry> claim(final PlayerEntry value, final String proxyId,
                                                final boolean force) {
      final boolean worker = Thread.currentThread().getName().startsWith("btc-redis");
      return CompletableFuture.supplyAsync(() -> {
        await(worker ? hang : claimHang);
        synchronized (map) {
          final PlayerEntry current = map.get(value.getUniqueId());
          if (current != null && !owns(current, proxyId)) {
            if (force) {
              map.put(value.getUniqueId(), value);
            }
            return current;
          }
          map.put(value.getUniqueId(), value);
          return null;
        }
      });
    }

    @Override
    public boolean removeIfOwner(final UUID key, final String proxyId) {
      await(hang);
      synchronized (map) {
        final PlayerEntry current = map.get(key);
        return current != null && owns(current, proxyId) && map.remove(key) != null;
      }
    }

    @Override
    public long removeAllOwnedBy(final String proxyId) {
      await(hang);
      synchronized (map) {
        final long before = map.size();
        map.values().removeIf(entry -> owns(entry, proxyId));
        return before - map.size();
      }
    }

    @Override
    public Collection<PlayerEntry> values() {
      await(hang);
      return new ArrayList<>(map.values());
    }

    @Override
    public Collection<String> keys() {
      await(hang);
      return map.keySet().stream().map(UUID::toString).toList();
    }
  }
}
