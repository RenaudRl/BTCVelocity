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

package com.btcvelocity.clientpolicy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Typed reads over a YAML tree, reporting a wrong shape instead of throwing. */
final class ConfigNodes {

  private ConfigNodes() {
  }

  /**
   * Reads a node as a map with string keys.
   *
   * @param node the node, {@code null} meaning "absent"
   * @param path where the node sits, for the error message
   * @param errors where a wrong shape is reported
   * @return the map, empty when the node is absent or of the wrong shape
   */
  static Map<String, Object> asMap(Object node, String path, List<String> errors) {
    Map<String, Object> result = new LinkedHashMap<>();
    if (node == null) {
      return result;
    }
    if (!(node instanceof Map<?, ?> map)) {
      errors.add(path + ": expected a mapping, found " + node.getClass().getSimpleName());
      return result;
    }
    map.forEach((key, value) -> result.put(String.valueOf(key), value));
    return result;
  }

  /**
   * Reads a node as a list.
   *
   * @param node the node, {@code null} meaning "absent"
   * @param path where the node sits, for the error message
   * @param errors where a wrong shape is reported
   * @return the list, empty when the node is absent or of the wrong shape
   */
  static List<Object> asList(Object node, String path, List<String> errors) {
    if (node == null) {
      return List.of();
    }
    if (!(node instanceof List<?> list)) {
      errors.add(path + ": expected a list, found " + node.getClass().getSimpleName());
      return List.of();
    }
    return List.copyOf(list);
  }

  /**
   * Reads a node as a non-blank string.
   *
   * @param node the node
   * @param fallback the value when the node is absent or blank
   * @return the trimmed text, or the fallback
   */
  static String asText(Object node, String fallback) {
    if (node == null || node.toString().isBlank()) {
      return fallback;
    }
    return node.toString().trim();
  }
}
