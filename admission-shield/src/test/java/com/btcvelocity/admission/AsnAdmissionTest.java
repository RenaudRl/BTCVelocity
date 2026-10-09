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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.btcvelocity.admission.Verdict.Action;
import com.btcvelocity.admission.Verdict.Basis;
import java.io.IOException;
import java.io.StringReader;
import java.net.InetAddress;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The engine with the IP-to-ASN database: deny, observe keywords, grouping, quarantine. */
class AsnAdmissionTest {

  private static final InetAddress HOSTING_A = ip("192.0.2.10");
  private static final InetAddress HOSTING_B = ip("203.0.113.20");
  private static final InetAddress HOME = ip("198.51.100.77");
  private static final UUID STAFF_UUID = UUID.fromString("11111111-1111-1111-1111-111111111111");

  /** AS 64496 announces two prefixes of different networks, AS 64497 is a home provider. */
  private static final AsnLookup DATABASE = database("""
      192.0.2.0\t192.0.2.255\t64496\tNL\tAlpha Hosting BV
      198.51.100.0\t198.51.100.255\t64497\tFR\tHome Fibre SA
      203.0.113.0\t203.0.113.255\t64496\tNL\tAlpha Hosting BV
      """);

  private static final IpClassifier NO_LIST = address -> Optional.empty();

  private static InetAddress ip(final String literal) {
    return InetAddress.ofLiteral(literal);
  }

  private static AsnLookup database(final String text) {
    final AsnDatabase parsed = AsnParser.parse(text, 1000).database();
    return parsed::lookup;
  }

  private static AdmissionConfig config(final String yaml) {
    try {
      return AdmissionConfig.parseYaml(new StringReader(yaml), warning -> { });
    } catch (IOException malformed) {
      throw new IllegalStateException(malformed);
    }
  }

  private static AdmissionEngine engine(final String yaml) {
    return new AdmissionEngine(config(yaml), NO_LIST, NO_LIST, new ManualClock(), DATABASE,
        AsnLookup.NONE);
  }

  private static Attempt login(final String name, final UUID uuid, final InetAddress from) {
    return Attempt.claimed(name, from).authenticated(name, uuid, false, null);
  }

  // --- deny-asns -----------------------------------------------------------------------------

  @Test
  void aDeniedSystemIsObservedByDefaultAndNamedInTheVerdict() {
    final Verdict verdict = engine("asn:\n  deny-asns: [64496]\n")
        .decide(Attempt.claimed("Steve", HOSTING_A), Phase.PRE_LOGIN);

    assertEquals(Action.OBSERVE, verdict.action());
    assertEquals(Basis.LISTED, verdict.basis());
    assertEquals(IpClass.ASN, verdict.ipClass());
    assertEquals("iptoasn:AS64496", verdict.sourceId());
    assertEquals(new AsnDatabase.Info(64496, "Alpha Hosting BV", "NL"), verdict.asn());
    assertEquals("ADM-VPN-ASN", verdict.causeCode());
  }

  @Test
  void aDeniedSystemIsRefusedInEnforceFromEitherOfItsPrefixes() {
    final AdmissionEngine engine = engine("asn:\n  mode: enforce\n  deny-asns: [64496]\n");

    assertEquals(Action.REFUSE,
        engine.decide(Attempt.claimed("Steve", HOSTING_A), Phase.PRE_LOGIN).action());
    assertEquals(Action.REFUSE,
        engine.decide(Attempt.claimed("Steve", HOSTING_B), Phase.PRE_LOGIN).action());
    assertEquals(Basis.CLEAN,
        engine.decide(Attempt.claimed("Steve", HOME), Phase.PRE_LOGIN).basis());
  }

  @Test
  void theModuleWideObserveStillHoldsTheAsnRefusalBack() {
    final Verdict verdict = engine("mode: observe\nasn:\n  mode: enforce\n  deny-asns: [64496]\n")
        .decide(Attempt.claimed("Steve", HOSTING_A), Phase.PRE_LOGIN);

    assertEquals(Action.OBSERVE, verdict.action());
  }

  @Test
  void theAsnModeDoesNotChangeTheModeOfTheLists() {
    final AdmissionEngine engine = new AdmissionEngine(
        config("mode: enforce\nasn:\n  mode: observe\n  deny-asns: [64496]\n"),
        address -> Optional.of(new IpClassifier.Match(IpClass.DATACENTER, "dc-list")),
        NO_LIST, new ManualClock(), DATABASE, AsnLookup.NONE);

    final Verdict verdict = engine.decide(Attempt.claimed("Steve", HOSTING_A), Phase.PRE_LOGIN);

    assertEquals(Action.REFUSE, verdict.action(), "the list refuses in enforce");
    assertEquals(IpClass.DATACENTER, verdict.ipClass(), "the list names the class first");
    assertEquals(64496, verdict.asn().number(), "and the system is added to the verdict");
  }

