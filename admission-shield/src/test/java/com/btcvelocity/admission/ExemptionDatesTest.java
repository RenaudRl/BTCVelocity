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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.btcvelocity.admission.Verdict.Action;
import com.btcvelocity.admission.Verdict.Basis;
import java.io.IOException;
import java.io.StringReader;
import java.net.InetAddress;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Reasons and end dates on the exemptions: what loads, what is refused, what lapses. */
class ExemptionDatesTest {

  private static final UUID STAFF_UUID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final InetAddress OFFICE = ip("203.0.113.9");
  private static final InetAddress OTHER_HOST = ip("198.51.100.20");
  private static final CidrSet DATACENTER_LIST = listOf("203.0.113.0/24", "198.51.100.0/24");
  private static final IpClassifier LISTS = address -> DATACENTER_LIST.contains(address)
      ? Optional.of(new IpClassifier.Match(IpClass.DATACENTER, "dc-list")) : Optional.empty();

  /** The manual clock starts on 2026-10-07 at noon UTC. */
  private final ManualClock clock = new ManualClock();
  private final List<String> warnings = new ArrayList<>();
  private final List<String> errors = new ArrayList<>();

  private static InetAddress ip(final String literal) {
    return InetAddress.ofLiteral(literal);
  }

  private static CidrSet listOf(final String... entries) {
    final CidrSet.Builder builder = CidrSet.builder();
    for (String entry : entries) {
      builder.add(entry);
    }
    return builder.build();
  }

  private AdmissionConfig parse(final String yaml) throws IOException {
    return AdmissionConfig.parseYaml(new StringReader(yaml), this.warnings::add, this.errors::add,
        this.clock);
  }

  private static Verdict preLogin(final AdmissionConfig config, final InetAddress from) {
    return new AdmissionEngine(config, LISTS)
        .decide(Attempt.claimed("Visitor", from), Phase.PRE_LOGIN);
  }

  @Test
  void aPlayerKeepsItsReasonAndItsEndDateWhetherTheDateIsQuotedOrNot() throws IOException {
    final AdmissionConfig config = parse("""
        exemptions:
          players:
            - name: StaffMember
              uuid: 11111111-1111-1111-1111-111111111111
              reason: moderateur en deplacement a l'etranger
              expires: 2026-12-31
            - name: Partner
              uuid: 22222222-2222-2222-2222-222222222222
              expires: "2027-01-15"
        """);

    final List<Exemptions.Player> players = config.exemptions().players();

    assertEquals(List.of(), this.errors);
    assertEquals(List.of(), this.warnings);
    assertEquals(2, players.size());
    assertEquals("moderateur en deplacement a l'etranger", players.get(0).reason());
    assertEquals(LocalDate.of(2026, 12, 31), players.get(0).expires());
    assertNull(players.get(1).reason());
    assertEquals(LocalDate.of(2027, 1, 15), players.get(1).expires());
  }

  @Test
  void aPlayerWithoutReasonOrEndDateStaysValidAndNeverLapses() throws IOException {
    final AdmissionConfig config = parse("""
        exemptions:
          players:
            - name: StaffMember
              uuid: 11111111-1111-1111-1111-111111111111
        """);
    this.clock.advance(Duration.ofDays(3650));

    assertEquals(List.of(), this.errors);
    assertEquals(List.of(), this.warnings);
    assertNull(config.exemptions().players().getFirst().expires());
    assertTrue(config.exemptions().coversPlayer("StaffMember", STAFF_UUID),
        "an entry written before the dates existed keeps its meaning");
  }

  @Test
  void anExpiredPlayerIsIgnoredWithAWarningThatNamesItAndItsReason() throws IOException {
    final AdmissionConfig config = parse("""
        exemptions:
          players:
            - name: StaffMember
              uuid: 11111111-1111-1111-1111-111111111111
              reason: test de charge
              expires: 2026-10-06
        """);

    assertEquals(List.of(), this.errors);
    assertEquals(1, this.warnings.size(), this.warnings.toString());
    assertTrue(this.warnings.getFirst().contains("StaffMember"), this.warnings.getFirst());
    assertTrue(this.warnings.getFirst().contains("2026-10-06"), this.warnings.getFirst());
    assertTrue(this.warnings.getFirst().contains("test de charge"), this.warnings.getFirst());
    assertTrue(config.exemptions().players().isEmpty());
    assertEquals(Action.REFUSE, new AdmissionEngine(config, LISTS).decide(
        Attempt.claimed("StaffMember", OFFICE).authenticated("StaffMember", STAFF_UUID, false,
            null), Phase.LOGIN).action());
  }

