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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.btcvelocity.proxy.redis.VelocityRedis;
import com.btcvelocity.proxy.redis.depot.Depot;
import com.btcvelocity.proxy.redis.provider.LettuceProvider;
import com.velocitypowered.api.scheduler.Scheduler;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.scheduler.VelocityScheduler;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The drain mark of a proxy: published on request, kept alive by the heartbeat while the drain
 * lasts, and read by the others to leave a drained proxy out of the transfer targets. The keys are
 * in memory here; {@code PlayerDepotServiceRedisIntegrationTest} runs the same against a server.
 */
class ProxyDepotServiceDrainTest {

  private static final String SELF = "proxy-a";
  private static final String PEER = "proxy-b";

  private final Set<String> keys = ConcurrentHashMap.newKeySet();
  private final List<Runnable> tasks = new ArrayList<>();
  private ProxyDepotService service;

  @BeforeEach
  @SuppressWarnings({"unchecked", "rawtypes"})
  void setUp() {
    final LettuceProvider provider = mock(LettuceProvider.class);
    when(provider.createDepot(ProxyEntry.class)).thenReturn(mock(Depot.class));
    when(provider.existsKey(anyString())).thenAnswer(inv -> keys.contains(inv.<String>getArgument(0)));
    doAnswer(inv -> keys.add(inv.<String>getArgument(0))).when(provider)
        .setWithExpiry(anyString(), anyString(), anyLong());
    doAnswer(inv -> keys.remove(inv.<String>getArgument(0))).when(provider).deleteKey(anyString());
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
    service = new ProxyDepotService(redis);
  }

  private void heartbeat() {
    tasks.getFirst().run();
  }

  @Test
  void aProxyThatBeatsAndWasNeverDrainedIsATransferTarget() {
    keys.add("heartbeat:" + PEER);

    assertTrue(service.isTransferTarget(PEER));
  }

  @Test
  void aProxyThatDoesNotBeatIsNotATransferTarget() {
    assertFalse(service.isTransferTarget(PEER));
  }

  @Test
  void aDrainedProxyIsNotATransferTargetAlthoughItBeats() {
    keys.add("heartbeat:" + SELF);

    service.setDraining(true);

    assertTrue(service.isAlive(SELF));
    assertFalse(service.isTransferTarget(SELF));
  }

  @Test
  void theHeartbeatRenewsTheMarkOnlyWhileTheProxyIsDrained() {
    heartbeat();
    assertFalse(keys.contains("draining:" + SELF), "marked without a drain");

    service.setDraining(true);
    keys.remove("draining:" + SELF); // expired meanwhile
    heartbeat();
    assertTrue(keys.contains("draining:" + SELF));

    service.setDraining(false);
    heartbeat();
    assertFalse(keys.contains("draining:" + SELF));
    assertTrue(service.isTransferTarget(SELF));
  }
}
