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

  private final Map<String, BridgeMessage.Health> healthByServer = new ConcurrentHashMap<>();

  /**
   * Stores or replaces the latest health report for the message's server.
   *
   * @param message the health report to store
   */
  public void update(final BridgeMessage.Health message) {
    healthByServer.put(message.serverName(), message);
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
   * Returns the latest health report for the named server, if any.
   *
   * @param serverName the name of the server to look up
   * @return the latest health report, or an empty optional if none has been received
   */
  public Optional<BridgeMessage.Health> getHealth(final String serverName) {
    return Optional.ofNullable(healthByServer.get(serverName));
  }

  /**
   * Returns the latest mspt reported by the named server.
   *
   * @param serverName the name of the server to look up
   * @return the latest mspt, or {@link #UNKNOWN_MSPT} if the server has never reported
   */
  public double getMspt(final String serverName) {
    final BridgeMessage.Health health = healthByServer.get(serverName);
    return health == null ? UNKNOWN_MSPT : health.mspt();
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
}
