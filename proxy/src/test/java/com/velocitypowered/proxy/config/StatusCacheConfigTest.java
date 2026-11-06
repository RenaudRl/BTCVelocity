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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatusCacheConfigTest {

  private static final String DEFAULT_LINE = "status-cache-ttl = 0";

  @Test
  void theShippedTomlKeepsTheCacheOff(@TempDir final Path dir) throws IOException {
    final VelocityConfiguration config = VelocityConfiguration.read(write(dir, DEFAULT_LINE));

    assertTrue(config.validate());
    assertEquals(0.0, config.getStatusCacheTtl());
  }

  @Test
  void aWholeNumberAndADecimalAreRead(@TempDir final Path dir) throws IOException {
    assertEquals(5.0, VelocityConfiguration.read(write(dir, "status-cache-ttl = 5")).getStatusCacheTtl());
    assertEquals(2.5, VelocityConfiguration.read(write(dir, "status-cache-ttl = 2.5")).getStatusCacheTtl());
  }

  @Test
  void minusOneAndPositiveValuesAreValid(@TempDir final Path dir) throws IOException {
    for (final String value : new String[] {"-1", "0", "3", "10.5"}) {
      assertTrue(VelocityConfiguration.read(write(dir, "status-cache-ttl = " + value)).validate(), value);
    }
  }

  @Test
  void anyOtherNegativeValueIsRefused(@TempDir final Path dir) throws IOException {
    for (final String value : new String[] {"-2", "-0.5", "nan"}) {
      assertFalse(VelocityConfiguration.read(write(dir, "status-cache-ttl = " + value)).validate(), value);
    }
  }

  @Test
  void anOldTomlWithoutTheKeyKeepsTheCacheOff(@TempDir final Path dir) throws IOException {
    final Path file = write(dir, DEFAULT_LINE);
    final String old = Files.readString(file).replace(DEFAULT_LINE, "");
    assertFalse(old.contains("status-cache-ttl ="));
    Files.writeString(file, old, StandardCharsets.UTF_8);

    final VelocityConfiguration config = VelocityConfiguration.read(file);

    assertTrue(config.validate());
    assertEquals(0.0, config.getStatusCacheTtl());
  }

  /** Writes the default velocity.toml with the status-cache-ttl line replaced. */
  private static Path write(final Path dir, final String ttlLine) throws IOException {
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
          .replace(DEFAULT_LINE, ttlLine), StandardCharsets.UTF_8);
    }
    return file;
  }
}