  @Test
  void anExemptionHoldsThroughItsLastDayAndLapsesTheNextWithoutARestart() throws IOException {
    final AdmissionConfig config = parse("""
        exemptions:
          networks:
            - network: 203.0.113.0/28
              reason: bureau du partenaire
              expires: 2026-10-08
        """);
    final Verdict today = preLogin(config, OFFICE);
    this.clock.advance(Duration.ofDays(1));
    final Verdict lastDay = preLogin(config, OFFICE);
    this.clock.advance(Duration.ofDays(1));
    final Verdict dayAfter = preLogin(config, OFFICE);

    assertEquals(Basis.EXEMPT_NETWORK, today.basis());
    assertEquals(Basis.EXEMPT_NETWORK, lastDay.basis(), "2026-10-08 is still the last valid day");
    assertEquals(Action.REFUSE, dayAfter.action(), "the proxy was not restarted, the entry ended");
    assertEquals(0, config.exemptions().activeCount());
  }

  @Test
  void aNamedExemptionAlsoLapsesWhileTheProxyRuns() throws IOException {
    final AdmissionConfig config = parse("""
        exemptions:
          players:
            - name: StaffMember
              uuid: 11111111-1111-1111-1111-111111111111
              expires: 2026-10-07
        """);
    assertTrue(config.exemptions().coversPlayer("StaffMember", STAFF_UUID));
    assertTrue(config.exemptions().mayCoverLater("staffmember"));

    this.clock.advance(Duration.ofDays(1));

    assertEquals(false, config.exemptions().coversPlayer("StaffMember", STAFF_UUID));
    assertEquals(false, config.exemptions().mayCoverLater("StaffMember"),
        "a lapsed name no longer defers the decision to the login");
  }

  @Test
  void aNetworkWithoutEndDateIsStillHonouredWithAWarningToDateIt() throws IOException {
    final AdmissionConfig config = parse("""
        exemptions:
          networks:
            - network: 203.0.113.0/28
              reason: bureau du partenaire
            - network: 198.51.100.0/24
              reason: laboratoire
              expires: 2026-12-31
        """);

    assertEquals(List.of(), this.errors, "an entry that exists is never refused for its missing date");
    assertEquals(1, this.warnings.size(), this.warnings.toString());
    assertTrue(this.warnings.getFirst().contains("exemptions.networks[0]"), this.warnings.getFirst());
    assertTrue(this.warnings.getFirst().contains("203.0.113.0/28"), this.warnings.getFirst());
    assertTrue(this.warnings.getFirst().contains("non datée"), this.warnings.getFirst());
    assertTrue(this.warnings.getFirst().contains("AAAA-MM-JJ"), this.warnings.getFirst());
    assertEquals(Basis.EXEMPT_NETWORK, preLogin(config, OFFICE).basis());
    assertEquals(Basis.EXEMPT_NETWORK, preLogin(config, OTHER_HOST).basis());
    assertNull(config.exemptions().networks().getFirst().expires());
    assertEquals(2, config.exemptions().activeNetworks());
  }

  @Test
  void anUndatedNetworkNeverLapsAndIsNeverListedAsEndingSoon() throws IOException {
    final AdmissionConfig config = parse("""
        exemptions:
          networks:
            - network: 203.0.113.0/28
        """);
    this.clock.advance(Duration.ofDays(3650));

    assertEquals(Basis.EXEMPT_NETWORK, preLogin(config, OFFICE).basis());
    assertEquals(List.of(), config.exemptions().expiringSoon());
    assertEquals(1, config.exemptions().undatedNetworks().size());
  }

