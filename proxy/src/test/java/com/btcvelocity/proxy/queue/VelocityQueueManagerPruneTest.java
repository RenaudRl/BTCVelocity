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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class VelocityQueueManagerPruneTest {

  private static VelocityQueue queueOf(final int size) {
    final VelocityQueue queue = mock(VelocityQueue.class);
    when(queue.size()).thenReturn(size);
    return queue;
  }

  @Test
  void anEmptyQueueOfAnUnregisteredServerIsDropped() {
    final VelocityQueue gone = queueOf(0);
    final Map<String, VelocityQueue> queues = new ConcurrentHashMap<>(Map.of("Dungeon-7", gone));

    final List<String> dropped = VelocityQueueManager.pruneStaleQueues(queues, name -> false);

    assertEquals(List.of("Dungeon-7"), dropped);
    assertTrue(queues.isEmpty());
    verify(gone).discard();
  }

  @Test
  void aRegisteredServerKeepsItsEmptyQueue() {
    // Witness: emptiness alone is not staleness, or every idle lobby would lose its queue.
    final Map<String, VelocityQueue> queues = new ConcurrentHashMap<>(Map.of("Lobby-1", queueOf(0)));

    VelocityQueueManager.pruneStaleQueues(queues, Set.of("Lobby-1")::contains);

    assertEquals(Set.of("Lobby-1"), queues.keySet());
  }

  @Test
  void anUnregisteredServerKeepsAQueueThatStillHoldsPlayers() {
    // A service restarted under the same name must find its waiting players again.
    final VelocityQueue waiting = queueOf(3);
    final Map<String, VelocityQueue> queues = new ConcurrentHashMap<>(Map.of("Dungeon-7", waiting));

    final List<String> dropped = VelocityQueueManager.pruneStaleQueues(queues, name -> false);

    assertTrue(dropped.isEmpty());
    assertEquals(Set.of("Dungeon-7"), queues.keySet());
    verify(waiting, never()).discard();
  }
}
