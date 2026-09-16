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

package com.btcvelocity.proxy.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The two bounds of the deduplication registry, each with the witness that it really bites. */
class BridgeDeduplicationTest {

  @Test
  void aRedeliveryInsideTheWindowIsSeenAsDuplicate() {
    final BridgeDeduplication registry = new BridgeDeduplication(1_000L, 16);
    final UUID id = UUID.randomUUID();

    assertFalse(registry.alreadySeen(id, 1_000L), "first delivery is not a duplicate");
    assertTrue(registry.alreadySeen(id, 1_500L), "redelivery inside the window is a duplicate");
  }

  @Test
  void aDifferentIdIsNeverADuplicate() {
    // Witness: without this, a registry that answered "duplicate" to everything would pass above.
    final BridgeDeduplication registry = new BridgeDeduplication(1_000L, 16);

    assertFalse(registry.alreadySeen(UUID.randomUUID(), 1_000L));
    assertFalse(registry.alreadySeen(UUID.randomUUID(), 1_000L));
  }

  @Test
  void anIdIsForgottenOnceTheWindowHasPassed() {
    final BridgeDeduplication registry = new BridgeDeduplication(1_000L, 16);
    final UUID id = UUID.randomUUID();

    assertFalse(registry.alreadySeen(id, 1_000L));
    assertFalse(registry.alreadySeen(id, 2_500L), "the window has passed, the id is forgotten");
  }

  @Test
  void theEntryCapEvictsTheOldestFirst() {
    // The time window alone would let a retrying backend grow this map for as long as the proxy
    // runs: the cap is the second bound, and this proves it evicts rather than refuses.
    final BridgeDeduplication registry = new BridgeDeduplication(600_000L, 2);
    final UUID first = UUID.randomUUID();
    final UUID second = UUID.randomUUID();
    final UUID third = UUID.randomUUID();

    assertFalse(registry.alreadySeen(first, 1_000L));
    assertFalse(registry.alreadySeen(second, 1_000L));
    assertFalse(registry.alreadySeen(third, 1_000L));

    assertEquals(2, registry.size(), "the cap holds");
    assertFalse(registry.alreadySeen(first, 1_000L), "the oldest id was evicted");
    assertTrue(registry.alreadySeen(third, 1_000L), "the newest id is still remembered");
  }

  @Test
  void boundsMustBePositive() {
    assertThrows(IllegalArgumentException.class, () -> new BridgeDeduplication(0L, 16));
    assertThrows(IllegalArgumentException.class, () -> new BridgeDeduplication(1_000L, 0));
  }
}
