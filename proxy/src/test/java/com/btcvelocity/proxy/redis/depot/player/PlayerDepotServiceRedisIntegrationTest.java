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
import static org.mockito.Mockito.verify;
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
import java.util.ArrayList;
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
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;

/**
 * Runs two proxies' player depots against one real Redis, and freezes or stops it with Docker.
 *
 * <p>Opt-in: start {@code docker run --rm -d --name btc-redis-56 -p 127.0.0.1:56379:6379
 * redis:7.4-alpine}, then {@code gradlew :velocity-proxy:test -PredisIntegration --tests
 * '*RedisIntegrationTest'}. One test stops the container and starts it again the same way.</p>
 */
@Tag("redis-integration")
class PlayerDepotServiceRedisIntegrationTest {

  private static final String CONTAINER = "btc-redis-56";
  private static final int PORT = 56379;
  private static final String HASH = "playerentry";
  private static final long HOOK_BUDGET_MS = 50;
  private static final long TICK_MS = 10;
  private static final long LOOP_LAG_BUDGET_MS = 100;
  private static final long CONVERGE_DEADLINE_MS = 30_000;
  private static final long SHUTDOWN_BUDGET_MS = 20_000;

  private final Map<UUID, String> servers = new ConcurrentHashMap<>();
  private final Map<String, VelocityServerConnection> connections = new ConcurrentHashMap<>();
  private final AtomicLong slowestHookMs = new AtomicLong();
  private final AtomicLong worstLagMs = new AtomicLong();
  private final List<Node> nodes = new ArrayList<>();
  private Node proxyA;
  private Node proxyB;
  private EventLoopGroup group;
  private EventLoop loop;

  @BeforeEach
  void setUp() {
    for (String name : List.of("lobby", "survival")) {
      final VelocityServerConnection connection = mock(VelocityServerConnection.class);
      when(connection.getServerInfo())
          .thenReturn(new ServerInfo(name, new InetSocketAddress("127.0.0.1", 30000)));
      connections.put(name, connection);
    }
    proxyA = node("proxy-a");
    proxyA.provider.deleteKey(HASH);
    proxyB = node("proxy-b");
    group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    loop = group.next();
  }

  @AfterEach
  void tearDown() {
    for (Node node : nodes) {
      try {
        node.close();
      } catch (RuntimeException e) {
        // a node cut off by the stop test may not have reconnected yet; the hash is wiped below
      }
    }
    try {
      final Node cleaner = node("cleaner");
      cleaner.provider.deleteKey(HASH);
      cleaner.close();
    } catch (RuntimeException ignored) {
      // the shutdown test already proved the cleanup is best effort
    }
    group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
  }

