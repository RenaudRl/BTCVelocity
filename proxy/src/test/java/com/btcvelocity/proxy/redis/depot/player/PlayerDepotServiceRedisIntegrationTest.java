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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.btcvelocity.proxy.redis.VelocityRedis;
import com.btcvelocity.proxy.redis.depot.Depot;
import com.btcvelocity.proxy.redis.depot.proxy.ProxyDepotService;
import com.btcvelocity.proxy.redis.depot.proxy.ProxyEntry;
import com.btcvelocity.proxy.redis.packet.PacketSerializer;
import com.btcvelocity.proxy.redis.provider.LettuceProvider;
import com.btcvelocity.proxy.redis.provider.RedisProvider.ReapOutcome;
import com.velocitypowered.api.proxy.player.PlayerSettings;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.scheduler.ScheduledTask;
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
import java.time.Duration;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
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
 * '*RedisIntegrationTest'}. One test stops the container and starts it again the same way; for
 * Valkey, start {@code valkey/valkey:8.1.1-alpine} instead and add
 * {@code -PredisImage=valkey/valkey:8.1.1-alpine}. To use another container name or port (the
 * defaults are taken by another run), add {@code -PredisContainer=name -PredisPort=56380}.</p>
 */
@Tag("redis-integration")
class PlayerDepotServiceRedisIntegrationTest {

  /** Defaults match the docker run above; -PredisContainer and -PredisPort override them. */
  private static final String CONTAINER = System.getProperty("btc.redis.container", "btc-redis-56");
  private static final int PORT = Integer.getInteger("btc.redis.port", 56379);
  private static final String HASH = "playerentry";
  private static final String PROXY_HASH = "proxyentry";
  private static final String LEASE_KEY = "reaper-lease";
  private static final int HEARTBEAT_TASK_INDEX = 2;
  /** How much later than the reapers the heartbeats run after a freeze: under one interval. */
  private static final long HEARTBEAT_WAKE_LAG_MS = 500;
  /** Past the heartbeat TTL (5 s) and the lease (3 s): both expire while Redis is frozen. */
  private static final long PAUSE_MS = 6_000;
  /** A lease taken after the pause, renewed, and a reaping pass: a few intervals. */
  private static final long OBSERVE_AFTER_PAUSE_MS = 8_000;
  /** About two repairs; the test fails if the duplicate lives longer than this. */
  private static final long CLOSE_DEADLINE_MS = 4_000;
  private static final long HOOK_BUDGET_MS = 50;
  private static final long TICK_MS = 10;
  private static final long LOOP_LAG_BUDGET_MS = 100;
  private static final long CONVERGE_DEADLINE_MS = 30_000;
  private static final long SHUTDOWN_BUDGET_MS = 20_000;
  /** The server image, also used to start it again after the stop test: -PredisImage=... */
  private static final String IMAGE = System.getProperty("btc.redis.image", "redis:7.4-alpine");

