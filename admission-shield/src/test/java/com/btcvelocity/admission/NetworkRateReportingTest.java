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

import java.io.IOException;
import java.io.StringReader;
import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Settings, journal, refusal text and status of the bound on attempts per network. */
class NetworkRateReportingTest {

  private final RecordingLogger log = new RecordingLogger();
  private final ManualClock clock = new ManualClock();

  private static InetAddress ip(final String literal) {
    return InetAddress.ofLiteral(literal);
  }

  private static AdmissionConfig parse(final String yaml, final List<String> warnings)
      throws IOException {
    return AdmissionConfig.parseYaml(new StringReader(yaml), warnings::add);
  }

  private static Verdict rateRefusal(final String address) {
    return new Verdict(Verdict.Action.REFUSE, Verdict.Basis.RATE_NETWORK, Verdict.Origin.DIRECT,
        ip(address), null, null);
  }

  // --- configuration -----------------------------------------------------------------------

  @Test
  void theDefaultsAreTheDocumentedOnesAndObserve() {
    final NetworkRateSettings rate = AdmissionConfig.defaults().networkRate();

    assertTrue(rate.enabled());
    assertEquals(AdmissionConfig.Mode.OBSERVE, rate.mode());
    assertEquals(10, rate.attempts());
    assertEquals(Duration.ofSeconds(10), rate.window());
    assertEquals(20, rate.burst());
    assertEquals(24, rate.ipv4Prefix());
    assertEquals(48, rate.ipv6Prefix());
    assertEquals(20_000, rate.maxNetworks());
    assertTrue(rate.message().contains("{code}"));
  }

  @Test
  void theBundledTemplateSaysTheDefaultsWithoutAWarning() throws IOException {
    final List<String> warnings = new ArrayList<>();
    final String template;
    try (var stream = AdmissionConfig.class.getResourceAsStream("/config.yml")) {
      template = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }

    final AdmissionConfig parsed = parse(template, warnings);

    assertEquals(List.of(), warnings);
    assertEquals(AdmissionConfig.defaults().networkRate(), parsed.networkRate());
    assertEquals(AdmissionConfig.Mode.OBSERVE, parsed.rateMode(),
        "a fresh install measures before it refuses");
  }

  @Test
  void everyValueIsReadFromTheFile() throws IOException {
    final AdmissionConfig parsed = parse("""
        network-rate:
          enabled: false
          mode: enforce
          attempts: 5
          window-seconds: 30
          burst: 7
          ipv4-prefix: 20
          ipv6-prefix: 56
          max-networks: 5000
        messages:
          network-rate: "<red>Doucement ({code})</red>"
        """, new ArrayList<>());

    final NetworkRateSettings rate = parsed.networkRate();
    assertFalse(rate.enabled());
    assertEquals(AdmissionConfig.Mode.ENFORCE, parsed.rateMode());
    assertEquals(5, rate.attempts());
    assertEquals(Duration.ofSeconds(30), rate.window());
    assertEquals(7, rate.burst());
    assertEquals(20, rate.ipv4Prefix());
    assertEquals(56, rate.ipv6Prefix());
    assertEquals(5000, rate.maxNetworks());
    assertEquals("<red>Doucement (ADM-RATE-NET)</red>",
        parsed.refusalText(rateRefusal("203.0.113.1")));
  }

  @Test
  void aValueThatMakesNoSenseIsReportedAndReplacedByItsDefault() throws IOException {
    final List<String> warnings = new ArrayList<>();

    final NetworkRateSettings rate = parse("""
        network-rate:
          mode: strict
          attempts: 0
          window-seconds: -3
          ipv4-prefix: 8
          ipv6-prefix: 128
          max-networks: 3
        """, warnings).networkRate();

    assertEquals(AdmissionConfig.Mode.OBSERVE, rate.mode(), "a typo never starts refusing");
    assertEquals(10, rate.attempts());
    assertEquals(Duration.ofSeconds(10), rate.window());
    assertEquals(24, rate.ipv4Prefix());
    assertEquals(48, rate.ipv6Prefix());
    assertEquals(20_000, rate.maxNetworks());
    assertEquals(6, warnings.size(), warnings.toString());
  }

  @Test
  void aBurstBelowTheSustainedRateIsRaisedToIt() throws IOException {
    final List<String> warnings = new ArrayList<>();

    final NetworkRateSettings rate =
        parse("network-rate:\n  attempts: 30\n  burst: 5\n", warnings).networkRate();

    assertEquals(30, rate.burst());
    assertEquals(1, warnings.size());
  }

  @Test
  void aBurstLeftOutIsTwiceTheSustainedRate() throws IOException {
    assertEquals(60, parse("network-rate:\n  attempts: 30\n", new ArrayList<>())
        .networkRate().burst());
  }

  @Test
  void aRefusedPlayerReadsTheRateMessageWithItsCode() {
    final AdmissionConfig defaults = AdmissionConfig.defaults();

    final String text = defaults.refusalText(rateRefusal("203.0.113.1"));

    assertTrue(text.contains("ADM-RATE-NET"), text);
    assertFalse(text.contains("{code}"), text);
    assertEquals(IpClass.DATACENTER.causeCode(), "ADM-VPN-DC", "the list codes did not move");
  }

