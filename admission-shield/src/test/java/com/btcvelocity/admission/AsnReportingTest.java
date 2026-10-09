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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What the journal, /admission why, /admission status and the staff alerts say of a system. */
class AsnReportingTest {

  private static final InetAddress HOST = InetAddress.ofLiteral("192.0.2.10");
  private static final AsnDatabase.Info ALPHA =
      new AsnDatabase.Info(64496, "Alpha Hosting BV", "NL");

  private final RecordingLogger log = new RecordingLogger();
  private final ManualClock clock = new ManualClock();
  private final AdmissionJournal journal = new AdmissionJournal(this.log.logger(), this.clock);

  private static AdmissionConfig config(final String yaml) {
    try {
      return AdmissionConfig.parseYaml(new StringReader(yaml), warning -> { });
    } catch (IOException malformed) {
      throw new IllegalStateException(malformed);
    }
  }

  private static Verdict denied(final AsnDatabase.Info info) {
    return new Verdict(Verdict.Action.REFUSE, Verdict.Basis.LISTED, Verdict.Origin.DIRECT, HOST,
        IpClass.ASN, "iptoasn:AS64496", info);
  }

  // --- the journal ----------------------------------------------------------------------------

  @Test
  void aJournaledLineNamesTheSystemAtTheEnd() {
    final Optional<AdmissionJournal.Entry> entry = this.journal.record(
        Attempt.claimed("Bot", HOST), denied(ALPHA), Phase.PRE_LOGIN,
        AdmissionConfig.Mode.ENFORCE);

    final String line = entry.orElseThrow().line();
    assertTrue(line.contains("class=asn"), line);
    assertTrue(line.contains("source=iptoasn:AS64496") || line.contains("source=\"iptoasn:AS64496\""),
        line);
    assertTrue(line.contains("code=ADM-VPN-ASN"), line);
    assertTrue(line.endsWith("asn=64496 as_name=\"Alpha Hosting BV\""), line);
    assertEquals(line, this.log.lines().getFirst().replaceFirst("^INFO ", ""));
  }

  @Test
  void aLineWithoutAKnownSystemIsTheLineItAlwaysWas() {
    final Optional<AdmissionJournal.Entry> entry = this.journal.record(
        Attempt.claimed("Bot", HOST), denied(null), Phase.PRE_LOGIN, AdmissionConfig.Mode.ENFORCE);

    final String line = entry.orElseThrow().line();
    assertFalse(line.contains("asn="), line);
    assertFalse(line.contains("as_name="), line);
    assertTrue(line.endsWith("mode=enforce"), line);
  }

  @Test
  void aNameFromTheRegistryCannotForgeAFieldOrBreakTheLine() {
    final AsnDatabase.Info hostile = new AsnDatabase.Info(64496,
        "Evil\" code=none decision=admitted" + (char) 0x0a + "ts=0", "NL");

    final String line = this.journal.record(Attempt.claimed("Bot", HOST), denied(hostile),
        Phase.PRE_LOGIN, AdmissionConfig.Mode.ENFORCE).orElseThrow().line();

    assertFalse(line.contains("\n"), line);
    assertTrue(line.indexOf("decision=refused") < line.indexOf("as_name="),
        "the real fields come first: " + line);
    assertTrue(line.endsWith("as_name=\"Evil\\\" code=none decision=admitted" + "\\" + "u000Ats=0\""),
        "the whole name is one quoted, escaped value: " + line);
  }

  @Test
  void aKeywordSignalIsWrittenAsAnAdmissionWithItsBasisAndSystem() {
    final Verdict signal = new Verdict(Verdict.Action.ADMIT, Verdict.Basis.ASN_KEYWORD,
        Verdict.Origin.DIRECT, HOST, null, null, ALPHA);

    final String line = this.journal.record(Attempt.claimed("Steve", HOST), signal,
        Phase.PRE_LOGIN, AdmissionConfig.Mode.ENFORCE).orElseThrow().line();

    assertTrue(line.contains("decision=admitted"), line);
    assertTrue(line.contains("basis=asn-keyword"), line);
    assertTrue(line.contains("class=none"), line);
    assertTrue(line.contains("asn=64496"), line);
  }

