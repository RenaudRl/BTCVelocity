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

/**
 * What a finished connection attempt says about the backend, for the circuit breaker. Only an
 * attempt that the backend itself ruined is a {@link #FAILURE}; one that the proxy, a plugin or
 * the player ended says nothing about it.
 */
public enum BackendConnectionOutcome {
  /** The player is on the backend. */
  SUCCESS,
  /** The backend could not be reached, or dropped the player without a word. */
  FAILURE,
  /** No evidence either way: a refusal, a plugin error, a player who left. */
  IGNORE;

  /**
   * Classifies a finished attempt.
   *
   * @param successful        the attempt ended with the player connected
   * @param threw             the attempt ended with an exception (connect failure, timeout, login
   *                          dropped)
   * @param abandoned         the player left, or the connection was closed by the proxy itself
   * @param proxySideFailure  the exception came from the proxy or a plugin, not the backend
   * @param closedByBackend   the backend closed the connection without a packet after login
   *                          (config or transition phase), which completes normally
   * @return what to tell the circuit breaker
   */
  public static BackendConnectionOutcome classify(final boolean successful, final boolean threw,
                                                  final boolean abandoned,
                                                  final boolean proxySideFailure,
                                                  final boolean closedByBackend) {
    if (successful) {
      return SUCCESS;
    }
    if (abandoned || proxySideFailure) {
      return IGNORE;
    }
    return threw || closedByBackend ? FAILURE : IGNORE;
  }
}
