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

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RefusalPolicyTest {

  private static ClientPolicyConfig config(String mode) {
    return ClientPolicyConfig.parseYaml("mode: " + mode + "\ndeny:\n"
        + "  - {label: Wurst, brand: '.*wurst.*'}\n"
        + "  - {label: Cheat channel, channel: 'examplecheat:.*'}\n").config().orElseThrow();
  }

  private static RefusalPolicy.Verdict run(ClientPolicyConfig config, ClientOrigin origin,
                                           boolean bypass, String brand, String... channels) {
    return RefusalPolicy.evaluate(config, origin, () -> bypass, brand, List.of(channels));
  }

  @Test
  void dryRunNamesTheRuleButRefusesNobody() {
    var verdict = run(config("dry-run"), ClientOrigin.JAVA, false, "Wurst-7", "fabric:x");

    assertEquals(RefusalPolicy.Action.WOULD_REFUSE, verdict.action());
    assertEquals("Wurst", verdict.rule().orElseThrow().label());
    assertEquals("Wurst-7", verdict.value().orElseThrow());
  }

  @Test
  void enforceRefusesTheSameClient() {
    var verdict = run(config("enforce"), ClientOrigin.JAVA, false, "Wurst-7");

    assertEquals(RefusalPolicy.Action.REFUSE, verdict.action());
  }

  @Test
  void aListedChannelIsEnoughWhateverTheBrand() {
    var verdict = run(config("enforce"), ClientOrigin.JAVA, false, "fabric",
        "fabric:register", "examplecheat:main");

    assertEquals(RefusalPolicy.Action.REFUSE, verdict.action());
    assertEquals("Cheat channel", verdict.rule().orElseThrow().label());
    assertEquals("examplecheat:main", verdict.value().orElseThrow());
  }

  @Test
  void aBedrockSessionIsExemptEvenInEnforce() {
    var verdict = run(config("enforce"), ClientOrigin.BEDROCK, false, "wurst");

    assertEquals(RefusalPolicy.Action.EXEMPT_BEDROCK, verdict.action());
  }

  @Test
  void staffWithTheBypassPermissionIsNeverRefused() {
    var verdict = run(config("enforce"), ClientOrigin.JAVA, true, "wurst");

    assertEquals(RefusalPolicy.Action.EXEMPT_BYPASS, verdict.action());
  }

  @Test
  void anUnmeasuredOriginIsNeverRefused() {
    // Only an established Java origin is refused: unknown might be Bedrock, and a wrongly refused
    // Bedrock player is worse than a cheat client let through this one door.
    assertEquals(RefusalPolicy.Action.EXEMPT_UNKNOWN,
        run(config("enforce"), ClientOrigin.UNKNOWN, false, "wurst").action());
    assertEquals(RefusalPolicy.Action.EXEMPT_UNKNOWN,
        run(config("dry-run"), ClientOrigin.UNKNOWN, false, "wurst").action());
  }

  @Test
  void theUnknownOriginJournalLineSaysTheRefusalWasSkipped() {
    var verdict = run(config("enforce"), ClientOrigin.UNKNOWN, false, "wurst");

    String line = ObservationLog.verdict(new ObservationLog.Who("Steve", java.util.UUID.randomUUID()),
        ClientOrigin.UNKNOWN, verdict, ClientPolicyConfig.Mode.ENFORCE);

    org.junit.jupiter.api.Assertions.assertTrue(line.contains("origin UNKNOWN, refusal skipped"), line);
  }

  @Test
  void aCleanClientEarnsNothingAndTheBypassLookupIsSkipped() {
    // Arrange
    AtomicInteger lookups = new AtomicInteger();

    // Act
    var verdict = RefusalPolicy.evaluate(config("enforce"), ClientOrigin.JAVA,
        () -> lookups.incrementAndGet() > 0, "fabric", List.of("fabric:register", "xaerominimap:main"));

    // Assert
    assertEquals(RefusalPolicy.Action.NONE, verdict.action());
    assertEquals(0, lookups.get(), "a permission lookup is only worth making for a match");
  }

  @Test
  void anEmptyDeckRefusesNoOne() {
    var verdict = run(ClientPolicyConfig.builtIn(), ClientOrigin.JAVA, false, "wurst");

    assertEquals(RefusalPolicy.Action.NONE, verdict.action());
    assertFalse(verdict.rule().isPresent());
  }

  @Test
  void anUndeclaredBrandMatchesNoBrandRule() {
    var verdict = run(config("enforce"), ClientOrigin.JAVA, false, null);

    assertEquals(RefusalPolicy.Action.NONE, verdict.action());
  }
}
