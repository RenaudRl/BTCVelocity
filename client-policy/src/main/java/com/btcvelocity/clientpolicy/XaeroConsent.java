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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Xaero's Minimap / World Map consent: a system message carrying section-sign codes that the mod
 * reads, then hides.
 *
 * <p>The codes are the ones the mod's author publishes in the FAQ of the mod page (read on
 * 29/09/2026, see {@code research/client-policy-2026-09-29.md}). They are a declared policy that
 * an honest mod applies to itself, not a protection: a modified client ignores them.
 *
 * <p>Each code is the letters of a word, every letter preceded by the section sign.
 *
 * @param defaultCode the code sent on every backend without a declaration of its own, may be empty
 * @param servers codes keyed by normalised server pattern, an empty code meaning "send nothing"
 */
public record XaeroConsent(String defaultCode, Map<String, String> servers) {

  /** Fair play: entity radar and cave mode off. The default. */
  public static final String FAIR_PLAY = coded("fairxaero");
  /** Minimap off. */
  public static final String NO_MINIMAP = coded("nominimap");
  /** Cave mode allowed in the Nether despite fair play (Minimap flavour). */
  public static final String NETHER_IS_FAIR_MINIMAP = coded("xaerommnetherisfair");
  /** Cave mode allowed in the Nether despite fair play (World Map flavour, different word). */
  public static final String NETHER_IS_FAIR_WORLD_MAP = coded("xaerowmnetherisfair");
  /** Puts every flag back. Sent when a player moves to a backend that declares nothing. */
  public static final String RESET = coded("resetxaero");

  private static final Map<String, String> ALIASES = Map.of(
      "fairplay", FAIR_PLAY,
      "no-minimap", NO_MINIMAP,
      "nether-fair-minimap", NETHER_IS_FAIR_MINIMAP,
      "nether-fair-worldmap", NETHER_IS_FAIR_WORLD_MAP,
      "reset", RESET,
      "none", "");

  private static final char SECTION = (char) 0xA7;

  public XaeroConsent {
    servers = Map.copyOf(servers);
  }

  /**
   * The built-in stance: fair play everywhere.
   *
   * @return the default consent
   */
  public static XaeroConsent fairPlayEverywhere() {
    return new XaeroConsent(FAIR_PLAY, Map.of());
  }

  /**
   * The code to send on entering a backend.
   *
   * @param serverName the Velocity server name
   * @return the code, empty when nothing must be sent
   */
  public String codeFor(String serverName) {
    return ServerPatterns.find(this.servers, serverName).orElse(this.defaultCode);
  }

  /**
   * Reads the {@code consent.xaero} node.
   *
   * @param node the parsed node, may be {@code null}
   * @param errors where problems are reported
   * @return the consent it describes, or the built-in one when the node is absent
   */
  static XaeroConsent parse(Object node, List<String> errors) {
    if (node == null) {
      return fairPlayEverywhere();
    }
    Map<String, Object> map = ConfigNodes.asMap(node, "consent.xaero", errors);
    String defaultCode = map.containsKey("default")
        ? resolve(map.get("default"), "consent.xaero.default", errors)
        : FAIR_PLAY;

    Map<String, String> servers = new LinkedHashMap<>();
    Map<String, Object> declared =
        ConfigNodes.asMap(map.get("servers"), "consent.xaero.servers", errors);
    declared.forEach((server, value) -> servers.put(ServerPatterns.normalize(server),
        resolve(value, "consent.xaero.servers." + server, errors)));
    return new XaeroConsent(defaultCode, servers);
  }

  /** A value is an alias, a raw code containing the section sign, or a list of those. */
  private static String resolve(Object value, String path, List<String> errors) {
    List<Object> pieces = new ArrayList<>();
    if (value instanceof List<?> list) {
      pieces.addAll(list);
    } else {
      pieces.add(value);
    }

    StringBuilder code = new StringBuilder();
    for (Object piece : pieces) {
      String text = piece == null ? "" : piece.toString().trim();
      String alias = ALIASES.get(text.toLowerCase(Locale.ROOT));
      if (alias != null) {
        code.append(alias);
      } else if (text.indexOf(SECTION) >= 0) {
        code.append(text);
      } else {
        errors.add(path + ": '" + text + "' is neither a known alias " + ALIASES.keySet()
            + " nor a raw code containing the section sign");
      }
    }
    return code.toString();
  }

  private static String coded(String word) {
    StringBuilder code = new StringBuilder();
    for (char letter : word.toCharArray()) {
      code.append(SECTION).append(letter);
    }
    return code.toString();
  }
}