  // --- journal -----------------------------------------------------------------------------

  @Test
  void aRateRefusalIsJournaledWithItsCodeItsBasisAndTheRateMode() {
    final AdmissionJournal journal = new AdmissionJournal(this.log.logger(), this.clock);
    final Verdict verdict = rateRefusal("203.0.113.9");

    journal.record(Attempt.claimed("Bot", ip("203.0.113.9")), verdict, Phase.PRE_LOGIN,
        AdmissionConfig.defaults().modeFor(verdict));

    final String line = this.log.lines().getFirst();
    assertTrue(line.contains("decision=refused"), line);
    assertTrue(line.contains("code=ADM-RATE-NET"), line);
    assertTrue(line.contains("basis=rate-network"), line);
    assertTrue(line.contains("ip=203.0.113.9"), line);
    assertTrue(line.contains("mode=observe"), "the bound's own mode, not the module's: " + line);
    assertTrue(journal.lastForAddress(ip("203.0.113.9")).isPresent(),
        "/admission why <ip> finds it");
  }

  @Test
  void anObservedRateAttemptCarriesNoCode() {
    final AdmissionJournal journal = new AdmissionJournal(this.log.logger(), this.clock);
    final Verdict observed = new Verdict(Verdict.Action.OBSERVE, Verdict.Basis.RATE_NETWORK,
        Verdict.Origin.DIRECT, ip("203.0.113.9"), null, null);

    journal.record(Attempt.claimed("Bot", ip("203.0.113.9")), observed, Phase.PRE_LOGIN,
        AdmissionConfig.Mode.OBSERVE);

    final String line = this.log.lines().getFirst();
    assertTrue(line.contains("decision=observed"), line);
    assertTrue(line.contains("code=none"), line);
  }

  @Test
  void aWaveOfRateRefusalsFromOneNetworkIsOneLineAndOneSummary() {
    final AdmissionJournal journal = new AdmissionJournal(this.log.logger(), this.clock);

    for (int host = 1; host <= 30; host++) {
      final String address = "203.0.113." + host;
      journal.record(Attempt.claimed("Bot", ip(address)), rateRefusal(address), Phase.PRE_LOGIN,
          AdmissionConfig.Mode.ENFORCE);
    }
    final int duringTheWindow = this.log.lines().size();
    this.clock.advance(Duration.ofSeconds(61));
    journal.flushExpired();

    assertEquals(1, duringTheWindow);
    final String summary = this.log.lines().getLast();
    assertTrue(summary.contains("event=aggregate"), summary);
    assertTrue(summary.contains("basis=rate-network"), summary);
    assertTrue(summary.contains("suppressed=29"), summary);
  }

  @Test
  void aRateWaveAndAListWaveFromTheSameNetworkAreCountedApart() {
    final AdmissionJournal journal = new AdmissionJournal(this.log.logger(), this.clock);
    final Verdict listed = new Verdict(Verdict.Action.REFUSE, Verdict.Basis.LISTED,
        Verdict.Origin.DIRECT, ip("203.0.113.9"), IpClass.DATACENTER, "dc-list");

    journal.record(Attempt.claimed("A", ip("203.0.113.9")), listed, Phase.PRE_LOGIN,
        AdmissionConfig.Mode.ENFORCE);
    journal.record(Attempt.claimed("B", ip("203.0.113.9")), rateRefusal("203.0.113.9"),
        Phase.PRE_LOGIN, AdmissionConfig.Mode.ENFORCE);

    assertEquals(2, this.log.lines().size(), "each cause gets its own full line");
  }

  // --- status ------------------------------------------------------------------------------

  @Test
  void theStatusLineShowsTheBoundAndWhatItHasSeen() {
    final AdmissionConfig config = AdmissionConfig.defaults();
    final AdmissionEngine engine = new AdmissionEngine(config, address -> Optional.empty(),
        address -> Optional.empty(), this.clock);
    for (int i = 0; i < 25; i++) {
      engine.decideRate(Attempt.claimed("Bot" + i, ip("203.0.113." + (i + 1))));
    }

    final String status = AdmissionCommand.rateStatus(config, engine.rateLimiter());

    assertTrue(status.contains("(observe)"), status);
    assertTrue(status.contains("10 tentatives / 10 s, rafale 20"), status);
    assertTrue(status.contains("/24 (IPv4)") && status.contains("/48 (IPv6)"), status);
    assertTrue(status.contains("1 réseau(x) suivi(s)"), status);
    assertTrue(status.contains("5 tentative(s) au-delà de la limite"), status);
    assertTrue(status.contains("ADM-RATE-NET"), status);
  }

  @Test
  void theStatusLineSaysWhenTheBoundIsOff() throws IOException {
    final AdmissionConfig off = parse("network-rate:\n  enabled: false\n", new ArrayList<>());

    final String status = AdmissionCommand.rateStatus(off,
        new NetworkRateLimiter(off.networkRate(), this.clock));

    assertTrue(status.contains("désactivé"), status);
  }
}
