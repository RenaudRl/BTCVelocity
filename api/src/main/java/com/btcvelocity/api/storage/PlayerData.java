/*
 * Copyright (C) 2018-2026 Velocity Contributors
 *
 * The Velocity API is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package com.btcvelocity.api.storage;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable record representing persisted player data stored in the
 * PostgreSQL backend.
 *
 * <p>This record is produced by {@link StorageService} query operations
 * and consumed by {@link StorageService#upsertPlayer(PlayerData)} for
 * persistence. All timestamps are epoch milliseconds.</p>
 *
 * @param uuid             the player's unique identifier
 * @param username         the player's username
 * @param ipAddress        the player's IP address, or {@code null} if unavailable
 * @param proxyId          the identifier of the proxy the player is connected to
 * @param serverName       the name of the backend server the player is on,
 *                         or {@code null} if the player has not yet joined a server
 * @param lastSeen         the epoch millisecond timestamp of the player's last activity
 * @param firstJoin        the epoch millisecond timestamp of the player's first join
 * @param playtimeSeconds  the total accumulated playtime in seconds
 */
public record PlayerData(
    @NotNull UUID uuid,
    @NotNull String username,
    @Nullable String ipAddress,
    @NotNull String proxyId,
    @Nullable String serverName,
    long lastSeen,
    long firstJoin,
    long playtimeSeconds
) {
}
