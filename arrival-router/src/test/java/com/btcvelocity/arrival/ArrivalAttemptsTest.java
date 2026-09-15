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

package com.btcvelocity.arrival;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ArrivalAttemptsTest {

  private final ArrivalAttempts attempts = new ArrivalAttempts();

  @Test
  void remembersWherePlayerWasSent() {
    // Arrange
    var player = UUID.randomUUID();

    // Act
    this.attempts.record(player, "BtcCore0-1");
    this.attempts.record(player, "BtcCore0-2");

    // Assert
    assertEquals(Set.of("BtcCore0-1", "BtcCore0-2"), this.attempts.of(player));
  }

  @Test
  void keepsPlayersApart() {
    // Arrange
    var first = UUID.randomUUID();
    var second = UUID.randomUUID();

    // Act
    this.attempts.record(first, "BtcCore0-1");

    // Assert
    assertTrue(this.attempts.of(second).isEmpty());
  }

  @Test
  void forgetsPlayerWhoLeft() {
    // Arrange
    var player = UUID.randomUUID();
    this.attempts.record(player, "BtcCore0-1");

    // Act
    this.attempts.forget(player);

    // Assert — the map must not outgrow the proxy
    assertTrue(this.attempts.of(player).isEmpty());
    assertEquals(0, this.attempts.trackedPlayers());
  }

  @Test
  void anUnknownPlayerHasAttemptedNothing() {
    assertTrue(this.attempts.of(UUID.randomUUID()).isEmpty());
    assertEquals(0, this.attempts.trackedPlayers(), "reading must not create an entry");
  }
}
