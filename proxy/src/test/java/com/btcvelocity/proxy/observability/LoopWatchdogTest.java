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

package com.btcvelocity.proxy.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.DefaultEventLoop;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LoopWatchdogTest {

  private static final long LONG_WINDOW_MILLIS = 60_000;
  private static final long ONE_HOUR_MILLIS = 3_600_000;
  private static final long THRESHOLD_MILLIS = 50;

  private DefaultEventLoop blockedLoop;
  private DefaultEventLoop healthyLoop;
  private final List<LoopWatchdog.Stall> stalls = new CopyOnWriteArrayList<>();
  private LoopWatchdog watchdog;

  @BeforeEach
  void setUp() {
    blockedLoop = new DefaultEventLoop(null, named("test-blocked-loop"));
    healthyLoop = new DefaultEventLoop(null, named("test-healthy-loop"));
  }

  private static ThreadFactory named(final String name) {
    return runnable -> new Thread(runnable, name);
  }

  @AfterEach
  void tearDown() {
    if (watchdog != null) {
      watchdog.close();
    }
    blockedLoop.shutdownGracefully(0, 1, TimeUnit.SECONDS);
    healthyLoop.shutdownGracefully(0, 1, TimeUnit.SECONDS);
  }

  @Test
  void aBlockedLoopIsReportedOnceWithItsThreadAndStack() throws Exception {
    watchdog = new LoopWatchdog(Map.of("blocked", blockedLoop),
        50, 10, LONG_WINDOW_MILLIS, stalls::add);

    final CountDownLatch release = new CountDownLatch(1);
    blockedLoop.execute(() -> {
      try {
        release.await();
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
    try {
      awaitStall();
      // Many more checks while it stays blocked: the window keeps the log to one report.
      Thread.sleep(300);
      assertFalse(watchdog.isResponsive());
      assertTrue(watchdog.readings().get(0).lagNanos() >= TimeUnit.MILLISECONDS.toNanos(50));
    } finally {
      release.countDown();
    }

    assertEquals(1, stalls.size(), "one stack per loop per window");
    final LoopWatchdog.Stall stall = stalls.get(0);
    assertEquals("blocked", stall.loop());
    assertEquals("test-blocked-loop", stall.threadName());
    assertTrue(stall.lagMillis() >= 50, "lag " + stall.lagMillis());
    assertTrue(stall.stack().length > 0);
  }

  @Test
  void aLoopThatHasNotStartedItsThreadYetDoesNotUseUpTheReportWindow() throws Exception {
    // The thread starts 200 ms late, so the first marker waits past the 50 ms threshold with no
    // thread to sample: that report would carry no stack and hide the real stall that follows.
    final ThreadFactory late = runnable -> new Thread(() -> {
      try {
        Thread.sleep(200);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      runnable.run();
    }, "test-slow-start-loop");
    final DefaultEventLoop slowStart = new DefaultEventLoop(null, late);
    final CountDownLatch release = new CountDownLatch(1);
    try {
      watchdog = new LoopWatchdog(Map.of("slow", slowStart), 50, 10, LONG_WINDOW_MILLIS,
          stalls::add);
      slowStart.execute(() -> {
        try {
          release.await();
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
      awaitStall();
      Thread.sleep(300);
    } finally {
      release.countDown();
      slowStart.shutdownGracefully(0, 1, TimeUnit.SECONDS);
    }

    assertEquals(1, stalls.size());
    assertEquals("test-slow-start-loop", stalls.get(0).threadName());
    assertTrue(stalls.get(0).stack().length > 0);
  }

  @Test
  void aHealthyLoopIsNeverReported() throws Exception {
    // Threshold far above any scheduling noise: only a real stall could trip it.
    watchdog = new LoopWatchdog(Map.of("healthy", healthyLoop),
        5_000, 10, LONG_WINDOW_MILLIS, stalls::add);

    Thread.sleep(300);

    assertTrue(stalls.isEmpty());
    assertTrue(watchdog.isResponsive());
    assertEquals(1, watchdog.readings().size());
  }

  @Test
  void aLoopBlockedAgainAfterTheWindowIsReportedAgain() throws Exception {
    watchdog = new LoopWatchdog(Map.of("blocked", blockedLoop), 50, 10, 100, stalls::add);

    final CountDownLatch release = new CountDownLatch(1);
    blockedLoop.execute(() -> {
      try {
        release.await();
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
    try {
      awaitStall();
      // Window is 100 ms: staying blocked longer must produce a second report.
      final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (stalls.size() < 2 && System.nanoTime() < deadline) {
        Thread.sleep(10);
      }
    } finally {
      release.countDown();
    }

    assertTrue(stalls.size() >= 2);
  }

  /**
   * The gauge and the alert measure the same marker: when the gauge shows a lag above the
   * threshold, a stall must have been reported. The scheduler never ticks here (one-hour interval)
   * and the checks are made by hand, as when the watchdog thread itself is paused (JVM pause, CPU
   * starvation) while the loop is stalled: the loop then runs its marker before any check saw it
   * waiting.
   */
  @Test
  void aMarkerThatRanLateBeforeAnyCheckSawItIsStillReported() throws Exception {
    watchdog = new LoopWatchdog(Map.of("late", blockedLoop), THRESHOLD_MILLIS, ONE_HOUR_MILLIS,
        LONG_WINDOW_MILLIS, stalls::add);
    blockedLoop.submit(() -> { }).sync();
    final CountDownLatch release = new CountDownLatch(1);
    blockedLoop.execute(() -> await(release));
    watchdog.tickSafely();
    Thread.sleep(3 * THRESHOLD_MILLIS);
    release.countDown();
    blockedLoop.submit(() -> { }).sync();

    final long gaugeNanos = watchdog.readings().get(0).lagNanos();
    assertTrue(gaugeNanos >= TimeUnit.MILLISECONDS.toNanos(THRESHOLD_MILLIS),
        "the gauge shows the stall: " + gaugeNanos);
    watchdog.tickSafely();

    assertEquals(1, stalls.size(), "the alert says what the gauge says");
    final LoopWatchdog.Stall stall = stalls.get(0);
    assertEquals("late", stall.loop());
    assertEquals("test-blocked-loop", stall.threadName());
    assertTrue(stall.lagMillis() >= THRESHOLD_MILLIS, "lag " + stall.lagMillis());
    assertEquals(0, stall.stack().length, "the stall was over: there is no stack to sample");
  }

  @Test
  void aStallReportedWhileItLastedIsNotReportedAgainOnceItEnds() throws Exception {
    // No window: only the bookkeeping of what was already reported can prevent a second report.
    watchdog = new LoopWatchdog(Map.of("blocked", blockedLoop), THRESHOLD_MILLIS, ONE_HOUR_MILLIS,
        0, stalls::add);
    blockedLoop.submit(() -> { }).sync();
    final CountDownLatch release = new CountDownLatch(1);
    blockedLoop.execute(() -> await(release));
    watchdog.tickSafely();
    Thread.sleep(3 * THRESHOLD_MILLIS);
    watchdog.tickSafely();
    assertEquals(1, stalls.size());
    assertTrue(stalls.get(0).stack().length > 0);
    release.countDown();
    blockedLoop.submit(() -> { }).sync();

    watchdog.tickSafely();

    assertEquals(1, stalls.size(), "the same stall is reported once");
  }

  @Test
  void aLoopThatOnlyStartedItsThreadLateIsNotReportedAsLate() throws Exception {
    // The first marker measures the thread starting, not a stall: nothing to report afterwards.
    final ThreadFactory late = runnable -> new Thread(() -> {
      try {
        Thread.sleep(3 * THRESHOLD_MILLIS);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      runnable.run();
    }, "test-slow-start-loop");
    final DefaultEventLoop slowStart = new DefaultEventLoop(null, late);
    try {
      watchdog = new LoopWatchdog(Map.of("slow", slowStart), THRESHOLD_MILLIS, ONE_HOUR_MILLIS,
          0, stalls::add);
      slowStart.submit(() -> { }).sync();

      watchdog.tickSafely();
    } finally {
      slowStart.shutdownGracefully(0, 1, TimeUnit.SECONDS);
    }

    assertTrue(stalls.isEmpty());
  }

  private static void await(final CountDownLatch latch) {
    try {
      latch.await();
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void awaitStall() throws InterruptedException {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (stalls.isEmpty() && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertFalse(stalls.isEmpty(), "the blocked loop was not reported within 5 s");
  }
}
