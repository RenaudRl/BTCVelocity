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

package com.btcvelocity.proxy.redis;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.jetbrains.annotations.NotNull;

/**
 * Lets one warning through per interval, so a Redis outage logs a line every so often instead of
 * one per player or per task.
 */
public final class LogThrottle {

  /**
   * Interval used by the Redis workers and the login check.
   */
  public static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(30);

  private final long intervalNanos;
  private final AtomicLong nextAt;
  private final AtomicLong suppressed = new AtomicLong();

  /**
   * Creates a throttle whose first event is let through.
   *
   * @param interval the minimum time between two events let through
   */
  public LogThrottle(final @NotNull Duration interval) {
    this.intervalNanos = interval.toNanos();
    this.nextAt = new AtomicLong(System.nanoTime());
  }

  /**
   * Records one event.
   *
   * @return the number of events kept quiet since the last one let through, or {@code -1} if
   *         this one must stay quiet too
   */
  public long acquire() {
    final long now = System.nanoTime();
    final long next = this.nextAt.get();
    if (now - next >= 0 && this.nextAt.compareAndSet(next, now + this.intervalNanos)) {
      return this.suppressed.getAndSet(0);
    }
    this.suppressed.incrementAndGet();
    return -1;
  }
}
