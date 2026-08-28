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

package com.btcvelocity.proxy.permission;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.regex.Pattern;

/** Configuration for the optional native BTC permissions provider. */
final class NativePermissionConfig {

  private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");
  private static final Pattern TABLE_PREFIX_PATTERN = Pattern.compile("[A-Za-z0-9_]{1,64}");

  private NativePermissionConfig() {
  }

  static boolean isEnabled() {
    return Boolean.parseBoolean(System.getProperty("btc.permissions.enabled", "false"));
  }

  static Config load(final Path path) throws IOException {
    final Properties properties = new Properties();
    try (InputStream input = Files.newInputStream(path)) {
      properties.load(input);
    }

    final String jdbcUrl = required(properties, "jdbc-url");
    final String username = required(properties, "username");
    final String password = properties.getProperty("password", "");
    final String networkId = value(properties, "network-id", "public");
    final String serverId = value(properties, "server-id", "proxy");
    final String tablePrefix = value(properties, "table-prefix", "btc_permissions_");
    final String redisUri = properties.getProperty("redis-uri", "").trim();

    if (!ID_PATTERN.matcher(networkId).matches() || !ID_PATTERN.matcher(serverId).matches()) {
      throw new IllegalArgumentException("Invalid native permissions network or server id");
    }
    if (!TABLE_PREFIX_PATTERN.matcher(tablePrefix).matches()) {
      throw new IllegalArgumentException("Invalid native permissions table prefix");
    }

    return new Config(jdbcUrl, username, password, networkId, serverId, tablePrefix, redisUri);
  }

  private static String required(final Properties properties, final String key) {
    final String value = properties.getProperty(key, "").trim();
    if (value.isEmpty()) {
      throw new IllegalArgumentException("Missing native permissions setting: " + key);
    }
    return value;
  }

  private static String value(final Properties properties, final String key, final String fallback) {
    final String value = properties.getProperty(key, fallback).trim();
    return value.isEmpty() ? fallback : value;
  }

  record Config(
      String jdbcUrl,
      String username,
      String password,
      String networkId,
      String serverId,
      String tablePrefix,
      String redisUri
  ) {

    String redisChannel() {
      return "btc:permissions:" + networkId;
    }
  }
}