  @Test
  void aPausedRedisNeverBlocksTheLoopRefusesLoginsThenConverges() throws Exception {
    final ConnectedPlayer alice = proxyA.join("alice", "lobby");
    final ConnectedPlayer carol = proxyA.join("carol", "lobby");
    assertTrue(onLoop(() -> proxyA.service.onPlayerConnect(alice)).get(5, TimeUnit.SECONDS));
    assertTrue(onLoop(() -> proxyA.service.onPlayerConnect(carol)).get(5, TimeUnit.SECONDS));
    proxyA.service.refreshNow();
    assertEquals(OptionalInt.of(2), proxyA.service.getCachedPlayersOnServer("lobby"));

    final ConnectedPlayer bob = proxyA.join("bob", "survival");
    final ScheduledFuture<?> probe = startLagProbe();
    docker("pause");
    try {
      final CompletableFuture<Boolean> bobLogin =
          onLoop(() -> proxyA.service.onPlayerConnect(bob));
      servers.put(alice.getUniqueId(), "survival");
      onLoop(() -> {
        proxyA.service.onPlayerSwitchServer(alice, "survival");
        return null;
      });
      proxyA.leave(carol);
      // (a) the count is served from the snapshot, no Redis round trip
      assertEquals(OptionalInt.of(2), onLoop(() -> proxyA.service.getCachedPlayersOnServer("lobby")));
      assertFalse(bobLogin.get(PlayerDepotService.CONNECT_CHECK_TIMEOUT.toMillis() + 2_000,
          TimeUnit.MILLISECONDS), "deny: an unchecked login is refused");
      verify(bob).disconnect0(argThat(key("velocity.error.login-check-unavailable")), anyBoolean());
      proxyA.leave(bob);

      Thread.sleep(PlayerDepotService.SNAPSHOT_MAX_AGE.toMillis() + 200);
      // stale snapshot: callers fall back to the local count instead of waiting on Redis
      assertEquals(OptionalInt.empty(), onLoop(() -> proxyA.service.getCachedPlayersOnServer("lobby")));
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

    // (b) once Redis answers, the repair drops carol and the late claim of the refused bob
    awaitHash(proxyA, Map.of("alice", "survival"));

    // (c) and logins are checked again
    final ConnectedPlayer dave = proxyA.join("dave", "lobby");
    assertTrue(onLoop(() -> proxyA.service.onPlayerConnect(dave)).get(5, TimeUnit.SECONDS));
  }

  @Test
  void aPlayerHeldByOneProxyIsRefusedByTheOtherAndKeepsItsEntry() throws Exception {
    final ConnectedPlayer onA = proxyA.join("alice", "lobby");
    assertTrue(onLoop(() -> proxyA.service.onPlayerConnect(onA)).get(5, TimeUnit.SECONDS));

    final ConnectedPlayer onB = proxyB.join("alice", "survival");
    assertFalse(onLoop(() -> proxyB.service.onPlayerConnect(onB)).get(5, TimeUnit.SECONDS));
    verify(onB).disconnect0(argThat(key("velocity.error.already-connected-proxy.remote")),
        anyBoolean());

    // B's refused session goes away: its owner-guarded removals must leave A's entry alone.
    proxyB.leave(onB);
    proxyB.service.refreshNow();
    assertFalse(proxyB.raw.removeIfOwner(onA.getUniqueId(), proxyB.proxyId));
    proxyB.service.removeEntriesOf(proxyB.proxyId);
    awaitIdle();
    assertEquals(proxyA.proxyId, entryOf(onA).getProxyId());

    // A hands its players over in one batch; B's entries are untouched.
    final ConnectedPlayer bob = proxyB.join("bob", "lobby");
    assertTrue(onLoop(() -> proxyB.service.onPlayerConnect(bob)).get(5, TimeUnit.SECONDS));
    proxyA.service.releaseAll();
    assertNull(proxyA.raw.get(onA.getUniqueId()));
    assertEquals(proxyB.proxyId, entryOf(bob).getProxyId());

    // Now B may take alice.
    final ConnectedPlayer again = proxyB.join("alice", "survival");
    assertTrue(onLoop(() -> proxyB.service.onPlayerConnect(again)).get(5, TimeUnit.SECONDS));
    assertEquals(proxyB.proxyId, entryOf(again).getProxyId());
  }

  @Test
  void aStoppedRedisStillLetsTheShutdownFinish() throws Exception {
    final ConnectedPlayer alice = proxyA.join("alice", "lobby");
    assertTrue(onLoop(() -> proxyA.service.onPlayerConnect(alice)).get(5, TimeUnit.SECONDS));

    docker("stop");
    try {
      proxyA.service.onPlayerSwitchServer(alice, "survival");
      final long start = System.nanoTime();
      for (Runnable step : List.<Runnable>of(proxyA.service::releaseAll, proxyA.service::teardown,
          proxyA.provider::disconnect)) {
        try {
          step.run();
        } catch (RuntimeException expected) {
          // Redis is gone: each step may fail, none may hang
        }
      }
      final long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      System.out.printf("redis-integration: shutdown with Redis stopped took %d ms%n", took);
      assertTrue(took < SHUTDOWN_BUDGET_MS, "shutdown took " + took + " ms");
      nodes.remove(proxyA);
    } finally {
      startRedis();
    }
  }

  private PlayerEntry entryOf(final ConnectedPlayer player) {
    return Objects.requireNonNull(proxyA.raw.get(player.getUniqueId()), "no entry");
  }

  /** Lets B's queued disconnect write land: a later write on the same worker has landed too. */
  private void awaitIdle() throws Exception {
    final ConnectedPlayer probe = proxyB.join("probe", "lobby");
    assertTrue(onLoop(() -> proxyB.service.onPlayerConnect(probe)).get(5, TimeUnit.SECONDS));
    proxyB.service.onPlayerSwitchServer(probe, "survival");
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!"survival".equals(entryOf(probe).getServerName())) {
      assertTrue(System.nanoTime() < deadline, "B's worker did not drain");
      Thread.sleep(20);
    }
  }

