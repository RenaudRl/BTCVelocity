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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.benmanes.caffeine.cache.Ticker;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class WindowedRatelimiterTest {

  private final AtomicLong elapsed = new AtomicLong();
  private final Ticker ticker = () -> elapsed.get();

  private void pass(final long millis) {
    elapsed.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));
  }

  @Test
  void allowsTheLimitThenRefusesUntilTheWindowEnds() {
    final Ratelimiter<String> limiter = new WindowedRatelimiter<>(3, 1000, 100, ticker);

    assertTrue(limiter.attempt("a"));
    assertTrue(limiter.attempt("a"));
    assertTrue(limiter.attempt("a"));
    assertFalse(limiter.attempt("a"));
    assertFalse(limiter.attempt("a"));

    pass(999);
    assertFalse(limiter.attempt("a"), "still inside the window");
    pass(2);
    assertTrue(limiter.attempt("a"), "a new window");
  }

  @Test
  void refusedAttemptsDoNotSlideTheWindow() {
    final Ratelimiter<String> limiter = new WindowedRatelimiter<>(1, 1000, 100, ticker);

    assertTrue(limiter.attempt("a"));
    for (int i = 0; i < 10; i++) {
      pass(90);
      assertFalse(limiter.attempt("a"));
    }
    pass(200);
    assertTrue(limiter.attempt("a"), "the window ended 1000 ms after the first attempt");
  }

  @Test
  void keysAreIndependent() {
    final Ratelimiter<String> limiter = new WindowedRatelimiter<>(1, 1000, 100, ticker);

    assertTrue(limiter.attempt("a"));
    assertFalse(limiter.attempt("a"));
    assertTrue(limiter.attempt("b"));
  }

  @Test
  void aLimitOfZeroIsNoLimiterAndABadWindowIsRejected() {
    final Ratelimiter<String> off = Ratelimiters.createWindowed(0, 1000, 100);
    for (int i = 0; i < 1000; i++) {
      assertTrue(off.attempt("a"));
    }
    assertThrows(IllegalArgumentException.class, () -> new WindowedRatelimiter<String>(1, 0, 100));
    assertThrows(IllegalArgumentException.class, () -> new WindowedRatelimiter<String>(1, 1000, 0));
  }

  @Test
  void theFactoryBuildsALimiterThatCounts() {
    final Ratelimiter<String> limiter = Ratelimiters.createWindowed(2, 60_000, 100);

    assertTrue(limiter.attempt("a"));
    assertTrue(limiter.attempt("a"));
    assertFalse(limiter.attempt("a"));
  }
}
