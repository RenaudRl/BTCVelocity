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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.permission.PermissionSubject;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.Player;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Secrets and least privilege of the native permissions configuration. Every secret here is random
 * per run, so no literal credential lives in the repository and the leak checks cannot be satisfied
 * by accident.
 */
class NativePermissionConfigTest {

  private static final String JDBC_URL = "jdbc:postgresql://db.invalid:5432/btc";
  private static final String VALKEY_URI = "redis://cache.invalid:6379";

  @TempDir
  Path directory;

  private String secret;
  private String previousEnabled;
  private String previousConfig;

  @BeforeEach
  void setUp() {
    secret = UUID.randomUUID().toString();
    previousEnabled = System.getProperty("btc.permissions.enabled");
    previousConfig = System.getProperty("btc.permissions.config");
  }

  @AfterEach
  void tearDown() {
    restore("btc.permissions.enabled", previousEnabled);
    restore("btc.permissions.config", previousConfig);
  }

  @Test
  void passwordAndValkeyUriAreReadFromTheEnvironment() throws IOException {
    final Path file = write(JDBC_URL, "${env:PERM_TEST_DB_PASSWORD}", "${env:PERM_TEST_VALKEY_URI}");
    final Map<String, String> environment = Map.of(
        "PERM_TEST_DB_PASSWORD", secret,
        "PERM_TEST_VALKEY_URI", "redis://:" + secret + "@cache.invalid:6379");

    final NativePermissionConfig.Config config = NativePermissionConfig.load(file, environment::get);

    assertEquals(secret, config.password());
    assertEquals("redis://:" + secret + "@cache.invalid:6379", config.valkeyUri());
  }

  @Test
  void aLiteralValueAndAPartialPlaceholderAreLeftAsTheyAre() throws IOException {
    final Path file = write(JDBC_URL, "literal-" + secret, VALKEY_URI);
    assertEquals("literal-" + secret, NativePermissionConfig.load(file, name -> "ignored").password());

    final Path partial = write(JDBC_URL, "x${env:PERM_TEST_DB_PASSWORD}", VALKEY_URI);
    assertEquals("x${env:PERM_TEST_DB_PASSWORD}",
        NativePermissionConfig.load(partial, name -> "ignored").password(),
        "only a value that is exactly a placeholder is resolved, as in the extension");
  }

  @Test
  void anAbsentVariableNamesItselfWithoutAnyValue() throws IOException {
    final Path file = write(JDBC_URL, "${env:PERM_TEST_DB_PASSWORD}", VALKEY_URI);

    final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> NativePermissionConfig.load(file, name -> null));