  private final Map<UUID, String> servers = new ConcurrentHashMap<>();
  private final Map<String, VelocityServerConnection> connections = new ConcurrentHashMap<>();
  private final AtomicLong slowestHookMs = new AtomicLong();
  private final AtomicLong worstLagMs = new AtomicLong();
  private final List<Node> nodes = new ArrayList<>();
  private final ScheduledExecutorService timers = Executors.newScheduledThreadPool(8);
  /** The plugin executor of Velocity: a cached pool, so runs of one task can overlap. */
  private final ExecutorService taskPool = Executors.newCachedThreadPool();
  /** While closed, the live proxies' tasks wait before their next run: a frozen process. */
  private volatile CountDownLatch frozenTasks = new CountDownLatch(0);
  /** Closed on top of {@link #frozenTasks} for the heartbeats only: they wake up later. */
  private volatile CountDownLatch frozenHeartbeats = new CountDownLatch(0);
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
    timers.shutdownNow();
    taskPool.shutdownNow();
    try {
      final Node cleaner = node("cleaner");
      cleaner.provider.deleteKey(HASH);
      cleaner.provider.deleteKey(PROXY_HASH);
      cleaner.close();
    } catch (RuntimeException ignored) {
      // the shutdown test already proved the cleanup is best effort
    }
    group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
  }

  @Test
  void aForcedDuplicateIsClosedOnTheProxyThatLostTheEntryInAboutTwoSeconds() throws Exception {
    final Node a = liveNode("live-a");
    final Node b = liveNode("live-b");
    final ConnectedPlayer onA = a.join("alice", "lobby");
    assertTrue(onLoop(() -> a.service.onPlayerConnect(onA)).get(5, TimeUnit.SECONDS));

    // B takes the entry by force while A keeps the session: what a late kick-existing claim does.
    final ConnectedPlayer onB = b.join("alice", "survival");
    final PlayerEntry previous = b.raw.claim(new PlayerEntry(onB, b.proxyId), b.proxyId, true)
        .get(5, TimeUnit.SECONDS);
    final long start = System.nanoTime();
    assertEquals(a.proxyId, previous.getProxyId());

    verify(onA, timeout(CLOSE_DEADLINE_MS)).disconnect(argThat(key("velocity.error.duplicate-session")));
    final long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    System.out.printf("redis-integration: forced duplicate closed on the old proxy after %d ms%n",
        took);
    assertTrue(took >= 900, "closed before the second repair: " + took + " ms");

    Thread.sleep(2 * PlayerDepotService.REFRESH_INTERVAL.toMillis() + 500);
    verify(onA, times(1)).disconnect(any(Component.class));
    verify(onB, never()).disconnect(any(Component.class));
    assertEquals(1, a.service.getDuplicateSessionsClosed());
    assertEquals(0, b.service.getDuplicateSessionsClosed());
    assertEquals(b.proxyId, a.raw.get(onA.getUniqueId()).getProxyId());
  }

  @Test
  void aPausedRedisNeverBlocksTheLoopRefusesLoginsThenConverges() throws Exception {
    final ConnectedPlayer alice = proxyA.join("alice", "lobby");
    final ConnectedPlayer carol = proxyA.join("carol", "lobby");
    assertTrue(onLoop(() -> proxyA.service.onPlayerConnect(alice)).get(5, TimeUnit.SECONDS));
    assertTrue(onLoop(() -> proxyA.service.onPlayerConnect(carol)).get(5, TimeUnit.SECONDS));
    proxyB.service.refreshNow();
    assertEquals(OptionalInt.of(2), proxyB.service.getCachedPlayersOnOtherProxies("lobby"));

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
      // (a) B's count of A's players is served from the snapshot, no Redis round trip
      assertEquals(OptionalInt.of(2),
          onLoop(() -> proxyB.service.getCachedPlayersOnOtherProxies("lobby")));
      assertFalse(bobLogin.get(PlayerDepotService.CONNECT_CHECK_TIMEOUT.toMillis() + 2_000,
          TimeUnit.MILLISECONDS), "deny: an unchecked login is refused");
      verify(bob).disconnect0(argThat(key("velocity.error.login-check-unavailable")), anyBoolean());
      proxyA.leave(bob);

      Thread.sleep(PlayerDepotService.SNAPSHOT_MAX_AGE.toMillis() + 200);
      // stale snapshot: callers fall back to the local count instead of waiting on Redis
      assertEquals(OptionalInt.empty(),
          onLoop(() -> proxyB.service.getCachedPlayersOnOtherProxies("lobby")));
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
  void theStartupProbePassesAndAServerRefusingScriptsIsNamed() throws Exception {
    assertTrue(proxyA.provider.verifyScripting(), "Lua with cjson must run on " + IMAGE);

    assertTrue(cli("ACL", "SETUSER", "noscript", "on", "nopass", "~*", "&*", "+@all",
        "-@scripting").contains("OK"));
    try {
      final Node limited = node("proxy-c", "noscript");
      assertFalse(limited.provider.verifyScripting());

      final ConnectedPlayer alice = limited.join("alice", "lobby");
      assertFalse(onLoop(() -> limited.service.onPlayerConnect(alice)).get(5, TimeUnit.SECONDS));
      verify(alice).disconnect0(argThat(key("velocity.error.login-check-unavailable")),
          anyBoolean());
      final ExecutionException failure = assertThrows(ExecutionException.class, () ->
          limited.raw.claim(new PlayerEntry(alice, limited.proxyId), limited.proxyId, false)
              .get(5, TimeUnit.SECONDS));
      assertTrue(LettuceProvider.isScriptRefusal(failure), failure.toString());
    } finally {
      cli("ACL", "DELUSER", "noscript");
    }
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

  /**
   * What a Redis pause cannot show: its queued commands refresh the keys the moment it resumes.
   * Here the proxies themselves are frozen (every task waits), so the heartbeats and the lease
   * really expire, and on the wake-up the reapers run before the heartbeats. Like Velocity's
   * fixed-rate tasks, each missed run was handed to a cached pool and waits: on the wake-up they
   * all run back to back, a burst of passes with no heartbeat interval between them.
   */
  @Test
  void proxiesFrozenTogetherDoNotReapEachOtherWhenTheirReapersWakeUpFirst() throws Exception {
    final Node a = liveNode("live-a");
    final Node b = liveNode("live-b");
    final ConnectedPlayer alice = a.join("alice", "lobby");
    final ConnectedPlayer bob = b.join("bob", "lobby");
    assertTrue(onLoop(() -> a.service.onPlayerConnect(alice)).get(5, TimeUnit.SECONDS));
    assertTrue(onLoop(() -> b.service.onPlayerConnect(bob)).get(5, TimeUnit.SECONDS));
    awaitTrue("both proxies beat and one holds the reaper lease", () ->
        a.provider.existsKey("heartbeat:live-a") && a.provider.existsKey("heartbeat:live-b")
            && a.provider.get(LEASE_KEY) != null);

    frozenTasks = new CountDownLatch(1);
    frozenHeartbeats = new CountDownLatch(1);
    Thread.sleep(PAUSE_MS);
    assertFalse(a.provider.existsKey("heartbeat:live-a"), "the heartbeat should have expired");
    assertFalse(a.provider.existsKey("heartbeat:live-b"), "the heartbeat should have expired");
    assertNull(a.provider.get(LEASE_KEY), "the lease should have expired");

    frozenTasks.countDown(); // the repairs and the reapers wake up...
    Thread.sleep(HEARTBEAT_WAKE_LAG_MS);
    frozenHeartbeats.countDown(); // ...a little before the heartbeats
    Thread.sleep(OBSERVE_AFTER_PAUSE_MS);

    System.out.printf("redis-integration: after a %d ms freeze, reaped a=%d b=%d, lease held by %s%n",
        PAUSE_MS, a.proxies.getReapedProxyCount(), b.proxies.getReapedProxyCount(),
        a.provider.get(LEASE_KEY));
    assertEquals(0, a.proxies.getReapedProxyCount(), "live-a reaped a live proxy");
    assertEquals(0, b.proxies.getReapedProxyCount(), "live-b reaped a live proxy");
    assertEquals(a.proxyId, entryOf(alice).getProxyId());
    assertEquals(b.proxyId, entryOf(bob).getProxyId());
  }

  /** The Lua scripts themselves: lease holder and heartbeat are checked where the deletion is. */
  @Test
  void theReapScriptDeletesOnlyForTheLeaseHolderAndOnlyWhileTheProxyDoesNotBeat() throws Exception {
    final LettuceProvider redis = proxyA.provider;
    final Depot<String, ProxyEntry> proxies = redis.createDepot(ProxyEntry.class);
    for (String id : List.of("dead", "proxy-a")) {
      final VelocityServer idServer = mock(VelocityServer.class);
      when(idServer.getProxyId()).thenReturn(id);
      proxies.upsert(new ProxyEntry(idServer));
    }
    final ConnectedPlayer onDead = proxyA.join("zed", "lobby");
    final ConnectedPlayer onOther = proxyA.join("yan", "lobby");
    proxyA.raw.claim(new PlayerEntry(onDead, "dead"), "dead", true).get(5, TimeUnit.SECONDS);
    proxyA.raw.claim(new PlayerEntry(onOther, "proxy-b"), "proxy-b", true).get(5, TimeUnit.SECONDS);
    redis.deleteKey(LEASE_KEY);
    redis.deleteKey("heartbeat:dead");
    redis.deleteKey("heartbeat:proxy-a");
    try {
      // Without the lease: nothing is touched.
      assertEquals(ReapOutcome.NOT_HOLDER, reap(redis, "proxy-a"));
      assertFalse(redis.renewLease(LEASE_KEY, "proxy-a", ProxyDepotService.REAPER_LEASE_TTL,
          "heartbeat:proxy-a"));
      assertTrue(redis.acquireLease(LEASE_KEY, "proxy-a", ProxyDepotService.REAPER_LEASE_TTL));
      assertNotNull(proxyA.raw.get(onDead.getUniqueId()));

      // The holder that does not beat does not renew; one that beats does.
      assertFalse(redis.renewLease(LEASE_KEY, "proxy-a", ProxyDepotService.REAPER_LEASE_TTL,
          "heartbeat:proxy-a"), "renewed without its own heartbeat");
      redis.setWithExpiry("heartbeat:proxy-a", "1", 5);
      assertTrue(redis.renewLease(LEASE_KEY, "proxy-a", ProxyDepotService.REAPER_LEASE_TTL,
          "heartbeat:proxy-a"));
      assertFalse(redis.renewLease(LEASE_KEY, "proxy-b", ProxyDepotService.REAPER_LEASE_TTL,
          "heartbeat:proxy-a"), "renewed for another owner");

      // The proxy beats: nothing is touched.
      redis.setWithExpiry("heartbeat:dead", "1", 5);
      assertEquals(ReapOutcome.ALIVE, reap(redis, "proxy-a"));
      assertNotNull(proxyA.raw.get(onDead.getUniqueId()));
      assertTrue(proxies.contains("dead"));

      // Dead, and the caller holds the lease: its players and proxy entry go, nobody else's.
      redis.deleteKey("heartbeat:dead");
      assertEquals(ReapOutcome.REAPED, reap(redis, "proxy-a"));
      assertNull(proxyA.raw.get(onDead.getUniqueId()));
      assertFalse(proxies.contains("dead"));
      assertNotNull(proxyA.raw.get(onOther.getUniqueId()), "another proxy's player was removed");
      assertTrue(proxies.contains("proxy-a"));
    } finally {
      redis.deleteKey(LEASE_KEY);
      redis.deleteKey("heartbeat:proxy-a");
      redis.deleteKey("heartbeat:dead");
    }
  }

  private static ReapOutcome reap(final LettuceProvider redis, final String owner) {
    return redis.reapProxy(LEASE_KEY, owner, "heartbeat:dead", "dead", PlayerEntry.class,
        ProxyEntry.class);
  }

  @Test
  void aDeadProxyIsReapedByExactlyOneSurvivorAndTheLeaseMovesOnWhenTheHolderStops()
      throws Exception {
    final Node a = liveNode("live-a");
    final Node b = liveNode("live-b");
    final Node c = liveNode("live-c");
    final ConnectedPlayer carol = c.join("carol", "lobby");
    assertTrue(onLoop(() -> c.service.onPlayerConnect(carol)).get(5, TimeUnit.SECONDS));
    awaitTrue("the three proxies beat and one holds the lease", () ->
        a.provider.existsKey("heartbeat:live-c") && a.provider.get(LEASE_KEY) != null);

    c.shutdown = true; // c dies: it stops beating and cleans nothing
    awaitTrue("a survivor reaped c", () ->
        a.proxies.getReapedProxyCount() + b.proxies.getReapedProxyCount() >= 1);
    Thread.sleep(2 * ProxyDepotService.HEARTBEAT_INTERVAL.toMillis() + 500);
    assertEquals(1, a.proxies.getReapedProxyCount() + b.proxies.getReapedProxyCount());
    assertNull(a.raw.get(carol.getUniqueId()), "c's players were not cleared");

    // The holder stops cleanly: the lease is released, the other survivor takes it over at once.
    final String holder = a.provider.get(LEASE_KEY);
    final Node survivor = holder.equals(a.proxyId) ? b : a;
    final Node stopping = holder.equals(a.proxyId) ? a : b;
    stopping.close();
    nodes.remove(stopping);
    awaitTrue("the other survivor holds the lease", () -> survivor.proxyId.equals(
        survivor.provider.get(LEASE_KEY)));
  }

  private void awaitTrue(final String what, final BooleanSupplier condition)
      throws InterruptedException {
    final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CONVERGE_DEADLINE_MS);
    while (!condition.getAsBoolean()) {
      assertTrue(System.nanoTime() < deadline, "timed out waiting: " + what);
      Thread.sleep(100);
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
    return node(proxyId, null);
  }

  private Node node(final String proxyId, final String username) {
    final Node node = new Node(proxyId, username, false);
    nodes.add(node);
    return node;
  }

  /** A proxy whose repair and heartbeat tasks really run, once a second like in production. */
  private Node liveNode(final String proxyId) {
    final Node node = new Node(proxyId, null, true);
    nodes.add(node);
    return node;
  }

  /**
   * A scheduler that runs the repeating tasks like Velocity's: {@code scheduleAtFixedRate} on
   * {@link #timers}, each run handed to the cached pool {@link #taskPool} (VelocityTask.run does
   * the same with the plugin's executor), so a run that waits does not delay the next ones.
   */
  private VelocityScheduler liveScheduler() {
    final VelocityScheduler scheduler = mock(VelocityScheduler.class);
    final AtomicInteger built = new AtomicInteger();
    when(scheduler.buildTask(any(), any(Runnable.class))).thenAnswer(inv -> {
      final Runnable task = inv.getArgument(1);
      // Built in this order: the repair, the heartbeat, the reaper.
      final boolean heartbeat = built.incrementAndGet() == HEARTBEAT_TASK_INDEX;
      final AtomicReference<Duration> period = new AtomicReference<>(Duration.ofSeconds(1));
      final Scheduler.TaskBuilder builder = mock(Scheduler.TaskBuilder.class);
      when(builder.repeat(any(Duration.class))).thenAnswer(repeat -> {
        period.set(repeat.getArgument(0));
        return builder;
      });
      when(builder.schedule()).thenAnswer(schedule -> {
        final ScheduledFuture<?> future = timers.scheduleAtFixedRate(() -> taskPool.execute(() -> {
          try {
            frozenTasks.await();
            if (heartbeat) {
              frozenHeartbeats.await();
            }
            task.run();
          } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
          } catch (RuntimeException failed) {
            // Velocity logs a failing task and keeps its schedule; so does this
          }
        }), 0, period.get().toMillis(), TimeUnit.MILLISECONDS);
        final ScheduledTask handle = mock(ScheduledTask.class);
        doAnswer(cancel -> future.cancel(false)).when(handle).cancel();
        return handle;
      });
      return builder;
    });
    return scheduler;
  }

  /** One proxy: its own Lettuce connections, players and depot service. */
  private final class Node {

    final String proxyId;
    final List<ConnectedPlayer> online = new CopyOnWriteArrayList<>();
    final LettuceProvider provider;
    final VelocityRedis redis = mock(VelocityRedis.class);
    volatile PlayerDepotService service; // read by the mock's answers, which timers call early
    volatile ProxyDepotService proxies;
    final Depot<UUID, PlayerEntry> raw;
    volatile boolean shutdown;

    Node(final String proxyId, final String username, final boolean live) {
      this.proxyId = proxyId;
      final VelocityConfiguration.Redis config = mock(VelocityConfiguration.Redis.class);
      when(config.getHost()).thenReturn("127.0.0.1");
      when(config.getPort()).thenReturn(PORT);
      when(config.getUsername()).thenReturn(username);
      when(config.getPassword()).thenReturn("");
      when(config.getBackend()).thenReturn("redis");
      provider = new LettuceProvider(config, mock(Scheduler.class, RETURNS_DEEP_STUBS),
          new PacketSerializer());
      provider.restart();

      final VelocityServer server = mock(VelocityServer.class);
      final VelocityScheduler scheduler = live ? liveScheduler()
          : mock(VelocityScheduler.class, RETURNS_DEEP_STUBS);
      when(server.getScheduler()).thenReturn(scheduler);
      when(server.getProxyId()).thenReturn(proxyId);
      when(server.getConfiguration()).thenReturn(mock(VelocityConfiguration.class, RETURNS_DEEP_STUBS));
      when(server.getAllPlayers()).thenAnswer(inv -> List.copyOf(online));
      when(server.getPlayer(any(UUID.class))).thenAnswer(inv -> online.stream()
          .filter(p -> p.getUniqueId().equals(inv.getArgument(0))).findFirst());
      when(redis.getProvider()).thenReturn(provider);
      when(redis.getServer()).thenReturn(server);
      when(redis.getProxyId()).thenReturn(proxyId);
      when(redis.isShutdown()).thenAnswer(inv -> shutdown);
      // Stubbed before any task runs: re-stubbing a mock the timers call races in Mockito.
      when(redis.getPlayerService()).thenAnswer(inv -> this.service);
      when(redis.getProxyService()).thenAnswer(inv -> this.proxies);
      service = new PlayerDepotService(redis);
      raw = provider.createDepot(PlayerEntry.class);
      if (live) {
        proxies = new ProxyDepotService(redis);
      }
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
        if (proxies != null) {
          proxies.teardown();
        }
      } finally {
        provider.disconnect();
      }
    }
  }

  private static void startRedis() throws IOException, InterruptedException {
    run("docker", "run", "--rm", "-d", "--name", CONTAINER, "-p", "127.0.0.1:" + PORT + ":6379",
        IMAGE);
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (!cli("ping").contains("PONG")) {
      assertTrue(System.nanoTime() < deadline, "Redis did not come back");
      Thread.sleep(200);
    }
  }

  /** Runs the server's CLI in the container: valkey-cli on Valkey, redis-cli on Redis. */
  private static String cli(final String... args) throws IOException, InterruptedException {
    // "cli" is $0 for sh -c; the arguments follow as "$@".
    final List<String> command = new ArrayList<>(List.of("docker", "exec", CONTAINER, "sh", "-c",
        "exec $(command -v valkey-cli || command -v redis-cli) \"$@\"", "cli"));
    command.addAll(List.of(args));
    return run(command.toArray(String[]::new));
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
