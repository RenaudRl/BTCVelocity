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

package com.btcvelocity.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.btcvelocity.admission.Verdict.Action;
import com.btcvelocity.admission.Verdict.Basis;
import com.btcvelocity.admission.Verdict.Origin;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountLinkConfigAndReportTest {

  private static final UUID LINKED = UUID.fromString("bbbbbbbb-1234-0000-0000-000000000001");
  private static final InetAddress ENTRANT = InetAddress.ofLiteral("198.51.100.7");

  private static AdmissionConfig parse(final String yaml, final List<String> errors) {
    try {
      return AdmissionConfig.parseYaml(new StringReader(yaml), warning -> { }, errors::add);
    } catch (IOException malformed) {
      throw new IllegalStateException(malformed);
    }
  }

  @Test
  void defaultsOffAndObserve() throws IOException {
    final LinkSettings defaults = AdmissionConfig.defaults().links();
    final LinkSettings shipped;
    try (Reader template = new InputStreamReader(
        AdmissionConfig.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8)) {
      shipped = AdmissionConfig.parseYaml(template, warning -> { }).links();
    }

    for (LinkSettings links : List.of(defaults, shipped)) {
      assertFalse(links.enabled());
      assertEquals(AdmissionConfig.Mode.OBSERVE, links.mode());
      assertEquals("BTC_ADMISSION_LINK_KEY", links.keyEnv());
      assertEquals(Duration.ofMillis(1500), links.lookupTimeout());
      assertEquals(Duration.ofDays(7), links.strongWindow());
      assertEquals(4, links.sharedAddressAccounts());
      assertEquals(Duration.ofDays(180), links.retention());
      assertEquals("borntocraft", links.networkId());
      assertEquals(Map.of("socketTimeout", "5", "options", "-c statement_timeout=2000"),
          links.driverProperties(), "no statement nor silent socket holds a thread for long");
    }
    assertEquals(defaults, shipped, "the template says what the code does");
    assertFalse(parse("""
        links:
          database:
            jdbc-url: "jdbc:postgresql://db.invalid:5432/sentinel"
        """, new ArrayList<>()).links().enabled(), "a database alone does not switch them on");
  }

  @Test
  void offModeKeepsTheLinksOff() {
    final List<String> errors = new ArrayList<>();

    assertFalse(parse(AccountLinkRuleTest.ON.formatted("off"), errors).links().enabled());
    assertTrue(parse(AccountLinkRuleTest.ON.formatted("observe"), errors).links().enabled());
    assertTrue(errors.isEmpty(), errors.toString());
  }

  @Test
  void badPrefixRejected() {
    final List<String> errors = new ArrayList<>();

    final LinkSettings links = parse(AccountLinkRuleTest.ON.formatted("enforce")
        + "    table-prefix: \"btc_; DROP TABLE x; --\"\n", errors).links();

    assertFalse(links.enabled());
    assertEquals("btc_", links.tablePrefix());
    assertEquals(1, errors.size(), errors.toString());
  }

  @Test
  void passwordInUrlRejected() {
    for (String url : List.of("jdbc:postgresql://db.invalid/s?user=a&password=hunter2",
        "jdbc:postgresql://a:hunter2@db.invalid/s")) {
      final List<String> errors = new ArrayList<>();

      final LinkSettings links = parse("""
          links:
            enabled: true
            database:
              jdbc-url: "%s"
          """.formatted(url), errors).links();

      assertFalse(links.enabled(), url);
      assertEquals(1, errors.size(), errors.toString());
      assertFalse(errors.getFirst().contains("hunter2"), "the secret is not echoed");
    }
  }

  @Test
  void namesRuleAndSanctionButNoOtherAddress() {
    final AdmissionConfig config = parse(AccountLinkRuleTest.ON.formatted("enforce"),
        new ArrayList<>());
    final Verdict refusal = new Verdict(Action.REFUSE, Basis.LINKED_BAN, Origin.DIRECT, ENTRANT,
        null, null, null, new LinkEvidence(Link.Strength.STRONG, LINKED, 42L, 2));
    final AdmissionJournal journal =
        new AdmissionJournal(new RecordingLogger().logger(), Clock.systemUTC());
    final Attempt attempt = Attempt.claimed("Newcomer", ENTRANT)
        .authenticated("Newcomer", UUID.randomUUID(), false, null);

    journal.record(attempt, refusal, Phase.LOGIN, config.modeFor(refusal));
    final List<String> why = WhyReport.explain("Newcomer", journal, address -> Optional.empty());
    final String line = why.get(1);

    assertTrue(line.contains("decision=refused code=ADM-ALT-BAN basis=linked-ban"), line);
    assertTrue(line.contains("mode=enforce"), line);
    assertTrue(line.endsWith("link=strong linked=bbbbbbbb sanction=42 accounts=2"), line);
    assertTrue(line.contains("ip=198.51.100.0/24"), line);
    assertFalse(line.contains("198.51.100.7"), "not even the entrant's own address");
    assertFalse(line.contains(LINKED.toString()), "the linked account is shortened");
    final String text = config.refusalText(refusal);
    assertTrue(text.contains("ADM-ALT-BAN") && text.contains("Connection refused"), text);
  }

  @Test
  void aLinkSignalIsJournaledAsObserve() {
    final AdmissionConfig config = parse(AccountLinkRuleTest.ON.formatted("enforce"),
        new ArrayList<>());

    assertEquals(AdmissionConfig.Mode.OBSERVE, config.modeFor(new Verdict(Action.ADMIT,
        Basis.LINKED_ACCOUNT, Origin.DIRECT, ENTRANT, null, null, null,
        new LinkEvidence(Link.Strength.WEAK, LINKED, 42L, 3))));
  }
}
