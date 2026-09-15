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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ArrivalPolicyTest {

  private static final ArrivalPolicy TASK_POLICY = ArrivalPolicy.parse("BtcCore0-*");

  @Test
  void anAbsentDeclarationSelectsNothing() {
    // Arrange
    var policy = ArrivalPolicy.parse(null);

    // Act & Assert — the counter-test of "empty means everything"
    assertTrue(policy.isEmpty());
    assertFalse(policy.accepts("BtcCore0-1"));
    assertTrue(policy.select(List.of(new ArrivalCandidate("BtcCore0-1", 0)), Set.of()).isEmpty());
  }

  @Test
  void blankDeclarationIsTreatedAsAbsent() {
    assertTrue(ArrivalPolicy.parse("   ").isEmpty());
    assertTrue(ArrivalPolicy.parse(" , ").isEmpty());
  }

  @Test
  void matchesPrefixAndExactNameRegardlessOfCase() {
    // Arrange
    var policy = ArrivalPolicy.parse("BtcCore0-*, lobby");

    // Act & Assert
    assertTrue(policy.accepts("BtcCore0-1"));
    assertTrue(policy.accepts("btccore0-27"));
    assertTrue(policy.accepts("LOBBY"));
    assertFalse(policy.accepts("BtcCore1-1"), "a neighbouring task must not match");
    assertFalse(policy.accepts("lobby-2"), "an exact pattern must not behave like a prefix");
  }

  @Test
  void picksTheLeastPopulatedCandidate() {
    // Arrange
    var candidates = List.of(
        new ArrivalCandidate("BtcCore0-1", 7),
        new ArrivalCandidate("BtcCore0-2", 2),
        new ArrivalCandidate("BtcCore0-3", 5));

    // Act
    var chosen = TASK_POLICY.select(candidates, Set.of());

    // Assert
    assertEquals("BtcCore0-2", chosen.orElseThrow().name());
  }

  @Test
  void breaksTiesByNameSoTheChoiceIsReproducible() {
    // Arrange
    var candidates = List.of(
        new ArrivalCandidate("BtcCore0-3", 4),
        new ArrivalCandidate("BtcCore0-1", 4),
        new ArrivalCandidate("BtcCore0-2", 4));

    // Act
    var first = TASK_POLICY.select(candidates, Set.of());
    var second = TASK_POLICY.select(List.copyOf(candidates), Set.of());

    // Assert
    assertEquals("BtcCore0-1", first.orElseThrow().name());
    assertEquals(first, second);
  }

  @Test
  void neverPicksServerOutsideThePolicyEvenWhenItIsEmpty() {
    // Arrange — the empty one is the most tempting by population
    var candidates = List.of(
        new ArrivalCandidate("BtcCore0-1", 40),
        new ArrivalCandidate("SomeOtherTask-1", 0));

    // Act
    var chosen = TASK_POLICY.select(candidates, Set.of());

    // Assert
    assertEquals("BtcCore0-1", chosen.orElseThrow().name());
  }

  @Test
  void skipsWhatThePlayerHasAlreadyBeenSentTo() {
    // Arrange
    var candidates = List.of(
        new ArrivalCandidate("BtcCore0-1", 0),
        new ArrivalCandidate("BtcCore0-2", 9));

    // Act
    var chosen = TASK_POLICY.select(candidates, Set.of("BtcCore0-1"));

    // Assert — the loop guard outranks the population rule
    assertEquals("BtcCore0-2", chosen.orElseThrow().name());
  }

  @Test
  void yieldsNothingWhenEveryCandidateWasAlreadyAttempted() {
    // Arrange
    var candidates = List.of(
        new ArrivalCandidate("BtcCore0-1", 0),
        new ArrivalCandidate("BtcCore0-2", 0));

    // Act
    var chosen = TASK_POLICY.select(candidates, Set.of("BtcCore0-1", "BtcCore0-2"));

    // Assert — a redirect chain ends, it does not cycle
    assertTrue(chosen.isEmpty());
  }

  @Test
  void keepsTheDeclaredPatternsForTheOperatorToRead() {
    assertEquals(List.of("btccore0-*", "lobby"), ArrivalPolicy.parse("BtcCore0-*, Lobby").patterns());
  }
}