    assertTrue(error.getMessage().contains("PERM_TEST_DB_PASSWORD"), error.getMessage());
    assertTrue(error.getMessage().contains("password"), error.getMessage());
  }

  @Test
  void anEmptyVariableIsAbsentToo() throws IOException {
    final Path file = write(JDBC_URL, "", "${env:PERM_TEST_VALKEY_URI}");
    final UnaryOperator<String> empty = name -> "";

    final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> NativePermissionConfig.load(file, empty));

    assertTrue(error.getMessage().contains("PERM_TEST_VALKEY_URI"), error.getMessage());
  }

  @Test
  void aJdbcUrlCarryingAPasswordIsRefusedWithoutEchoingIt() throws IOException {
    for (final String parameter : new String[] {"password=", "PASSWORD=", "Password="}) {
      final String url = JDBC_URL + "?user=reader&" + parameter + secret;
      final Path file = write(url, "", VALKEY_URI);

      final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
          () -> NativePermissionConfig.load(file, name -> null));

      assertFalse(error.getMessage().contains(secret), "the URL must not be copied into the message");
      assertFalse(error.getMessage().contains("db.invalid"), "the URL must not be copied into the message");
    }
  }

  @Test
  void aJdbcUrlOverridingWhatThePoolSetsIsRefused() throws IOException {
    final Map<String, String> overrides = Map.of(
        "readOnlyMode=ignore", "readOnlyMode",
        "readonlymode=transaction", "readOnlyMode",
        "options=-c%20statement_timeout=0", "options",
        "socketTimeout=0", "socketTimeout");
    for (final Map.Entry<String, String> override : overrides.entrySet()) {
      final Path file = write(JDBC_URL + "?" + override.getKey(), "", VALKEY_URI);

      final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
          () -> NativePermissionConfig.load(file, name -> null));

      assertTrue(error.getMessage().contains(override.getValue()), error.getMessage());
    }
  }

  @Test
  void anInvalidValkeyUriIsRefusedWithoutEchoingItNorChainingItsParserError() throws IOException {
    final String invalid = "no-such-scheme://:" + secret + "@cache.invalid:notaport";
    final Path file = write(JDBC_URL, "", invalid);

    final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> NativePermissionConfig.load(file, name -> null));

    assertFalse(error.getMessage().contains(secret), error.getMessage());
    assertNull(error.getCause(), "the parser's message can quote the URI, so it is not chained");
  }

  @Test
  void theConfigDoesNotPrintItsSecrets() throws IOException {
    final Path file = write(JDBC_URL, secret, "redis://:" + secret + "@cache.invalid:6379");

    final String printed = NativePermissionConfig.load(file, name -> null).toString();

    assertFalse(printed.contains(secret), printed);
  }

  @Test
  void thePoolIsReadOnly() throws IOException {
    final NativePermissionConfig.Config config =
        NativePermissionConfig.load(write(JDBC_URL, "", VALKEY_URI), name -> null);

    final var hikari = NativePermissionService.hikariConfig(config);
    assertTrue(hikari.isReadOnly());
    assertEquals("always", hikari.getDataSourceProperties().getProperty("readOnlyMode"),
        "pgjdbc ignores setReadOnly in autocommit unless readOnlyMode=always");
    assertFalse(hikari.getJdbcUrl().contains("readOnlyMode"), "the property is not smuggled into the URL");
  }

  @Test
  void everyQueryIsBoundedOnTheServerAndOnTheSocket() throws IOException {
    final NativePermissionConfig.Config config =
        NativePermissionConfig.load(write(JDBC_URL, "", VALKEY_URI), name -> null);

    final var properties = NativePermissionService.hikariConfig(config).getDataSourceProperties();

    assertEquals("-c statement_timeout=" + NativePermissionService.QUERY_TIMEOUT.toMillis(),
        properties.getProperty("options"));
    assertEquals(String.valueOf(NativePermissionService.SOCKET_TIMEOUT.toSeconds()),
        properties.getProperty("socketTimeout"), "pgjdbc reads socketTimeout in seconds");
  }

  /** An invalid Valkey URI with a password must reach neither the log nor the thrown error. */
  @Test
  void aBadValkeyUriLeaksNeitherIntoTheLogNorIntoTheFailure() throws Exception {
    final Path file = write(JDBC_URL, "", "no-such-scheme://:" + secret + "@cache.invalid:notaport");
    System.setProperty("btc.permissions.config", file.toString());
    final java.util.List<String> logged = new java.util.concurrent.CopyOnWriteArrayList<>();
    final org.apache.logging.log4j.core.LoggerContext context =
        (org.apache.logging.log4j.core.LoggerContext) org.apache.logging.log4j.LogManager.getContext(false);
    final String loggerName = NativePermissionService.class.getName();
    org.apache.logging.log4j.core.config.Configurator.setLevel(loggerName, org.apache.logging.log4j.Level.ALL);
    final org.apache.logging.log4j.core.appender.AbstractAppender capture =
        new org.apache.logging.log4j.core.appender.AbstractAppender(
            "perm-capture-" + secret, null, null, true, org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY) {
          @Override
          public void append(final org.apache.logging.log4j.core.LogEvent event) {
            final StringBuilder text = new StringBuilder(event.getMessage().getFormattedMessage());
            for (Throwable thrown = event.getThrown(); thrown != null; thrown = thrown.getCause()) {
              text.append(' ').append(thrown).append(' ').append(java.util.Arrays.toString(thrown.getStackTrace()));
            }
            logged.add(text.toString());
          }
        };
    capture.start();
    context.getConfiguration().getLoggerConfig(loggerName)
        .addAppender(capture, org.apache.logging.log4j.Level.ALL, null);
    try (NativePermissionService service = new NativePermissionService()) {
      final var failure = service.load(UUID.randomUUID()).handle((snapshot, error) -> error).join();
      final String thrown = failure == null ? "" : failure.toString();
      assertFalse(thrown.contains(secret), thrown);
      assertFalse(service.health().backendAvailable());
      assertFalse(String.valueOf(service.health().lastFailureReason()).contains(secret));
    } finally {
      context.getConfiguration().getLoggerConfig(loggerName).removeAppender(capture.getName());
      capture.stop();
    }

    assertFalse(logged.isEmpty(), "the failure must be logged so the test observes something");
    logged.forEach(line -> assertFalse(line.contains(secret), "secret found in a log record"));
  }

  @Test
  void anEmptyValkeyUriIsRefused() throws IOException {
    final Path file = write(JDBC_URL, "", " ");

    final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> NativePermissionConfig.load(file, name -> null));

    assertTrue(error.getMessage().contains("valkey-uri"), error.getMessage());
  }

  /** H3: with the flag set, a configuration error refuses everything instead of Velocity's defaults. */
  @Test
  void aMissingVariableKeepsTheProviderAndRefusesEveryPlayerPermission() throws IOException {
    // A name no real environment defines, so the process environment is genuinely queried.
    final Path file = write(JDBC_URL, "${env:BTC_PERM_TEST_NEVER_DEFINED_" + secret.replace('-', '_') + "}", VALKEY_URI);
    assertRefusesEverythingWhenEnabledWith(file);
  }

  @Test
  void anEmptyValkeyUriKeepsTheProviderAndRefusesEveryPlayerPermission() throws IOException {
    assertRefusesEverythingWhenEnabledWith(write(JDBC_URL, "", ""));
  }

  private void assertRefusesEverythingWhenEnabledWith(final Path file) {
    System.setProperty("btc.permissions.enabled", "true");
    System.setProperty("btc.permissions.config", file.toString());
    final Player player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(UUID.randomUUID());
    when(player.isActive()).thenReturn(true);

    try (NativePermissionResolverProvider provider = new NativePermissionResolverProvider()) {
      assertTrue(provider.isAvailable(), "Velocity's defaults would grant /server to everyone");
      final var resolver = provider.createResolver(player, permission -> Tristate.TRUE);
      assertEquals(Tristate.FALSE, resolver.getPermissionValue("velocity.command.server"));
      assertNull(provider.createResolver(mock(PermissionSubject.class), null),
          "the console keeps its own permission function");
    }
  }

  @Test
  void theProviderIsOnWithAValidConfiguration() throws IOException {
    System.setProperty("btc.permissions.enabled", "true");
    System.setProperty("btc.permissions.config", write(JDBC_URL, "", VALKEY_URI).toString());

    assertTrue(new NativePermissionResolverProvider().isAvailable());
  }

  @Test
  void theProviderStaysOffWithoutTheFlag() throws IOException {
    System.setProperty("btc.permissions.enabled", "false");
    System.setProperty("btc.permissions.config", write(JDBC_URL, "", VALKEY_URI).toString());

    assertFalse(new NativePermissionResolverProvider().isAvailable());
  }

  private Path write(final String jdbcUrl, final String password, final String valkeyUri)
      throws IOException {
    final String text = "jdbc-url=" + jdbcUrl + "\nusername=reader\npassword=" + password
        + "\nvalkey-uri=" + valkeyUri + "\n";
    final Path file = directory.resolve("btc-permissions-" + UUID.randomUUID() + ".properties");
    Files.writeString(file, text, StandardCharsets.UTF_8);
    return file;
  }

  private static void restore(final String key, final String previous) {
    if (previous == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, previous);
    }
  }
}
