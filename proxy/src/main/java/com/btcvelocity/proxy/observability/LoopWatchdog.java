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

package com.btcvelocity.proxy.observability;

import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.SingleThreadEventExecutor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Measures how late each Netty event loop runs a task, and reports the loops that are stuck.
 *
 * <p>A dedicated daemon thread (never a Netty loop) drops a marker task on every loop. A loop whose
 * marker has waited longer than the threshold is blocked right now, so that is when its thread is
 * sampled: waiting for the marker to finally run would show a stack that no longer holds the
 * culprit. At most one stall report per loop per window leaves the log readable when a loop stays
 * blocked for minutes. A marker that ran late before any check saw it waiting (the watchdog thread
 * was paused too) is reported afterwards without a stack, so the gauge and the log agree.</p>
 *
 * <p>The watchdog never waits on a loop: it only submits and reads volatile state.</p>
 */
public final class LoopWatchdog implements AutoCloseable {

  private static final Logger LOGGER = LogManager.getLogger(LoopWatchdog.class);

  private static final StackTraceElement[] NO_STACK = new StackTraceElement[0];

  /** One loop as last seen: how late its marker ran, and how many tasks are queued behind it. */
  public record Reading(String loop, long lagNanos, long pendingTasks) {
  }

  /** A loop that did not run its marker within the threshold. */
  public record Stall(String loop, String threadName, long lagMillis, StackTraceElement[] stack) {
  }

  private static final class Probe {
    final String name;
    final EventExecutor loop;
    volatile Thread thread;
    volatile boolean outstanding;
    volatile long sentAtNanos;
    volatile long lastLagNanos;
    // Only touched by the watchdog thread.
    boolean reported;
    long lastReportNanos;
    long sentCount;
    // The marker (by sentCount) whose lag a check already judged.
    long judgedCount;

    Probe(final String name, final EventExecutor loop) {
      this.name = name;
      this.loop = loop;
    }
  }

  private final List<Probe> probes = new ArrayList<>();
  private final long thresholdNanos;
  private final long reportWindowNanos;
  private final Consumer<Stall> sink;
  private final ScheduledExecutorService scheduler;

