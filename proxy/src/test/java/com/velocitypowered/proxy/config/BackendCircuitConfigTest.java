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

package com.velocitypowered.proxy.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.btcvelocity.proxy.bridge.BackendCircuitBreaker;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackendCircuitConfigTest {

  private static final String FAILURES = "backend-circuit-failures = 0";
  private static final String ACTIVE = "backend-circuit-failures = 3";
  private static final String WINDOW = "backend-circuit-window-seconds = 30";
  private static final String OPEN = "backend-circuit-open-seconds = 30";

  @Test
  void theShippedTomlCarriesTheNamedDefaults(@TempDir final Path dir) throws IOException {
    final VelocityConfiguration config = VelocityConfiguration.read(write(dir, FAILURES, WINDOW, OPEN));

    assertTrue(config.validate());
    assertEquals(0, BackendCircuitBreaker.DEFAULT_FAILURES, "off until validated on the bench");
    assertEquals(BackendCircuitBreaker.DEFAULT_FAILURES, config.getBackendCircuitFailures());
    assertEquals(BackendCircuitBreaker.DEFAULT_WINDOW_SECONDS, config.getBackendCircuitWindowSeconds());
    assertEquals(BackendCircuitBreaker.DEFAULT_OPEN_SECONDS, config.getBackendCircuitOpenSeconds());
  }

  @Test
  void theValuesAreRead(@TempDir final Path dir) throws IOException {
    final VelocityConfiguration config = VelocityConfiguration.read(write(dir,
        "backend-circuit-failures = 5", "backend-circuit-window-seconds = 12",
        "backend-circuit-open-seconds = 45"));

    assertTrue(config.validate());
    assertEquals(5, config.getBackendCircuitFailures());
    assertEquals(12, config.getBackendCircuitWindowSeconds());
    assertEquals(45, config.getBackendCircuitOpenSeconds());
  }

  @Test
  void zeroFailuresTurnsItOffAndIgnoresTheOtherTwo(@TempDir final Path dir) throws IOException {
    final VelocityConfiguration config = VelocityConfiguration.read(write(dir,
        "backend-circuit-failures = 0", "backend-circuit-window-seconds = 0",
        "backend-circuit-open-seconds = 0"));

    assertTrue(config.validate());
    assertEquals(0, config.getBackendCircuitFailures());
  }

  @Test
  void aNegativeCountOrAnUnusableDelayIsRefused(@TempDir final Path dir) throws IOException {
    assertFalse(VelocityConfiguration.read(write(dir, "backend-circuit-failures = -1", WINDOW, OPEN))
        .validate());
    assertFalse(VelocityConfiguration.read(write(dir, ACTIVE, "backend-circuit-window-seconds = 0", OPEN))
        .validate());
    assertFalse(VelocityConfiguration.read(write(dir, ACTIVE, WINDOW, "backend-circuit-open-seconds = -5"))
        .validate());
  }

  @Test
  void anOldTomlWithoutTheKeysKeepsTheDefaults(@TempDir final Path dir) throws IOException {
    final Path file = write(dir, FAILURES, WINDOW, OPEN);
    Files.writeString(file, Files.readString(file)
        .replace(FAILURES, "").replace(WINDOW, "").replace(OPEN, ""), StandardCharsets.UTF_8);

    final VelocityConfiguration config = VelocityConfiguration.read(file);

    assertTrue(config.validate());
    assertEquals(BackendCircuitBreaker.DEFAULT_FAILURES, config.getBackendCircuitFailures());
    assertEquals(BackendCircuitBreaker.DEFAULT_WINDOW_SECONDS, config.getBackendCircuitWindowSeconds());
    assertEquals(BackendCircuitBreaker.DEFAULT_OPEN_SECONDS, config.getBackendCircuitOpenSeconds());
  }

  /** Writes the default velocity.toml with the three circuit lines replaced. */
  private static Path write(final Path dir, final String failures, final String window,
                            final String open) throws IOException {
    final Path secret = dir.resolve("forwarding.secret");
    Files.writeString(secret, "secret");
    final Path file = dir.resolve("velocity.toml");
    try (InputStream in = VelocityConfiguration.class.getClassLoader()
        .getResourceAsStream("default-velocity.toml")) {
      final String defaults = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(defaults.contains(FAILURES) && defaults.contains(WINDOW) && defaults.contains(OPEN));
      Files.writeString(file, defaults
          .replace("forwarding-secret-file = \"forwarding.secret\"",
              "forwarding-secret-file = '" + secret + "'")
          .replace(FAILURES, failures).replace(WINDOW, window).replace(OPEN, open),
          StandardCharsets.UTF_8);
    }
    return file;
  }
}
