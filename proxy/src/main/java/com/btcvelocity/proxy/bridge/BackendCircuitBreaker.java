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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * A passive circuit breaker per backend, fed by the connections the proxy really makes.
 *
 * <p>Nothing is probed: when a player's connection to a backend fails, the failure is recorded
 * here, and when one succeeds the backend is cleared. A backend that fails {@code failures} times
 * within {@code window} is <em>open</em>: routing avoids it for {@code open}. Then it is
 * <em>half-open</em>: it may be tried again, and the next outcome decides — a success closes it, a
 * failure opens it again for another {@code open}.</p>
 *
 * <p>A backend that dies stops receiving players here before the orchestrator has declared it
 * gone. It is a routing hint, not a verdict: nothing is removed from the server map.</p>
 *
 * <p>shortcut: half-open lets every caller through, not a single trial one. The first failure
 * closes the door again, so the exposure is the players routed during one connection attempt;
 * upgrade to a single-trial permit if a backend that accepts the TCP connection and then hangs
 * ever shows a stampede.</p>
 *
 * <p>Thread-safe: outcomes are recorded from connection event loops.</p>
 */
public final class BackendCircuitBreaker {

  /** Failed connections within the window that open the circuit. Zero or less turns it off. */
  public static final int DEFAULT_FAILURES = 3;

  /** The span, in seconds, in which the failures are counted. */
  public static final int DEFAULT_WINDOW_SECONDS = 30;

  /** How long, in seconds, an open circuit makes routing avoid the backend. */
  public static final int DEFAULT_OPEN_SECONDS = 30;

  /** Where a backend stands. */
  public enum State {
    /** Healthy as far as the proxy knows. */
    CLOSED,
    /** Avoided by routing. */
    OPEN,
    /** The open delay is over: may be tried, and the next outcome decides. */
    HALF_OPEN
  }

  /** Failure instants still inside the window, and when the circuit stops being open (0: closed). */
  private record Circuit(List<Long> failures, long openUntil) {
    private static final Circuit CLOSED = new Circuit(List.of(), 0L);
  }

  private final int failures;
  private final long windowMillis;
  private final long openMillis;
  private final LongSupplier clock;
  private final Map<String, Circuit> circuits = new ConcurrentHashMap<>();

  /** Creates a breaker with the default thresholds and the system clock. */
  public BackendCircuitBreaker() {
    this(DEFAULT_FAILURES, DEFAULT_WINDOW_SECONDS * 1_000L, DEFAULT_OPEN_SECONDS * 1_000L,
        System::currentTimeMillis);
  }

  /**
   * Creates a breaker.
   *
   * @param failures     failed connections within the window that open the circuit; zero or less
   *                     turns the breaker off
   * @param windowMillis the span in which failures are counted, in milliseconds
   * @param openMillis   how long an open circuit lasts, in milliseconds
   * @param clock        the source of the current time, in milliseconds
   */
  public BackendCircuitBreaker(final int failures, final long windowMillis, final long openMillis,
                               final LongSupplier clock) {
    if (failures > 0 && (windowMillis <= 0 || openMillis <= 0)) {
      throw new IllegalArgumentException("window and open delay must be positive");
    }
    this.failures = failures;
    this.windowMillis = windowMillis;
    this.openMillis = openMillis;
    this.clock = clock;
  }

  /**
   * Records a failed connection to a backend.
   *
   * @param serverName the backend
   * @return {@code true} when this failure opened the circuit (or opened it again)
   */
  public boolean recordFailure(final String serverName) {
    if (this.failures <= 0) {
      return false;
    }
    final long now = this.clock.getAsLong();
    final boolean[] opened = new boolean[1];
    this.circuits.compute(key(serverName), (name, circuit) -> {
      final Circuit current = circuit == null ? Circuit.CLOSED : circuit;
      if (current.openUntil() > now) {
        return current; // already open: a connection that began before it cannot extend it
      }
      if (current.openUntil() != 0) {
        opened[0] = true; // half-open: the trial failed
        return new Circuit(List.of(), now + this.openMillis);
      }
      final List<Long> recent = new ArrayList<>();
      for (final long at : current.failures()) {
        if (now - at < this.windowMillis) {
          recent.add(at);
        }
      }
      recent.add(now);
      if (recent.size() >= this.failures) {
        opened[0] = true;
        return new Circuit(List.of(), now + this.openMillis);
      }
      return new Circuit(List.copyOf(recent), 0L);
    });
    return opened[0];
  }

  /**
   * Records a successful connection to a backend: it is clear, whatever it was.
   *
   * @param serverName the backend
   * @return {@code true} when the backend had been open or half-open
   */
  public boolean recordSuccess(final String serverName) {
    if (this.failures <= 0) {
      return false;
    }
    final Circuit previous = this.circuits.remove(key(serverName));
    return previous != null && previous.openUntil() != 0;
  }

  /**
   * Whether routing should leave this backend alone right now.
   *
   * @param serverName the backend
   * @return {@code true} while the circuit is open; a half-open backend is not avoided
   */
  public boolean isAvoided(final String serverName) {
    return state(serverName) == State.OPEN;
  }

  /**
   * Where a backend stands.
   *
   * @param serverName the backend
   * @return its state; a backend never seen failing is {@link State#CLOSED}
   */
  public State state(final String serverName) {
    final Circuit circuit = this.circuits.get(key(serverName));
    if (circuit == null || circuit.openUntil() == 0) {
      return State.CLOSED;
    }
    return circuit.openUntil() > this.clock.getAsLong() ? State.OPEN : State.HALF_OPEN;
  }

  /**
   * Forgets a backend, used when it is gone: a service registered again under the same name is
   * not the one that failed.
   *
   * @param serverName the backend
   */
  public void forget(final String serverName) {
    this.circuits.remove(key(serverName));
  }

  // Velocity compares server names case-insensitively.
  private static String key(final String serverName) {
    return serverName.toLowerCase(Locale.ROOT);
  }
}
