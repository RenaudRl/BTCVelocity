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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The wiring between a verdict and the disconnect, exercised without a proxy. */
class SessionEnforcerTest {

  private static final ObservationLog.Who WHO = new ObservationLog.Who("Steve", UUID.randomUUID());

  private final List<String> infos = new ArrayList<>();
  private final List<String> warns = new ArrayList<>();
  private final List<String> disconnects = new ArrayList<>();

  private static ClientPolicyConfig config(String mode) {
    return ClientPolicyConfig.parseYaml("mode: " + mode + "\ndeny:\n"
        + "  - {label: Wurst, brand: '.*wurst.*', message: '<red>No wurst.</red>'}\n")
        .config().orElseThrow();
  }

  private ClientSession session(String brand) {
    var session = new ClientSession(new Object());
    session.recordBrand(brand);
    return session;
  }

  private RefusalPolicy.Verdict run(ClientPolicyConfig config, ClientOrigin origin,
                                    ClientSession session, boolean bypass) {
    return SessionEnforcer.apply(config, WHO, origin, session, () -> bypass, this.infos::add,
        this.warns::add, this.disconnects::add);
  }

  @Test
  void enforceDisconnectsWithTheRulesMessageAndWarns() {
    run(config("enforce"), ClientOrigin.JAVA, session("wurst"), false);

    assertEquals(List.of("<red>No wurst.</red>"), this.disconnects);
    assertEquals(1, this.warns.size());
    assertTrue(this.warns.get(0).contains("event=refused"));
  }

  @Test
  void dryRunOnlyJournalsWouldRefuse() {
    run(config("dry-run"), ClientOrigin.JAVA, session("wurst"), false);

    assertTrue(this.disconnects.isEmpty(), "dry-run must touch nobody");
    assertTrue(this.warns.isEmpty());
    assertTrue(this.infos.get(0).contains("event=would-refuse"));
  }

  @Test
  void staffBypassBedrockAndUnknownOriginAreNeverDisconnected() {
    run(config("enforce"), ClientOrigin.JAVA, session("wurst"), true);
    run(config("enforce"), ClientOrigin.BEDROCK, session("wurst"), false);
    run(config("enforce"), ClientOrigin.UNKNOWN, session("wurst"), false);

    assertTrue(this.disconnects.isEmpty());
    assertTrue(this.infos.get(0).contains("exempt-bypass"));
    assertTrue(this.infos.get(1).contains("exempt-bedrock"));
    assertTrue(this.infos.get(2).contains("origin UNKNOWN, refusal skipped"));
  }

  @Test
  void aRuleIsReportedAndActedOnceEvenIfEvaluatedAgain() {
    var session = session("wurst");
    var config = config("enforce");

    run(config, ClientOrigin.JAVA, session, false);
    run(config, ClientOrigin.JAVA, session, false);

    assertEquals(1, this.disconnects.size());
    assertEquals(1, this.warns.size());
  }

  @Test
  void aCleanSessionEarnsNoLine() {
    run(config("enforce"), ClientOrigin.JAVA, session("fabric"), false);

    assertTrue(this.disconnects.isEmpty() && this.warns.isEmpty() && this.infos.isEmpty());
  }
}
