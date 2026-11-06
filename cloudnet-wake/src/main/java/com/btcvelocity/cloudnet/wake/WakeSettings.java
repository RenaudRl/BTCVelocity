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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * What the operator declared in the environment of the proxy task. An environment variable rather
 * than a file, as for the other proxy plugins: a CloudNet service directory is recreated from its
 * template at every start, while the task's environment survives.
 *
 * @param tasks   CloudNet tasks a player may wake up; empty means the plugin is off
 * @param timeout how long a player waits for the service before going back to a lobby
 * @param limbo   the server that holds the waiting players, as declared for the arrival router
 * @param arrivalSweep whether the arrival router declares destinations, which is what runs its
 *                     limbo sweep: without it nobody leaves the limbo once the wait ends
 */
public record WakeSettings(List<String> tasks, Duration timeout, String limbo,
                           boolean arrivalSweep) {

  /** Comma-separated CloudNet task names that may be woken up. Unset: the plugin stays off. */
  public static final String TASKS_VARIABLE = "BTC_WAKE_TASKS";

  /** Longest wait, in seconds, before the player is sent back. */
  public static final String TIMEOUT_VARIABLE = "BTC_WAKE_TIMEOUT_SECONDS";

  // The variable of btc-arrival-router: the waiting room is the one it already sweeps.
  static final String LIMBO_VARIABLE = "BTC_ARRIVAL_LIMBO";

  // Same variable again: the router only runs its limbo sweep when it declares destinations.
  static final String ARRIVAL_SERVERS_VARIABLE = "BTC_ARRIVAL_SERVERS";

  static final long DEFAULT_TIMEOUT_SECONDS = 120;

  /**
   * Reads the settings.
   *
   * @param environment the lookup for an environment variable, {@code null} when it is unset
   * @return the settings; an unreadable or non-positive timeout falls back to the default
   */
  public static WakeSettings fromEnvironment(UnaryOperator<String> environment) {
    List<String> tasks = Arrays.stream(blankIfNull(environment.apply(TASKS_VARIABLE)).split(","))
        .map(String::trim)
        .filter(name -> !name.isEmpty())
        .distinct()
        .toList();
    String limbo = blankIfNull(environment.apply(LIMBO_VARIABLE)).trim();
    return new WakeSettings(tasks, Duration.ofSeconds(parseSeconds(
        environment.apply(TIMEOUT_VARIABLE))), limbo.isEmpty() ? null : limbo,
        !blankIfNull(environment.apply(ARRIVAL_SERVERS_VARIABLE)).isBlank());
  }

  /**
   * Whether the plugin acts at all: tasks to wake, a limbo to park the players in, and the router
   * sweep that sends them back to a lobby when the wait ends.
   *
   * @return {@code true} when all three are declared
   */
  public boolean enabled() {
    return !this.tasks.isEmpty() && this.missingVariables().isEmpty();
  }

  /**
   * The environment variables the plugin needs besides the task list and that are not set.
   *
   * @return their names, empty when the plugin has everything it needs
   */
  public List<String> missingVariables() {
    var missing = new ArrayList<String>();
    if (this.limbo == null) {
      missing.add(LIMBO_VARIABLE);
    }
    if (!this.arrivalSweep) {
      missing.add(ARRIVAL_SERVERS_VARIABLE);
    }
    return missing;
  }

  /**
   * The configured task a requested server name stands for.
   *
   * @param requested the name the player typed
   * @return the task as written in the configuration, empty when the name is not a wakeable task
   */
  public Optional<String> taskFor(String requested) {
    return this.tasks.stream().filter(task -> task.equalsIgnoreCase(requested)).findFirst();
  }

  private static long parseSeconds(String raw) {
    try {
      long seconds = Long.parseLong(blankIfNull(raw).trim());
      return seconds > 0 ? seconds : DEFAULT_TIMEOUT_SECONDS;
    } catch (NumberFormatException e) {
      return DEFAULT_TIMEOUT_SECONDS;
    }
  }

  private static String blankIfNull(String value) {
    return value == null ? "" : value;
  }
}
