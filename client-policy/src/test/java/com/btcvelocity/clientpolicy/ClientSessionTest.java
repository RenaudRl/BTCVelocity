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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.btcvelocity.api.bridge.BridgeMessage;
import com.btcvelocity.api.bridge.PlatformSource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClientSessionTest {

  @Test
  void theFirstBrandIsDeclaredAndARepeatIsSilent() {
    // Arrange
    var session = new ClientSession();

    // Act & Assert
    var first = session.recordBrand("fabric").orElseThrow();
    assertEquals(ClientSession.Kind.BRAND_DECLARED, first.kind());
    assertTrue(session.recordBrand("fabric").isEmpty());
    assertEquals(ClientSession.Kind.BRAND_CHANGED, session.recordBrand("vanilla").orElseThrow().kind());
  }

  @Test
  void onlyNewChannelsAreReportedAsAdded() {
    // Arrange
    var session = new ClientSession();
    session.recordChannels(List.of("fabric:register", "c:version"));

    // Act
    var change = session.recordChannels(List.of("c:version", "xaerominimap:main")).orElseThrow();

    // Assert
    assertEquals(List.of("xaerominimap:main"), change.added());
    assertEquals(3, change.channelTotal());
    assertTrue(session.recordChannels(List.of("c:version")).isEmpty());
  }

  @Test
  void aVanillaBrandWithModChannelsIsInconsistent() {
    assertTrue(ClientCoherence.isVanillaWithModChannels("vanilla", List.of("fabric:register")));
    assertTrue(ClientCoherence.isVanillaWithModChannels(" Vanilla ", List.of("FML|HS")),
        "a legacy channel without a namespace is foreign");
  }

  @Test
  void vanillaWithOnlyGameChannelsOrAModBrandIsNotInconsistent() {
    assertFalse(ClientCoherence.isVanillaWithModChannels("vanilla", List.of()));
    assertFalse(ClientCoherence.isVanillaWithModChannels("vanilla",
        List.of("minecraft:register", "MINECRAFT:brand")));
    assertFalse(ClientCoherence.isVanillaWithModChannels("fabric", List.of("fabric:register")));
    assertFalse(ClientCoherence.isVanillaWithModChannels(null, List.of("fabric:register")));
  }

  @Test
  void theOnceOnlyFlagsFireOnce() {
    var session = new ClientSession();

    assertTrue(session.markInconsistencyNoted());
    assertFalse(session.markInconsistencyNoted());
    assertTrue(session.markBedrockNoted());
    assertFalse(session.markBedrockNoted());
    assertTrue(session.markRuleReported("Wurst"));
    assertFalse(session.markRuleReported("Wurst"));
    assertTrue(session.markRuleReported("Other"));
  }

  @Test
  void theJournalLinesAreStructuredAndSayBedrockExplicitly() {
    // Arrange
    var who = new ObservationLog.Who("Steve", UUID.fromString("00000000-0000-0000-0000-000000000001"));
    var session = new ClientSession();

    // Act
    String brandLine = ObservationLog.observation(who, ClientOrigin.JAVA,
        session.recordBrand("vanilla").orElseThrow());
    String channelLine = ObservationLog.observation(who, ClientOrigin.JAVA,
        session.recordChannels(List.of("fabric:register")).orElseThrow());
    String clue = ObservationLog.inconsistency(who, ClientOrigin.JAVA, "vanilla",
        List.of("fabric:register"));

    // Assert
    assertEquals("client-policy player=Steve uuid=00000000-0000-0000-0000-000000000001"
        + " origin=JAVA event=brand-declared brand=\"vanilla\" channels=0", brandLine);
    assertTrue(channelLine.contains("event=channels-added added=[\"fabric:register\"]"));
    assertTrue(clue.contains("event=inconsistency") && clue.endsWith("action=none"));
    assertTrue(ObservationLog.bedrock(who).endsWith("origin BEDROCK, client policy n/a"));
  }

  @Test
  void aHostileBrandCannotForgeALineOrBreakTheQuoting() {
    // Arrange
    var who = new ObservationLog.Who("Steve", UUID.randomUUID());
    var change = new ClientSession().recordBrand("x\"\nclient-policy player=Admin \\").orElseThrow();

    // Act
    String line = ObservationLog.observation(who, ClientOrigin.JAVA, change);

    // Assert
    assertFalse(line.contains("\n"));
    assertTrue(line.contains("brand=\"x__client-policy player=Admin _\""), line);
  }

  @Test
  void anOversizedValueIsCutShort() {
    String line = ObservationLog.quote("a".repeat(500));

    assertTrue(line.length() < 110, line);
    assertTrue(line.endsWith("...\""));
  }

  @Test
  void theOriginIsMeasuredNeverGuessed() {
    UUID player = UUID.randomUUID();

    assertEquals(ClientOrigin.UNKNOWN,
        BtcClientPolicyPlugin.resolveOrigin(PlatformSource.UNAVAILABLE, player));
    assertEquals(ClientOrigin.BEDROCK, BtcClientPolicyPlugin.resolveOrigin(
        source(true, Optional.of(BridgeMessage.Platform.BEDROCK)), player));
    assertEquals(ClientOrigin.JAVA, BtcClientPolicyPlugin.resolveOrigin(
        source(true, Optional.of(BridgeMessage.Platform.JAVA)), player));
    assertEquals(ClientOrigin.UNKNOWN,
        BtcClientPolicyPlugin.resolveOrigin(source(true, Optional.empty()), player));
  }

  @Test
  void theProfileViewShowsBrandChannelsAndTheDenyVerdict() {
    // Arrange
    var config = ClientPolicyConfig.parseYaml("deny:\n  - {label: Wurst, brand: '.*wurst.*'}\n")
        .config().orElseThrow();
    var snapshot = new ClientSession.Snapshot("wurst", List.of("fabric:register"));
    var verdict = RefusalPolicy.evaluate(config, ClientOrigin.JAVA, () -> false, "wurst",
        snapshot.channels());

    // Act
    List<String> lines = ProfileView.render("Steve", ClientOrigin.JAVA, snapshot, verdict);

    // Assert
    assertTrue(lines.contains("Brand: \"wurst\""));
    assertTrue(lines.contains("Channels (1): \"fabric:register\""));
    assertTrue(lines.stream().anyMatch(line -> line.contains("rule \"Wurst\"")
        && line.contains("WOULD_REFUSE")));
    assertEquals(List.of("Client of Steve (origin BEDROCK)", "origin BEDROCK, client policy n/a"),
        ProfileView.render("Steve", ClientOrigin.BEDROCK, null, RefusalPolicy.Verdict.NOTHING));
  }

  private static PlatformSource source(boolean canResolve, Optional<BridgeMessage.Platform> answer) {
    return new PlatformSource() {
      @Override
      public boolean canResolve() {
        return canResolve;
      }

      @Override
      public Optional<BridgeMessage.Platform> platformOf(UUID player) {
        return answer;
      }

      @Override
      public String describe() {
        return "test";
      }
    };
  }
}
