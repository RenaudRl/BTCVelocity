/*
 * Copyright (C) 2018-2026 Velocity Contributors
 *
 * The Velocity API is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package com.btcvelocity.api.storage;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Asynchronous storage service for persisting player data and proxy state
 * to a durable backend (PostgreSQL).
 *
 * <p>All methods are non-blocking and return {@link CompletableFuture}s.
 * Callers should never block on the returned futures from a Netty event
 * loop thread. Operations are dispatched to a dedicated thread pool so
 * that database I/O never stalls the proxy's network threads.</p>
 *
 * <p>Implementations must use parameterized queries to protect against
 * SQL injection. Any exception encountered during a database operation
 * completes the returned future exceptionally.</p>
 */
public interface StorageService {

  /**
   * Inserts or updates the persisted record for the given player.
   *
   * <p>If a row with the same UUID already exists, all columns are
   * updated to the values in {@code data}. Otherwise a new row is
   * inserted.</p>
   *
   * @param data the player data to persist; must not be {@code null}
   * @return a future that completes when the upsert is done, or
   *         completes exceptionally on database error
   */
  CompletableFuture<Void> upsertPlayer(PlayerData data);

  /**
   * Retrieves the persisted data for the player with the given UUID.
   *
   * @param uuid the player's unique identifier; must not be {@code null}
   * @return a future that completes with an {@link Optional} containing
   *         the player data, or an empty optional if no row was found;
   *         completes exceptionally on database error
   */
  CompletableFuture<Optional<PlayerData>> getPlayer(UUID uuid);

  /**
   * Retrieves the persisted data for the player with the given username.
   *
   * <p>The lookup is case-insensitive.</p>
   *
   * @param username the player's username; must not be {@code null}
   * @return a future that completes with an {@link Optional} containing
   *         the player data, or an empty optional if no row was found;
   *         completes exceptionally on database error
   */
  CompletableFuture<Optional<PlayerData>> getPlayerByName(String username);

  /**
   * Updates the heartbeat row for the specified proxy, recording the
   * current player count and uptime.
   *
   * <p>If no row exists for the given {@code proxyId}, one is inserted.</p>
   *
   * @param proxyId        the proxy identifier; must not be {@code null}
   * @param playerCount    the current number of players on the proxy
   * @param uptimeSeconds  the proxy uptime in seconds
   * @return a future that completes when the heartbeat is written, or
   *         completes exceptionally on database error
   */
  CompletableFuture<Void> updateProxyHeartbeat(String proxyId, int playerCount, long uptimeSeconds);

  /**
   * Updates the {@code last_seen} timestamp and current server for the
   * player with the given UUID.
   *
   * @param uuid        the player's unique identifier; must not be {@code null}
   * @param serverName  the name of the server the player is now on,
   *                    or {@code null} to clear it
   * @param lastSeen    the epoch millisecond timestamp to record
   * @return a future that completes when the update is done, or
   *         completes exceptionally on database error
   */
  CompletableFuture<Void> updatePlayerLastSeen(UUID uuid, String serverName, long lastSeen);
}