  @Test
  void aBareNetworkStringIsHonouredLikeAnEntryWithoutEndDate() throws IOException {
    final AdmissionConfig config = parse("""
        exemptions:
          networks: ['203.0.113.0/28']
        """);

    assertEquals(List.of(), this.errors);
    assertEquals(1, this.warnings.size(), this.warnings.toString());
    assertTrue(this.warnings.getFirst().contains("non datée"), this.warnings.getFirst());
    assertEquals(Basis.EXEMPT_NETWORK, preLogin(config, OFFICE).basis());
  }

  @Test
  void aBareValueThatIsNotANetworkIsStillReportedAndIgnored() throws IOException {
    final AdmissionConfig config = parse("""
        exemptions:
          networks: ['not-a-network']
        """);

    assertEquals(0, config.exemptions().networks().size());
    assertEquals(1, this.warnings.size(), this.warnings.toString());
    assertTrue(this.warnings.getFirst().contains("not-a-network"), this.warnings.getFirst());
  }

  @Test
  void anExpiredNetworkIsIgnoredWithAWarning() throws IOException {
    final AdmissionConfig config = parse("""
        exemptions:
          networks:
            - network: 203.0.113.0/28
              reason: ancien partenaire
              expires: 2026-09-30
        """);

    assertEquals(List.of(), this.errors);
    assertEquals(1, this.warnings.size(), this.warnings.toString());
    assertTrue(this.warnings.getFirst().contains("203.0.113.0/28"), this.warnings.getFirst());
    assertTrue(this.warnings.getFirst().contains("2026-09-30"), this.warnings.getFirst());
    assertEquals(Action.REFUSE, preLogin(config, OFFICE).action());
  }

  @Test
  void anUnreadableEndDateRefusesTheEntryAndOnlyIt() throws IOException {
    final AdmissionConfig config = parse("""
        exemptions:
          players:
            - name: Careless
              uuid: 22222222-2222-2222-2222-222222222222
              expires: 31/12/2026
            - name: StaffMember
              uuid: 11111111-1111-1111-1111-111111111111
          networks:
            - network: 203.0.113.0/28
              expires: soon
        """);

    assertEquals(2, this.errors.size(), this.errors.toString());
    assertTrue(this.errors.stream().allMatch(error -> error.contains("AAAA-MM-JJ")),
        this.errors.toString());
    assertEquals(1, config.exemptions().players().size());
    assertEquals("StaffMember", config.exemptions().players().getFirst().name());
    assertEquals(0, config.exemptions().networks().size());
  }

  @Test
  void theCountsSeparateActiveEntriesFromThoseEndingWithinSevenDays() throws IOException {
    final AdmissionConfig config = parse("""
        exemptions:
          players:
            - name: Permanent
              uuid: 11111111-1111-1111-1111-111111111111
            - name: ThreeDays
              uuid: 22222222-2222-2222-2222-222222222222
              reason: remplacement
              expires: 2026-10-10
            - name: SevenDays
              uuid: 33333333-3333-3333-3333-333333333333
              expires: 2026-10-14
            - name: EightDays
              uuid: 44444444-4444-4444-4444-444444444444
              expires: 2026-10-15
          networks:
            - network: 203.0.113.0/28
              reason: bureau
              expires: 2026-10-09
        """);

    final Exemptions exemptions = config.exemptions();
    final List<Exemptions.Expiring> soon = exemptions.expiringSoon();

    assertEquals(5, exemptions.activeCount());
    assertEquals(List.of("203.0.113.0/28", "ThreeDays", "SevenDays"),
        soon.stream().map(Exemptions.Expiring::label).toList(), "soonest first, eighth day out");
    assertEquals(Exemptions.Kind.NETWORK, soon.getFirst().kind());
    assertEquals("bureau", soon.getFirst().reason());
    assertEquals(LocalDate.of(2026, 10, 9), soon.getFirst().expires());

    this.clock.advance(Duration.ofDays(3));

    assertEquals(4, exemptions.activeCount(), "the network has ended; ThreeDays has its last day");
    assertEquals(List.of("ThreeDays", "SevenDays", "EightDays"),
        exemptions.expiringSoon().stream().map(Exemptions.Expiring::label).toList(),
        "EightDays now falls inside the notice window");
  }