  @Test
  void aWaveFromAKeywordSystemIsAggregatedAndTheSummaryNamesTheSystem() {
    final Verdict signal = new Verdict(Verdict.Action.ADMIT, Verdict.Basis.ASN_KEYWORD,
        Verdict.Origin.DIRECT, HOST, null, null, ALPHA);
    for (int i = 0; i < 5; i++) {
      this.journal.record(Attempt.claimed("Bot" + i, HOST), signal, Phase.PRE_LOGIN,
          AdmissionConfig.Mode.ENFORCE);
    }
    this.clock.advance(Duration.ofSeconds(61));
    this.journal.flushExpired();

    assertEquals(2, this.log.lines().size(), "one full line, one summary");
    final String summary = this.log.lines().getLast();
    assertTrue(summary.contains("event=aggregate"), summary);
    assertTrue(summary.contains("suppressed=4"), summary);
    assertTrue(summary.contains("basis=asn-keyword"), summary);
    assertTrue(summary.contains("asn=64496"), summary);
  }

  @Test
  void aWouldRefuseLineNamesTheSystemToo() {
    this.journal.recordWouldRefuse(Attempt.claimed("Steve", HOST), denied(ALPHA),
        AdmissionConfig.Mode.OBSERVE);

    final String line = this.log.lines().getFirst();
    assertTrue(line.contains("decision=would_refuse"), line);
    assertTrue(line.contains("asn=64496"), line);
  }

  // --- /admission why -------------------------------------------------------------------------

  @Test
  void whyNamesTheSystemOfAnAddressAndWhatTheSettingsDoAboutIt() {
    final AsnSettings settings = config("asn:\n  deny-asns: [64496]\n  mode: enforce\n").asn();
    final AsnLookup lookup = address -> Optional.of(ALPHA);
    final IpClassifier classifier = new AsnClassifier(lookup, settings);

    final List<String> lines = WhyReport.explain("192.0.2.10", this.journal, classifier, lookup,
        settings, AdmissionConfig.Mode.ENFORCE);

    final String joined = String.join("\n", lines);
    assertTrue(joined.contains("Classement actuel de l'adresse : asn (liste iptoasn:AS64496)"),
        joined);
    assertTrue(joined.contains("Système autonome : AS64496 \"Alpha Hosting BV\" (NL)"), joined);
    assertTrue(joined.contains("asn.deny-asns (mode enforce)"), joined);
  }

  @Test
  void whyShowsTheModeThatReallyApplyWhenTheModuleIsInObserve() {
    final AdmissionConfig config = config("mode: observe\nasn:\n  deny-asns: [64496]\n"
        + "  mode: enforce\n");
    assertEquals(AdmissionConfig.Mode.ENFORCE, config.asn().mode(), "the asn section alone says enforce");
    final AsnLookup lookup = address -> Optional.of(ALPHA);

    final List<String> lines = WhyReport.explain("192.0.2.10", this.journal,
        new AsnClassifier(lookup, config.asn()), lookup, config.asn(), config.asnMode());

    final String joined = String.join("\n", lines);
    assertTrue(joined.contains("asn.deny-asns (mode observe)"), joined);
  }

  @Test
  void whyMentionsAKeywordHitAsASignalAndNotARefusal() {
    final AsnSettings settings = config("asn:\n  observe-keywords: [hosting]\n").asn();

    final String line = WhyReport.describeAsn(ALPHA, settings, AdmissionConfig.Mode.OBSERVE);

    assertTrue(line.contains("mot-clé d'observation 'hosting'"), line);
    assertTrue(line.contains("jamais un refus"), line);
  }

  @Test
  void whySaysWhenNoRuleAppliesAndWhenTheSystemIsUnknown() {
    final AsnSettings settings = AsnSettings.defaults();

    assertTrue(WhyReport.describeAsn(ALPHA, settings, AdmissionConfig.Mode.OBSERVE)
        .contains("aucune règle asn"));
    assertTrue(WhyReport.describeAsn(null, settings, AdmissionConfig.Mode.OBSERVE)
        .contains("inconnu"));
  }

  @Test
  void whyAboutAPlayerShowsTheRememberedLineWhichCarriesTheSystem() {
    this.journal.record(Attempt.claimed("Bot", HOST).authenticated("Bot",
        java.util.UUID.randomUUID(), false, null), denied(ALPHA), Phase.LOGIN,
        AdmissionConfig.Mode.ENFORCE);

    final List<String> lines = WhyReport.explain("Bot", this.journal, address -> Optional.empty(),
        AsnLookup.NONE, AsnSettings.defaults(), AdmissionConfig.Mode.OBSERVE);

    assertTrue(lines.get(1).contains("asn=64496"), lines.toString());
  }

