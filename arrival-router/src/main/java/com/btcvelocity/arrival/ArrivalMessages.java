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

package com.btcvelocity.arrival;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * Every text this plugin shows a player or an operator, in MiniMessage, read from
 * {@code messages.properties} in the plugin's data directory. Nothing player-facing is written in
 * code: the bundled file only seeds the defaults, and a key the operator removed falls back to it.
 *
 * <p>On CloudNet the data directory comes from the proxy template, so an edited file belongs there.
 */
public final class ArrivalMessages {

  static final String FILE_NAME = "messages.properties";

  private final Properties defaults;
  private final Properties values;

  private ArrivalMessages(Properties defaults, Properties values) {
    this.defaults = defaults;
    this.values = values;
  }

  /**
   * The bundled defaults alone. For tests, and for a proxy started without a data directory.
   *
   * @return the default messages
   */
  public static ArrivalMessages defaults() {
    var bundled = bundled();
    return new ArrivalMessages(bundled, bundled);
  }

  /**
   * Reads the operator's file, writing the bundled one first when there is none.
   *
   * @param dataDirectory the plugin's data directory
   * @return the messages, operator values first
   */
  public static ArrivalMessages load(Path dataDirectory) {
    var file = dataDirectory.resolve(FILE_NAME);
    try {
      if (Files.notExists(file)) {
        Files.createDirectories(dataDirectory);
        try (InputStream in = resource()) {
          Files.copy(in, file);
        }
      }
      var values = new Properties();
      try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
        values.load(reader);
      }
      return new ArrivalMessages(bundled(), values);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + file, e);
    }
  }

  /**
   * One message, rendered.
   *
   * @param key the message key, as in the bundled file
   * @return the component, empty when the key exists nowhere
   */
  public Component get(String key) {
    var raw = this.values.getProperty(key, this.defaults.getProperty(key, ""));
    return MiniMessage.miniMessage().deserialize(raw);
  }

  private static Properties bundled() {
    var properties = new Properties();
    try (InputStream in = resource();
         Reader reader = new java.io.InputStreamReader(in, StandardCharsets.UTF_8)) {
      properties.load(reader);
      return properties;
    } catch (IOException e) {
      throw new UncheckedIOException("Bundled " + FILE_NAME + " is unreadable", e);
    }
  }

  private static InputStream resource() throws IOException {
    var in = ArrivalMessages.class.getResourceAsStream("/" + FILE_NAME);
    if (in == null) {
      throw new IOException("Bundled " + FILE_NAME + " is missing from the jar");
    }
    return in;
  }
}