  @Test
  void theStatusSaysHowManyAreActiveAndWhichEndSoon() throws IOException {
    final AdmissionConfig config = parse("""
        exemptions:
          players:
            - name: Permanent
              uuid: 11111111-1111-1111-1111-111111111111
            - name: ThreeDays
              uuid: 22222222-2222-2222-2222-222222222222
              reason: remplacement
              expires: 2026-10-10
          networks:
            - network: 203.0.113.0/28
              expires: 2026-12-31
        """);

    final List<String> lines = AdmissionCommand.exemptionStatus(config.exemptions());

    assertTrue(lines.getFirst().contains("3"), lines.toString());
    assertTrue(lines.getFirst().contains("2 joueur"), lines.toString());
    assertTrue(lines.getFirst().contains("1 réseau"), lines.toString());
    assertEquals(2, lines.size(), "one summary line, one line for the single entry ending soon");
    assertTrue(lines.get(1).contains("ThreeDays"), lines.toString());
    assertTrue(lines.get(1).contains("2026-10-10"), lines.toString());
    assertTrue(lines.get(1).contains("remplacement"), lines.toString());
  }

  @Test
  void theStatusListsAnUndatedNetworkAsSuch() throws IOException {
    final AdmissionConfig config = parse("""
        exemptions:
          networks:
            - network: 203.0.113.0/28
              reason: bureau du partenaire
            - network: 198.51.100.0/24
              expires: 2026-12-31
        """);

    final List<String> lines = AdmissionCommand.exemptionStatus(config.exemptions());

    assertTrue(lines.getFirst().contains("2 réseau"), lines.toString());
    assertTrue(lines.stream().anyMatch(line -> line.contains("non datée")
        && line.contains("203.0.113.0/28") && line.contains("bureau du partenaire")),
        lines.toString());
    assertEquals(1, lines.stream().filter(line -> line.contains("non datée")).count(),
        "the dated network is not listed as undated");
  }

  @Test
  void theFileLoaderLogsAnExpiredEntryAndAnUndatedNetworkAtWarnAndKeepsTheNetworkHonoured(
      @org.junit.jupiter.api.io.TempDir final java.nio.file.Path directory) throws IOException {
    final java.nio.file.Path file = directory.resolve("config.yml");
    java.nio.file.Files.writeString(file, """
        exemptions:
          players:
            - name: StaffMember
              uuid: 11111111-1111-1111-1111-111111111111
              expires: 2020-01-01
          networks:
            - network: 203.0.113.0/28
        """);
    final RecordingLogger log = new RecordingLogger();

    final AdmissionConfig config = AdmissionConfig.loadOrCreate(file, log.logger());

    assertTrue(log.has("WARN", "2020-01-01"), log.lines().toString());
    assertTrue(log.has("WARN", "non datée"), log.lines().toString());
    assertEquals(false, log.has("ERROR", "expires"), log.lines().toString());
    assertEquals(1, config.exemptions().activeCount());
  }

  @Test
  void theExamplesOfTheBundledTemplateAreAcceptedAsTheyStand() throws IOException {
    final String template;
    try (var stream = AdmissionConfig.class.getResourceAsStream("/config.yml")) {
      template = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
    // Uncomment the examples; the checkout may use CRLF or LF, the text is normalised first.
    final String uncommented = template.replace("\r\n", "\n")
        .replace("  players: []\n", "  players:\n")
        .replace("  networks: []\n", "  networks:\n")
        .replaceAll("(?m)^  #(  - |    )(name|uuid|reason|expires|network):", "  $1$2:");

    final AdmissionConfig config = parse(uncommented);

    assertEquals(List.of(), this.errors);
    assertEquals(List.of(), this.warnings);
    assertEquals(2, config.exemptions().players().size());
    assertEquals(1, config.exemptions().networks().size());
    assertEquals("bureau du partenaire", config.exemptions().networks().getFirst().reason());
  }

  @Test
  void theStatusStaysOneLineWhenNothingEndsSoon() throws IOException {
    final AdmissionConfig config = parse("""
        exemptions:
          players:
            - name: Permanent
              uuid: 11111111-1111-1111-1111-111111111111
        """);

    final List<String> lines = AdmissionCommand.exemptionStatus(config.exemptions());

    assertEquals(1, lines.size(), lines.toString());
    assertTrue(lines.getFirst().contains("1"), lines.toString());
  }
}
