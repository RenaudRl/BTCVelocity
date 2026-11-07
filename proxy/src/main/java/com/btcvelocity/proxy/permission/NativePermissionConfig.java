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
import io.lettuce.core.RedisURI;
import java.util.Locale;
import java.util.Properties;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Configuration for the optional native BTC permissions provider. */
final class NativePermissionConfig {

  private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");
  /** Same syntax as the Permissions extension: the whole value, nothing around it. */
  private static final Pattern ENV_PLACEHOLDER = Pattern.compile("\\$\\{env:([A-Za-z_][A-Za-z0-9_]*)}");
  private static final Pattern TABLE_PREFIX_PATTERN = Pattern.compile("[A-Za-z0-9_]{1,64}");

  private NativePermissionConfig() {
  }

  static boolean isEnabled() {
    return Boolean.parseBoolean(System.getProperty("btc.permissions.enabled", "false"));
  }

  static Path configPath() {
    return Path.of(System.getProperty("btc.permissions.config", "btc-permissions.properties"));
  }

  static Config load(final Path path) throws IOException {
    return load(path, System::getenv);
  }

  /**
   * Reads the configuration, resolving {@code ${env:NAME}} in {@code password} and {@code valkey-uri}.
   *
   * <p>Every {@link IllegalArgumentException} thrown here is safe to log: no message carries a
   * setting value, a secret or an environment value, only setting keys and variable names.
   *
   * @param environment looks a variable up, {@code null} when absent (injected: a JVM cannot edit its
   *                    own environment, so tests could not exercise the lookup otherwise)
   */
  static Config load(final Path path, final UnaryOperator<String> environment) throws IOException {
    final Properties properties = new Properties();
    try (InputStream input = Files.newInputStream(path)) {
      properties.load(input);
    }

    final String jdbcUrl = required(properties, "jdbc-url");
    final String username = required(properties, "username");
    final String password = resolve("password", properties.getProperty("password", ""), environment);
    final String networkId = value(properties, "network-id", "public");
    final String serverId = value(properties, "server-id", "proxy");
    final String tablePrefix = value(properties, "table-prefix", "btc_permissions_");
    final String valkeyUri =
        resolve("valkey-uri", properties.getProperty("valkey-uri", ""), environment).trim();

    if (!ID_PATTERN.matcher(networkId).matches() || !ID_PATTERN.matcher(serverId).matches()) {
      throw new IllegalArgumentException("Invalid native permissions network or server id");
    }
    if (!TABLE_PREFIX_PATTERN.matcher(tablePrefix).matches()) {
      throw new IllegalArgumentException("Invalid native permissions table prefix");
    }

    if (!jdbcUrl.startsWith("jdbc:postgresql://")) {
      throw new IllegalArgumentException("Native permissions requires a PostgreSQL JDBC URL");
    }
    // The URL is never echoed: it is the very value that would carry the secret.
    final String lowerUrl = jdbcUrl.toLowerCase(Locale.ROOT);
    if (lowerUrl.contains("password=")) {
      throw new IllegalArgumentException(
          "Native permissions jdbc-url must not carry a password; use the password setting");
    }
    // pgjdbc lets the URL override the readOnlyMode=always the pool sets, which would let writes in.
    if (lowerUrl.contains("readonlymode=")) {
      throw new IllegalArgumentException(
          "Native permissions jdbc-url must not set readOnlyMode; the proxy forces it to always");
    }
    validateValkeyUri(valkeyUri);

    return new Config(jdbcUrl, username, password, networkId, serverId, tablePrefix, valkeyUri);
  }

  /**
   * A value that is exactly {@code ${env:NAME}} is read from the environment, as in the Permissions
   * extension; any other value is returned as is. An unset or empty variable is a failure, never an
   * empty secret: a blank password that "works" would move the error far from its cause.
   */
  private static String resolve(
      final String key,
      final String value,
      final UnaryOperator<String> environment
  ) {
    final Matcher placeholder = ENV_PLACEHOLDER.matcher(value.trim());
    if (!placeholder.matches()) {
      return value;
    }
    final String variable = placeholder.group(1);
    final String found = environment.apply(variable);
    if (found == null || found.isEmpty()) {
      throw new IllegalArgumentException("Native permissions setting " + key
          + " references environment variable " + variable + ", which is not set or is empty");
    }
    return found;
  }

  /**
   * Requires a parsable URI, without chaining the parser's error: its message can quote the URI.
   * Without the bus no revocation would reach the proxy, so a missing URI is an error, not a mode.
   */
  private static void validateValkeyUri(final String valkeyUri) {
    if (valkeyUri.isEmpty()) {
      throw new IllegalArgumentException("Missing native permissions setting: valkey-uri");
    }
    try {
      RedisURI.create(valkeyUri);
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("Invalid native permissions valkey-uri");
    }
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
      String valkeyUri
  ) {

    String redisChannel() {
      return "btc:permissions:" + networkId;
    }

    /** Hides the secrets: a record's default {@code toString} would print password and URI. */
    @Override
    public String toString() {
      return "Config[networkId=" + networkId + ", serverId=" + serverId
          + ", tablePrefix=" + tablePrefix + "]";
    }
  }
}
