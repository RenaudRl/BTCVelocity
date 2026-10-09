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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AdmissionConfigAndJournalTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-29T20:15:30Z"), ZoneOffset.UTC);
  private static final UUID PLAYER_UUID = UUID.fromString("33333333-3333-3333-3333-333333333333");

  private static InetAddress ip(final String literal) {
    return InetAddress.ofLiteral(literal);
  }

  private static Verdict listed(final Verdict.Action action, final String address) {
    return new Verdict(action, Verdict.Basis.LISTED, Verdict.Origin.DIRECT, ip(address),
        IpClass.DATACENTER, "dc-list");
  }

  // --- configuration ---------------------------------------------------------------------

  @Test
  void theBundledTemplateParsesWithoutWarningAndEqualsTheStrictDefaults() throws IOException {
    // Arrange
    final List<String> warnings = new ArrayList<>();
    final String template;
    try (var stream = AdmissionConfig.class.getResourceAsStream("/config.yml")) {
      template = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }

    // Act
    final AdmissionConfig parsed = AdmissionConfig.parseYaml(new StringReader(template),
        warnings::add);
    final AdmissionConfig defaults = AdmissionConfig.defaults();

    // Assert
    assertEquals(List.of(), warnings);
    assertEquals(AdmissionConfig.Mode.ENFORCE, parsed.mode());
    assertEquals(defaults.sources(), parsed.sources());
    assertEquals(defaults.messages(), parsed.messages());
    assertEquals(Duration.ofHours(24), parsed.refreshInterval());
    assertEquals(AdmissionConfig.DEFAULT_MAX_ENTRIES, parsed.maxEntries());
    assertEquals(defaults.maxEntries(), parsed.maxEntries());
    assertTrue(parsed.geyserAddresses().isEmpty(), "no Geyser address until 13.1 has measured");
    assertTrue(defaults.geyserAddresses().isEmpty());
    assertTrue(parsed.exemptions().players().isEmpty());
  }

  @Test
  void theTemplateShowsOnlyExemptionsWithBothNameAndUuid() throws IOException {
    // The commented examples, uncommented, must be accepted as they stand: a template that
    // taught a UUID-only entry would teach a configuration error.
    final String template;
    try (var stream = AdmissionConfig.class.getResourceAsStream("/config.yml")) {
      template = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
    // A Windows checkout ships the template with CRLF; the replacements below assume LF.
    final String uncommented = template.replace("\r\n", "\n")
        .replace("  players: []\n", "  players:\n")
        .replace("  #  - name:", "    - name:")
        .replace("  #    uuid:", "      uuid:");
    final List<String> errors = new ArrayList<>();

    final AdmissionConfig parsed =
        AdmissionConfig.parseYaml(new StringReader(uncommented), errors::add, errors::add);

    assertEquals(List.of(), errors);
    assertEquals(2, parsed.exemptions().players().size());
  }

  @Test
  void theListCeilingIsConfigurable() throws IOException {
    final AdmissionConfig parsed = AdmissionConfig.parseYaml(
        new StringReader("lists:\n  max-entries: 1234\n"), warning -> { });

    assertEquals(1234, parsed.maxEntries());
  }

  @Test
  void aDownloadIsBoundedTo8MegabytesByDefaultInTheCodeAndInTheTemplate() throws IOException {
    final String template;
    try (var stream = AdmissionConfig.class.getResourceAsStream("/config.yml")) {
      template = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }

    assertEquals(8L * 1024 * 1024, AdmissionConfig.defaults().maxDownloadBytes());
    assertEquals(8L * 1024 * 1024, AdmissionConfig.parseYaml(new StringReader(template),
        warning -> { }).maxDownloadBytes());
  }

  @Test
  void theEmbeddedBaselineIsOnByDefaultAndCanBeSwitchedOff() throws IOException {
    assertTrue(AdmissionConfig.defaults().embeddedBaseline());
    assertFalse(AdmissionConfig.parseYaml(
        new StringReader("lists:\n  embedded-baseline: false\n"), warning -> { })
        .embeddedBaseline());
  }

  @Test
  void theDefaultListsAreHttpsAndCoverBothFamiliesForDatacentersAndTor() {
    final List<ListSource> sources = AdmissionConfig.defaultSources();

    assertEquals(3, sources.size());
    assertTrue(sources.stream().allMatch(s -> "https".equals(s.url().getScheme())));
    assertEquals(2, sources.stream().filter(s -> s.ipClass() == IpClass.DATACENTER).count());
    assertEquals(1, sources.stream().filter(s -> s.ipClass() == IpClass.TOR).count());
  }

  @Test
  void defaultMessagesCarryTheCauseCodePlaceholder() {
    final AdmissionConfig defaults = AdmissionConfig.defaults();

    assertTrue(defaults.messageFor(IpClass.DATACENTER).contains("{code}"));
    assertTrue(defaults.messageFor(IpClass.TOR).contains("{code}"));
    assertEquals("ADM-VPN-DC", IpClass.DATACENTER.causeCode());
    assertEquals("ADM-VPN-TOR", IpClass.TOR.causeCode());
  }

  @Test
  void aSourceWithAnUnsafeFileNameOrANonHttpsUrlIsDroppedWithAWarning() throws IOException {
    final List<String> warnings = new ArrayList<>();

    final AdmissionConfig parsed = AdmissionConfig.parseYaml(new StringReader("""
        lists:
          sources:
            fine: {class: tor, url: 'https://example.org/t.txt', file: t.txt}
            escape: {class: tor, url: 'https://example.org/t.txt', file: '../../evil.txt'}
            plain-http: {class: tor, url: 'http://example.org/t.txt', file: h.txt}
            weird-class: {class: residential, url: 'https://example.org/r.txt', file: r.txt}
        """), warnings::add);

    assertEquals(List.of("fine"), parsed.sources().stream().map(ListSource::id).toList());
    assertEquals(3, warnings.size());
  }

  @Test
  void aMissingConfigFileIsCreatedFromTheTemplateAndAnUnreadableOneFallsBackToDefaults(
      @TempDir final Path directory) throws IOException {
    final RecordingLogger log = new RecordingLogger();
    final Path file = directory.resolve("nested").resolve("config.yml");

    final AdmissionConfig created = AdmissionConfig.loadOrCreate(file, log.logger());

    assertTrue(Files.exists(file));
    assertEquals(AdmissionConfig.Mode.ENFORCE, created.mode());

    Files.writeString(file, "mode: [unclosed");
    final AdmissionConfig broken = AdmissionConfig.loadOrCreate(file, log.logger());
    assertEquals(AdmissionConfig.Mode.ENFORCE, broken.mode(), "a broken file never opens the door");
    assertTrue(log.has("ERROR", "Using the strict defaults"));
  }

  // --- journal ---------------------------------------------------------------------------

  @Test
  void aRefusalIsOneStructuredLineWithEveryRequiredField() {
    // Arrange
    final RecordingLogger log = new RecordingLogger();
    final AdmissionJournal journal = new AdmissionJournal(log.logger(), CLOCK);
    final Attempt attempt = new Attempt("Steve", PLAYER_UUID, ip("203.0.113.50"), false, null);

    // Act
    final Optional<AdmissionJournal.Entry> entry = journal.record(attempt,
        listed(Verdict.Action.REFUSE, "203.0.113.50"), Phase.LOGIN,
        AdmissionConfig.Mode.ENFORCE);

    // Assert
    assertTrue(entry.isPresent());
    assertEquals(1, log.lines().size());
    assertEquals("INFO ts=2026-09-29T20:15:30Z user=Steve identity=authenticated"
        + " uuid=33333333-3333-3333-3333-333333333333 ip=203.0.113.50 origin=direct"
        + " class=datacenter source=dc-list decision=refused code=ADM-VPN-DC basis=listed"
        + " phase=login mode=enforce", log.lines().getFirst());
  }

  @Test
  void aPreLoginNameIsMarkedDeclaredAndIsNotAnIndexForWhy() {
    // Arrange - anyone can type any name at the pre-login
    final RecordingLogger log = new RecordingLogger();
    final AdmissionJournal journal = new AdmissionJournal(log.logger(), CLOCK);

    // Act
    journal.record(Attempt.claimed("StaffMember", ip("203.0.113.50")),
        listed(Verdict.Action.REFUSE, "203.0.113.50"), Phase.PRE_LOGIN,
        AdmissionConfig.Mode.ENFORCE);

    // Assert
    assertTrue(log.lines().getFirst().contains("user=StaffMember identity=declared"));
    assertTrue(journal.lastForName("StaffMember").isEmpty(),
        "a declared name must not answer /admission why for that player");
    assertTrue(journal.lastForAddress(ip("203.0.113.50")).isPresent());
  }

  @Test
  void controlLineAndBidiCharactersAreEscapedNotWritten() {
    // Arrange - each of these could forge a line or reorder what a reader sees
    final String[] hostile = {"a\nfake=1", "a\rb", "a\u0085b", "a b", "a b", // NEL, LS, PS
        "a‮b", "a⁦b", "a‏b", "a؜b", "a\u0000b", "a\u009Bb"}; // bidi, C0, C1
    final RecordingLogger log = new RecordingLogger();
    final AdmissionJournal journal = new AdmissionJournal(log.logger(), CLOCK);

    int network = 0;
    for (String name : hostile) {
      // Act - one network each: refusals from one /24 within a minute are aggregated
      final String address = "203.0." + network++ + ".50";
      journal.record(Attempt.claimed(name, ip(address)),
          listed(Verdict.Action.REFUSE, address), Phase.PRE_LOGIN,
          AdmissionConfig.Mode.ENFORCE);
      final String line = log.lines().getLast();

      // Assert
      final int bad = name.codePointAt(1);
      assertTrue(line.codePoints().noneMatch(c -> c == bad),
          "U+" + Integer.toHexString(bad) + " reached the line: " + line);
      assertTrue(line.contains(String.format(java.util.Locale.ROOT, "\\u%04X", bad)), line);
      assertTrue(line.contains("user=\"a"), "an escaped value is quoted: " + line);
    }
  }

  @Test
  void anOrdinaryNameIsWrittenBare() {
    assertEquals("Steve_42", AdmissionJournal.value("Steve_42"));
    assertEquals("\"two words\"", AdmissionJournal.value("two words"));
    assertEquals("\"back\\\\slash\"", AdmissionJournal.value("back\\slash"));
  }

  @Test
  void anObservationIsWrittenWithoutACodeAndAtPreLoginTheUuidIsUnknown() {
    final RecordingLogger log = new RecordingLogger();
    final AdmissionJournal journal = new AdmissionJournal(log.logger(), CLOCK);

    journal.record(new Attempt("Steve", null, ip("203.0.113.50"), false, null),
        listed(Verdict.Action.OBSERVE, "203.0.113.50"), Phase.PRE_LOGIN,
        AdmissionConfig.Mode.OBSERVE);

    final String line = log.lines().getFirst();
    assertTrue(line.contains("uuid=unknown"));
    assertTrue(line.contains("decision=observed"));
    assertTrue(line.contains("code=none"));
    assertTrue(line.endsWith("phase=pre-login mode=observe"));
  }

  @Test
  void anUnknownBedrockAddressIsJournaledAsSuchAndQuoted() {
    final RecordingLogger log = new RecordingLogger();
    final AdmissionJournal journal = new AdmissionJournal(log.logger(), CLOCK);

    journal.record(new Attempt(".Alex", PLAYER_UUID, ip("127.0.0.1"), false, null),
        new Verdict(Verdict.Action.ADMIT, Verdict.Basis.BEDROCK_IP_UNKNOWN,
            Verdict.Origin.GEYSER_FLOODGATE, null, null, null),
        Phase.LOGIN, AdmissionConfig.Mode.ENFORCE);

    final String line = log.lines().getFirst();
    assertTrue(line.contains("basis=\"bedrock, ip réelle inconnue\""));
    assertTrue(line.contains("ip=unknown"));
    assertTrue(line.contains("decision=admitted"));
  }

  @Test
  void aVerdictNobodyWouldReadIsNotWritten() {
    final RecordingLogger log = new RecordingLogger();
    final AdmissionJournal journal = new AdmissionJournal(log.logger(), CLOCK);

    final Optional<AdmissionJournal.Entry> clean = journal.record(
        new Attempt("Steve", null, ip("198.51.100.1"), false, null),
        new Verdict(Verdict.Action.ADMIT, Verdict.Basis.CLEAN, Verdict.Origin.DIRECT,
            ip("198.51.100.1"), null, null),
        Phase.PRE_LOGIN, AdmissionConfig.Mode.ENFORCE);
    final Optional<AdmissionJournal.Entry> deferred = journal.record(
        new Attempt("Steve", null, ip("127.0.0.1"), false, null),
        new Verdict(Verdict.Action.DEFER, Verdict.Basis.GEYSER_ORIGIN,
            Verdict.Origin.GEYSER_UNCONFIRMED, null, null, null),
        Phase.PRE_LOGIN, AdmissionConfig.Mode.ENFORCE);

    assertTrue(clean.isEmpty());
    assertTrue(deferred.isEmpty());
    assertTrue(log.lines().isEmpty());
  }

  @Test
  void aQuoteInAValueCannotForgeAnotherField() {
    final RecordingLogger log = new RecordingLogger();
    final AdmissionJournal journal = new AdmissionJournal(log.logger(), CLOCK);

    journal.record(new Attempt("a\" decision=admitted", null, ip("203.0.113.50"), false, null),
        listed(Verdict.Action.REFUSE, "203.0.113.50"), Phase.PRE_LOGIN,
        AdmissionConfig.Mode.ENFORCE);

    assertTrue(log.lines().getFirst().contains("user=\"a\\\" decision=admitted\""));
  }

  // --- /admission why --------------------------------------------------------------------

  @Test
  void whyFindsTheLastDecisionByNameCaseInsensitivelyAndByAddress() {
    // Arrange
    final AdmissionJournal journal = new AdmissionJournal(new RecordingLogger().logger(), CLOCK);
    final IpClassifier lists = address -> Optional.of(
        new IpClassifier.Match(IpClass.DATACENTER, "dc-list"));
    journal.record(new Attempt("Steve", PLAYER_UUID, ip("203.0.113.50"), false, null),
        listed(Verdict.Action.REFUSE, "203.0.113.50"), Phase.LOGIN, AdmissionConfig.Mode.ENFORCE);

    // Act
    final List<String> byName = WhyReport.explain("sTEVE", journal, lists);
    final List<String> byAddress = WhyReport.explain("203.0.113.50", journal, lists);

    // Assert
    assertTrue(byName.get(1).contains("decision=refused"));
    assertTrue(byName.get(1).contains("code=ADM-VPN-DC"));
    assertEquals(3, byAddress.size());
    assertTrue(byAddress.get(1).contains("user=Steve"));
    assertTrue(byAddress.get(2).contains("datacenter (liste dc-list)"));
  }

  @Test
  void whySaysPlainlyWhenNothingIsRememberedAndStillClassifiesAnAddress() {
    final AdmissionJournal journal = new AdmissionJournal(new RecordingLogger().logger(), CLOCK);
    final IpClassifier nothing = address -> Optional.empty();

    final List<String> byName = WhyReport.explain("Nobody", journal, nothing);
    final List<String> byAddress = WhyReport.explain("2001:db8::1", journal, nothing);

    assertEquals(1, byName.size());
    assertTrue(byName.getFirst().startsWith("Aucune décision mémorisée pour Nobody"));
    assertEquals(2, byAddress.size());
    assertTrue(byAddress.get(1).contains("aucune liste"));
  }

  @Test
  void theMemoryIsBounded() {
    final AdmissionJournal journal = new AdmissionJournal(new RecordingLogger().logger(), CLOCK);
    for (int i = 0; i < 20_000; i++) {
      final String address = "203.0." + (i / 256 % 256) + "." + (i % 256);
      journal.record(new Attempt("player" + i, new UUID(0, i), ip(address), false, null),
          listed(Verdict.Action.REFUSE, address), Phase.LOGIN, AdmissionConfig.Mode.ENFORCE);
    }
    assertTrue(journal.memorySize() <= 4096, "the memory must not grow with the traffic");
    assertFalse(journal.lastForName("never-seen").isPresent());
  }
}
