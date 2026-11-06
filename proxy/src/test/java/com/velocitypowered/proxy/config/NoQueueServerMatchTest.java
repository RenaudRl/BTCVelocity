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

package com.velocitypowered.proxy.config;

import static com.velocitypowered.proxy.config.VelocityConfiguration.Queue.matchesNoQueueServer;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class NoQueueServerMatchTest {

  private static final List<String> LISTED = List.of("lobby");

  @Test
  void aListedNameMatchesItself() {
    assertTrue(matchesNoQueueServer(LISTED, "lobby"));
  }

  @Test
  void aCloudNetInstanceOfAListedTaskMatchesWhateverTheCase() {
    assertTrue(matchesNoQueueServer(LISTED, "Lobby-1"));
    assertTrue(matchesNoQueueServer(LISTED, "lobby-12"));
  }

  @Test
  void aNameWithoutTheSuffixKeepsTheExactComparison() {
    // Witness: the plain-name semantics are unchanged (case-sensitive equality).
    assertFalse(matchesNoQueueServer(LISTED, "Lobby"));
    assertFalse(matchesNoQueueServer(List.of("Lobby"), "lobby"));
  }

  @Test
  void onlyTheDigitSuffixFormIsAnInstance() {
    assertFalse(matchesNoQueueServer(LISTED, "lobby-"));
    assertFalse(matchesNoQueueServer(LISTED, "lobby-a"));
    assertFalse(matchesNoQueueServer(LISTED, "lobby-1-2"));
    assertFalse(matchesNoQueueServer(LISTED, "lobbyist-1"));
    assertFalse(matchesNoQueueServer(LISTED, "lobby1"));
    assertFalse(matchesNoQueueServer(LISTED, "survival-1"));
  }

  @Test
  void anEmptyListMatchesNothing() {
    assertFalse(matchesNoQueueServer(List.of(), "lobby-1"));
  }
}
