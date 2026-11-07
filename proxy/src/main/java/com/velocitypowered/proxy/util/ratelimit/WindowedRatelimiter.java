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

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.google.common.annotations.VisibleForTesting;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jetbrains.annotations.NotNull;

/**
 * A {@link Ratelimiter} that allows a number of attempts per key in a fixed window, where
 * {@link CaffeineCacheRatelimiter} allows one. Built on the same expiring Caffeine cache.
 *
 * <p>A key's window starts at its first attempt and is never extended: after {@code window} the
 * count starts again. Refused attempts are counted too, so a flood does not see its window slide.
 * The table is bounded: past {@code maxKeys} the least recently used keys are forgotten, which can
 * only let attempts through, never refuse a key that did nothing.
 *
 * @param <T> the type of object to apply rate limiting to
 */
public final class WindowedRatelimiter<T> implements Ratelimiter<T> {

  private final Cache<T, AtomicInteger> counts;
  private final int limit;

  WindowedRatelimiter(final int limit, final long windowMillis, final long maxKeys) {
    this(limit, windowMillis, maxKeys, Ticker.systemTicker());
  }

  @VisibleForTesting
  WindowedRatelimiter(final int limit, final long windowMillis, final long maxKeys,
      final Ticker ticker) {
    if (limit < 1 || windowMillis < 1 || maxKeys < 1) {
      throw new IllegalArgumentException("limit, window and maxKeys must be at least 1");
    }
    this.limit = limit;
    this.counts = Caffeine.newBuilder()
        .ticker(ticker)
        .expireAfterWrite(windowMillis, TimeUnit.MILLISECONDS)
        .maximumSize(maxKeys)
        .build();
  }

  @Override
  public boolean attempt(final @NotNull T key) {
    final int attempts = counts.get(key, k -> new AtomicInteger()).incrementAndGet();
    return attempts > 0 && attempts <= limit; // attempts < 0: the counter wrapped, refuse
  }
}
