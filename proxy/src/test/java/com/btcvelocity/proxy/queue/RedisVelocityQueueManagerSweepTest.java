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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.btcvelocity.proxy.queue.redis.depot.VelocityQueueDepotService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RedisVelocityQueueManagerSweepTest {

  private final VelocityQueueDepotService depot = mock(VelocityQueueDepotService.class);

  @Test
  void anUnknownServerWithAnEmptySnapshotIsRemoved() {
    when(depot.getQueueKeys()).thenReturn(List.of("Dungeon-7"));
    when(depot.removeQueueIfEmpty("Dungeon-7")).thenReturn(true);

    final List<String> removed = RedisVelocityQueueManager.removeOrphanSnapshots(depot, name -> false);

    assertEquals(List.of("Dungeon-7"), removed);
  }

  @Test
  void aKnownServerIsNeverTouched() {
    // Witness: a registered or in-memory server keeps its snapshot, empty or not.
    when(depot.getQueueKeys()).thenReturn(List.of("Lobby-1", "Dungeon-7"));
    when(depot.removeQueueIfEmpty("Dungeon-7")).thenReturn(true);

    final List<String> removed =
        RedisVelocityQueueManager.removeOrphanSnapshots(depot, Set.of("Lobby-1")::contains);

    assertEquals(List.of("Dungeon-7"), removed);
    verify(depot, never()).removeQueueIfEmpty("Lobby-1");
  }

  @Test
  void anUnknownServerWhoseSnapshotHoldsPlayersIsReportedAsKept() {
    // The depot refuses (players waiting, or unreadable): nothing is claimed removed.
    when(depot.getQueueKeys()).thenReturn(List.of("Dungeon-7"));
    when(depot.removeQueueIfEmpty("Dungeon-7")).thenReturn(false);

    final List<String> removed = RedisVelocityQueueManager.removeOrphanSnapshots(depot, name -> false);

    assertEquals(List.of(), removed);
    verify(depot).removeQueueIfEmpty("Dungeon-7");
  }
}
