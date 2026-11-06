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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OrderedRedisExecutorTest {

  private static final int CAPACITY = 2;

  private final OrderedRedisExecutor executor = new OrderedRedisExecutor("test-redis", CAPACITY);

  @AfterEach
  void tearDown() {
    executor.shutdown(Duration.ofSeconds(1));
  }

  @Test
  void runsTasksInSubmissionOrderOffTheCallingThread() throws Exception {
    final OrderedRedisExecutor roomy = new OrderedRedisExecutor("test-roomy", 1000);
    final List<Integer> seen = new CopyOnWriteArrayList<>();
    final Thread caller = Thread.currentThread();
    final List<Thread> threads = new CopyOnWriteArrayList<>();

    IntStream.range(0, 500).forEach(i -> roomy.execute(() -> {
      seen.add(i);
      threads.add(Thread.currentThread());
    }));
    roomy.shutdown(Duration.ofSeconds(5));

    assertEquals(IntStream.range(0, 500).boxed().toList(), seen);
    assertNotSame(caller, threads.getFirst());
  }

  @Test
  void rejectsInsteadOfGrowingWhenTheWorkerIsStuck() throws Exception {
    final CountDownLatch release = new CountDownLatch(1);
    final CountDownLatch started = new CountDownLatch(1);
    assertTrue(executor.execute(() -> {
      started.countDown();
      awaitQuietly(release);
    }));
    assertTrue(started.await(5, TimeUnit.SECONDS));

    // The worker is busy: CAPACITY tasks fit in the queue, the next one is refused at once.
    assertTrue(executor.execute(() -> { }));
    assertTrue(executor.execute(() -> { }));
    final long before = System.nanoTime();
    assertFalse(executor.execute(() -> { }));
    final ExecutionException rejected = assertThrows(ExecutionException.class,
        () -> executor.supply(() -> 1).get(1, TimeUnit.SECONDS));
    assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 1000);
    assertTrue(rejected.getCause() instanceof java.util.concurrent.RejectedExecutionException);

    release.countDown();
  }

  @Test
  void aFailingTaskFailsItsFutureAndKeepsTheWorkerAlive() throws Exception {
    final ExecutionException failed = assertThrows(ExecutionException.class,
        () -> executor.supply(() -> {
          throw new IllegalStateException("redis down");
        }).get(5, TimeUnit.SECONDS));
    assertTrue(failed.getCause() instanceof IllegalStateException);

    assertEquals(42, executor.supply(() -> 42).get(5, TimeUnit.SECONDS));
  }

  private static void awaitQuietly(final CountDownLatch latch) {
    try {
      latch.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
