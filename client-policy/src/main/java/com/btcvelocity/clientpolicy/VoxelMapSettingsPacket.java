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

import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * The {@code voxelmap:settings} server-to-client payload of VoxelMap-Updated.
 *
 * <p>Wire format, read at the source of the mod's {@code VoxelMapSettingsPayload} on 29/09/2026:
 * {@code buf.writeByte(0); buf.writeUtf(settingsJson);} where {@code writeUtf} is Minecraft's
 * string encoding, a VarInt byte length followed by the UTF-8 bytes. Which protocol phase the
 * mod expects the packet in is <em>not</em> established by the source: this proxy sends it in the
 * play phase, once the player is on a backend, and the bench has to confirm it is applied.
 */
final class VoxelMapSettingsPacket {

  /** Channel namespace. */
  static final String NAMESPACE = "voxelmap";
  /** Channel name. */
  static final String NAME = "settings";

  /** Boolean keys the client reads, in the order they are written. */
  static final List<String> BOOLEAN_KEYS = List.of(
      "radarAllowed",
      "radarMobsAllowed",
      "radarPlayersAllowed",
      "cavesAllowed",
      "minimapAllowed",
      "worldmapAllowed",
      "waypointsAllowed",
      "deathWaypointAllowed");

  /** The one string key a server may set. */
  static final String TELEPORT_COMMAND_KEY = "teleportCommand";

  private static final int VARINT_PAYLOAD_BITS = 7;
  private static final int VARINT_CONTINUE = 0x80;
  private static final int VARINT_MASK = 0x7F;

  private VoxelMapSettingsPacket() {
  }

  /**
   * Renders the settings as the JSON object the client parses.
   *
   * @param settings booleans for {@link #BOOLEAN_KEYS} and an optional {@code teleportCommand}
   * @return the compact JSON text
   */
  static String toJson(Map<String, Object> settings) {
    JsonObject json = new JsonObject();
    for (String key : BOOLEAN_KEYS) {
      if (settings.get(key) instanceof Boolean value) {
        json.addProperty(key, value);
      }
    }
    if (settings.get(TELEPORT_COMMAND_KEY) instanceof String command && !command.isBlank()) {
      json.addProperty(TELEPORT_COMMAND_KEY, command);
    }
    return json.toString();
  }

  /**
   * Encodes the payload: a zero byte, then the JSON as a VarInt-prefixed UTF-8 string.
   *
   * @param json the settings JSON
   * @return the bytes to send on {@code voxelmap:settings}
   */
  static byte[] encode(String json) {
    byte[] text = json.getBytes(StandardCharsets.UTF_8);
    ByteArrayOutputStream out = new ByteArrayOutputStream(text.length + 4);
    out.write(0);
    writeVarInt(out, text.length);
    out.writeBytes(text);
    return out.toByteArray();
  }

  private static void writeVarInt(ByteArrayOutputStream out, int value) {
    int remaining = value;
    while ((remaining & ~VARINT_MASK) != 0) {
      out.write((remaining & VARINT_MASK) | VARINT_CONTINUE);
      remaining >>>= VARINT_PAYLOAD_BITS;
    }
    out.write(remaining);
  }
}
