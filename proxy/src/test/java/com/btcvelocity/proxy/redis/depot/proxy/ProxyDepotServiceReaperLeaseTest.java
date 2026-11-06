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

package com.btcvelocity.proxy.redis.depot.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.btcvelocity.proxy.redis.VelocityRedis;
import com.btcvelocity.proxy.redis.depot.Depot;
import com.btcvelocity.proxy.redis.depot.player.PlayerDepotService;
import com.btcvelocity.proxy.redis.provider.LettuceProvider;
import com.velocitypowered.api.scheduler.Scheduler;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.scheduler.VelocityScheduler;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * One proxy reaps at a time, through a lease it renews on every pass (AD-092 point 2). The lease
 * and the heartbeats are in memory here; {@code PlayerDepotServiceRedisIntegrationTest} runs the
 * same rules against a real server.
 */
class ProxyDepotServiceReaperLeaseTest {

  private static final String SELF = "proxy-a";
  private static final String DEAD = "proxy-b";

  private final Map<String, ProxyEntry> entries = new ConcurrentHashMap<>();
  private final AtomicReference<String> lease = new AtomicReference<>();
  private final Set<String> beating = ConcurrentHashMap.newKeySet();
  private final AtomicLong clock = new AtomicLong(1_000_000_000L);
  private final AtomicBoolean shutdown = new AtomicBoolean();
  private final LettuceProvider provider = mock(LettuceProvider.class);
  private final PlayerDepotService players = mock(PlayerDepotService.class);
  private final List<Runnable> tasks = new ArrayList<>();
  private ProxyDepotService service;
  private Runnable reap;

  @BeforeEach
  @SuppressWarnings({"unchecked", "rawtypes"})
  void setUp() {
    when(provider.createDepot(ProxyEntry.class)).thenReturn((Depot) new MapDepot(entries));
    when(provider.existsKey(anyString())).thenAnswer(inv -> beating.contains(inv.<String>getArgument(0)));
    when(provider.acquireLease(eq(ProxyDepotService.REAPER_LEASE_KEY), anyString(), any(Duration.class)))
        .thenAnswer(inv -> lease.compareAndSet(null, inv.<String>getArgument(1)));
    when(provider.renewLease(eq(ProxyDepotService.REAPER_LEASE_KEY), anyString(), any(Duration.class)))
        .thenAnswer(inv -> inv.<String>getArgument(1).equals(lease.get()));
    when(provider.releaseLease(eq(ProxyDepotService.REAPER_LEASE_KEY), anyString()))
        .thenAnswer(inv -> lease.compareAndSet(inv.<String>getArgument(1), null));

    final VelocityServer server = mock(VelocityServer.class);
    when(server.getProxyId()).thenReturn(SELF);
    final VelocityScheduler scheduler = mock(VelocityScheduler.class);
    when(scheduler.buildTask(any(), any(Runnable.class))).thenAnswer(inv -> {
      tasks.add(inv.getArgument(1));
      return mock(Scheduler.TaskBuilder.class, RETURNS_SELF);
    });
    when(server.getScheduler()).thenReturn(scheduler);
    final VelocityRedis redis = mock(VelocityRedis.class);
    when(redis.getProvider()).thenReturn(provider);
    when(redis.getServer()).thenReturn(server);
    when(redis.getProxyId()).thenReturn(SELF);
    when(redis.getPlayerService()).thenReturn(players);
    when(redis.isShutdown()).thenAnswer(inv -> shutdown.get());

    service = new ProxyDepotService(redis, clock::get);
    reap = tasks.get(1); // the heartbeat task is built first
    entries.put(DEAD, entry(DEAD));
    beating.add("heartbeat:" + SELF);
  }

  @Test
  void theHolderReapsADeadProxyFromItsSecondPassOnly() {
    reap.run(); // takes the lease: the others had no time to publish yet
    assertEquals(SELF, lease.get());
    assertTrue(entries.containsKey(DEAD));
    verify(players, never()).removeEntriesOf(anyString());

    reap.run(); // renewed: now it may reap
    assertNull(entries.get(DEAD));
    verify(players).removeEntriesOf(DEAD);
    assertEquals(1, service.getReapedProxyCount());
    assertEquals(SELF, lease.get());
  }

  @Test
  void aProxyWithoutTheLeaseNeverReapsNorTakesIt() {
    lease.set("proxy-c"); // another live proxy holds it

    for (int i = 0; i < 5; i++) {
      reap.run();
    }

    assertTrue(entries.containsKey(DEAD));
    verify(players, never()).removeEntriesOf(anyString());
    assertEquals("proxy-c", lease.get());
  }

