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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VoxelMapSettingsPacketTest {

  @Test
  void aShortPayloadIsAZeroByteThenALengthPrefixedUtf8String() {
    // Arrange
    String json = "{\"radarAllowed\":false}";

    // Act
    byte[] payload = VoxelMapSettingsPacket.encode(json);

    // Assert
    byte[] text = json.getBytes(StandardCharsets.UTF_8);
    assertEquals(0, payload[0], "the first byte the client reads and discards is 0");
    assertEquals(text.length, payload[1], "one-byte VarInt below 128");
    assertArrayEquals(text, Arrays.copyOfRange(payload, 2, payload.length));
    assertEquals(text.length + 2, payload.length);
  }

  @Test
  void aLongPayloadUsesAMultiByteVarIntLength() {
    // Arrange: 300 bytes -> VarInt 0xAC 0x02
    String json = "x".repeat(300);

    // Act
    byte[] payload = VoxelMapSettingsPacket.encode(json);

    // Assert
    assertEquals(0, payload[0]);
    assertEquals((byte) 0xAC, payload[1]);
    assertEquals(0x02, payload[2]);
    assertEquals(300 + 3, payload.length);
  }

  @Test
  void theLengthCountsBytesNotCharacters() {
    // Arrange: two characters, four bytes in UTF-8.
    String json = "éè";

    // Act
    byte[] payload = VoxelMapSettingsPacket.encode(json);

    // Assert
    assertEquals(4, payload[1]);
    assertEquals(6, payload.length);
  }

  @Test
  void theJsonCarriesTheConfigurableKeysInAFixedOrder() {
    // Arrange
    Map<String, Object> settings = new LinkedHashMap<>(VoxelMapConsent.fairPlay().defaults());
    settings.put("teleportCommand", "/tp {x} {y} {z}");

    // Act
    String json = VoxelMapSettingsPacket.toJson(settings);

    // Assert
    assertEquals("{\"radarAllowed\":false,\"radarMobsAllowed\":false,"
        + "\"radarPlayersAllowed\":false,\"cavesAllowed\":false,\"minimapAllowed\":true,"
        + "\"worldmapAllowed\":true,\"waypointsAllowed\":true,\"deathWaypointAllowed\":false,"
        + "\"teleportCommand\":\"/tp {x} {y} {z}\"}", json);
  }

  @Test
  void aBlankTeleportCommandIsLeftOutAndUnknownKeysAreNeverWritten() {
    Map<String, Object> settings = new LinkedHashMap<>();
    settings.put("teleportCommand", " ");
    settings.put("serverIdentity", "x");

    assertEquals("{}", VoxelMapSettingsPacket.toJson(settings));
  }
}
