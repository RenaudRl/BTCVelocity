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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.btcvelocity.proxy.bridge.BridgeMetrics.Event;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Counters, and nothing but counters. */
class BridgeMetricsTest {

  @Test
  void everyCounterStartsAtZeroAndCountsWhatHappened() {
    final BridgeMetrics metrics = new BridgeMetrics();

    for (final Event event : Event.values()) {
      assertEquals(0L, metrics.count(event), event + " starts at zero");
    }

    metrics.record(Event.RECEIVED);
    metrics.record(Event.RECEIVED);
    metrics.record(Event.DUPLICATE);

    assertEquals(2L, metrics.count(Event.RECEIVED));
    assertEquals(1L, metrics.count(Event.DUPLICATE));
    // Witness: counting one event must not move another, or the numbers would mean nothing.
    assertEquals(0L, metrics.count(Event.DISPATCHED));
  }

  @Test
  void aSnapshotIsImmutableAndComplete() {
    final BridgeMetrics metrics = new BridgeMetrics();
    metrics.record(Event.ACKNOWLEDGED);

    final Map<Event, Long> snapshot = metrics.snapshot();

    assertEquals(Event.values().length, snapshot.size(), "no event is missing from a reading");
    assertEquals(1L, snapshot.get(Event.ACKNOWLEDGED));
    assertThrows(UnsupportedOperationException.class, () -> snapshot.put(Event.RECEIVED, 99L));

    // A snapshot is a reading, not a view: it does not follow later counting.
    metrics.record(Event.ACKNOWLEDGED);
    assertEquals(1L, snapshot.get(Event.ACKNOWLEDGED));
    assertEquals(2L, metrics.count(Event.ACKNOWLEDGED));
  }

  @Test
  void countingFromManyThreadsLosesNothing() throws InterruptedException {
    // Plugin messages arrive on several Netty event loops at once: a counter that lost increments
    // under contention would under-report exactly when the proxy is busiest.
    final BridgeMetrics metrics = new BridgeMetrics();
    final int threads = 8;
    final int perThread = 2_000;
    final ExecutorService pool = Executors.newFixedThreadPool(threads);
    final CountDownLatch start = new CountDownLatch(1);
    final CountDownLatch done = new CountDownLatch(threads);

    try {
      for (int i = 0; i < threads; i++) {
        pool.execute(() -> {
          try {
            start.await();
            for (int n = 0; n < perThread; n++) {
              metrics.record(Event.RECEIVED);
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            done.countDown();
          }
        });
      }
      start.countDown();
      assertTrue(done.await(30, TimeUnit.SECONDS), "the counting threads finished");
    } finally {
      pool.shutdownNow();
    }

    assertEquals((long) threads * perThread, metrics.count(Event.RECEIVED));
  }
}
