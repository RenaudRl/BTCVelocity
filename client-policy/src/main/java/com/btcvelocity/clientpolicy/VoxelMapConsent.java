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
import java.util.Optional;

/**
 * The VoxelMap-Updated consent: what the server allows, sent as a {@code voxelmap:settings}
 * packet (see {@link VoxelMapSettingsPacket}).
 *
 * <p>A server-specific block overrides the defaults key by key; a key it does not mention keeps
 * the default. The built-in defaults are the fair-play stance, in line with the Xaero one: no
 * entity radar, no cave mode, no death waypoint.
 *
 * @param enabled whether the packet is sent at all
 * @param defaults the complete settings applied on every backend without an override
 * @param servers partial overrides keyed by normalised server pattern
 */
public record VoxelMapConsent(boolean enabled, Map<String, Object> defaults,
                              Map<String, Map<String, Object>> servers) {

  public VoxelMapConsent {
    defaults = Map.copyOf(defaults);
    servers = Map.copyOf(servers);
  }

  /**
   * The built-in stance.
   *
   * @return fair-play settings, sent
   */
  public static VoxelMapConsent fairPlay() {
    Map<String, Object> settings = new LinkedHashMap<>();
    settings.put("radarAllowed", false);
    settings.put("radarMobsAllowed", false);
    settings.put("radarPlayersAllowed", false);
    settings.put("cavesAllowed", false);
    settings.put("minimapAllowed", true);
    settings.put("worldmapAllowed", true);
    settings.put("waypointsAllowed", true);
    settings.put("deathWaypointAllowed", false);
    return new VoxelMapConsent(true, settings, Map.of());
  }

  /**
   * The settings JSON for a backend.
   *
   * @param serverName the Velocity server name
   * @return the JSON, or empty when the packet is switched off
   */
  public Optional<String> jsonFor(String serverName) {
    if (!this.enabled) {
      return Optional.empty();
    }
    Map<String, Object> merged = new LinkedHashMap<>(this.defaults);
    ServerPatterns.find(this.servers, serverName).ifPresent(merged::putAll);
    return Optional.of(VoxelMapSettingsPacket.toJson(merged));
  }

  /**
   * Reads the {@code consent.voxelmap} node.
   *
   * @param node the parsed node, may be {@code null}
   * @param errors where problems are reported
   * @return the consent it describes, or the built-in one when the node is absent
   */
  static VoxelMapConsent parse(Object node, List<String> errors) {
    VoxelMapConsent builtIn = fairPlay();
    if (node == null) {
      return builtIn;
    }
    Map<String, Object> map = ConfigNodes.asMap(node, "consent.voxelmap", errors);
    boolean enabled = !Boolean.FALSE.equals(map.get("enabled"));

    Map<String, Object> defaults = new LinkedHashMap<>(builtIn.defaults());
    defaults.putAll(settings(map.get("default"), "consent.voxelmap.default", errors));

    Map<String, Map<String, Object>> servers = new LinkedHashMap<>();
    ConfigNodes.asMap(map.get("servers"), "consent.voxelmap.servers", errors)
        .forEach((server, block) -> servers.put(ServerPatterns.normalize(server),
            settings(block, "consent.voxelmap.servers." + server, errors)));
    return new VoxelMapConsent(enabled, defaults, servers);
  }

  /** A settings block: known keys only, booleans for flags, text for the teleport command. */
  private static Map<String, Object> settings(Object node, String path, List<String> errors) {
    Map<String, Object> result = new LinkedHashMap<>();
    ConfigNodes.asMap(node, path, errors).forEach((key, value) -> {
      if (VoxelMapSettingsPacket.BOOLEAN_KEYS.contains(key)) {
        if (value instanceof Boolean flag) {
          result.put(key, flag);
        } else {
          errors.add(path + "." + key + ": expected true or false, found " + value);
        }
      } else if (VoxelMapSettingsPacket.TELEPORT_COMMAND_KEY.equals(key)) {
        result.put(key, String.valueOf(value));
      } else {
        errors.add(path + "." + key + ": unknown setting, the client reads "
            + VoxelMapSettingsPacket.BOOLEAN_KEYS + " and "
            + VoxelMapSettingsPacket.TELEPORT_COMMAND_KEY);
      }
    });
    return result;
  }
}
