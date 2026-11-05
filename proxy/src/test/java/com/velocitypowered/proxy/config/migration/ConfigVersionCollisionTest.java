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

package com.velocitypowered.proxy.config.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import java.nio.file.Path;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigVersionCollisionTest {

  private static final Logger LOGGER = LogManager.getLogger(ConfigVersionCollisionTest.class);

  @Test
  void aDeployed27ConfigGetsBothMigrationsAndKeepsItsLimits(@TempDir Path dir) throws Exception {
    // Arrange — the shape of our staging and rc16 velocity.toml (config-version 2.7)
    try (CommentedFileConfig config = CommentedFileConfig.of(dir.resolve("velocity.toml"))) {
      config.set("config-version", "2.7");
      config.set("motd-line1", "BTC");
      config.set("line1-alignment", "center");
      config.set("packet-limiter.packets-per-second", 500);

      // Act — in the order VelocityConfiguration runs them
      for (ConfigurationMigration migration :
          List.of(new MotdAlignmentMigration(), new PacketLimiterMigration())) {
        if (migration.shouldMigrate(config)) {
          migration.migrate(config, LOGGER);
        }
      }

      // Assert
      assertEquals("<center>BTC", config.get("motd-line1"));
      assertFalse(config.contains("line1-alignment"));
      assertTrue(config.contains("packet-limiter.decompressed-bytes-per-second"),
          "the upstream migration must not be skipped by ours");
      assertEquals(500, config.<Integer>get("packet-limiter.packets-per-second"),
          "an operator's limit is kept, not reset to the upstream default");
      assertEquals("2.8", config.get("config-version"));
    }
  }
}
