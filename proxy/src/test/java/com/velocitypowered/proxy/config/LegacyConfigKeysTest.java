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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An existing velocity.toml may still carry settings that were removed ([playercaps] and
 * redis.max-concurrent-connections): they must be ignored on load and reported as deprecated.
 */
class LegacyConfigKeysTest {

  @Test
  void oldConfigWithRemovedKeysStillLoads(@TempDir final Path dir) throws IOException {
    final Path secret = dir.resolve("forwarding.secret");
    Files.writeString(secret, "secret");
    final Path file = dir.resolve("velocity.toml");
    Files.writeString(file, legacyConfig(secret), StandardCharsets.UTF_8);

    assertDoesNotThrow(() -> VelocityConfiguration.read(file));

    final ConfigDetector.ConfigAnalysis analysis =
        new ConfigDetector(LogManager.getLogger()).analyzeConfiguration(file);
    assertTrue(analysis.deprecatedOptions().contains("playercaps"));
    assertTrue(analysis.deprecatedOptions().contains("redis.max-concurrent-connections"));
  }

  private static String legacyConfig(final Path secret) throws IOException {
    try (InputStream in = VelocityConfiguration.class.getClassLoader()
        .getResourceAsStream("default-velocity.toml")) {
      final String defaults = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      return defaults
          .replace("forwarding-secret-file = \"forwarding.secret\"",
              "forwarding-secret-file = '" + secret + "'")
          .replace("[redis]", "[redis]\nmax-concurrent-connections = 50")
          + "\n[playercaps]\nfactions = 300\n";
    }
  }
}
