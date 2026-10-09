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

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Per-backend lookup, with the same vocabulary as the arrival and pack policies: a key is either
 * an exact server name or a prefix ending in {@code *}, compared case-insensitively.
 */
final class ServerPatterns {

  private static final String WILDCARD = "*";

  private ServerPatterns() {
  }

  /**
   * Normalises a key the way {@link #find} expects to compare it.
   *
   * @param key a raw server name or prefix pattern
   * @return the trimmed, lower-cased key
   */
  static String normalize(String key) {
    return key.trim().toLowerCase(Locale.ROOT);
  }

  /**
   * Finds the value declared for a server: the exact name wins, else the longest matching prefix.
   *
   * @param byPattern values keyed by {@linkplain #normalize normalised} pattern
   * @param serverName the Velocity server name
   * @param <V> the value type
   * @return the value, or empty when no key covers the server
   */
  static <V> Optional<V> find(Map<String, V> byPattern, String serverName) {
    String candidate = normalize(serverName);
    V exact = byPattern.get(candidate);
    if (exact != null) {
      return Optional.of(exact);
    }

    String bestKey = null;
    for (String key : byPattern.keySet()) {
      if (key.endsWith(WILDCARD)
          && candidate.startsWith(key.substring(0, key.length() - 1))
          && (bestKey == null || key.length() > bestKey.length())) {
        bestKey = key;
      }
    }
    return bestKey == null ? Optional.empty() : Optional.of(byPattern.get(bestKey));
  }
}
