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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RedisLoginCheckConfigTest {

  private static final String DEFAULT_LINE = "login-check-failure = \"deny\"";

  @Test
  void theShippedTomlRefusesUncheckedLogins(@TempDir final Path dir) throws IOException {
    final VelocityConfiguration config = VelocityConfiguration.read(write(dir, DEFAULT_LINE));

    assertTrue(config.validate());
    assertFalse(config.getRedis().isLoginAllowedWhenUnchecked());
  }

  @Test
  void allowIsReadInAnyCase(@TempDir final Path dir) throws IOException {
    final VelocityConfiguration config =
        VelocityConfiguration.read(write(dir, "login-check-failure = \"Allow\""));

    assertTrue(config.validate());
    assertTrue(config.getRedis().isLoginAllowedWhenUnchecked());
  }

  @Test
  void anOldTomlWithoutTheKeyRefuses(@TempDir final Path dir) throws IOException {
    final VelocityConfiguration config = VelocityConfiguration.read(write(dir, ""));

    assertTrue(config.validate());
    assertFalse(config.getRedis().isLoginAllowedWhenUnchecked());
  }

  @Test
  void anyOtherValueIsRefused(@TempDir final Path dir) throws IOException {
    assertFalse(VelocityConfiguration.read(write(dir, "login-check-failure = \"maybe\"")).validate());
  }

  /** Writes the default velocity.toml with the login-check-failure line replaced. */
  private static Path write(final Path dir, final String line) throws IOException {
    final Path secret = dir.resolve("forwarding.secret");
    Files.writeString(secret, "secret");
    final Path file = dir.resolve("velocity.toml");
    try (InputStream in = VelocityConfiguration.class.getClassLoader()
        .getResourceAsStream("default-velocity.toml")) {
      final String defaults = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(defaults.contains(DEFAULT_LINE));
      Files.writeString(file, defaults
          .replace("forwarding-secret-file = \"forwarding.secret\"",
              "forwarding-secret-file = '" + secret + "'")
          .replace(DEFAULT_LINE, line), StandardCharsets.UTF_8);
    }
    return file;
  }
}
