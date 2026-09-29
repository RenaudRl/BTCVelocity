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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ConsentPlannerTest {

  private static final char SECTION = (char) 0xA7;

  /** The author-published codes, written independently of the production constants. */
  private static String codes(String dollarForm) {
    return dollarForm.replace('$', SECTION);
  }

  private static final ClientPolicyConfig CONFIG = ClientPolicyConfig.parseYaml(
      "consent:\n  xaero:\n    default: fairplay\n    servers:\n      creatif: no-minimap\n"
          + "      free-*: none\n  voxelmap:\n    servers:\n      creatif:\n"
          + "        radarAllowed: true\n").config().orElseThrow();

  @Test
  void theCodesAreTheOnesTheAuthorPublishes() {
    assertEquals(codes("$f$a$i$r$x$a$e$r$o"), XaeroConsent.FAIR_PLAY);
    assertEquals(codes("$n$o$m$i$n$i$m$a$p"), XaeroConsent.NO_MINIMAP);
    assertEquals(codes("$x$a$e$r$o$m$m$n$e$t$h$e$r$i$s$f$a$i$r"),
        XaeroConsent.NETHER_IS_FAIR_MINIMAP);
    assertEquals(codes("$x$a$e$r$o$w$m$n$e$t$h$e$r$i$s$f$a$i$r"),
        XaeroConsent.NETHER_IS_FAIR_WORLD_MAP);
    assertEquals(codes("$r$e$s$e$t$x$a$e$r$o"), XaeroConsent.RESET);
  }

  @Test
  void aFirstJoinSendsFairPlayAndTheVoxelMapSettings() {
    // Act
    var delta = ConsentPlanner.delta(null, ConsentPlanner.desired(CONFIG, "lobby"));

    // Assert
    assertEquals(codes("$f$a$i$r$x$a$e$r$o"), delta.xaeroMessage());
    assertTrue(delta.voxelJson().contains("\"radarAllowed\":false"));
  }

  @Test
  void movingToABackendWithTheSameConsentSendsNothing() {
    // Arrange
    var lobby = ConsentPlanner.desired(CONFIG, "lobby");

    // Act
    var delta = ConsentPlanner.delta(lobby, ConsentPlanner.desired(CONFIG, "survie"));

    // Assert
    assertTrue(delta.isEmpty());
  }

  @Test
  void movingToABackendWithDifferentCodesResendsThem() {
    // Arrange
    var lobby = ConsentPlanner.desired(CONFIG, "lobby");

    // Act
    var delta = ConsentPlanner.delta(lobby, ConsentPlanner.desired(CONFIG, "creatif"));

    // Assert
    assertEquals(codes("$n$o$m$i$n$i$m$a$p"), delta.xaeroMessage());
    assertTrue(delta.voxelJson().contains("\"radarAllowed\":true"));
  }

  @Test
  void leavingARestrictedBackendForOneThatDeclaresNothingSendsAReset() {
    // Arrange
    var lobby = ConsentPlanner.desired(CONFIG, "lobby");
    var free = ConsentPlanner.desired(CONFIG, "free-1");

    // Act
    var delta = ConsentPlanner.delta(lobby, free);

    // Assert
    assertEquals(codes("$r$e$s$e$t$x$a$e$r$o"), delta.xaeroMessage(),
        "the fair-play flags would otherwise follow the player");
  }

  @Test
  void aFirstJoinOnABackendThatDeclaresNothingSendsNoXaeroMessage() {
    var delta = ConsentPlanner.delta(null, ConsentPlanner.desired(CONFIG, "free-1"));

    assertNull(delta.xaeroMessage());
  }

  @Test
  void aSwitchedOffVoxelMapPacketIsNeverSent() {
    var config = ClientPolicyConfig.parseYaml("consent:\n  voxelmap:\n    enabled: false\n")
        .config().orElseThrow();

    var delta = ConsentPlanner.delta(null, ConsentPlanner.desired(config, "lobby"));

    assertNull(delta.voxelJson());
  }
}