  /**
   * Starts watching the given loops.
   *
   * @param loops loops to watch, by display name
   * @param thresholdMillis lag above which a loop is reported as stuck
   * @param intervalMillis time between two checks
   * @param reportWindowMillis minimum time between two reports about the same loop
   * @param sink receives each report; runs on the watchdog thread
   */
  public LoopWatchdog(final Map<String, ? extends EventExecutor> loops, final long thresholdMillis,
                      final long intervalMillis, final long reportWindowMillis,
                      final Consumer<Stall> sink) {
    // Sorted: metrics come out in a stable order.
    new TreeMap<>(loops).forEach((name, loop) -> probes.add(new Probe(name, loop)));
    this.thresholdNanos = TimeUnit.MILLISECONDS.toNanos(thresholdMillis);
    this.reportWindowNanos = TimeUnit.MILLISECONDS.toNanos(reportWindowMillis);
    this.sink = sink;
    this.scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
      final Thread thread = new Thread(task, "btc-loop-watchdog");
      thread.setDaemon(true);
      return thread;
    });
    // Learn each loop's thread before any of them can block, so a first stall still has a stack.
    probes.forEach(this::send);
    scheduler.scheduleWithFixedDelay(this::tickSafely, intervalMillis, intervalMillis,
        TimeUnit.MILLISECONDS);
  }

  /**
   * Reads every loop, in name order.
   *
   * @return one reading per watched loop
   */
  public List<Reading> readings() {
    final long now = System.nanoTime();
    final List<Reading> readings = new ArrayList<>(probes.size());
    for (final Probe probe : probes) {
      long lag = probe.lastLagNanos;
      if (probe.outstanding) {
        lag = Math.max(lag, now - probe.sentAtNanos);
      }
      final long pending = probe.loop instanceof SingleThreadEventExecutor single
          ? single.pendingTasks() : 0;
      readings.add(new Reading(probe.name, lag, pending));
    }
    return readings;
  }

  /**
   * Tells whether every loop is currently running its tasks within the threshold.
   *
   * @return {@code false} if any loop is stuck right now
   */
  public boolean isResponsive() {
    final long now = System.nanoTime();
    for (final Probe probe : probes) {
      if (probe.outstanding && now - probe.sentAtNanos > thresholdNanos) {
        return false;
      }
    }
    return true;
  }

  @Override
  public void close() {
    scheduler.shutdownNow();
  }

  // Package-private: the tests drive the checks by hand.
  void tickSafely() {
    // An exception escaping a periodic task would cancel it silently for good.
    try {
      final long now = System.nanoTime();
      for (final Probe probe : probes) {
        if (!probe.outstanding) {
          reportIfRanLate(probe, now);
          send(probe);
        } else if (now - probe.sentAtNanos > thresholdNanos) {
          probe.judgedCount = probe.sentCount;
          report(probe, now, now - probe.sentAtNanos, true);
        }
      }
    } catch (final RuntimeException e) {
      LOGGER.error("Event loop watchdog check failed", e);
    }
  }

  private void send(final Probe probe) {
    probe.sentCount++;
    probe.sentAtNanos = System.nanoTime();
    probe.outstanding = true;
    try {
      probe.loop.execute(() -> {
        probe.thread = Thread.currentThread();
        probe.lastLagNanos = System.nanoTime() - probe.sentAtNanos;
        probe.outstanding = false;
      });
    } catch (final RejectedExecutionException e) {
      // The loop is shutting down: nothing left to watch.
      probe.judgedCount = probe.sentCount;
      probe.outstanding = false;
    }
  }

  /**
   * Reports a marker that ran past the threshold without any check having seen it wait, so the
   * alert says what the gauge says. That happens when the watchdog thread itself did not run while
   * the loop was stalled (JVM pause, starved CPU): the loop ran the marker before the next check.
   * The stall is over, so there is no stack to sample.
   */
  private void reportIfRanLate(final Probe probe, final long now) {
    // The first marker measures the loop's thread starting, which is not a stall.
    if (probe.sentCount <= 1 || probe.judgedCount == probe.sentCount) {
      return;
    }
    probe.judgedCount = probe.sentCount;
    if (probe.lastLagNanos > thresholdNanos) {
      report(probe, now, probe.lastLagNanos, false);
    }
  }

  private void report(final Probe probe, final long now, final long lagNanos,
                      final boolean sampleStack) {
    if (probe.reported && now - probe.lastReportNanos < reportWindowNanos) {
      return;
    }
    // No marker has run yet (the loop's thread is still starting): nothing to sample, and a report
    // without a stack would use up the window that the real stall needs. isResponsive() and
    // readings() still show the lag.
    final Thread thread = probe.thread;
    if (thread == null) {
      return;
    }
    probe.reported = true;
    probe.lastReportNanos = now;
    try {
      sink.accept(new Stall(probe.name, thread.getName(), TimeUnit.NANOSECONDS.toMillis(lagNanos),
          sampleStack ? thread.getStackTrace() : NO_STACK));
    } catch (final RuntimeException e) {
      LOGGER.error("Event loop stall sink failed", e);
    }
  }

  /**
   * The production sink: one warning with the loop's thread name and its stack.
   *
   * @param stall what was observed
   */
  public static void logStall(final Stall stall) {
    if (stall.stack().length == 0) {
      LOGGER.warn("Event loop {} (thread {}) ran a task {} ms late, but was running again before "
              + "it could be sampled (JVM pause or starved watchdog thread): no stack available",
          stall.loop(), stall.threadName(), stall.lagMillis());
      return;
    }
    final StringBuilder stack = new StringBuilder();
    for (final StackTraceElement frame : stall.stack()) {
      stack.append("\n\tat ").append(frame);
    }
    LOGGER.warn("Event loop {} (thread {}) has not run a task for {} ms, it is blocked at:{}",
        stall.loop(), stall.threadName(), stall.lagMillis(), stack);
  }
}