  private void awaitHash(final Node node, final Map<String, String> expected) throws Exception {
    final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CONVERGE_DEADLINE_MS);
    while (!matches(node.raw.values(), node.proxyId, expected)) {
      assertTrue(System.nanoTime() < deadline, "Redis did not converge: " + node.raw.values());
      try {
        node.service.refreshNow();
      } catch (RuntimeException stillDraining) {
        // the commands issued during the pause may still be timing out
      }
      Thread.sleep(200);
    }
  }

  private static boolean matches(final Collection<PlayerEntry> entries, final String proxyId,
                                 final Map<String, String> expected) {
    if (entries.size() != expected.size()) {
      return false;
    }
    for (PlayerEntry entry : entries) {
      if (!proxyId.equals(entry.getProxyId())
          || !Objects.equals(entry.getServerName(), expected.get(entry.getUsername()))) {
        return false;
      }
    }
    return true;
  }

  private static ArgumentMatcher<Component> key(final String key) {
    return component -> component instanceof TranslatableComponent translatable
        && translatable.key().equals(key);
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

  private Node node(final String proxyId) {
    final Node node = new Node(proxyId);
    nodes.add(node);
    return node;
  }

  /** One proxy: its own Lettuce connections, players and depot service. */
  private final class Node {

    final String proxyId;
    final List<ConnectedPlayer> online = new CopyOnWriteArrayList<>();
    final LettuceProvider provider;
    final VelocityRedis redis = mock(VelocityRedis.class);
    final PlayerDepotService service;
    final Depot<UUID, PlayerEntry> raw;
    volatile boolean shutdown;

    Node(final String proxyId) {
      this.proxyId = proxyId;
      final VelocityConfiguration.Redis config = mock(VelocityConfiguration.Redis.class);
      when(config.getHost()).thenReturn("127.0.0.1");
      when(config.getPort()).thenReturn(PORT);
      when(config.getPassword()).thenReturn("");
      when(config.getBackend()).thenReturn("redis");
      provider = new LettuceProvider(config, mock(Scheduler.class, RETURNS_DEEP_STUBS),
          new PacketSerializer());
      provider.restart();

      final VelocityServer server = mock(VelocityServer.class);
      when(server.getScheduler()).thenReturn(mock(VelocityScheduler.class, RETURNS_DEEP_STUBS));
      when(server.getConfiguration()).thenReturn(mock(VelocityConfiguration.class, RETURNS_DEEP_STUBS));
      when(server.getAllPlayers()).thenAnswer(inv -> List.copyOf(online));
      when(server.getPlayer(any(UUID.class))).thenAnswer(inv -> online.stream()
          .filter(p -> p.getUniqueId().equals(inv.getArgument(0))).findFirst());
      when(redis.getProvider()).thenReturn(provider);
      when(redis.getServer()).thenReturn(server);
      when(redis.getProxyId()).thenReturn(proxyId);
      when(redis.isShutdown()).thenAnswer(inv -> shutdown);
      service = new PlayerDepotService(redis);
      raw = provider.createDepot(PlayerEntry.class);
    }

    ConnectedPlayer join(final String name, final String serverName) {
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

    /** What Velocity does once the connection closed: unlist, then the disconnect hook. */
    void leave(final ConnectedPlayer player) {
      online.remove(player);
      service.onPlayerDisconnect(player);
    }

    void close() {
      shutdown = true;
      try {
        service.teardown();
      } finally {
        provider.disconnect();
      }
    }
  }

  private static void startRedis() throws IOException, InterruptedException {
    run("docker", "run", "--rm", "-d", "--name", CONTAINER, "-p", "127.0.0.1:" + PORT + ":6379",
        "redis:7.4-alpine");
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (!run("docker", "exec", CONTAINER, "redis-cli", "ping").contains("PONG")) {
      assertTrue(System.nanoTime() < deadline, "Redis did not come back");
      Thread.sleep(200);
    }
  }

  private static void docker(final String verb) throws IOException, InterruptedException {
    run("docker", verb, CONTAINER);
  }

  private static String run(final String... command) throws IOException, InterruptedException {
    final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    final String output = new String(process.getInputStream().readAllBytes(),
        StandardCharsets.UTF_8);
    final boolean done = process.waitFor(60, TimeUnit.SECONDS);
    if (command.length > 1 && "exec".equals(command[1])) {
      return output;
    }
    assertTrue(done && process.exitValue() == 0, String.join(" ", command) + " failed: " + output);
    return output;
  }
}
