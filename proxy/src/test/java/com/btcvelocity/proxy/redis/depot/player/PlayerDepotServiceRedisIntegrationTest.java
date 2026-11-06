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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.btcvelocity.proxy.redis.VelocityRedis;
import com.btcvelocity.proxy.redis.depot.Depot;
import com.btcvelocity.proxy.redis.packet.PacketSerializer;
import com.btcvelocity.proxy.redis.provider.LettuceProvider;
import com.velocitypowered.api.proxy.player.PlayerSettings;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.scheduler.Scheduler;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.config.VelocityConfiguration;
import com.velocitypowered.proxy.connection.backend.VelocityServerConnection;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.scheduler.VelocityScheduler;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Runs the player depot against a real Redis and freezes it with {@code docker pause}.
 *
 * <p>Opt-in: start {@code docker run --rm -d --name btc-redis-56 -p 127.0.0.1:56379:6379
 * redis:7.4-alpine}, then {@code gradlew :velocity-proxy:test -PredisIntegration --tests
 * '*RedisIntegrationTest'}.</p>
 */
@Tag("redis-integration")
class PlayerDepotServiceRedisIntegrationTest {

  private static final String CONTAINER = "btc-redis-56";
  private static final int PORT = 56379;
  private static final String SELF = "it-proxy";
  private static final String HASH = "playerentry";
  private static final long HOOK_BUDGET_MS = 50;
  private static final long TICK_MS = 10;
  private static final long LOOP_LAG_BUDGET_MS = 100;
  private static final long CONVERGE_DEADLINE_MS = 30_000;

  private final List<ConnectedPlayer> online = new CopyOnWriteArrayList<>();
  private final Map<UUID, String> servers = new ConcurrentHashMap<>();
  private final Map<String, VelocityServerConnection> connections = new ConcurrentHashMap<>();
  private final AtomicLong slowestHookMs = new AtomicLong();
  private final AtomicLong worstLagMs = new AtomicLong();
  private final VelocityRedis redis = mock(VelocityRedis.class);
  private LettuceProvider provider;
  private PlayerDepotService service;
  private EventLoopGroup group;
  private EventLoop loop;

  @BeforeEach
  void setUp() {
    final VelocityConfiguration.Redis config = mock(VelocityConfiguration.Redis.class);
    when(config.getHost()).thenReturn("127.0.0.1");
    when(config.getPort()).thenReturn(PORT);
    when(config.getPassword()).thenReturn("");
    when(config.getBackend()).thenReturn("redis");
    provider = new LettuceProvider(config, mock(Scheduler.class, RETURNS_DEEP_STUBS),
        new PacketSerializer());
    provider.restart();
    provider.deleteKey(HASH);

    final VelocityServer server = mock(VelocityServer.class);
    when(server.getScheduler()).thenReturn(mock(VelocityScheduler.class, RETURNS_DEEP_STUBS));
    when(server.getConfiguration()).thenReturn(mock(VelocityConfiguration.class));
    when(server.getAllPlayers()).thenAnswer(inv -> List.copyOf(online));
    when(server.getPlayer(any(UUID.class))).thenAnswer(inv -> online.stream()
        .filter(p -> p.getUniqueId().equals(inv.getArgument(0))).findFirst());
    when(redis.getProvider()).thenReturn(provider);
    when(redis.getServer()).thenReturn(server);
    when(redis.getProxyId()).thenReturn(SELF);
    for (String name : List.of("lobby", "survival")) {
      final VelocityServerConnection connection = mock(VelocityServerConnection.class);
      when(connection.getServerInfo())
          .thenReturn(new ServerInfo(name, new InetSocketAddress("127.0.0.1", 30000)));
      connections.put(name, connection);
    }
    service = new PlayerDepotService(redis);
    group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    loop = group.next();
  }

  @AfterEach
  void tearDown() {
    when(redis.isShutdown()).thenReturn(true);
    service.teardown();
    provider.deleteKey(HASH);
    provider.disconnect();
    group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
  }

