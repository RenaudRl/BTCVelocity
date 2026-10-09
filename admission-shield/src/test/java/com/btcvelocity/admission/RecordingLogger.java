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

package com.btcvelocity.admission;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.helpers.MessageFormatter;

/** A logger that keeps what it is told, formatted, so a test can assert on it. */
final class RecordingLogger {

  private final List<String> lines = new CopyOnWriteArrayList<>();
  private final Logger logger;

  RecordingLogger() {
    this.logger = (Logger) Proxy.newProxyInstance(Logger.class.getClassLoader(),
        new Class<?>[] {Logger.class}, (proxy, method, arguments) -> {
          final String level = method.getName();
          if (arguments != null && arguments.length > 0 && arguments[0] instanceof String format
              && List.of("trace", "debug", "info", "warn", "error").contains(level)) {
            final Object[] rest = new Object[arguments.length - 1];
            System.arraycopy(arguments, 1, rest, 0, rest.length);
            final Object[] flattened = rest.length == 1 && rest[0] instanceof Object[] array
                ? array : rest;
            this.lines.add(level.toUpperCase(java.util.Locale.ROOT) + " "
                + MessageFormatter.arrayFormat(format, flattened).getMessage());
            return null;
          }
          if (method.getReturnType() == boolean.class) {
            return true;
          }
          return null;
        });
  }

  Logger logger() {
    return this.logger;
  }

  List<String> lines() {
    return this.lines;
  }

  boolean has(final String level, final String fragment) {
    return this.lines.stream().anyMatch(line -> line.startsWith(level) && line.contains(fragment));
  }
}