  // --- the staff -------------------------------------------------------------------------------

  @Test
  void aRefusalAlertNamesTheSystemAndAKeywordSignalHasItsOwnWords() {
    final List<String> told = new ArrayList<>();
    final StaffAlerts refusals = new StaffAlerts(this.clock, told::add);
    final StaffAlerts signals = StaffAlerts.forAsnKeywords(this.clock, told::add);
    final AdmissionJournal.Entry refusal = new AdmissionJournal.Entry(this.clock.instant(),
        "Bot", null, denied(ALPHA), "refused", "ADM-VPN-ASN", Phase.PRE_LOGIN,
        AdmissionConfig.Mode.ENFORCE);
    final AdmissionJournal.Entry signal = new AdmissionJournal.Entry(this.clock.instant(),
        "Steve", null, new Verdict(Verdict.Action.ADMIT, Verdict.Basis.ASN_KEYWORD,
            Verdict.Origin.DIRECT, HOST, null, null, ALPHA), "admitted", null, Phase.PRE_LOGIN,
        AdmissionConfig.Mode.ENFORCE);

    refusals.onRefusal(refusal);
    signals.onRefusal(signal);

    assertEquals(2, told.size(), "a signal is never held back by a refusal's window");
    assertTrue(told.get(0).startsWith("[Admission] Refus : "), told.get(0));
    assertTrue(told.get(0).contains("AS64496"), told.get(0));
    assertTrue(told.get(1).startsWith("[Admission] Signal ASN (observation, non refusé) : "),
        told.get(1));
    assertTrue(told.get(1).contains("\"Alpha Hosting BV\""), told.get(1));
  }

  @Test
  void aSectionSignInARegistryNameIsWrittenOutNotInterpretedByTheChat() {
    final List<String> told = new ArrayList<>();
    final StaffAlerts alerts = new StaffAlerts(this.clock, told::add);
    final AsnDatabase.Info sneaky = new AsnDatabase.Info(64496, "Evil §4Red", "NL");

    alerts.onRefusal(new AdmissionJournal.Entry(this.clock.instant(), "Bot", null,
        denied(sneaky), "refused", "ADM-VPN-ASN", Phase.PRE_LOGIN, AdmissionConfig.Mode.ENFORCE));

    assertFalse(told.getFirst().contains("§"), told.getFirst());
    assertTrue(told.getFirst().contains("\\u00A7"), told.getFirst());
  }

  // --- /admission status ------------------------------------------------------------------------

  @Test
  void statusSaysWhenNoDatabaseIsLoadedAndWhenTheFeatureIsOff(@TempDir final Path directory) {
    final AdmissionConfig on = config("asn:\n  deny-asns: [64496]\n");
    final AdmissionConfig off = config("asn:\n  enabled: false\n");
    final AsnStore empty = new AsnStore(directory, this.log.logger(), this.clock, on.asn(), false);

    final List<String> lines = AdmissionCommand.asnStatus(on, empty);

    assertTrue(lines.getFirst().contains("AUCUNE chargée"), lines.toString());
    assertTrue(lines.getLast().contains("1 AS refusé(s) (code ADM-VPN-ASN)"), lines.toString());
    assertTrue(AdmissionCommand.asnStatus(off, empty).getFirst().contains("désactivée"));
  }

  @Test
  void statusGivesTheSizeOfTheLoadedDatabaseAndTheSourceNote(@TempDir final Path directory)
      throws IOException {
    final AdmissionConfig config = config("asn:\n  mode: enforce\n");
    final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(out)) {
      gz.write("192.0.2.0\t192.0.2.255\t64496\tNL\tAlpha\n".getBytes(StandardCharsets.UTF_8));
    }
    Files.write(directory.resolve(AsnSettings.FILE_NAME), out.toByteArray());
    final AsnStore store = new AsnStore(directory, this.log.logger(), this.clock, config.asn(),
        false);
    store.loadAtStart();

    final List<String> lines = AdmissionCommand.asnStatus(config, store);

    assertTrue(lines.getFirst().contains("iptoasn.com"), lines.toString());
    assertTrue(lines.getFirst().contains("1 lignes, 1 plages IPv4, 0 plages IPv6, 1 systèmes"),
        lines.toString());
    assertTrue(lines.getLast().contains("ASN (enforce)"), lines.toString());
  }
}
