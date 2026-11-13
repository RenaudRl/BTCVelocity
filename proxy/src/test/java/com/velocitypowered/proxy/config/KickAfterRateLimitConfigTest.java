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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The shipped file kicks a flooder after 100 refused commands or 40 refused tab-completes. */
class KickAfterRateLimitConfigTest {

  private static final String BUNDLE_DIR = "com/velocitypowered/proxy/l10n/";

  @Test
  void theShippedFileKicksAfterOneHundredCommandsAndFortyTabCompletes(@TempDir final Path dir)
      throws IOException {
    final Path secret = dir.resolve("forwarding.secret");
    Files.writeString(secret, "secret");
    final String text;
    try (InputStream in = VelocityConfiguration.class.getClassLoader()
        .getResourceAsStream("default-velocity.toml")) {
      text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    final Path file = dir.resolve("velocity.toml");
    Files.writeString(file, text.replace("forwarding-secret-file = \"forwarding.secret\"",
        "forwarding-secret-file = '" + secret + "'"), StandardCharsets.UTF_8);

    final VelocityConfiguration config = VelocityConfiguration.read(file);

    assertEquals(100, config.getKickAfterRateLimitedCommands());
    assertEquals(40, config.getKickAfterRateLimitedTabCompletes());
  }

  /** The eight network languages: the root bundle is English, the others are suffixed. */
  @Test
  void everyNetworkLanguageTranslatesBothKickMessages() throws IOException {
    for (String suffix : new String[] {"", "_fr_FR", "_de_DE", "_es_ES", "_pt_BR", "_ru_RU",
        "_it_IT", "_pl_PL"}) {
      final String resource = BUNDLE_DIR + "messages" + suffix + ".properties";
      final Properties messages = new Properties();
      try (InputStream in = VelocityConfiguration.class.getClassLoader()
          .getResourceAsStream(resource)) {
        assertNotNull(in, resource + " must exist");
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
          messages.load(reader);
        }
      }

      for (String key : new String[] {"velocity.kick.command-rate-limit",
          "velocity.kick.tab-complete-rate-limit"}) {
        final String value = messages.getProperty(key);
        assertNotNull(value, resource + " lacks " + key);
        assertTrue(!value.isBlank(), resource + " has a blank " + key);
      }
    }
  }
}
