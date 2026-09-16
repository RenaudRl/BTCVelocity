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

import com.btcvelocity.api.bridge.BridgeMessage;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Thread-safe registry of the latest {@link BridgeMessage.Health} report received from each
 * backend server.
 *
 * <p>The registry exposes convenience predicates that classify a server's load based on its
 * current milliseconds-per-tick (mspt) value:</p>
 * <ul>
 *   <li>{@code mspt < 40} - healthy</li>
 *   <li>{@code 40 &le; mspt &le; 60} - degraded</li>
 *   <li>{@code mspt > 60} - overloaded</li>
 * </ul>
 *
 * <p>Servers that have never reported are treated as overloaded (an mspt of {@code 999.0} is
 * returned for them) so that the queue system conservatively avoids transferring players to
 * a backend whose state is unknown.</p>
 *
 * <p><b>A report expires.</b> Backends report every ten seconds; a report older than
 * {@link #DEFAULT_FRESHNESS_MILLIS} is <em>stale</em> and is treated exactly like no report at
 * all. Without this, a backend that dies silently keeps its last good report forever, and the
 * proxy goes on believing a dead server is healthy — the registry would answer a question nobody
 * can still answer. An unknown state must never read as a good state.</p>
 */
public final class BackendHealthRegistry {

  /**
   * The mspt value returned for servers that have never reported, chosen to be well above any
   * realistic overloaded threshold so that unknown servers are treated conservatively.
   */
  public static final double UNKNOWN_MSPT = 999.0;

  /** A server is considered healthy when its mspt is strictly below this threshold. */
  public static final double HEALTHY_THRESHOLD = 40.0;

  /** A server is considered overloaded when its mspt is strictly above this threshold. */
  public static final double OVERLOADED_THRESHOLD = 60.0;

  /**
   * How long a report stays current. The backend reports every ten seconds, so this tolerates
   * two missed reports before a server is declared unknown.
   */
  public static final long DEFAULT_FRESHNESS_MILLIS = 30_000L;

  private final Map<String, Entry> healthByServer = new ConcurrentHashMap<>();
  private final long freshnessMillis;
  private final LongSupplier clock;

  /** A report and the instant it was received by the proxy. */
  private record Entry(BridgeMessage.Health health, long receivedAt) {
  }

  /** Creates a registry using the default freshness window and the system clock. */
  public BackendHealthRegistry() {
    this(DEFAULT_FRESHNESS_MILLIS, System::currentTimeMillis);
  }

  /**
   * Creates a registry.
   *
   * @param freshnessMillis how long a report stays current, in milliseconds
   * @param clock           the source of the current time, in milliseconds
   */
  public BackendHealthRegistry(final long freshnessMillis, final LongSupplier clock) {
    if (freshnessMillis <= 0) {
      throw new IllegalArgumentException("freshness must be positive");
    }
    this.freshnessMillis = freshnessMillis;
    this.clock = clock;
  }

  /**
   * Stores or replaces the latest health report for the message's server.
   *
   * @param message the health report to store
   */
  public void update(final BridgeMessage.Health message) {
    healthByServer.put(message.serverName(), new Entry(message, clock.getAsLong()));
  }

  /**
   * Bridge-message listener entry point suitable for registration with
   * {@link com.btcvelocity.api.bridge.BridgeChannel#registerListener}. It ignores every
   * message type other than {@link BridgeMessage.Health}.
   *
   * @param sourceServer the name of the server that sent the message
   * @param message      the decoded bridge message
   */
  public void onHealth(final String sourceServer, final BridgeMessage message) {
    if (message instanceof BridgeMessage.Health health) {
      update(health);
    }
  }

  /**
   * Forgets everything known about a server, used when the proxy learns it is gone rather than
   * merely quiet.
   *
   * @param serverName the server to forget
   */
  public void forget(final String serverName) {
    healthByServer.remove(serverName);
  }

  /**
   * Returns the latest <em>current</em> health report for the named server, if any.
   *
   * @param serverName the name of the server to look up
   * @return the latest report, or an empty optional when none was received or it has expired
   */
  public Optional<BridgeMessage.Health> getHealth(final String serverName) {
    return Optional.ofNullable(current(serverName)).map(Entry::health);
  }

  /**
   * Returns whether what the registry knows about a server has expired.
   *
   * <p>A server that never reported is <em>not</em> stale: nothing about it ever grew old. Both
   * cases are unknown, and both are treated conservatively, but they are not the same event — one
   * means "never spoke", the other "stopped speaking".</p>
   *
   * @param serverName the name of the server to check
   * @return {@code true} when a report exists but is older than the freshness window
   */
  public boolean isStale(final String serverName) {
    final Entry entry = healthByServer.get(serverName);
    return entry != null && expired(entry);
  }

  /**
   * Returns the latest mspt reported by the named server.
   *
   * @param serverName the name of the server to look up
   * @return the latest mspt, or {@link #UNKNOWN_MSPT} when the server never reported or its
   *         report has expired
   */
  public double getMspt(final String serverName) {
    final Entry entry = current(serverName);
    return entry == null ? UNKNOWN_MSPT : entry.health().mspt();
  }

  /**
   * Returns whether the server is healthy (mspt strictly below {@link #HEALTHY_THRESHOLD}).
   *
   * @param serverName the name of the server to check
   * @return {@code true} if the server is healthy
   */
  public boolean isHealthy(final String serverName) {
    return getMspt(serverName) < HEALTHY_THRESHOLD;
  }

  /**
   * Returns whether the server is degraded (mspt between {@link #HEALTHY_THRESHOLD} and
   * {@link #OVERLOADED_THRESHOLD}, inclusive).
   *
   * @param serverName the name of the server to check
   * @return {@code true} if the server is degraded
   */
  public boolean isDegraded(final String serverName) {
    final double mspt = getMspt(serverName);
    return mspt >= HEALTHY_THRESHOLD && mspt <= OVERLOADED_THRESHOLD;
  }

  /**
   * Returns whether the server is overloaded (mspt strictly above
   * {@link #OVERLOADED_THRESHOLD}).
   *
   * @param serverName the name of the server to check
   * @return {@code true} if the server is overloaded
   */
  public boolean isOverloaded(final String serverName) {
    return getMspt(serverName) > OVERLOADED_THRESHOLD;
  }

  /**
   * The entry for a server when it is still current.
   *
   * <p>An expired entry is <em>hidden, not removed</em>. Removing it on read looked tidier and was
   * wrong: the first reader to notice the expiry would erase the evidence, and {@link #isStale}
   * would answer {@code false} straight after — "stopped speaking" would silently become "never
   * spoke". Memory is bounded by the number of registered backends, not by time, and
   * {@link #forget} is how an entry actually goes away.</p>
   */
  private Entry current(final String serverName) {
    final Entry entry = healthByServer.get(serverName);
    return entry == null || expired(entry) ? null : entry;
  }

  private boolean expired(final Entry entry) {
    return clock.getAsLong() - entry.receivedAt() > freshnessMillis;
  }
}