  @Test
  void aFrozenHolderTakesTheLeaseAgainBeforeItReapsAtItsWakeUp() {
    beating.add("heartbeat:" + DEAD);
    reap.run();
    reap.run(); // holds the lease, nothing to reap

    // Frozen for longer than the lease and the heartbeats: all expired, nobody renewed.
    clock.addAndGet(Duration.ofSeconds(6).toNanos());
    lease.set(null);
    beating.clear();

    reap.run(); // wake-up: its renewal fails, it takes the lease again and judges nothing
    assertTrue(entries.containsKey(DEAD), "reaped on the wake-up pass");
    assertEquals(SELF, lease.get());

    beating.add("heartbeat:" + DEAD); // the others publish again within one interval
    reap.run();
    assertTrue(entries.containsKey(DEAD), "reaped a proxy that is alive again");
    assertEquals(0, service.getReapedProxyCount());
  }

  @Test
  void aFrozenHolderWhoseLeaseWasTakenOverNeverReaps() {
    beating.add("heartbeat:" + DEAD);
    reap.run();
    reap.run();
    lease.set("proxy-c"); // expired while frozen, then taken by another proxy
    beating.remove("heartbeat:" + DEAD);

    reap.run();
    reap.run();

    assertTrue(entries.containsKey(DEAD));
    assertEquals("proxy-c", lease.get());
    assertEquals(0, service.getReapedProxyCount());
  }

  @Test
  void aPassFrozenAfterItsRenewalDoesNotReapWhatItReadBefore() {
    reap.run();
    when(provider.existsKey(anyString())).thenAnswer(inv -> {
      clock.addAndGet(ProxyDepotService.REAPER_LEASE_TTL.toNanos()); // frozen right after the read
      return false;
    });

    reap.run();

    assertTrue(entries.containsKey(DEAD));
    verify(players, never()).removeEntriesOf(anyString());
  }

  @Test
  void aRenewalThatFailsOnRedisStopsThePassAndForgetsTheLease() {
    beating.add("heartbeat:" + DEAD);
    reap.run();
    reap.run();
    when(provider.renewLease(anyString(), anyString(), any(Duration.class)))
        .thenThrow(new IllegalStateException("redis down"));

    assertThrows(IllegalStateException.class, reap::run);

    beating.remove("heartbeat:" + DEAD);
    when(provider.renewLease(anyString(), anyString(), any(Duration.class))).thenReturn(false);
    lease.set(null);
    reap.run(); // Redis is back: the lease is taken again, not assumed
    assertTrue(entries.containsKey(DEAD));
    assertEquals(SELF, lease.get());
    assertEquals(0, service.getReapedProxyCount());
  }

  @Test
  void theShutdownReleasesTheLeaseOfTheHolderOnly() {
    reap.run();
    assertEquals(SELF, lease.get());

    shutdown.set(true);
    service.teardown();
    assertNull(lease.get(), "released: another proxy takes over at once");
    clearInvocations(provider);
    reap.run();
    verify(provider, never()).renewLease(anyString(), anyString(), any(Duration.class));

    lease.set("proxy-c");
    service.teardown();
    assertEquals("proxy-c", lease.get(), "released a lease it does not hold");
  }

  @Test
  void theLeaseExpiresBeforeAHeartbeatDoesAndIsRenewedMoreOftenThanItExpires() {
    assertTrue(ProxyDepotService.REAPER_LEASE_TTL.compareTo(ProxyDepotService.HEARTBEAT_TTL) < 0,
        "a dead holder's lease must be free before its own heartbeat expires");
    assertTrue(ProxyDepotService.HEARTBEAT_INTERVAL.compareTo(ProxyDepotService.REAPER_LEASE_TTL) < 0,
        "renewed at least once per lease");
  }

  private static ProxyEntry entry(final String proxyId) {
    final VelocityServer server = mock(VelocityServer.class);
    when(server.getProxyId()).thenReturn(proxyId);
    return new ProxyEntry(server);
  }

  private record MapDepot(Map<String, ProxyEntry> map) implements Depot<String, ProxyEntry> {

    @Override
    public boolean contains(final String key) {
      return map.containsKey(key);
    }

    @Override
    public ProxyEntry get(final String key) {
      return map.get(key);
    }

    @Override
    public void upsert(final ProxyEntry value) {
      value.setDepot(this);
      map.put(value.getUniqueId(), value);
    }

    @Override
    public ProxyEntry remove(final String key) {
      return map.remove(key);
    }

    @Override
    public Collection<ProxyEntry> values() {
      return List.copyOf(map.values());
    }

    @Override
    public Collection<String> keys() {
      return List.copyOf(map.keySet());
    }
  }
}
