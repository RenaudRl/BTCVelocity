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

package com.velocitypowered.proxy.config.migration;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.velocitypowered.proxy.util.MotdRenderer;
import org.apache.logging.log4j.Logger;

/**
 * Migrates the per-line MOTD alignment keys to the inline {@code <left>}/{@code <center>}/
 * {@code <right>} tags.
 *
 * <p>{@code line1-alignment = "center"} becomes a {@code <center>} tag at the start of
 * {@code motd-line1}, which the new renderer lays out with real font metrics. The old keys are
 * removed, and {@code motd-width} is introduced.
 */
public final class MotdAlignmentMigration implements ConfigurationMigration {

  private static final String CONFIG_VERSION = "2.8";

  @Override
  public boolean shouldMigrate(final CommentedFileConfig config) {
    return configVersion(config) < 2.8;
  }

  @Override
  public void migrate(final CommentedFileConfig config, final Logger logger) {
    migrateLine(config, "motd-line1", "line1-alignment", logger);
    migrateLine(config, "motd-line2", "line2-alignment", logger);

    config.remove("line1-alignment");
    config.remove("line2-alignment");

    if (!config.contains("motd-width")) {
      config.set("motd-width", MotdRenderer.DEFAULT_MOTD_WIDTH);
      config.setComment("motd-width",
          " Usable width of the MOTD area, in pixels. 270 matches the vanilla server list; lower it\n"
              + " if your MOTD looks shifted right, raise it if it looks shifted left.");
    }

    config.setComment("motd-line1",
        " Alignment is written inline with the <left>, <center> and <right> tags. A tag opens a\n"
            + " segment that runs until the next alignment tag or the end of the line, so a single\n"
            + " line can mix alignments, e.g. \"<center><gold>My Server<right><dark_gray>1.26\".\n"
            + " First line of the MOTD");

    config.set("config-version", CONFIG_VERSION);
  }

  private static void migrateLine(final CommentedFileConfig config, final String lineKey,
      final String alignmentKey, final Logger logger) {
    final String alignment = config.get(alignmentKey);
    if (alignment == null) {
      return;
    }

    final String tag = switch (alignment.toLowerCase(java.util.Locale.ROOT)) {
      case "center" -> "<center>";
      case "right" -> "<right>";
      // Left is the implicit default: no tag needed.
      default -> "";
    };
    if (tag.isEmpty()) {
      return;
    }

    final String line = config.get(lineKey);
    if (line == null || line.isEmpty()) {
      return;
    }

    config.set(lineKey, tag + line);
    logger.info("Migrated {} = \"{}\" to a {} tag on {}", alignmentKey, alignment, tag, lineKey);
  }
}
