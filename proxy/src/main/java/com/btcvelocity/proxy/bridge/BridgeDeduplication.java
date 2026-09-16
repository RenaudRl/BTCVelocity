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

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Remembers the message ids the proxy has already executed, so a redelivered command is
 * acknowledged again without being executed twice.
 *
 * <p>Bounded twice, on purpose. The time window alone would be a memory leak on a proxy that runs
 * for weeks under a backend that retries; the entry cap alone would let a quiet period keep stale
 * ids forever. When the cap is reached the oldest remembered id is evicted first — insertion order
 * is the eviction order.</p>
 *
 * <p>This mirrors the backend implementation ({@code BridgeMessageHandler.alreadyProcessed}) so
 * that both ends of the control plane forget at the same rate. Plugin messages reach the proxy on
 * Netty event loops, so every method is synchronized; the work is a map lookup and a bounded
 * purge, never a blocking call.</p>
 */
public final class BridgeDeduplication {

  /** Matches the backend's default deduplication window. */
  public static final long DEFAULT_WINDOW_MILLIS = 60_000L;

  /** Upper bound on remembered ids, so the registry cannot grow without limit. */
  public static final int DEFAULT_MAX_ENTRIES = 4096;

  private final long windowMillis;
  private final int maxEntries;
  private final LinkedHashMap<UUID, Long> seen = new LinkedHashMap<>();

  /**
   * Creates a registry.
   *
   * @param windowMillis how long a message id is remembered, in milliseconds
   * @param maxEntries   the greatest number of ids remembered at once
   */
  public BridgeDeduplication(final long windowMillis, final int maxEntries) {
    if (windowMillis <= 0 || maxEntries <= 0) {
      throw new IllegalArgumentException("deduplication bounds must be positive");
    }
    this.windowMillis = windowMillis;
    this.maxEntries = maxEntries;
  }

  /** Creates a registry with the defaults shared with the backend. */
  public static BridgeDeduplication defaults() {
    return new BridgeDeduplication(DEFAULT_WINDOW_MILLIS, DEFAULT_MAX_ENTRIES);
  }

  /**
   * Records a message id and reports whether it had already been seen.
   *
   * @param messageId the id carried by the message envelope
   * @param nowMillis the current time, in milliseconds
   * @return {@code true} when this id was already recorded inside the window
   */
  public synchronized boolean alreadySeen(final UUID messageId, final long nowMillis) {
    Objects.requireNonNull(messageId, "messageId");
    purgeExpired(nowMillis);
    if (seen.containsKey(messageId)) {
      return true;
    }
    while (seen.size() >= maxEntries) {
      final Iterator<UUID> oldest = seen.keySet().iterator();
      oldest.next();
      oldest.remove();
    }
    seen.put(messageId, nowMillis);
    return false;
  }

  /** The number of ids currently remembered. Exposed so a test can prove the bounds bite. */
  public synchronized int size() {
    return seen.size();
  }

  private void purgeExpired(final long nowMillis) {
    final Iterator<Map.Entry<UUID, Long>> iterator = seen.entrySet().iterator();
    while (iterator.hasNext()) {
      if (nowMillis - iterator.next().getValue() > windowMillis) {
        iterator.remove();
      }
    }
  }
}