  @Test
  void aPausedRedisNeverBlocksTheLoopAndDataConvergesOnceItAnswers() throws Exception {
    final ConnectedPlayer alice = join("alice", "lobby");
    final ConnectedPlayer carol = join("carol", "lobby");
    assertTrue(onLoop(() -> service.onPlayerConnect(alice)).get(5, TimeUnit.SECONDS));
    assertTrue(onLoop(() -> service.onPlayerConnect(carol)).get(5, TimeUnit.SECONDS));
    service.refreshNow();
    assertEquals(OptionalInt.of(2), service.getCachedPlayersOnServer("lobby"));

    final ScheduledFuture<?> probe = startLagProbe();
    docker("pause");
    try {
      final ConnectedPlayer bob = join("bob", "survival");
      final CompletableFuture<Boolean> bobLogin = onLoop(() -> service.onPlayerConnect(bob));
      servers.put(alice.getUniqueId(), "survival");
      onLoop(() -> {
        service.onPlayerSwitchServer(alice, "survival");
        return null;
      });
      online.remove(carol);
      onLoop(() -> {
        service.onPlayerDisconnect(carol);
        return null;
      });
      // (a) the count is served from the snapshot, no Redis round trip
      assertEquals(OptionalInt.of(2), onLoop(() -> service.getCachedPlayersOnServer("lobby")));
      assertTrue(bobLogin.get(PlayerDepotService.CONNECT_CHECK_TIMEOUT.toMillis() + 500,
          TimeUnit.MILLISECONDS), "a frozen Redis must not lock bob out");

      Thread.sleep(PlayerDepotService.SNAPSHOT_MAX_AGE.toMillis() + 200);
      // stale snapshot: callers fall back to the local count instead of waiting on Redis
      assertEquals(OptionalInt.empty(), onLoop(() -> service.getCachedPlayersOnServer("lobby")));
    } finally {
      docker("unpause");
      probe.cancel(false);
    }
    System.out.printf("redis-integration: slowest hook %d ms, worst loop lag %d ms%n",
        slowestHookMs.get(), worstLagMs.get());
    assertTrue(slowestHookMs.get() < HOOK_BUDGET_MS, "a hook held the loop "
        + slowestHookMs.get() + " ms");
    assertTrue(worstLagMs.get() < LOOP_LAG_BUDGET_MS, "the loop lagged "
        + worstLagMs.get() + " ms");

    // (b) once Redis answers, the periodic repair makes the hash match the live players
    final Depot<UUID, PlayerEntry> raw = provider.createDepot(PlayerEntry.class);
    final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CONVERGE_DEADLINE_MS);
    while (!matches(raw.values(), Map.of("alice", "survival", "bob", "survival"))) {
      assertTrue(System.nanoTime() < deadline, "Redis did not converge: " + raw.values());
      try {
        service.refreshNow();
      } catch (RuntimeException stillDraining) {
        // the worker may still be timing out the commands issued during the pause
      }
      Thread.sleep(200);
    }
    service.refreshNow();
    assertEquals(OptionalInt.of(2), service.getCachedPlayersOnServer("survival"));
    assertEquals(OptionalInt.of(0), service.getCachedPlayersOnServer("lobby"));
    assertEquals(2, service.getTotalPlayerCount());
  }

  private static boolean matches(final Collection<PlayerEntry> entries,
                                 final Map<String, String> expected) {
    if (entries.size() != expected.size()) {
      return false;
    }
    for (PlayerEntry entry : entries) {
      if (!SELF.equals(entry.getProxyId())
          || !Objects.equals(entry.getServerName(), expected.get(entry.getUsername()))) {
        return false;
      }
    }
    return true;
  }

  private <T> T onLoop(final Callable<T> action) throws Exception {
    return loop.submit(() -> {
      final long start = System.nanoTime();
      try {
        return action.call();
      } finally {
        final long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        slowestHookMs.accumulateAndGet(took, Math::max);
      }
    }).get(5, TimeUnit.SECONDS);
  }

  private ScheduledFuture<?> startLagProbe() {
    final AtomicLong last = new AtomicLong(System.nanoTime());
    return loop.scheduleAtFixedRate(() -> {
      final long now = System.nanoTime();
      final long lag = TimeUnit.NANOSECONDS.toMillis(now - last.getAndSet(now)) - TICK_MS;
      worstLagMs.accumulateAndGet(lag, Math::max);
    }, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
  }

  private ConnectedPlayer join(final String name, final String serverName) {
    final UUID id = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    final ConnectedPlayer player = mock(ConnectedPlayer.class);
    when(player.getUniqueId()).thenReturn(id);
    when(player.getUsername()).thenReturn(name);
    when(player.getQueuePriorities()).thenReturn(Map.of());
    when(player.getRemoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 25577));
    when(player.getPlayerSettings()).thenReturn(mock(PlayerSettings.class));
    when(player.getCurrentServer()).thenAnswer(inv ->
        Optional.ofNullable(servers.get(id)).map(connections::get));
    servers.put(id, serverName);
    online.add(player);
    return player;
  }

  private static void docker(final String verb) throws IOException, InterruptedException {
    final Process process = new ProcessBuilder("docker", verb, CONTAINER)
        .redirectErrorStream(true).start();
    final String output = new String(process.getInputStream().readAllBytes(),
        StandardCharsets.UTF_8);
    assertTrue(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0,
        "docker " + verb + " failed: " + output);
  }
}
