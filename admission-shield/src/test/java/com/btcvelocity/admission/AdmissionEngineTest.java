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
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdmissionEngineTest {

  private static final InetAddress GEYSER = ip("127.0.0.1");
  private static final InetAddress DATACENTER_HOST = ip("203.0.113.50");
  private static final InetAddress TOR_EXIT = ip("185.220.101.9");
  private static final InetAddress HOME = ip("198.51.100.77");
  private static final UUID STAFF_UUID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID OTHER_UUID = UUID.fromString("22222222-2222-2222-2222-222222222222");

  /** The lists: 203.0.113.0/24 and 2001:db8::/32 are datacenter, 185.220.101.0/24 is Tor. */
  private static final CidrSet DATACENTER_LIST = setOf(
      "203.0.113.0/24", "2001:db8::/32", "127.0.0.1"); // deliberately names Geyser's own address
  private static final CidrSet TOR_LIST = setOf("185.220.101.0/24");
  private static final IpClassifier LISTS = address -> {
    if (TOR_LIST.contains(address)) {
      return Optional.of(new IpClassifier.Match(IpClass.TOR, "tor-list"));
    }
    if (DATACENTER_LIST.contains(address)) {
      return Optional.of(new IpClassifier.Match(IpClass.DATACENTER, "dc-list"));
    }
    return Optional.empty();
  };

  private static CidrSet setOf(final String... entries) {
    final CidrSet.Builder builder = CidrSet.builder();
    for (String entry : entries) {
      builder.add(entry);
    }
    return builder.build();
  }

  private static InetAddress ip(final String literal) {
    return InetAddress.ofLiteral(literal);
  }

  private static AdmissionConfig config(final String yaml) {
    try {
      return AdmissionConfig.parseYaml(new StringReader(yaml), warning -> { });
    } catch (IOException malformed) {
      throw new IllegalStateException(malformed);
    }
  }

  private static AdmissionEngine engine(final String yaml) {
    return new AdmissionEngine(config(yaml), LISTS);
  }

  private static Attempt java(final String name, final InetAddress from) {
    return new Attempt(name, null, from, null);
  }

  @Test
  void aDatacenterAddressIsRefusedWithItsClassAndSource() {
    final Verdict verdict = engine("").decide(java("Steve", DATACENTER_HOST), Phase.PRE_LOGIN);

    assertEquals(Action.REFUSE, verdict.action());
    assertEquals(Basis.LISTED, verdict.basis());
    assertEquals(IpClass.DATACENTER, verdict.ipClass());
    assertEquals("dc-list", verdict.sourceId());
    assertEquals(DATACENTER_HOST, verdict.judged());
  }

  @Test
  void aTorExitIsRefused() {
    final Verdict verdict = engine("").decide(java("Steve", TOR_EXIT), Phase.PRE_LOGIN);

    assertEquals(Action.REFUSE, verdict.action());
    assertEquals(IpClass.TOR, verdict.ipClass());
  }

  @Test
  void anIpv6DatacenterAddressIsRefused() {
    final Verdict verdict =
        engine("").decide(java("Steve", ip("2001:db8:1::5")), Phase.PRE_LOGIN);

    assertEquals(Action.REFUSE, verdict.action());
  }

  @Test
  void aResidentialAddressIsAdmittedSilently() {
    final Verdict verdict = engine("").decide(java("Steve", HOME), Phase.PRE_LOGIN);

    assertEquals(Action.ADMIT, verdict.action());
    assertEquals(Basis.CLEAN, verdict.basis());
    assertTrue(!verdict.isJournaled(), "an ordinary admission is not written");
  }

  @Test
  void withNoListLoadedEveryoneIsAdmitted() {
    final AdmissionEngine open = new AdmissionEngine(config(""), address -> Optional.empty());

    assertEquals(Action.ADMIT, open.decide(java("Steve", DATACENTER_HOST), Phase.PRE_LOGIN)
        .action());
    assertEquals(Action.ADMIT, open.decide(java("Steve", TOR_EXIT), Phase.LOGIN).action());
  }

  @Test
  void observeModeJournalsTheSameVerdictButLetsTheConnectionIn() {
    final Verdict verdict =
        engine("mode: observe").decide(java("Steve", DATACENTER_HOST), Phase.PRE_LOGIN);

    assertEquals(Action.OBSERVE, verdict.action());
    assertEquals(Basis.LISTED, verdict.basis());
    assertEquals(IpClass.DATACENTER, verdict.ipClass());
    assertTrue(verdict.isJournaled());
  }

  @Test
  void enforceIsTheDefaultAndAnUnknownModeFallsBackToIt() {
    assertEquals(AdmissionConfig.Mode.ENFORCE, config("").mode());
    assertEquals(AdmissionConfig.Mode.ENFORCE, config("mode: audit").mode());
    assertEquals(AdmissionConfig.Mode.OBSERVE, config("mode: OBSERVE").mode());
  }

  @Test
  void geysersOwnAddressIsNeverJudgedEvenWhenAListNamesIt() {
    // Arrange - the fixture list DOES contain 127.0.0.1: judging it would refuse every Bedrock player
    final AdmissionEngine engine = engine("");

    // Act
    final Verdict atPreLogin = engine.decide(java("BedrockPlayer", GEYSER), Phase.PRE_LOGIN);
    final Verdict atLogin = engine.decide(
        new Attempt(".BedrockPlayer", OTHER_UUID, GEYSER, null), Phase.LOGIN);

    // Assert
    assertEquals(Action.DEFER, atPreLogin.action());
    assertEquals(Basis.GEYSER_ORIGIN, atPreLogin.basis());
    assertEquals(Action.ADMIT, atLogin.action());
    assertEquals(Basis.BEDROCK_IP_UNKNOWN, atLogin.basis());
    assertNull(atLogin.judged(), "no address was judged");
    assertEquals("bedrock, ip réelle inconnue", atLogin.basis().label());
  }

  @Test
  void aBedrockPlayerWhoseRealAddressIsADatacenterIsRefusedOnThatAddress() {
    final Verdict verdict = engine("").decide(
        new Attempt(".BedrockPlayer", OTHER_UUID, GEYSER, DATACENTER_HOST), Phase.LOGIN);

    assertEquals(Action.REFUSE, verdict.action());
    assertEquals(DATACENTER_HOST, verdict.judged());
  }

  @Test
  void aBedrockPlayerWhoseRealAddressIsResidentialIsAdmittedOnThatAddress() {
    final Verdict verdict = engine("").decide(
        new Attempt(".BedrockPlayer", OTHER_UUID, GEYSER, HOME), Phase.LOGIN);

    assertEquals(Action.ADMIT, verdict.action());
    assertEquals(Basis.CLEAN, verdict.basis());
    assertEquals(HOME, verdict.judged());
  }

  @Test
  void aFloodgateAddressThatIsGeysersOwnCountsAsUnknown() {
    // Floodgate recording Geyser's own address would make every Bedrock player look the same
    final Verdict verdict = engine("").decide(
        new Attempt(".BedrockPlayer", OTHER_UUID, GEYSER, GEYSER), Phase.LOGIN);

    assertEquals(Action.ADMIT, verdict.action());
    assertEquals(Basis.BEDROCK_IP_UNKNOWN, verdict.basis());
  }

  @Test
  void additionalGeyserAddressesAreHonouredAndTheDefaultsReplacedByTheList() {
    final AdmissionEngine engine = engine("geyser-addresses: ['10.9.0.0/24']");

    assertEquals(Action.DEFER, engine.decide(java("B", ip("10.9.0.12")), Phase.PRE_LOGIN).action());
    // 127.0.0.1 is no longer Geyser here, so it is judged like anyone (and the fixture lists it)
    assertEquals(Action.REFUSE, engine.decide(java("B", GEYSER), Phase.PRE_LOGIN).action());
  }

  @Test
  void anExemptedNetworkIsAdmittedAndJournaled() {
    final Verdict verdict = engine("exemptions:\n  networks: ['203.0.113.0/28']")
        .decide(java("Partner", ip("203.0.113.9")), Phase.PRE_LOGIN);

    assertEquals(Action.ADMIT, verdict.action());
    assertEquals(Basis.EXEMPT_NETWORK, verdict.basis());
    assertTrue(verdict.isJournaled());
    assertEquals(Action.REFUSE, engine("exemptions:\n  networks: ['203.0.113.0/28']")
        .decide(java("Partner", ip("203.0.113.99")), Phase.PRE_LOGIN).action(),
        "the exemption stops at its own boundary");
  }

  @Test
  void aNamedExemptionDefersAtPreLoginAndAdmitsOnlyOnceAuthenticated() {
    // Arrange
    final AdmissionEngine engine = engine("exemptions:\n  players:\n    - name: StaffMember");

    // Act
    final Verdict claimed = engine.decide(java("staffmember", DATACENTER_HOST), Phase.PRE_LOGIN);
    final Verdict authenticated = engine.decide(
        new Attempt("StaffMember", STAFF_UUID, DATACENTER_HOST, null), Phase.LOGIN);
    final Verdict stranger = engine.decide(java("Stranger", DATACENTER_HOST), Phase.PRE_LOGIN);

    // Assert
    assertEquals(Action.DEFER, claimed.action(), "the name is only claimed, not proven yet");
    assertEquals(Basis.AWAITING_IDENTITY, claimed.basis());
    assertTrue(!claimed.isJournaled());
    assertEquals(Action.ADMIT, authenticated.action());
    assertEquals(Basis.EXEMPT_PLAYER, authenticated.basis());
    assertEquals(Action.REFUSE, stranger.action(), "an unlisted name is refused at once");
  }

  @Test
  void aUuidOnlyExemptionAdmitsThatAccountAndRefusesAnotherOneAtLogin() {
    final AdmissionEngine engine = engine(
        "exemptions:\n  players:\n    - uuid: 11111111-1111-1111-1111-111111111111");

    final Verdict pre = engine.decide(java("Anyone", DATACENTER_HOST), Phase.PRE_LOGIN);
    final Verdict staff = engine.decide(
        new Attempt("Renamed", STAFF_UUID, DATACENTER_HOST, null), Phase.LOGIN);
    final Verdict other = engine.decide(
        new Attempt("Anyone", OTHER_UUID, DATACENTER_HOST, null), Phase.LOGIN);

    assertEquals(Action.DEFER, pre.action(), "a UUID is unknown before authentication");
    assertEquals(Action.ADMIT, staff.action());
    assertEquals(Action.REFUSE, other.action());
  }

  @Test
  void anEntryWithNameAndUuidNeedsBothToMatch() {
    final AdmissionEngine engine = engine("""
        exemptions:
          players:
            - name: StaffMember
              uuid: 11111111-1111-1111-1111-111111111111
        """);

    assertEquals(Action.ADMIT, engine.decide(
        new Attempt("StaffMember", STAFF_UUID, DATACENTER_HOST, null), Phase.LOGIN).action());
    assertEquals(Action.REFUSE, engine.decide(
        new Attempt("StaffMember", OTHER_UUID, DATACENTER_HOST, null), Phase.LOGIN).action(),
        "same name, other account: the name alone proves nothing");
  }

  @Test
  void anExemptedNameIsStillRefusedInObserveModeAsObservedOnly() {
    final AdmissionEngine engine = engine("mode: observe\nexemptions:\n  players: [Someone]");

    final Verdict verdict = engine.decide(
        new Attempt("Other", OTHER_UUID, DATACENTER_HOST, null), Phase.LOGIN);

    assertEquals(Action.OBSERVE, verdict.action());
  }

  @Test
  void aBadExemptionEntryIsIgnoredWithAWarningAndExemptsNobody() throws IOException {
    final java.util.List<String> warnings = new java.util.ArrayList<>();

    final AdmissionConfig parsed = AdmissionConfig.parseYaml(new StringReader("""
        exemptions:
          players:
            - uuid: not-a-uuid
            - {}
          networks: ['10.0.0.0/99']
        """), warnings::add);

    assertEquals(3, warnings.size());
    assertEquals(Action.REFUSE, new AdmissionEngine(parsed, LISTS)
        .decide(java("Steve", DATACENTER_HOST), Phase.PRE_LOGIN).action());
  }
}
