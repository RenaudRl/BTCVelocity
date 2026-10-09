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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
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

  private static final String FAIR = "$f$a$i$r$x$a$e$r$o".replace('$', SECTION);
  private static final String NO_MAP = "$n$o$m$i$n$i$m$a$p".replace('$', SECTION);
  private static final String RESET = "$r$e$s$e$t$x$a$e$r$o".replace('$', SECTION);

  @Test
  void aFirstJoinSendsFairPlayAndTheVoxelMapSettings() {
    // Act
    var delta = ConsentPlanner.plan(null, ConsentPlanner.desired(CONFIG, "lobby"));

    // Assert
    assertEquals(List.of(FAIR), delta.xaeroMessages());
    assertTrue(delta.voxelJson().contains("\"radarAllowed\":false"));
  }

  @Test
  void movingToABackendWithTheSameConsentResendsItAnyway() {
    // Arrange: sending is idempotent, and the proxy cannot know the client applied the first one.
    var lobby = ConsentPlanner.desired(CONFIG, "lobby");

    // Act
    var delta = ConsentPlanner.plan(lobby, ConsentPlanner.desired(CONFIG, "survie"));

    // Assert
    assertEquals(List.of(FAIR), delta.xaeroMessages(), "no reset: the code did not change");
    assertEquals(lobby.voxelJson(), delta.voxelJson());
  }

  @Test
  void movingToABackendWithADifferentCodeResetsThenSendsTheNewOne() {
    // Arrange: the flags are cumulative, so the new code alone would add to the old one.
    var lobby = ConsentPlanner.desired(CONFIG, "lobby");

    // Act
    var delta = ConsentPlanner.plan(lobby, ConsentPlanner.desired(CONFIG, "creatif"));

    // Assert
    assertEquals(List.of(RESET, NO_MAP), delta.xaeroMessages());
    assertTrue(delta.voxelJson().contains("\"radarAllowed\":true"));
  }

  @Test
  void leavingARestrictedBackendForOneThatDeclaresNothingSendsOnlyAReset() {
    var lobby = ConsentPlanner.desired(CONFIG, "lobby");
    var free = ConsentPlanner.desired(CONFIG, "free-1");

    var delta = ConsentPlanner.plan(lobby, free);

    assertEquals(List.of(RESET), delta.xaeroMessages(),
        "the fair-play flags would otherwise follow the player");
  }

  @Test
  void aFirstJoinOrAStayOnABackendThatDeclaresNothingSendsNoXaeroMessage() {
    var free = ConsentPlanner.desired(CONFIG, "free-1");

    assertTrue(ConsentPlanner.plan(null, free).xaeroMessages().isEmpty());
    assertTrue(ConsentPlanner.plan(free, free).xaeroMessages().isEmpty());
  }

  @Test
  void aSwitchedOffVoxelMapPacketIsNeverSent() {
    var config = ClientPolicyConfig.parseYaml("consent:\n  voxelmap:\n    enabled: false\n")
        .config().orElseThrow();

    var delta = ConsentPlanner.plan(null, ConsentPlanner.desired(config, "lobby"));

    assertNull(delta.voxelJson());
  }

  @Test
  void theConsentJournalLineIsReadable() {
    // Arrange
    var who = new ObservationLog.Who("Steve", java.util.UUID.randomUUID());
    var delta = ConsentPlanner.plan(ConsentPlanner.desired(CONFIG, "lobby"),
        ConsentPlanner.desired(CONFIG, "creatif"));

    // Act
    String line = ObservationLog.consent(who, ClientOrigin.UNKNOWN, "creatif", delta);

    // Assert
    assertTrue(line.contains("origin=UNKNOWN"));
    assertTrue(line.contains("xaero=\"resetxaero+nominimap\""), line);
    assertTrue(line.contains("voxelmap=\"radarAllowed=true, radarMobsAllowed=false,"), line);
    assertFalse(line.contains("{"), "no raw JSON in the journal");
  }
}
