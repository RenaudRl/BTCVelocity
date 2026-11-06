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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.ToIntFunction;
import org.slf4j.Logger;

/**
 * How many players a server holds, as the cluster sees it, with this proxy's own view as the net.
 *
 * <p>With several proxies in front of the same backends the local count is only a slice of the
 * truth: each proxy would pick "the least populated" lobby from its own slice and they would pile
 * players on the same one. The cluster count is the sum over every proxy. It can fail — with Redis
 * the call is a blocking round trip — so a failure is never allowed to cost an arrival: the local
 * count answers instead, and the cluster is left alone for {@link #FALLBACK_MILLIS} rather than
 * asked (and waited for) again on every player.
 *
 * <p>An answer is also kept for {@link #CACHE_MILLIS}: a sweep routes every waiting player, and a
 * count per server per player would be a Redis round trip each.
 *
 * <p>Thread-safe: Velocity fires its events on connection event loops.
 */
final class PlayerCounts {

  /** How long a count is reused. Short: a stale count only skews a balance, never a rule. */
  static final long CACHE_MILLIS = 1_000L;

  /** How long the cluster is left alone after it failed to answer. */
  static final long FALLBACK_MILLIS = 10_000L;

  private record Cached(int count, long at) {
  }

  private final ToIntFunction<String> cluster;
  private final ToIntFunction<String> local;
  private final LongSupplier clock;
  private final Logger logger;
  private final Map<String, Cached> cache = new ConcurrentHashMap<>();
  private volatile long clusterDownUntil;

  /**
   * Creates the counter.
   *
   * @param cluster the cluster-wide count of a server, by name; may throw when the cluster is down
   * @param local the count of this proxy alone, by name; never throws
   * @param clock the current time in milliseconds
   * @param logger where a fall back to the local count, and the return, are said
   */
  PlayerCounts(ToIntFunction<String> cluster, ToIntFunction<String> local, LongSupplier clock,
               Logger logger) {
    this.cluster = cluster;
    this.local = local;
    this.clock = clock;
    this.logger = logger;
  }

  /**
   * The number of players on a server.
   *
   * @param serverName the Velocity server name
   * @return the cluster count, or this proxy's count while the cluster cannot answer
   */
  int of(String serverName) {
    long now = this.clock.getAsLong();
    if (now < this.clusterDownUntil) {
      return this.local.applyAsInt(serverName);
    }
    Cached cached = this.cache.get(serverName);
    if (cached != null && now - cached.at() < CACHE_MILLIS) {
      return cached.count();
    }
    try {
      int count = this.cluster.applyAsInt(serverName);
      if (this.clusterDownUntil != 0) {
        this.clusterDownUntil = 0;
        this.logger.info("The cluster player count answers again; routing on it.");
      }
      this.cache.put(serverName, new Cached(count, now));
      return count;
    } catch (RuntimeException failure) {
      this.clusterDownUntil = now + FALLBACK_MILLIS;
      this.cache.clear();
      this.logger.warn("The cluster player count failed ({}); routing on this proxy's own count "
          + "for {} s.", failure.toString(), FALLBACK_MILLIS / 1_000L);
      return this.local.applyAsInt(serverName);
    }
  }
}
