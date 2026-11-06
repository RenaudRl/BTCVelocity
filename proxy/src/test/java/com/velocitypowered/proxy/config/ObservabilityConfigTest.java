/*
 * Copyright (C) 2026 Velocity Contributors
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

package com.velocitypowered.proxy.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.electronwill.nightconfig.core.CommentedConfig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The metrics listener has no authentication: velocity.toml must never be able to expose it.
 */
class ObservabilityConfigTest {

  private static final String DEFAULT_BIND = "prometheus-bind = \"127.0.0.1:9225\"";

  @Test
  void defaultsKeepTheListenerOffAndOnLoopback(@TempDir final Path dir) throws IOException {
    final VelocityConfiguration config = VelocityConfiguration.read(write(dir, DEFAULT_BIND));

    assertTrue(config.validate());
    final VelocityConfiguration.Observability observability = config.getObservability();
    assertFalse(observability.isPrometheusEnabled());
    assertEquals("127.0.0.1:9225", observability.getPrometheusBind());
    assertTrue(observability.isWatchdogEnabled());
    assertEquals(100, observability.getWatchdogThresholdMs());
  }

  @Test
  void validateRefusesAnyBindOutsideLoopback(@TempDir final Path dir) throws IOException {
    for (final String bind : new String[] {"0.0.0.0:9225", "8.8.8.8:9225", "[::]:9225",
        "metrics.example.com:9225", "127.0.0.1"}) {
      final VelocityConfiguration config = VelocityConfiguration.read(
          write(dir, "prometheus-bind = \"" + bind + "\""));
      assertFalse(config.validate(), bind);
    }
  }

  @Test
  void validateAcceptsLoopbackBinds(@TempDir final Path dir) throws IOException {
    for (final String bind : new String[] {"127.0.0.1:0", "127.0.0.2:9300", "[::1]:9225",
        "localhost:9225"}) {
      final VelocityConfiguration config = VelocityConfiguration.read(
          write(dir, "prometheus-bind = \"" + bind + "\""));
      assertTrue(config.validate(), bind);
    }
  }

  @Test
  void validateRefusesAZeroThreshold(@TempDir final Path dir) throws IOException {
    final VelocityConfiguration config = VelocityConfiguration.read(
        write(dir, DEFAULT_BIND, "watchdog-threshold-ms = 0"));
    assertFalse(config.validate());
  }

  @Test
  void aWholeNumberThresholdIsAccepted() {
    final CommentedConfig toml = CommentedConfig.inMemory();
    toml.set("watchdog-threshold-ms", 250);

    assertEquals(250, new VelocityConfiguration.Observability(toml).getWatchdogThresholdMs());
    assertEquals(100, new VelocityConfiguration.Observability(null).getWatchdogThresholdMs());
  }

  private static Path write(final Path dir, final String bindLine) throws IOException {
    return write(dir, bindLine, "watchdog-threshold-ms = 100");
  }

  /** Writes the default velocity.toml with the observability lines replaced. */
  private static Path write(final Path dir, final String bindLine, final String thresholdLine)
      throws IOException {
    final Path secret = dir.resolve("forwarding.secret");
    Files.writeString(secret, "secret");
    final Path file = dir.resolve("velocity.toml");
    try (InputStream in = VelocityConfiguration.class.getClassLoader()
        .getResourceAsStream("default-velocity.toml")) {
      final String defaults = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      Files.writeString(file, defaults
          .replace("forwarding-secret-file = \"forwarding.secret\"",
              "forwarding-secret-file = '" + secret + "'")
          .replace(DEFAULT_BIND, bindLine)
          .replace("watchdog-threshold-ms = 100", thresholdLine), StandardCharsets.UTF_8);
    }
    return file;
  }
}
