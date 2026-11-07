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

package com.velocitypowered.proxy.util.ratelimit;

import java.util.concurrent.TimeUnit;

/**
 * Factory to create rate limiters.
 */
public final class Ratelimiters {

  private Ratelimiters() {
    throw new AssertionError();
  }

  public static Ratelimiter createWithMilliseconds(final long ms) {
    return ms <= 0 ? NoopCacheRatelimiter.INSTANCE : new CaffeineCacheRatelimiter(ms, TimeUnit.MILLISECONDS);
  }

  /**
   * A limiter that allows {@code limit} attempts per key in each window.
   *
   * @param limit attempts allowed per window; 0 or less turns the limiter off
   * @param windowMillis the window, at least 1 when the limiter is on
   * @param maxKeys keys followed at once; the least recently used are forgotten beyond that
   * @param <T> the type of key
   * @return the limiter
   */
  @SuppressWarnings("unchecked")
  public static <T> Ratelimiter<T> createWindowed(final int limit, final long windowMillis,
      final long maxKeys) {
    return limit <= 0 ? (Ratelimiter<T>) NoopCacheRatelimiter.INSTANCE
        : new WindowedRatelimiter<>(limit, windowMillis, maxKeys);
  }
}
