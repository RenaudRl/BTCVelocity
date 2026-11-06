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

package com.btcvelocity.cloudnet.wake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WakeSettingsTest {

  private static WakeSettings read(Map<String, String> environment) {
    return WakeSettings.fromEnvironment(environment::get);
  }

  @Test
  void withNothingDeclaredThePluginIsOff() {
    // Act
    var settings = read(Map.of());

    // Assert
    assertFalse(settings.enabled());
    assertEquals(Duration.ofSeconds(WakeSettings.DEFAULT_TIMEOUT_SECONDS), settings.timeout());
  }

  @Test
  void tasksWithoutALimboLeaveThePluginOffBecauseNobodyCanBeParked() {
    var settings = read(Map.of(WakeSettings.TASKS_VARIABLE, "Donjon"));

    assertFalse(settings.enabled());
    assertNull(settings.limbo());
  }

  @Test
  void aLimboWithoutTasksLeavesThePluginOff() {
    assertFalse(read(Map.of(WakeSettings.LIMBO_VARIABLE, "limbo")).enabled());
  }

  @Test
  void tasksAndLimboEnableItAndTheListIsTrimmedAndDeduplicated() {
    var settings = read(Map.of(
        WakeSettings.TASKS_VARIABLE, " Donjon , Test,,Donjon ",
        WakeSettings.LIMBO_VARIABLE, "limbo",
        WakeSettings.TIMEOUT_VARIABLE, "45"));

    assertTrue(settings.enabled());
    assertEquals(List.of("Donjon", "Test"), settings.tasks());
    assertEquals(Duration.ofSeconds(45), settings.timeout());
  }

  @Test
  void anUnreadableOrNonPositiveTimeoutFallsBackToTheDefault() {
    var expected = Duration.ofSeconds(WakeSettings.DEFAULT_TIMEOUT_SECONDS);

    assertEquals(expected, read(Map.of(WakeSettings.TIMEOUT_VARIABLE, "soon")).timeout());
    assertEquals(expected, read(Map.of(WakeSettings.TIMEOUT_VARIABLE, "0")).timeout());
    assertEquals(expected, read(Map.of(WakeSettings.TIMEOUT_VARIABLE, "-5")).timeout());
  }

  @Test
  void aRequestedNameMatchesAConfiguredTaskIgnoringCaseAndReturnsItsConfiguredSpelling() {
    var settings = read(Map.of(
        WakeSettings.TASKS_VARIABLE, "Donjon", WakeSettings.LIMBO_VARIABLE, "limbo"));

    assertEquals("Donjon", settings.taskFor("donjon").orElseThrow());
    assertTrue(settings.taskFor("Lobby").isEmpty());
  }
}
