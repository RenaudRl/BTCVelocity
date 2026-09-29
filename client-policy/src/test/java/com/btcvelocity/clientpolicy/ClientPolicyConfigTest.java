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

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ClientPolicyConfigTest {

  private static ClientPolicyConfig.Parsed parseDeny(String... ruleLines) {
    return ClientPolicyConfig.parseYaml("deny:\n" + String.join("\n", ruleLines) + "\n");
  }

  @Test
  void anEmptyFileYieldsDryRunFairPlayAndNoRule() {
    // Act
    var parsed = ClientPolicyConfig.parseYaml("");

    // Assert
    assertTrue(parsed.errors().isEmpty());
    ClientPolicyConfig config = parsed.config().orElseThrow();
    assertEquals(ClientPolicyConfig.Mode.DRY_RUN, config.mode());
    assertTrue(config.deny().isEmpty(), "the deny list is empty until an operator fills it");
    assertEquals(XaeroConsent.FAIR_PLAY, config.xaero().defaultCode());
    assertTrue(config.voxelMap().enabled());
  }

  @Test
  void theShippedDefaultFileIsValidAndDryRun() throws Exception {
    // Arrange
    String template;
    try (var stream = ClientPolicyConfigTest.class.getResourceAsStream("/config.yml")) {
      template = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }

    // Act
    var parsed = ClientPolicyConfig.parseYaml(template);

    // Assert
    assertTrue(parsed.errors().isEmpty(), () -> parsed.errors().toString());
    assertEquals(ClientPolicyConfig.Mode.DRY_RUN, parsed.config().orElseThrow().mode());
    assertTrue(parsed.config().orElseThrow().deny().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"fabric", "forge", "neoforge", "vanilla", "quilt", "FABRIC", ".*", "fab.*",
      "(?i)vanilla", ".*fabric.*", "f.*e", "^neo.*$", "fabri[a-z]"})
  void aBrandRuleThatCoversAProtectedBrandRefusesTheConfiguration(String pattern) {
    // Act
    var parsed = parseDeny("  - {label: Bad, brand: '" + pattern + "'}");

    // Assert
    assertEquals(Optional.empty(), parsed.config(), "the configuration must be refused");
    assertEquals(1, parsed.errors().size());
    assertTrue(parsed.errors().get(0).contains("fabric, forge, neoforge, vanilla or quilt"));
  }

  @Test
  void aChannelRuleIsHeldToTheSameGuard() {
    var parsed = parseDeny("  - {label: Everything, channel: '.*'}");

    assertTrue(parsed.config().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"fabric:.*", "c:.*", ".+:.+", "forge:.*", "fml:.*", "neoforge:.*",
      "xaero.*:main", "voxelmap:.*", "journeymap:.*", "minecraft:.*", "fabric:register", "c:version"})
  void aChannelRuleThatCoversAnHonestModChannelRefusesTheConfiguration(String pattern) {
    // Act
    var parsed = parseDeny("  - {label: Bad, channel: '" + pattern + "'}");

    // Assert
    assertEquals(Optional.empty(), parsed.config(), pattern);
    assertTrue(parsed.errors().get(0).contains("honest mods register"), parsed.errors().toString());
  }

  @Test
  void everyControlChannelIsProtected() {
    assertEquals(12, DenyRule.PROTECTED_CHANNELS.size());
    for (String channel : List.of("fabric:register", "c:register", "c:version", "fml:handshake",
        "forge:handshake", "neoforge:register", "xaerominimap:main", "xaeroworldmap:main",
        "voxelmap:settings", "journeymap:version", "minecraft:brand", "minecraft:register")) {
      assertTrue(DenyRule.PROTECTED_CHANNELS.contains(channel), channel);
    }
  }

  @Test
  void aPreciseChannelRuleIsAccepted() {
    var parsed = parseDeny("  - {label: Meteor, channel: 'meteor-client:.*'}");

    assertTrue(parsed.errors().isEmpty(), () -> parsed.errors().toString());
    assertEquals(1, parsed.config().orElseThrow().deny().size());
  }

  @Test
  void aRuleAimedAtACheatClientIsAccepted() {
    // Act
    var parsed = parseDeny(
        "  - {label: Wurst, brand: '.*wurst.*', message: '<red>No.</red>'}",
        "  - {label: Cheat channel, channel: 'examplecheat:.*'}");

    // Assert
    assertTrue(parsed.errors().isEmpty(), () -> parsed.errors().toString());
    List<DenyRule> rules = parsed.config().orElseThrow().deny();
    assertEquals(2, rules.size());
    assertEquals(DenyRule.Target.BRAND, rules.get(0).target());
    assertEquals("<red>No.</red>", rules.get(0).message());
    assertEquals(DenyRule.Target.CHANNEL, rules.get(1).target());
    assertEquals(DenyRule.DEFAULT_MESSAGE, rules.get(1).message());
  }

  @Test
  void aSingleBadRuleRefusesEveryRuleAroundIt() {
    // A deny list half applied would look protective while being partly off.
    var parsed = parseDeny(
        "  - {label: Wurst, brand: '.*wurst.*'}",
        "  - {label: Bad, brand: 'fabric'}");

    assertTrue(parsed.config().isEmpty());
  }

  @Test
  void matchingIsOnTheWholeValueAndIgnoresCase() {
    var rule = parseDeny("  - {label: W, brand: 'wurst'}").config().orElseThrow().deny().get(0);

    assertTrue(rule.matches("WURST", List.of()).isPresent());
    assertTrue(rule.matches("wurst-client", List.of()).isEmpty(), "not a substring match");
  }

  @Test
  void aRuleNeedsALabelAndExactlyOneTarget() {
    assertTrue(parseDeny("  - {brand: 'x.*'}").config().isEmpty());
    assertTrue(parseDeny("  - {label: A}").config().isEmpty());
    assertTrue(parseDeny("  - {label: A, brand: 'x', channel: 'y'}").config().isEmpty());
    assertTrue(parseDeny("  - {label: A, brand: '('}").errors().get(0).contains("regular"));
    assertTrue(parseDeny("  - just text").config().isEmpty());
  }

  @Test
  void theModeMustBeOneOfTheTwoKnownValues() {
    assertEquals(ClientPolicyConfig.Mode.ENFORCE,
        ClientPolicyConfig.parseYaml("mode: enforce").config().orElseThrow().mode());
    assertEquals(ClientPolicyConfig.Mode.DRY_RUN,
        ClientPolicyConfig.parseYaml("mode: DRY-RUN").config().orElseThrow().mode());
    assertTrue(ClientPolicyConfig.parseYaml("mode: block").config().isEmpty());
  }

  @Test
  void aSyntaxErrorOrADuplicateKeyRefusesTheConfigurationInsteadOfThrowing() {
    assertTrue(ClientPolicyConfig.parseYaml("deny: [").config().isEmpty());
    assertTrue(ClientPolicyConfig.parseYaml("mode: enforce\nmode: dry-run\n").config().isEmpty());
  }

  @Test
  void xaeroServersOverrideTheDefaultByExactNameOrPrefix() {
    // Act
    var config = ClientPolicyConfig.parseYaml(
        "consent:\n  xaero:\n    default: fairplay\n    servers:\n      creatif: no-minimap\n"
            + "      Build-*: [none]\n").config().orElseThrow();

    // Assert
    assertEquals(XaeroConsent.NO_MINIMAP, config.xaero().codeFor("Creatif"));
    assertEquals("", config.xaero().codeFor("build-2"));
    assertEquals(XaeroConsent.FAIR_PLAY, config.xaero().codeFor("lobby-1"));
    assertFalse(config.xaero().codeFor("creatif-2").equals(XaeroConsent.NO_MINIMAP),
        "an exact name is not a prefix");
  }

  @Test
  void anUnknownXaeroAliasIsAnError() {
    var parsed = ClientPolicyConfig.parseYaml("consent:\n  xaero:\n    default: fairplays\n");

    assertTrue(parsed.config().isEmpty());
    assertTrue(parsed.errors().get(0).contains("fairplays"));
  }

  @Test
  void voxelMapOverridesMergeKeyByKeyAndRejectUnknownKeys() {
    // Act
    var config = ClientPolicyConfig.parseYaml("consent:\n  voxelmap:\n    servers:\n"
        + "      creatif:\n        radarAllowed: true\n        teleportCommand: '/tp'\n")
        .config().orElseThrow();

    // Assert
    assertTrue(config.voxelMap().jsonFor("creatif").orElseThrow()
        .contains("\"radarAllowed\":true"));
    assertTrue(config.voxelMap().jsonFor("creatif").orElseThrow()
        .contains("\"teleportCommand\":\"/tp\""));
    assertTrue(config.voxelMap().jsonFor("lobby").orElseThrow()
        .contains("\"radarAllowed\":false"), "other backends keep the default");
    assertTrue(ClientPolicyConfig.parseYaml("consent:\n  voxelmap:\n    default:\n"
        + "      radarAlowed: true\n").config().isEmpty());
    assertTrue(ClientPolicyConfig.parseYaml("consent:\n  voxelmap:\n    default:\n"
        + "      radarAllowed: maybe\n").config().isEmpty());
  }

  @Test
  void aNullOrEmptyTeleportCommandIsOmittedNeverWrittenAsNull() {
    // Arrange & Act
    var nullDefault = ClientPolicyConfig.parseYaml("consent:\n  voxelmap:\n    default:\n"
        + "      teleportCommand:\n").config().orElseThrow();
    var override = ClientPolicyConfig.parseYaml("consent:\n  voxelmap:\n    default:\n"
        + "      teleportCommand: '/tp'\n    servers:\n      creatif:\n"
        + "        teleportCommand: ''\n").config().orElseThrow();

    // Assert
    assertFalse(nullDefault.voxelMap().jsonFor("lobby").orElseThrow().contains("teleportCommand"));
    assertFalse(nullDefault.voxelMap().jsonFor("lobby").orElseThrow().contains("null"));
    assertTrue(override.voxelMap().jsonFor("lobby").orElseThrow().contains("\"teleportCommand\":\"/tp\""));
    assertFalse(override.voxelMap().jsonFor("creatif").orElseThrow().contains("teleportCommand"),
        "an empty override removes the default command on that backend");
  }

  @Test
  void voxelMapCanBeSwitchedOff() {
    var config = ClientPolicyConfig.parseYaml("consent:\n  voxelmap:\n    enabled: false\n")
        .config().orElseThrow();

    assertEquals(Optional.empty(), config.voxelMap().jsonFor("creatif"));
  }
}
