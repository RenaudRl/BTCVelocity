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

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages a PostgreSQL connection pool via HikariCP for BTC Velocity.
 *
 * <p>This class provides async-friendly, non-blocking access to PostgreSQL
 * through a pooled DataSource. All connections are managed by HikariCP's
 * efficient pool, avoiding any blocking of the Netty event loop when used
 * with async callbacks.</p>
 */
public final class PostgresPool {

  private static final Logger LOGGER = LoggerFactory.getLogger(PostgresPool.class);

  private final HikariDataSource dataSource;

  /**
   * Creates a new PostgreSQL connection pool.
   *
   * @param config the PostgreSQL configuration
   */
  public PostgresPool(final PostgresConfiguration config) {
    final HikariConfig hikariConfig = new HikariConfig();
    hikariConfig.setJdbcUrl(config.toJdbcUrl());
    hikariConfig.setUsername(config.username());
    hikariConfig.setPassword(config.password());
    hikariConfig.setMaximumPoolSize(config.maxPoolSize());
    hikariConfig.setMinimumIdle(config.minIdle());
    hikariConfig.setConnectionTimeout(config.connectionTimeout());
    hikariConfig.setIdleTimeout(config.idleTimeout());
    hikariConfig.setMaxLifetime(config.maxLifetime());
    hikariConfig.setPoolName("BTCVelocity-PostgreSQL");

    // Performance settings
    hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
    hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250");
    hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
    hikariConfig.addDataSourceProperty("useServerPrepStmts", "true");

    this.dataSource = new HikariDataSource(hikariConfig);

    LOGGER.info("PostgreSQL connection pool initialized: {}:{}/{} (pool size: {}/{})",
        config.host(), config.port(), config.database(),
        config.minIdle(), config.maxPoolSize());
  }

  /**
   * Gets the underlying DataSource for advanced usage.
   *
   * @return the HikariCP DataSource
   */
  public DataSource getDataSource() {
    return dataSource;
  }

  /**
   * Acquires a connection from the pool.
   *
   * <p>Callers MUST close the returned connection to return it to the pool.</p>
   *
   * @return a pooled connection
   * @throws SQLException if a connection cannot be acquired
   */
  public Connection getConnection() throws SQLException {
    return dataSource.getConnection();
  }

  /**
   * Returns the current number of active connections.
   *
   * @return active connection count
   */
  public int getActiveConnections() {
    return dataSource.getHikariPoolMXBean().getActiveConnections();
  }

  /**
   * Returns the current number of idle connections.
   *
   * @return idle connection count
   */
  public int getIdleConnections() {
    return dataSource.getHikariPoolMXBean().getIdleConnections();
  }

  /**
   * Initializes the database schema by creating the required tables and
   * indexes if they do not already exist.
   *
   * <p>This method is idempotent — calling it multiple times has no
   * adverse effect. The following objects are created:</p>
   * <ul>
   *   <li>{@code btc_player_data} — stores per-player persistent data</li>
   *   <li>{@code btc_proxy_state} — stores per-proxy heartbeat state</li>
   *   <li>Index on {@code btc_player_data.username} for name-based lookups</li>
   * </ul>
   *
   * @throws SQLException if the schema cannot be initialized
   */
  public void initializeSchema() throws SQLException {
    try (Connection connection = dataSource.getConnection();
         Statement statement = connection.createStatement()) {

      statement.execute("""
          CREATE TABLE IF NOT EXISTS btc_player_data (
            uuid             UUID          PRIMARY KEY,
            username         VARCHAR(16)   NOT NULL,
            ip_address       VARCHAR(45),
            proxy_id         VARCHAR(64)   NOT NULL,
            server_name      VARCHAR(128),
            last_seen        BIGINT        NOT NULL,
            first_join       BIGINT        NOT NULL,
            playtime_seconds BIGINT        NOT NULL DEFAULT 0,
            data             JSONB         NOT NULL DEFAULT '{}'::jsonb
          )
          """);

      statement.execute("""
          CREATE TABLE IF NOT EXISTS btc_proxy_state (
            proxy_id        VARCHAR(64) PRIMARY KEY,
            last_heartbeat  BIGINT      NOT NULL,
            player_count    INT         NOT NULL DEFAULT 0,
            uptime_seconds  BIGINT      NOT NULL DEFAULT 0,
            version         VARCHAR(32)
          )
          """);

      statement.execute("""
          CREATE INDEX IF NOT EXISTS idx_btc_player_data_username
            ON btc_player_data (username)
          """);

      LOGGER.info("PostgreSQL schema initialized (tables: btc_player_data, btc_proxy_state)");
    }
  }

  /**
   * Shuts down the connection pool gracefully.
   */
  public void shutdown() {
    if (dataSource != null && !dataSource.isClosed()) {
      dataSource.close();
      LOGGER.info("PostgreSQL connection pool shut down");
    }
  }
}
