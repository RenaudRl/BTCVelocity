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

package com.btcvelocity.proxy.security;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * What the pre-handshake admission checks did since the proxy started, for {@code /metrics}.
 * Owned by the server, so a configuration reload does not reset the counters.
 */
public final class AdmissionStats {

  /** An outcome worth counting. */
  public enum Result {
    /** A handshake host outside the allow list, seen in observe mode. */
    HOST_OBSERVED,
    /** A handshake host outside the allow list, connection closed in enforce mode. */
    HOST_REFUSED,
    /** A connection closed because its source opened too many in the window. */
    RATE_LIMITED,
    /** A connection closed because it did not finish its handshake in time. */
    HANDSHAKE_TIMEOUT,
    /** A login refused because this proxy is draining (arrivee-limbo-et-reveil 1.6). */
    DRAINING
  }

  private final Map<Result, LongAdder> counters = new EnumMap<>(Result.class);

  /** Creates zeroed counters. */
  public AdmissionStats() {
    for (final Result result : Result.values()) {
      counters.put(result, new LongAdder());
    }
  }

  /**
   * Counts one outcome.
   *
   * @param result what happened
   */
  public void record(final Result result) {
    counters.get(result).increment();
  }

  /**
   * Reads one counter.
   *
   * @param result the outcome
   * @return how many times it happened
   */
  public long count(final Result result) {
    return counters.get(result).sum();
  }

  /**
   * A copy of every counter.
   *
   * @return the count of each outcome
   */
  public Map<Result, Long> snapshot() {
    final Map<Result, Long> copy = new EnumMap<>(Result.class);
    counters.forEach((result, counter) -> copy.put(result, counter.sum()));
    return copy;
  }
}
