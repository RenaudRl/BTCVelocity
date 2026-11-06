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

package com.velocitypowered.proxy.connection.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.network.ProtocolVersion;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class StatusPingCacheTest {

  private static final StatusPingCache.Key KEY =
      new StatusPingCache.Key(ProtocolVersion.MINECRAFT_1_21, "play.example.com");

  private final AtomicLong nanos = new AtomicLong();
  private final StatusPingCache cache = new StatusPingCache(nanos::get);
  private final AtomicInteger builds = new AtomicInteger();

  private CompletableFuture<Optional<String>> build() {
    return CompletableFuture.completedFuture(Optional.of("ping-" + builds.incrementAndGet()));
  }

  private static void advance(final AtomicLong clock, final double seconds) {
    clock.addAndGet((long) (seconds * 1e9));
  }

  @Test
  void aPingInsideTheTtlIsNotRebuiltAndHitsDoNotExtendIt() throws Exception {
    assertEquals("ping-1", cache.get(KEY, 5, this::build).get().orElseThrow());
    advance(nanos, 3);
    assertEquals("ping-1", cache.get(KEY, 5, this::build).get().orElseThrow());
    advance(nanos, 3); // 6 s after the build: the hit at 3 s must not have pushed the end to 8 s
    assertEquals("ping-2", cache.get(KEY, 5, this::build).get().orElseThrow());
    assertEquals(2, builds.get());
    assertEquals(1, cache.hits());
    assertEquals(2, cache.misses());
  }

  @Test
  void anotherProtocolVersionOrHostIsAnotherEntry() throws Exception {
    cache.get(KEY, 5, this::build);
    cache.get(new StatusPingCache.Key(ProtocolVersion.MINECRAFT_1_20_2, KEY.virtualHost()), 5, this::build);
    cache.get(new StatusPingCache.Key(KEY.version(), "forced.example.com"), 5, this::build);
    cache.get(new StatusPingCache.Key(KEY.version(), null), 5, this::build);
    assertEquals(4, builds.get());

    cache.get(KEY, 5, this::build);
    assertEquals(4, builds.get());
  }

  @Test
  void aDisabledTtlBuildsEveryTimeAndCountsNothing() {
    for (final double ttl : new double[] {0, -1, Double.NaN}) {
      cache.get(KEY, ttl, this::build);
      cache.get(KEY, ttl, this::build);
    }
    assertEquals(6, builds.get());
    assertEquals(0, cache.hits() + cache.misses());
  }

  @Test
  void aRefusedPingAndAFailedBuildAreNotKept() throws Exception {
    assertTrue(cache.get(KEY, 5, () -> CompletableFuture.completedFuture(Optional.<String>empty()))
        .get().isEmpty());
    assertEquals("ping-1", cache.get(KEY, 5, this::build).get().orElseThrow());

    final StatusPingCache.Key other = new StatusPingCache.Key(ProtocolVersion.MINECRAFT_1_21, "b");
    final ExecutionException failure = assertThrows(ExecutionException.class,
        () -> cache.get(other, 5, () -> CompletableFuture.failedFuture(new IllegalStateException("boom")))
            .get());
    assertEquals("boom", failure.getCause().getMessage());
    assertThrows(ExecutionException.class, () -> cache.get(other, 5, () -> {
      throw new IllegalStateException("thrown");
    }).get());
    assertEquals("ping-2", cache.get(other, 5, this::build).get().orElseThrow());
  }

  @Test
  void clearForgetsEverything() throws Exception {
    cache.get(KEY, 5, this::build);
    cache.clear();
    assertEquals("ping-2", cache.get(KEY, 5, this::build).get().orElseThrow());
  }

  @Test
  void requestsDuringABuildShareItAndABuildThatNeverEndsIsReplaced() throws Exception {
    final CompletableFuture<Optional<String>> pending = new CompletableFuture<>();
    final CompletableFuture<Optional<String>> first = cache.get(KEY, 5, () -> {
      builds.incrementAndGet();
      return pending;
    });
    final CompletableFuture<Optional<String>> waiter = cache.get(KEY, 5, this::build);
    assertFalse(waiter.isDone());
    assertEquals(1, builds.get());

    nanos.addAndGet(StatusPingCache.IN_FLIGHT_LIMIT_NANOS); // the limit is reached
    assertEquals("ping-2", cache.get(KEY, 5, this::build).get().orElseThrow());
    assertFalse(first.isDone());

    pending.complete(Optional.of("late"));
    assertEquals("late", waiter.get().orElseThrow());
    assertEquals(2, builds.get());
  }

  @Test
  void requestsWaitingOnASuccessfulBuildReceiveIt() throws Exception {
    final CompletableFuture<Optional<String>> pending = new CompletableFuture<>();
    cache.get(KEY, 5, () -> {
      builds.incrementAndGet();
      return pending;
    });
    final CompletableFuture<Optional<String>> waiter = cache.get(KEY, 5, this::build);
    pending.complete(Optional.of("shared"));

    assertEquals("shared", waiter.get().orElseThrow());
    assertEquals(1, builds.get());
  }

  @Test
  void aRefusalIsNotHandedToTheRequestsWaitingOnIt() throws Exception {
    final CompletableFuture<Optional<String>> refusal = new CompletableFuture<>();
    final CompletableFuture<Optional<String>> builder = cache.get(KEY, 5, () -> refusal);
    final CompletableFuture<Optional<String>> waiter = cache.get(KEY, 5, this::build);
    assertFalse(waiter.isDone());

    refusal.complete(Optional.empty()); // connection A was refused

    assertTrue(builder.get().isEmpty());
    assertEquals("ping-1", waiter.get().orElseThrow()); // connection B got its own answer
    assertEquals("ping-2", cache.get(KEY, 5, this::build).get().orElseThrow()); // and nothing was kept
  }

  @Test
  void aFailureIsNotHandedToTheRequestsWaitingOnItEither() throws Exception {
    final CompletableFuture<Optional<String>> failure = new CompletableFuture<>();
    final CompletableFuture<Optional<String>> builder = cache.get(KEY, 5, () -> failure);
    final CompletableFuture<Optional<String>> waiter = cache.get(KEY, 5, this::build);

    failure.completeExceptionally(new IllegalStateException("boom"));

    assertThrows(ExecutionException.class, builder::get);
    assertEquals("ping-1", waiter.get().orElseThrow());
  }

  @Test
  void theNumberOfEntriesIsBounded() throws Exception {
    for (int i = 0; i < 600; i++) {
      cache.get(new StatusPingCache.Key(ProtocolVersion.MINECRAFT_1_21, "h" + i), 60, this::build);
    }
    // Past the bound a new key is built but not kept; a kept one is still served.
    final int before = builds.get();
    cache.get(new StatusPingCache.Key(ProtocolVersion.MINECRAFT_1_21, "h0"), 60, this::build);
    assertEquals(before, builds.get());
    cache.get(new StatusPingCache.Key(ProtocolVersion.MINECRAFT_1_21, "h599"), 60, this::build);
    cache.get(new StatusPingCache.Key(ProtocolVersion.MINECRAFT_1_21, "h599"), 60, this::build);
    assertEquals(before + 2, builds.get());

    advance(nanos, 61); // everything expired: room again
    cache.get(new StatusPingCache.Key(ProtocolVersion.MINECRAFT_1_21, "h599"), 60, this::build);
    final int rebuilt = builds.get();
    cache.get(new StatusPingCache.Key(ProtocolVersion.MINECRAFT_1_21, "h599"), 60, this::build);
    assertEquals(rebuilt, builds.get());
  }

  @Test
  void concurrentRequestsOnAnExpiredEntryTriggerASingleBuild() throws Exception {
    final StatusPingCache real = new StatusPingCache();
    final int threads = 16;
    final CompletableFuture<Optional<String>> pending = new CompletableFuture<>();
    final CountDownLatch go = new CountDownLatch(1);
    final ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      final List<CompletableFuture<Optional<String>>> results = new ArrayList<>();
      final List<Future<CompletableFuture<Optional<String>>>> started = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        started.add(pool.submit(() -> {
          go.await();
          return real.get(KEY, 5, () -> {
            builds.incrementAndGet();
            return pending;
          });
        }));
      }
      go.countDown();
      for (final Future<CompletableFuture<Optional<String>>> future : started) {
        results.add(future.get(10, TimeUnit.SECONDS));
      }
      pending.complete(Optional.of("shared"));

      assertEquals(1, builds.get());
      for (final CompletableFuture<Optional<String>> result : results) {
        assertEquals("shared", result.get(10, TimeUnit.SECONDS).orElseThrow());
      }
      assertEquals(1, real.misses());
      assertEquals(threads - 1, real.hits());
    } finally {
      pool.shutdownNow();
    }
  }
}
