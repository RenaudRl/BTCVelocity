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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PacklessServersTest {

  @Test
  void anAbsentDeclarationCoversNothing() {
    // Arrange
    var packless = PacklessServers.parse(null);

    // Act & Assert — the counter-test of "empty means everything"
    assertTrue(packless.isEmpty());
    assertFalse(packless.covers("creatif"));
    assertFalse(packless.covers("Lobby-1"));
  }

  @Test
  void blankDeclarationIsTreatedAsAbsent() {
    assertTrue(PacklessServers.parse("   ").isEmpty());
    assertTrue(PacklessServers.parse(" , ").isEmpty());
  }

  @Test
  void coversExactNameAndPrefixRegardlessOfCase() {
    // Arrange
    var packless = PacklessServers.parse("creatif, Build-*");

    // Act & Assert
    assertTrue(packless.covers("creatif"));
    assertTrue(packless.covers("CREATIF"));
    assertTrue(packless.covers("build-2"));
    assertFalse(packless.covers("Lobby-1"), "a CraftEngine backend must keep its pack");
    assertFalse(packless.covers("creatif-2"), "an exact pattern must not behave like a prefix");
    assertFalse(packless.covers("builder"), "a prefix covers its own names only");
  }

  @Test
  void keepsDeclarationOrderLowercased() {
    assertEquals(List.of("creatif", "build-*"),
        PacklessServers.parse(" Creatif ,Build-* ").patterns());
  }
}