  @Test
  void nothingIsDeniedWhileTheDatabaseIsNotLoaded() {
    final AdmissionEngine engine = new AdmissionEngine(
        config("asn:\n  mode: enforce\n  deny-asns: [64496]\n"), NO_LIST, NO_LIST,
        new ManualClock(), AsnLookup.NONE, AsnLookup.NONE);

    final Verdict verdict = engine.decide(Attempt.claimed("Steve", HOSTING_A), Phase.PRE_LOGIN);

    assertEquals(Action.ADMIT, verdict.action());
    assertEquals(Basis.CLEAN, verdict.basis());
  }

  @Test
  void aDeniedSystemIsRefusedAtTheLoginToo() {
    final Optional<Verdict> settled = engine("asn:\n  mode: enforce\n  deny-asns: [64496]\n")
        .decideAtLogin(login("Steve", UUID.randomUUID(), HOSTING_A));

    assertTrue(settled.isPresent());
    assertEquals(Action.REFUSE, settled.get().action());
    assertEquals(IpClass.ASN, settled.get().ipClass());
  }

  @Test
  void anExemptNetworkAndAnExemptPlayerStillGetThroughADeniedSystem() {
    final String yaml = """
        asn:
          mode: enforce
          deny-asns: [64496]
        exemptions:
          players:
            - name: StaffMember
              uuid: 11111111-1111-1111-1111-111111111111
          networks:
            - network: 203.0.113.0/24
              expires: 2099-12-31
        """;
    final AdmissionEngine engine = engine(yaml);

    final Verdict network = engine.decide(Attempt.claimed("Steve", HOSTING_B), Phase.PRE_LOGIN);
    final Optional<Verdict> player =
        engine.decideAtLogin(login("StaffMember", STAFF_UUID, HOSTING_A));

    assertEquals(Basis.EXEMPT_NETWORK, network.basis());
    assertEquals(Action.ADMIT, network.action());
    assertEquals(64496, network.asn().number());
    assertTrue(player.isPresent());
    assertEquals(Basis.EXEMPT_PLAYER, player.get().basis());
    assertEquals(Action.ADMIT, player.get().action());
  }

  // --- observe-keywords ----------------------------------------------------------------------

  @Test
  void aKeywordInTheNameIsASignalAndNeverARefusal() {
    final AdmissionEngine engine = engine("mode: enforce\nasn:\n  mode: enforce\n"
        + "  observe-keywords: [hosting]\n");

    final Verdict verdict = engine.decide(Attempt.claimed("Steve", HOSTING_A), Phase.PRE_LOGIN);

    assertEquals(Action.ADMIT, verdict.action());
    assertEquals(Basis.ASN_KEYWORD, verdict.basis());
    assertTrue(verdict.isJournaled());
    assertNull(verdict.ipClass());
    assertEquals("Alpha Hosting BV", verdict.asn().name());
    assertNull(verdict.causeCode());
  }

  @Test
  void anAddressWhoseSystemNameHoldsNoKeywordStaysClean() {
    final Verdict verdict = engine("asn:\n  observe-keywords: [hosting]\n")
        .decide(Attempt.claimed("Steve", HOME), Phase.PRE_LOGIN);

    assertEquals(Basis.CLEAN, verdict.basis());
    assertNull(verdict.asn(), "a clean admission is not written, so the system is not looked up");
    assertFalse(verdict.isJournaled());
  }

  @Test
  void aListedAddressIsNotAlsoASignal() {
    final AdmissionEngine engine = new AdmissionEngine(
        config("asn:\n  observe-keywords: [hosting]\n"),
        address -> Optional.of(new IpClassifier.Match(IpClass.TOR, "tor-list")), NO_LIST,
        new ManualClock(), DATABASE, AsnLookup.NONE);

    assertEquals(Basis.LISTED,
        engine.decide(Attempt.claimed("Steve", HOSTING_A), Phase.PRE_LOGIN).basis());
  }

  @Test
  void noKeywordsMeansNoSignalWhateverTheNames() {
    assertEquals(Basis.CLEAN, engine("")
        .decide(Attempt.claimed("Steve", HOSTING_A), Phase.PRE_LOGIN).basis());
  }

  // --- system named on the other verdicts that are written -----------------------------------

  @Test
  void aRateVerdictNamesTheSystemOfTheNetwork() {
    final AdmissionEngine engine = engine("""
        network-rate:
          mode: enforce
          attempts: 1
          burst: 1
          window-seconds: 1000
        """);

    engine.decidePreLogin(Attempt.claimed("A", HOSTING_A));
    final Verdict over = engine.decidePreLogin(Attempt.claimed("B", HOSTING_A));

    assertEquals(Basis.RATE_NETWORK, over.basis());
    assertEquals(64496, over.asn().number());
  }

  // --- grouping of the per-network bound ------------------------------------------------------

