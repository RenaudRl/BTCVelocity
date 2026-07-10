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

package com.btcvelocity.proxy.storage;

import com.btcvelocity.api.storage.PlayerData;
import com.btcvelocity.api.storage.StorageService;
import com.velocitypowered.proxy.VelocityServer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PostgreSQL-backed implementation of {@link StorageService}.
 *
 * <p>All database operations are dispatched to a dedicated fixed-size
 * thread pool (3 threads) so that JDBC I/O never blocks the Netty event
 * loop. Every method returns a {@link CompletableFuture} that completes
 * exceptionally with a {@link CompletionException} wrapping the
 * underlying {@link SQLException} on failure.</p>
 *
 * <p>Queries use parameterized {@link PreparedStatement}s throughout to
 * prevent SQL injection. Nullable columns are written via
 * {@link PreparedStatement#setNull(int, int)} so that SQL {@code NULL}
 * is stored correctly rather than a literal {@code "null"} string.</p>
 */
public final class VelocityStorageService implements StorageService {

  private static final Logger LOGGER = LoggerFactory.getLogger(VelocityStorageService.class);

  private static final String UPSERT_PLAYER = """
      INSERT INTO btc_player_data
        (uuid, username, ip_address, proxy_id, server_name, last_seen, first_join, playtime_seconds)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT (uuid) DO UPDATE SET
        username         = EXCLUDED.username,
        ip_address       = EXCLUDED.ip_address,
        proxy_id         = EXCLUDED.proxy_id,
        server_name      = EXCLUDED.server_name,
        last_seen        = EXCLUDED.last_seen,
        first_join       = EXCLUDED.first_join,
        playtime_seconds = EXCLUDED.playtime_seconds
      """;

  private static final String SELECT_PLAYER_BY_UUID = """
      SELECT uuid, username, ip_address, proxy_id, server_name,
             last_seen, first_join, playtime_seconds
        FROM btc_player_data
       WHERE uuid = ?
      """;

  private static final String SELECT_PLAYER_BY_NAME = """
      SELECT uuid, username, ip_address, proxy_id, server_name,
             last_seen, first_join, playtime_seconds
        FROM btc_player_data
       WHERE LOWER(username) = LOWER(?)
      """;

  private static final String UPSERT_PROXY_HEARTBEAT = """
      INSERT INTO btc_proxy_state
        (proxy_id, last_heartbeat, player_count, uptime_seconds, version)
      VALUES (?, ?, ?, ?, ?)
      ON CONFLICT (proxy_id) DO UPDATE SET
        last_heartbeat  = EXCLUDED.last_heartbeat,
        player_count    = EXCLUDED.player_count,
        uptime_seconds  = EXCLUDED.uptime_seconds,
        version         = EXCLUDED.version
      """;

  private static final String UPDATE_PLAYER_LAST_SEEN = """
      UPDATE btc_player_data
         SET last_seen   = ?,
             server_name = ?
       WHERE uuid = ?
      """;

  private final PostgresPool postgresPool;
  private final VelocityServer server;
  private final DataSource dataSource;
  private final ExecutorService executor;

  /**
   * Creates a new {@link VelocityStorageService} backed by the given
   * connection pool.
   *
   * <p>A dedicated fixed thread pool of 3 daemon threads is created for
   * executing database operations off the Netty event loop.</p>
   *
   * @param postgresPool the PostgreSQL connection pool; must not be {@code null}
   * @param server       the {@link VelocityServer} instance, used for proxy
   *                     metadata such as version; must not be {@code null}
   */
  public VelocityStorageService(@NotNull final PostgresPool postgresPool,
                                @NotNull final VelocityServer server) {
    this.postgresPool = postgresPool;
    this.server = server;
    this.dataSource = postgresPool.getDataSource();
    this.executor = Executors.newFixedThreadPool(3, new StorageThreadFactory());
    LOGGER.info("VelocityStorageService initialized with 3-thread executor");
  }

  @Override
  public CompletableFuture<Void> upsertPlayer(@NotNull final PlayerData data) {
    return CompletableFuture.runAsync(() -> {
      try (Connection connection = dataSource.getConnection();
           PreparedStatement statement = connection.prepareStatement(UPSERT_PLAYER)) {
        statement.setObject(1, data.uuid());
        statement.setString(2, data.username());
        setNullableString(statement, 3, data.ipAddress());
        statement.setString(4, data.proxyId());
        setNullableString(statement, 5, data.serverName());
        statement.setLong(6, data.lastSeen());
        statement.setLong(7, data.firstJoin());
        statement.setLong(8, data.playtimeSeconds());
        statement.executeUpdate();
      } catch (SQLException e) {
        throw new CompletionException("Failed to upsert player " + data.uuid(), e);
      }
    }, executor);
  }

  @Override
  public CompletableFuture<Optional<PlayerData>> getPlayer(@NotNull final UUID uuid) {
    return CompletableFuture.supplyAsync(() -> {
      try (Connection connection = dataSource.getConnection();
           PreparedStatement statement = connection.prepareStatement(SELECT_PLAYER_BY_UUID)) {
        statement.setObject(1, uuid);
        try (ResultSet resultSet = statement.executeQuery()) {
          if (resultSet.next()) {
            return Optional.of(mapPlayerData(resultSet));
          }
          return Optional.empty();
        }
      } catch (SQLException e) {
        throw new CompletionException("Failed to get player " + uuid, e);
      }
    }, executor);
  }

  @Override
  public CompletableFuture<Optional<PlayerData>> getPlayerByName(@NotNull final String username) {
    return CompletableFuture.supplyAsync(() -> {
      try (Connection connection = dataSource.getConnection();
           PreparedStatement statement = connection.prepareStatement(SELECT_PLAYER_BY_NAME)) {
        statement.setString(1, username);
        try (ResultSet resultSet = statement.executeQuery()) {
          if (resultSet.next()) {
            return Optional.of(mapPlayerData(resultSet));
          }
          return Optional.empty();
        }
      } catch (SQLException e) {
        throw new CompletionException("Failed to get player by name " + username, e);
      }
    }, executor);
  }

  @Override
  public CompletableFuture<Void> updateProxyHeartbeat(@NotNull final String proxyId,
                                                       final int playerCount,
                                                       final long uptimeSeconds) {
    return CompletableFuture.runAsync(() -> {
      String version = null;
      try {
        version = server.getVersion().getVersion();
      } catch (Exception ignored) {
        // Version may be unavailable during early startup; store NULL.
      }
      try (Connection connection = dataSource.getConnection();
           PreparedStatement statement = connection.prepareStatement(UPSERT_PROXY_HEARTBEAT)) {
        statement.setString(1, proxyId);
        statement.setLong(2, System.currentTimeMillis());
        statement.setInt(3, playerCount);
        statement.setLong(4, uptimeSeconds);
        setNullableString(statement, 5, version);
        statement.executeUpdate();
      } catch (SQLException e) {
        throw new CompletionException("Failed to update proxy heartbeat for " + proxyId, e);
      }
    }, executor);
  }

  @Override
  public CompletableFuture<Void> updatePlayerLastSeen(@NotNull final UUID uuid,
                                                       @Nullable final String serverName,
                                                       final long lastSeen) {
    return CompletableFuture.runAsync(() -> {
      try (Connection connection = dataSource.getConnection();
           PreparedStatement statement = connection.prepareStatement(UPDATE_PLAYER_LAST_SEEN)) {
        statement.setLong(1, lastSeen);
        setNullableString(statement, 2, serverName);
        statement.setObject(3, uuid);
        statement.executeUpdate();
      } catch (SQLException e) {
        throw new CompletionException("Failed to update last-seen for " + uuid, e);
      }
    }, executor);
  }

  /**
   * Shuts down the internal executor, waiting up to 5 seconds for
   * pending database operations to complete.
   */
  public void shutdown() {
    executor.shutdown();
    try {
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        LOGGER.warn("VelocityStorageService executor did not terminate in 5s, forcing shutdown");
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
    LOGGER.info("VelocityStorageService executor shut down");
  }

  /**
   * Sets a nullable string parameter on a prepared statement.
   *
   * <p>When {@code value} is {@code null}, {@link PreparedStatement#setNull(int, int)}
   * is called so that SQL {@code NULL} is stored rather than the
   * literal string {@code "null"}.</p>
   *
   * @param statement the prepared statement
   * @param index     the parameter index (1-based)
   * @param value     the string value, or {@code null}
   * @throws SQLException if the parameter cannot be set
   */
  private static void setNullableString(final PreparedStatement statement,
                                        final int index,
                                        final @Nullable String value) throws SQLException {
    if (value != null) {
      statement.setString(index, value);
    } else {
      statement.setNull(index, Types.VARCHAR);
    }
  }

  /**
   * Maps the current row of a {@link ResultSet} to a {@link PlayerData}
   * record.
   *
   * @param resultSet the result set positioned at the row to map
   * @return the mapped {@link PlayerData}
   * @throws SQLException if a column cannot be read
   */
  private static PlayerData mapPlayerData(final ResultSet resultSet) throws SQLException {
    return new PlayerData(
        resultSet.getObject("uuid", UUID.class),
        resultSet.getString("username"),
        resultSet.getString("ip_address"),
        resultSet.getString("proxy_id"),
        resultSet.getString("server_name"),
        resultSet.getLong("last_seen"),
        resultSet.getLong("first_join"),
        resultSet.getLong("playtime_seconds")
    );
  }

  /**
   * Thread factory that produces named daemon threads for the storage
   * executor, making them easy to identify in thread dumps.
   */
  private static final class StorageThreadFactory implements ThreadFactory {

    private final AtomicInteger counter = new AtomicInteger(0);

    @Override
    public Thread newThread(final Runnable r) {
      Thread thread = new Thread(r, "BTCVelocity-Storage-" + counter.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    }
  }
}
