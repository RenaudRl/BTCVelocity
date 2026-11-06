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

import com.velocitypowered.api.network.ProtocolVersion;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Keeps the final server list ping response (the JSON, after {@code ProxyPingEvent}) for a fixed
 * time. The time is counted from the moment the response was built and is never extended by hits.
 *
 * <p>At most one build per key is in flight: requests that arrive while it runs, or after it
 * succeeded and before it expires, receive the same future. Nothing here waits: callers get a
 * future and the build runs wherever the supplier runs it, so an event loop is never blocked.</p>
 *
 * <p>A refused ping (empty result) and a failed build are handed to the request that ran the build
 * only, and are not kept: the requests that were waiting on it run their own build, without the
 * cache. The event sees the connection of the request that ran a build and nobody else's, so a
 * listener that reads {@code event.getConnection()} (address, rate, ban) must run with the cache
 * off.</p>
 */
public final class StatusPingCache {

  /*
   * The callers keep the key small (protocol versions are an enum, hosts are reduced to the
   * configured forced hosts), so this is only a safety net: past it a new key is simply not
   * cached (the build is done as without a cache). It is not a tuning knob.
   */
  private static final int MAX_ENTRIES = 512;

  /*
   * A build that never completes must not pin its key: after this it is replaced by a new one,
   * at most one per key per period. A passthrough ping already ends by itself (connect timeout
   * 5 s, then the read-timeout, 30 s by default, in VelocityRegisteredServer.ping), so this is
   * set to that read-timeout: a slower legitimate build is not rebuilt behind its back. It only
   * bounds a ProxyPingEvent listener that never completes.
   */
  static final long IN_FLIGHT_LIMIT_NANOS = TimeUnit.SECONDS.toNanos(30);

  /**
   * What makes two clients receive the same response: their protocol version (the version string
   * and the JSON format depend on it) and the forced host they matched.
   *
   * @param version the client's protocol version
   * @param virtualHost the configured forced-host entry the client's host matched, or {@code null}
   *     for any host that matches none (one shared entry)
   */
  public record Key(ProtocolVersion version, @Nullable String virtualHost) {
  }

  private static final class Entry {

    final CompletableFuture<Optional<String>> future = new CompletableFuture<>();
    // Written before the future completes, so a reader that sees it done sees this value.
    volatile long deadline;

    Entry(final long deadline) {
      this.deadline = deadline;
    }

    boolean expired(final long now) {
      return future.isCompletedExceptionally() || now - deadline >= 0;
    }
  }

  private final ConcurrentHashMap<Key, Entry> entries = new ConcurrentHashMap<>();
  private final LongSupplier clock;
  private final LongAdder hits = new LongAdder();
  private final LongAdder misses = new LongAdder();

  public StatusPingCache() {
    this(System::nanoTime);
  }

  StatusPingCache(final LongSupplier clock) {
    this.clock = clock;
  }

  /**
   * Returns the response for a key, building it when no fresh one exists.
   *
   * @param key the key
   * @param ttlSeconds how long a built response is kept; zero or less disables the cache
   * @param build builds the response; called at most once for concurrent requests on one key
   * @return a future with the response ({@link Optional#empty()} when the ping is refused)
   */
  public CompletableFuture<Optional<String>> get(final Key key, final double ttlSeconds,
                                                 final Supplier<CompletableFuture<Optional<String>>> build) {
    if (!(ttlSeconds > 0)) { // also catches NaN
      return build.get();
    }
    final long ttlNanos = (long) Math.min(ttlSeconds * 1e9, Long.MAX_VALUE / 4);
    final long now = clock.getAsLong();

    if (entries.size() >= MAX_ENTRIES) {
      entries.values().removeIf(entry -> entry.expired(now));
      if (entries.size() >= MAX_ENTRIES && !entries.containsKey(key)) {
        misses.increment();
        return build.get();
      }
    }

    final Entry[] created = new Entry[1];
    final Entry entry = entries.compute(key, (k, old) -> {
      if (old != null && !old.expired(now)) {
        return old;
      }
      created[0] = new Entry(now + IN_FLIGHT_LIMIT_NANOS);
      return created[0];
    });
    if (created[0] != entry) {
      // A refusal or a failure belongs to the connection that built it: a waiter builds its own.
      return entry.future.handle((value, error) -> {
        if (error == null && value.isPresent()) {
          hits.increment();
          return CompletableFuture.completedFuture(value);
        }
        misses.increment();
        return startBuild(build);
      }).thenCompose(Function.identity());
    }

    misses.increment();
    final CompletableFuture<Optional<String>> built = startBuild(build);
    built.whenComplete((value, error) -> {
      if (error != null) {
        entries.remove(key, entry);
        entry.future.completeExceptionally(error);
      } else if (value.isEmpty()) {
        entries.remove(key, entry);
        entry.future.complete(value);
      } else {
        entry.deadline = clock.getAsLong() + ttlNanos;
        entry.future.complete(value);
      }
    });
    return entry.future;
  }

  private static CompletableFuture<Optional<String>> startBuild(
      final Supplier<CompletableFuture<Optional<String>>> build) {
    try {
      return build.get();
    } catch (final RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  /** Forgets every response; builds already in flight still answer their waiters. */
  public void clear() {
    entries.clear();
  }

  /** Requests answered by an existing or in-flight response, while the cache is on. */
  public long hits() {
    return hits.sum();
  }

  /** Requests that had to build a response, while the cache is on. */
  public long misses() {
    return misses.sum();
  }
}
