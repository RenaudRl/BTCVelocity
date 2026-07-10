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

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * PostgreSQL connection configuration for BTC Velocity.
 *
 * <p>Supports both direct connection parameters and JDBC URL.
 * When {@link #jdbcUrl} is provided, it takes precedence over individual parameters.</p>
 */
public final class PostgresConfiguration {

  private final String host;
  private final int port;
  private final String database;
  private final String username;
  private final String password;
  private final boolean useSsl;
  private final int maxPoolSize;
  private final int minIdle;
  private final long connectionTimeout;
  private final long idleTimeout;
  private final long maxLifetime;
  private final @Nullable String jdbcUrl;

  private PostgresConfiguration(final Builder builder) {
    this.host = builder.host;
    this.port = builder.port;
    this.database = builder.database;
    this.username = builder.username;
    this.password = builder.password;
    this.useSsl = builder.useSsl;
    this.maxPoolSize = builder.maxPoolSize;
    this.minIdle = builder.minIdle;
    this.connectionTimeout = builder.connectionTimeout;
    this.idleTimeout = builder.idleTimeout;
    this.maxLifetime = builder.maxLifetime;
    this.jdbcUrl = builder.jdbcUrl;
  }

  public String host() {
    return host;
  }

  public int port() {
    return port;
  }

  public String database() {
    return database;
  }

  public String username() {
    return username;
  }

  public String password() {
    return password;
  }

  public boolean useSsl() {
    return useSsl;
  }

  public int maxPoolSize() {
    return maxPoolSize;
  }

  public int minIdle() {
    return minIdle;
  }

  public long connectionTimeout() {
    return connectionTimeout;
  }

  public long idleTimeout() {
    return idleTimeout;
  }

  public long maxLifetime() {
    return maxLifetime;
  }

  public @Nullable String jdbcUrl() {
    return jdbcUrl;
  }

  /**
   * Builds a JDBC URL from individual parameters.
   *
   * @return the JDBC URL string
   */
  public String toJdbcUrl() {
    if (jdbcUrl != null && !jdbcUrl.isBlank()) {
      return jdbcUrl;
    }
    return "jdbc:postgresql://" + host + ":" + port + "/" + database
        + "?ssl=" + useSsl
        + "&application_name=BTCVelocity";
  }

  /**
   * Creates a new {@link Builder} with sensible defaults.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Builder for {@link PostgresConfiguration}.
   */
  public static final class Builder {

    private String host = "127.0.0.1";
    private int port = 5432;
    private String database = "btcvelocity";
    private String username = "btcvelocity";
    private String password = "";
    private boolean useSsl = false;
    private int maxPoolSize = 10;
    private int minIdle = 2;
    private long connectionTimeout = 5000;
    private long idleTimeout = 300000;
    private long maxLifetime = 600000;
    private @Nullable String jdbcUrl = null;

    public Builder host(final String host) {
      this.host = host;
      return this;
    }

    public Builder port(final int port) {
      this.port = port;
      return this;
    }

    public Builder database(final String database) {
      this.database = database;
      return this;
    }

    public Builder username(final String username) {
      this.username = username;
      return this;
    }

    public Builder password(final String password) {
      this.password = password;
      return this;
    }

    public Builder useSsl(final boolean useSsl) {
      this.useSsl = useSsl;
      return this;
    }

    public Builder maxPoolSize(final int maxPoolSize) {
      this.maxPoolSize = maxPoolSize;
      return this;
    }

    public Builder minIdle(final int minIdle) {
      this.minIdle = minIdle;
      return this;
    }

    public Builder connectionTimeout(final long connectionTimeout) {
      this.connectionTimeout = connectionTimeout;
      return this;
    }

    public Builder idleTimeout(final long idleTimeout) {
      this.idleTimeout = idleTimeout;
      return this;
    }

    public Builder maxLifetime(final long maxLifetime) {
      this.maxLifetime = maxLifetime;
      return this;
    }

    public Builder jdbcUrl(final @Nullable String jdbcUrl) {
      this.jdbcUrl = jdbcUrl;
      return this;
    }

    public PostgresConfiguration build() {
      return new PostgresConfiguration(this);
    }
  }
}
