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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * A passive circuit breaker per backend, fed by the connections the proxy really makes.
 *
 * <p>Nothing is probed: when a player's connection to a backend fails, the failure is recorded
 * here with the player's id, and when one succeeds the backend is cleared (the count restarts from
 * zero). A backend whose connections fail {@code failures} times within {@code window}, with no
 * success in between and for at least {@link #MIN_DISTINCT_PLAYERS} different players, is
 * <em>open</em>: routing avoids it for {@code open}. One player failing over and over (a broken
 * client, a mod mismatch) says nothing about the backend and never opens it. Then it is
 * <em>half-open</em>: it may be tried again, and the next outcome decides — a success closes it, the
 * failure of a player not yet counted opens it again for another {@code open}.</p>
 *
 * <p>A backend that dies stops receiving players here before the orchestrator has declared it
 * gone. It is a routing hint, not a verdict: nothing is removed from the server map. The state
 * belongs to this proxy alone: it is not shared through Redis, so each proxy learns from the
 * connections it makes itself.</p>
 *
 * <p>shortcut: the breaker lets every caller through while half-open; the single-trial rule lives
 * in the routing code that asks (see {@code ProxyServer#isBackendHalfOpen}), not here.
 * The first failure closes the door again, so the exposure elsewhere is the players routed during
 * one connection attempt; upgrade to a permit held here if a backend that accepts the TCP
 * connection and then hangs ever shows a stampede.</p>
 *
 * <p>Thread-safe: outcomes are recorded from connection event loops.</p>
 */
public final class BackendCircuitBreaker {

  /**
   * Failed connections within the window that open the circuit. Zero or less turns it off, and it
   * is off by default until the behaviour has been seen on a real network.
   */
  public static final int DEFAULT_FAILURES = 0;

  /** Different players whose connections must have failed for the circuit to open. */
  public static final int MIN_DISTINCT_PLAYERS = 2;

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

  /** One failed connection: when, and whose. */
  private record Failure(long at, UUID player) {
  }

  /**
   * The failures still inside the window; when the circuit stops being open (0: closed); and the
   * players whose failures opened it, who cannot open it again by failing once more.
   */
  private record Circuit(List<Failure> failures, long openUntil, Set<UUID> blamed) {
    private static final Circuit CLOSED = new Circuit(List.of(), 0L, Set.of());
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
   * @param player     the player whose connection failed
   * @return {@code true} when this failure opened the circuit (or opened it again)
   */
  public boolean recordFailure(final String serverName, final UUID player) {
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
        // Half-open: the trial failed, unless it is a player already counted against this backend.
        if (current.blamed().contains(player)) {
          return current;
        }
        opened[0] = true;
        final Set<UUID> blamed = new HashSet<>(current.blamed());
        blamed.add(player);
        return new Circuit(List.of(), now + this.openMillis, Set.copyOf(blamed));
      }
      final List<Failure> recent = new ArrayList<>();
      final Set<UUID> players = new HashSet<>();
      for (final Failure failure : current.failures()) {
        if (now - failure.at() < this.windowMillis) {
          recent.add(failure);
          players.add(failure.player());
        }
      }
      recent.add(new Failure(now, player));
      players.add(player);
      if (recent.size() >= this.failures && players.size() >= MIN_DISTINCT_PLAYERS) {
        opened[0] = true;
        return new Circuit(List.of(), now + this.openMillis, Set.copyOf(players));
      }
      return new Circuit(List.copyOf(recent), 0L, Set.of());
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
