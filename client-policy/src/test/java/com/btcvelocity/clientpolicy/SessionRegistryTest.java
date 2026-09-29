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

package com.btcvelocity.clientpolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SessionRegistryTest {

  private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Test
  void anEventForAConnectionAlreadyGoneLeavesNothingBehind() {
    // Arrange
    var registry = new SessionRegistry();
    var connection = new Object();

    // Act
    var opened = registry.open(ID, connection, () -> false);

    // Assert
    assertTrue(opened.isEmpty());
    assertEquals(0, registry.size(), "the state inserted before the liveness test is withdrawn");
  }

  @Test
  void aConnectionThatDiesBetweenInsertAndTestIsWithdrawn() {
    // Arrange: the liveness test runs after the insert, so a disconnect that lands in between
    // must still leave the map empty.
    var registry = new SessionRegistry();
    var connection = new Object();
    AtomicReference<Integer> sizeWhenTested = new AtomicReference<>();

    // Act
    var opened = registry.open(ID, connection, () -> {
      sizeWhenTested.set(registry.size());
      return false;
    });

    // Assert
    assertEquals(1, sizeWhenTested.get(), "the state is already in the map when liveness is read");
    assertTrue(opened.isEmpty());
    assertEquals(0, registry.size());
  }

  @Test
  void theSameConnectionKeepsItsState() {
    var registry = new SessionRegistry();
    var connection = new Object();

    var first = registry.open(ID, connection, () -> true).orElseThrow();
    first.recordBrand("fabric");

    assertSame(first, registry.open(ID, connection, () -> true).orElseThrow());
    assertEquals("fabric", registry.find(ID, connection).orElseThrow().snapshot().brand());
  }

  @Test
  void theOldConnectionsDisconnectDoesNotEraseTheNewerConnectionsState() {
    // Arrange: the same account reconnects before the first disconnect is processed.
    var registry = new SessionRegistry();
    var older = new Object();
    var newer = new Object();
    registry.open(ID, older, () -> true);
    var current = registry.open(ID, newer, () -> true).orElseThrow();
    current.recordBrand("vanilla");

    // Act
    registry.close(ID, older);

    // Assert
    assertSame(current, registry.find(ID, newer).orElseThrow());
    assertTrue(registry.find(ID, older).isEmpty(), "the older connection no longer owns a state");
    registry.close(ID, newer);
    assertEquals(0, registry.size());
  }

  @Test
  void aStateOfAnEarlierConnectionIsNeverReused() {
    var registry = new SessionRegistry();
    var older = new Object();
    var newer = new Object();
    var stale = registry.open(ID, older, () -> true).orElseThrow();
    stale.recordChannels(List.of("wurst:main"));

    var fresh = registry.open(ID, newer, () -> true).orElseThrow();

    assertFalse(stale == fresh);
    assertTrue(fresh.snapshot().channels().isEmpty());
  }
}
