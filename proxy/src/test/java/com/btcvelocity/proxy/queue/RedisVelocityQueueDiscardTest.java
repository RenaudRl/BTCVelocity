/*
 * Copyright (C) 2026 Velocity Contributors
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

package com.btcvelocity.proxy.queue;

import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.btcvelocity.api.queue.QueueState;
import com.btcvelocity.proxy.queue.redis.depot.VelocityQueueDepotService;
import com.btcvelocity.proxy.redis.VelocityRedis;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.server.VelocityRegisteredServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A dropped queue takes its Redis snapshot with it, and never writes it back. */
class RedisVelocityQueueDiscardTest {

  private static final String SERVER_NAME = "Dungeon-7";
  private static final long ABSENCE_WINDOW_MS = 300;

  private final VelocityQueueDepotService depot = mock(VelocityQueueDepotService.class);
  private RedisVelocityQueue queue;

  @BeforeEach
  void setUp() {
    final VelocityServer server = mock(VelocityServer.class);
    final VelocityRedis redis = mock(VelocityRedis.class);
    when(server.getRedis()).thenReturn(redis);
    when(redis.getQueueService()).thenReturn(depot);
    final VelocityQueueManager manager = mock(VelocityQueueManager.class);
    when(manager.isMasterProxy()).thenReturn(true);
    final VelocityRegisteredServer backend = mock(VelocityRegisteredServer.class, RETURNS_DEEP_STUBS);
    when(backend.getServerInfo().getName()).thenReturn(SERVER_NAME);
    queue = new RedisVelocityQueue(server, manager, backend, QueueState.ACTIVE);
  }

  @Test
  void discardingRemovesTheSnapshotOfThatServer() {
    queue.discard();

    verify(depot, timeout(2_000)).removeQueueIfOwner(SERVER_NAME);
  }

  @Test
  void aDiscardedQueueNeverWritesItsSnapshotBack() {
    queue.discard();

    queue.setState(QueueState.PAUSED);

    verify(depot, after(ABSENCE_WINDOW_MS).never()).upsertQueue(queue);
  }

  @Test
  void aTornDownQueueStillPersistsItself() {
    // Witness: teardown (proxy shutdown) keeps its final snapshot, or the guard above would pass
    // for a queue that never persists at all.
    queue.teardown();

    verify(depot, timeout(2_000)).upsertQueue(queue);
    verify(depot, never()).removeQueueIfOwner(SERVER_NAME);
  }
}
