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

package com.btcvelocity.proxy.queue.redis.depot;

import com.btcvelocity.proxy.queue.RedisVelocityQueue;
import com.btcvelocity.proxy.redis.VelocityRedis;
import com.btcvelocity.proxy.redis.depot.AbstractDepotService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Manages persistence of {@link VelocityQueueDepotEntry} objects in Redis.
 *
 * <p>This service is used only for cold-start state recovery. The master proxy
 * writes queue snapshots here periodically; a newly-started or newly-promoted
 * master proxy reads them back on initialization.</p>
 */
public final class VelocityQueueDepotService
    extends AbstractDepotService<String, VelocityQueueDepotEntry> {

  private final VelocityRedis redis;

  /**
   * Constructs a new {@link VelocityQueueDepotService}.
   *
   * @param redis the active Redis integration
   */
  public VelocityQueueDepotService(final @NotNull VelocityRedis redis) {
    super(VelocityQueueDepotEntry.class, redis.getProvider());
    this.redis = redis;
  }

  /**
   * Persists the current state of the given queue to Redis, as this proxy's snapshot.
   *
   * @param queue the queue to persist
   */
  public void upsertQueue(final @NotNull RedisVelocityQueue queue) {
    this.depot.upsert(new VelocityQueueDepotEntry(queue, redis.getProxyId()));
  }

  /**
   * Removes a server's snapshot, only if this proxy wrote it. A snapshot another proxy wrote (the
   * master before a hand-over, say) or one that predates the owner field stays where it is.
   *
   * @param serverName the name of the server whose queue is gone
   * @return {@code true} if the snapshot was removed
   */
  public boolean removeQueueIfOwner(final @NotNull String serverName) {
    return this.depot.removeIfOwner(serverName, redis.getProxyId());
  }

  /**
   * Retrieves the persisted state for the given server's queue, or {@code null} if none exists.
   *
   * @param serverName the name of the server
   * @return the depot entry, or {@code null}
   */
  public @Nullable VelocityQueueDepotEntry getQueueEntry(final @NotNull String serverName) {
    return this.depot.get(serverName);
  }
}
