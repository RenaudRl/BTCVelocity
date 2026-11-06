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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArrivalMessagesTest {

  private static String plain(ArrivalMessages messages, String key) {
    return PlainTextComponentSerializer.plainText().serialize(messages.get(key));
  }

  @Test
  void aMissingFileIsSeededWithTheDefaults(@TempDir Path dir) {
    // Act
    var messages = ArrivalMessages.load(dir);

    // Assert
    assertTrue(Files.exists(dir.resolve(ArrivalMessages.FILE_NAME)));
    assertEquals(plain(ArrivalMessages.defaults(), "limbo.waiting"), plain(messages, "limbo.waiting"));
  }

  @Test
  void theOperatorsTextWinsAndARemovedKeyFallsBack(@TempDir Path dir) throws Exception {
    // Arrange — the operator kept a single key, accented, in UTF-8
    Files.writeString(dir.resolve(ArrivalMessages.FILE_NAME),
        "limbo.waiting=<red>Patientez, réveil en cours\n", StandardCharsets.UTF_8);

    // Act
    var messages = ArrivalMessages.load(dir);

    // Assert
    assertEquals("Patientez, réveil en cours", plain(messages, "limbo.waiting"));
    assertEquals(plain(ArrivalMessages.defaults(), "limbo.maintenance"),
        plain(messages, "limbo.maintenance"));
  }
}
