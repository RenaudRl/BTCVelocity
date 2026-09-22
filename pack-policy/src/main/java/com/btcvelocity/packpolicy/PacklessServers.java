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

package com.btcvelocity.packpolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The backends a player must enter without any resource pack applied.
 *
 * <p>A pure value — no Velocity type — so the rule is testable without a runtime. A pattern is
 * either an exact server name or a prefix ending in {@code *}, the same form as the arrival
 * policy, so one operator vocabulary covers both.
 */
public final class PacklessServers {

  private static final String WILDCARD = "*";

  private final List<String> patterns;

  private PacklessServers(List<String> patterns) {
    this.patterns = List.copyOf(patterns);
  }

  /**
   * Reads the declaration: patterns separated by commas.
   *
   * <p>A blank declaration yields an empty set, which matches nothing: an absent declaration must
   * leave every player's packs exactly as the backends set them.
   *
   * @param declaration the raw declaration, may be {@code null}
   * @return the set it describes
   */
  public static PacklessServers parse(String declaration) {
    if (declaration == null || declaration.isBlank()) {
      return new PacklessServers(List.of());
    }

    List<String> parsed = new ArrayList<>();
    for (String piece : declaration.split(",")) {
      String trimmed = piece.trim();
      if (!trimmed.isEmpty()) {
        parsed.add(trimmed.toLowerCase(Locale.ROOT));
      }
    }
    return new PacklessServers(parsed);
  }

  /**
   * Whether nothing was declared.
   *
   * @return {@code true} when no pattern was declared
   */
  public boolean isEmpty() {
    return this.patterns.isEmpty();
  }

  /**
   * Whether a player entering this server must lose their packs.
   *
   * @param serverName the Velocity server name
   * @return {@code true} if some declared pattern covers it
   */
  public boolean covers(String serverName) {
    String candidate = serverName.toLowerCase(Locale.ROOT);
    for (String pattern : this.patterns) {
      if (pattern.endsWith(WILDCARD)) {
        if (candidate.startsWith(pattern.substring(0, pattern.length() - 1))) {
          return true;
        }
      } else if (pattern.equals(candidate)) {
        return true;
      }
    }
    return false;
  }

  /**
   * The declared patterns, in declaration order.
   *
   * @return the patterns
   */
  public List<String> patterns() {
    return this.patterns;
  }
}