  private static final String ONE_ATTEMPT_PER_NETWORK = """
      network-rate:
        mode: enforce
        attempts: 1
        burst: 1
        window-seconds: 1000
        group-hosting-by-asn: %s
      asn:
        deny-asns: [64496]
      """;

  @Test
  void twoNetworksOfADeniedSystemShareOneBucketWhenGroupedByAsn() {
    final AdmissionEngine grouped = engine(ONE_ATTEMPT_PER_NETWORK.formatted(true));

    assertEquals(Action.ADMIT,
        grouped.decideRate(Attempt.claimed("A", HOSTING_A)).action());
    assertNotEquals(Action.ADMIT,
        grouped.decideRate(Attempt.claimed("B", HOSTING_B)).action(),
        "another /24 of the same provider is the same wave");
  }

  @Test
  void twoNetworksOfADeniedSystemAreSeparateBucketsByDefault() {
    final AdmissionEngine byPrefix = engine(ONE_ATTEMPT_PER_NETWORK.formatted(false));

    assertEquals(Action.ADMIT, byPrefix.decideRate(Attempt.claimed("A", HOSTING_A)).action());
    assertEquals(Action.ADMIT, byPrefix.decideRate(Attempt.claimed("B", HOSTING_B)).action());
  }

  @Test
  void aSystemThatIsNotHostingKeepsItsPrefixEvenWhenGrouping() {
    final AdmissionEngine grouped = engine(ONE_ATTEMPT_PER_NETWORK.formatted(true));

    assertEquals(Action.ADMIT, grouped.decideRate(Attempt.claimed("A", HOME)).action());
    assertEquals(Action.ADMIT,
        grouped.decideRate(Attempt.claimed("B", ip("198.51.101.5"))).action(),
        "another network, unknown to the database: its own bucket");
    assertNotEquals(Action.ADMIT, grouped.decideRate(Attempt.claimed("C", HOME)).action(),
        "the same /24 is still one bucket");
  }

  @Test
  void aKeywordSystemIsGroupedToo() {
    final AdmissionEngine grouped = engine("""
        network-rate:
          mode: enforce
          attempts: 1
          burst: 1
          window-seconds: 1000
          group-hosting-by-asn: true
        asn:
          observe-keywords: [hosting]
        """);

    assertEquals(Action.ADMIT, grouped.decideRate(Attempt.claimed("A", HOSTING_A)).action());
    assertNotEquals(Action.ADMIT, grouped.decideRate(Attempt.claimed("B", HOSTING_B)).action());
  }

  @Test
  void theGroupingKeyOfASystemCannotBeAPrefixKey() {
    final NetworkRateLimiter.Grouping grouping = NetworkRateLimiter.Grouping.byHostingAsn(
        DATABASE, info -> true, NetworkRateLimiter.Grouping.byPrefix(24, 48));

    assertEquals("AS64496", grouping.keyOf(HOSTING_A));
    assertEquals(grouping.keyOf(HOSTING_A), grouping.keyOf(HOSTING_B));
    assertEquals(NetworkRateLimiter.Grouping.byPrefix(24, 48).keyOf(ip("100.0.0.1")),
        grouping.keyOf(ip("100.0.0.1")), "an address the database does not know keeps its prefix");
  }

  // --- quarantine ------------------------------------------------------------------------------

  @Test
  void aWaitingVersionThatWouldDenyAPlayerInAdditionIsReported() {
    final AsnLookup waiting = database(
        "192.0.2.0\t192.0.2.255\t64496\tNL\tAlpha Hosting BV\n"
            + "198.51.100.0\t198.51.100.255\t64496\tFR\tHome Fibre SA, now part of Alpha\n");
    final AdmissionEngine engine = new AdmissionEngine(
        config("asn:\n  mode: enforce\n  deny-asns: [64496]\n"), NO_LIST, NO_LIST,
        new ManualClock(), DATABASE, waiting);

    final Optional<Verdict> extraForHome =
        engine.wouldRefuseInAddition(login("Steve", UUID.randomUUID(), HOME));
    final Optional<Verdict> alreadyRefused =
        engine.wouldRefuseInAddition(login("Steve", UUID.randomUUID(), HOSTING_A));

    assertTrue(extraForHome.isPresent());
    assertEquals(IpClass.ASN, extraForHome.get().ipClass());
    assertNotNull(extraForHome.get().sourceId());
    assertTrue(alreadyRefused.isEmpty(), "the version in use refuses it already");
  }

  @Test
  void withNothingWaitingNothingIsReportedInAddition() {
    final AdmissionEngine engine = engine("asn:\n  mode: enforce\n  deny-asns: [64496]\n");

    assertTrue(engine.wouldRefuseInAddition(login("Steve", UUID.randomUUID(), HOME)).isEmpty());
    assertTrue(engine.wouldRefuseInAddition(login("Steve", UUID.randomUUID(), HOSTING_A))
        .isEmpty());
  }
}
