/*
 * Copyright (C) 2018-2026 Velocity Contributors
 *
 * The Velocity API is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package com.btcvelocity.api.queue;

/**
 * Enumerates the status of a backend server as seen by the queue system.
 */
public enum ServerStatus {

  /**
   * The server is unreachable or has failed to respond. No players will be transferred.
   */
  OFFLINE,

  /**
   * The server responded but is in a warmup period. Players remain queued until the delay elapses.
   */
  WAITING,

  /**
   * The server is online and accepting player connections at full capacity.
   */
  ONLINE,

  /**
   * The server is online but at capacity. Only players with the full-bypass permission
   * will be transferred.
   */
  FULL,

  /**
   * The server is online but under elevated load (mspt between 40 and 60). It remains
   * eligible for player transfers, but the queue should use a reduced transfer batch size
   * to avoid overloading it further.
   */
  DEGRADED,

  /**
   * The server is under heavy load (mspt above 60) and should not receive additional
   * players until its health recovers. Players remain queued until the server is no longer
   * overloaded.
   */
  OVERLOADED;

  /**
   * Returns {@code true} if the server is reachable and eligible for player transfers.
   * This covers {@link #ONLINE}, {@link #FULL} and {@link #DEGRADED}.
   *
   * <p>{@link #DEGRADED} servers are still considered active so that queues can continue
   * to drain, albeit at a reduced batch size. {@link #OVERLOADED} servers are not active
   * and will not receive transfers until they recover.</p>
   *
   * @return {@code true} for {@code ONLINE}, {@code FULL} and {@code DEGRADED},
   *         {@code false} otherwise
   */
  public boolean isActive() {
    return this == ONLINE || this == FULL || this == DEGRADED;
  }

  /**
   * Returns {@code true} if this status indicates the server is under load and transfers
   * should be throttled.
   *
   * @return {@code true} for {@link #DEGRADED} and {@link #OVERLOADED}
   */
  public boolean isLoaded() {
    return this == DEGRADED || this == OVERLOADED;
  }
}
