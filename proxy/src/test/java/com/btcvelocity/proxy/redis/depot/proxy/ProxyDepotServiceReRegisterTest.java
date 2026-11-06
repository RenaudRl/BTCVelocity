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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.btcvelocity.proxy.redis.VelocityRedis;
import com.btcvelocity.proxy.redis.depot.Depot;
import com.btcvelocity.proxy.redis.provider.LettuceProvider;
import com.velocitypowered.api.scheduler.Scheduler;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.scheduler.VelocityScheduler;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/**
 * A proxy whose heartbeat lapsed (GC pause, Redis hiccup) gets reaped by its peers although it
 * still runs. It must put its own entry back, or it stays invisible to the cluster for good
 * (CTD {@code bba967f0c}).
 */
class ProxyDepotServiceReRegisterTest {

  private static final String SELF = "proxy-a";

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void aReapedProxyReRegistersOnItsNextHeartbeat() {
    final Map<String, ProxyEntry> entries = new ConcurrentHashMap<>();
    final Depot<String, ProxyEntry> depot = new MapDepot(entries);
    final LettuceProvider provider = mock(LettuceProvider.class);
    when(provider.createDepot(ProxyEntry.class)).thenReturn((Depot) depot);
    final VelocityServer server = mock(VelocityServer.class);
    when(server.getProxyId()).thenReturn(SELF);
    final List<Runnable> tasks = new ArrayList<>();
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

    final ProxyDepotService service = new ProxyDepotService(redis);
    assertTrue(entries.containsKey(SELF));
    final Runnable heartbeat = tasks.getFirst();

    entries.remove(SELF); // a peer reaped us
    heartbeat.run();

    assertEquals(List.of(SELF), List.copyOf(entries.keySet()));

    // Shutting down: the flag is set first, then the teardown; a late beat must not undo it.
    when(redis.isShutdown()).thenReturn(true);
    service.teardown();
    heartbeat.run();
    assertTrue(entries.isEmpty());
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
