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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.btcvelocity.admission.Verdict.Action;
import com.btcvelocity.admission.Verdict.Basis;
import com.btcvelocity.admission.Verdict.Origin;
import java.io.IOException;
import java.io.StringReader;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdmissionEngineTest {

  private static final InetAddress GEYSER = ip("127.0.0.1");
  private static final String WITH_GEYSER = "geyser-addresses: ['127.0.0.1']\n";
  private static final InetAddress DATACENTER_HOST = ip("203.0.113.50");
  private static final InetAddress TOR_EXIT = ip("185.220.101.9");
  private static final InetAddress HOME = ip("198.51.100.77");
  private static final UUID STAFF_UUID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID OTHER_UUID = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String STAFF_EXEMPTION = """
      exemptions:
        players:
          - name: StaffMember
            uuid: 11111111-1111-1111-1111-111111111111
      """;

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
    return Attempt.claimed(name, from);
  }

  private static Attempt login(final String name, final UUID uuid, final InetAddress from) {
    return Attempt.claimed(name, from).authenticated(name, uuid, false, null);
  }

  private static Attempt bedrock(final UUID uuid, final InetAddress real) {
    return Attempt.claimed(".BedrockPlayer", GEYSER)
        .authenticated(".BedrockPlayer", uuid, true, real);
  }

  @Test
  void aDatacenterAddressIsRefusedWithItsClassAndSource() {
    final Verdict verdict = engine("").decide(java("Steve", DATACENTER_HOST), Phase.PRE_LOGIN);

    assertEquals(Action.REFUSE, verdict.action());
    assertEquals(Basis.LISTED, verdict.basis());
    assertEquals(Origin.DIRECT, verdict.origin());
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
    assertFalse(verdict.isJournaled(), "an ordinary admission is not written");
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

  // --- M6: a pre-login refusal is applied again at the login ----------------------------------

  @Test
  void aPreLoginRefusalIsAppliedAgainAtTheLogin() {
    // Arrange - refused at the pre-login; a later listener then turned the refusal into an
    // admission, so the connection reaches the login
    final AdmissionEngine engine = engine("");
    assertEquals(Action.REFUSE,
        engine.decide(java("Steve", DATACENTER_HOST), Phase.PRE_LOGIN).action());

    // Act
    final Optional<Verdict> atLogin =
        engine.decideAtLogin(login("Steve", OTHER_UUID, DATACENTER_HOST));

    // Assert
    assertTrue(atLogin.isPresent(), "the login must not stay silent about a refused address");
    assertEquals(Action.REFUSE, atLogin.get().action());
    assertEquals(IpClass.DATACENTER, atLogin.get().ipClass());
  }

  @Test
  void aConnectionThePreLoginAlreadySettledIsNotJudgedTwice() {
    final AdmissionEngine enforce = engine("");
    final AdmissionEngine observe = engine("mode: observe");

    assertTrue(enforce.decideAtLogin(login("Steve", OTHER_UUID, HOME)).isEmpty(),
        "clean at the pre-login: nothing to add");
    assertTrue(observe.decideAtLogin(login("Steve", OTHER_UUID, DATACENTER_HOST)).isEmpty(),
        "observed at the pre-login: journaled once, not twice");
  }

  @Test
  void whatThePreLoginDeferredIsDecidedAtTheLogin() {
    final AdmissionEngine engine = engine(STAFF_EXEMPTION);

    final Optional<Verdict> staff =
        engine.decideAtLogin(login("StaffMember", STAFF_UUID, DATACENTER_HOST));
    final Optional<Verdict> impostor =
        engine.decideAtLogin(login("StaffMember", OTHER_UUID, DATACENTER_HOST));

    assertEquals(Basis.EXEMPT_PLAYER, staff.orElseThrow().basis());
    assertEquals(Action.REFUSE, impostor.orElseThrow().action());
  }

  // --- Geyser and Floodgate (M8) --------------------------------------------------------------

  @Test
  void noAddressIsGeysersByDefault() {
    assertTrue(config("").geyserAddresses().isEmpty());
    assertEquals(Action.REFUSE, engine("").decide(java("B", GEYSER), Phase.PRE_LOGIN).action(),
        "with no Geyser address configured, 127.0.0.1 is judged like any address");
  }

  @Test
  void aFloodgatePlayerFromGeysersAddressIsNeverJudgedOnItEvenWhenAListNamesIt() {
    // Arrange - the fixture list DOES contain 127.0.0.1: judging it would refuse every Bedrock player
    final AdmissionEngine engine = engine(WITH_GEYSER);

    // Act
    final Verdict atPreLogin = engine.decide(java("BedrockPlayer", GEYSER), Phase.PRE_LOGIN);
    final Verdict atLogin = engine.decide(bedrock(OTHER_UUID, null), Phase.LOGIN);

    // Assert
    assertEquals(Action.DEFER, atPreLogin.action());
    assertEquals(Basis.GEYSER_ORIGIN, atPreLogin.basis());
    assertEquals(Origin.GEYSER_UNCONFIRMED, atPreLogin.origin());
    assertEquals(Action.ADMIT, atLogin.action());
    assertEquals(Basis.BEDROCK_IP_UNKNOWN, atLogin.basis());
    assertEquals(Origin.GEYSER_FLOODGATE, atLogin.origin());
    assertNull(atLogin.judged(), "no address was judged");
    assertEquals("bedrock, ip réelle inconnue", atLogin.basis().label());
  }

  @Test
  void aNonFloodgateConnectionFromAGeyserAddressIsFlaggedAndJudgedLikeAnyOther() {
    // Arrange - a Java client (or a forged forward) coming from the Geyser host
    final AdmissionEngine engine = engine(WITH_GEYSER);
    final AdmissionEngine unlisted = new AdmissionEngine(config(WITH_GEYSER),
        address -> Optional.empty());

    // Act
    final Verdict listed = engine.decide(login("Sneaky", OTHER_UUID, GEYSER), Phase.LOGIN);
    final Verdict clean = unlisted.decide(login("Sneaky", OTHER_UUID, GEYSER), Phase.LOGIN);

    // Assert
    assertEquals(Action.REFUSE, listed.action(), "the Geyser exception is for Floodgate players");
    assertEquals(Origin.GEYSER_ADDRESS_NOT_FLOODGATE, listed.origin());
    assertEquals(GEYSER, listed.judged());
    assertEquals(Action.ADMIT, clean.action());
    assertEquals(Basis.GEYSER_ADDRESS_NOT_FLOODGATE, clean.basis());
    assertEquals("geyser-address-not-floodgate", clean.basis().label());
    assertTrue(clean.isJournaled(), "the anomaly is written even when the address is clean");
  }

  @Test
  void aBedrockPlayerWhoseRealAddressIsADatacenterIsRefusedOnThatAddress() {
    final Verdict verdict =
        engine(WITH_GEYSER).decide(bedrock(OTHER_UUID, DATACENTER_HOST), Phase.LOGIN);

    assertEquals(Action.REFUSE, verdict.action());
    assertEquals(DATACENTER_HOST, verdict.judged());
    assertEquals(Origin.GEYSER_FLOODGATE, verdict.origin());
  }

  @Test
  void aBedrockPlayerWhoseRealAddressIsResidentialIsAdmittedOnThatAddress() {
    final Verdict verdict = engine(WITH_GEYSER).decide(bedrock(OTHER_UUID, HOME), Phase.LOGIN);

    assertEquals(Action.ADMIT, verdict.action());
    assertEquals(Basis.CLEAN, verdict.basis());
    assertEquals(HOME, verdict.judged());
  }

  @Test
  void aFloodgateAddressThatIsGeysersOwnCountsAsUnknown() {
    // Floodgate recording Geyser's own address would make every Bedrock player look the same
    final Verdict verdict = engine(WITH_GEYSER).decide(bedrock(OTHER_UUID, GEYSER), Phase.LOGIN);

    assertEquals(Action.ADMIT, verdict.action());
    assertEquals(Basis.BEDROCK_IP_UNKNOWN, verdict.basis());
  }

  @Test
  void geyserAddressesAreHonouredAsNetworks() {
    final AdmissionEngine engine = engine("geyser-addresses: ['10.9.0.0/24']");

    assertEquals(Action.DEFER, engine.decide(java("B", ip("10.9.0.12")), Phase.PRE_LOGIN).action());
    assertEquals(Action.REFUSE, engine.decide(java("B", GEYSER), Phase.PRE_LOGIN).action());
  }

  // --- exemptions (M7) ------------------------------------------------------------------------

  @Test
  void anExemptedNetworkIsAdmittedAndJournaled() {
    final String partnerOffice =
        "exemptions:\n  networks:\n    - {network: 203.0.113.0/28, expires: 2099-12-31}";
    final Verdict verdict = engine(partnerOffice)
        .decide(java("Partner", ip("203.0.113.9")), Phase.PRE_LOGIN);

    assertEquals(Action.ADMIT, verdict.action());
    assertEquals(Basis.EXEMPT_NETWORK, verdict.basis());
    assertTrue(verdict.isJournaled());
    assertEquals(Action.REFUSE, engine(partnerOffice)
        .decide(java("Partner", ip("203.0.113.99")), Phase.PRE_LOGIN).action(),
        "the exemption stops at its own boundary");
  }

  @Test
  void aNamedExemptionDefersAtPreLoginAndAdmitsOnlyOnceAuthenticated() {
    // Arrange
    final AdmissionEngine engine = engine(STAFF_EXEMPTION);

    // Act
    final Verdict claimed = engine.decide(java("staffmember", DATACENTER_HOST), Phase.PRE_LOGIN);
    final Verdict authenticated =
        engine.decide(login("StaffMember", STAFF_UUID, DATACENTER_HOST), Phase.LOGIN);
    final Verdict stranger = engine.decide(java("Stranger", DATACENTER_HOST), Phase.PRE_LOGIN);

    // Assert
    assertEquals(Action.DEFER, claimed.action(), "the name is only claimed, not proven yet");
    assertEquals(Basis.AWAITING_IDENTITY, claimed.basis());
    assertFalse(claimed.isJournaled());
    assertEquals(Action.ADMIT, authenticated.action());
    assertEquals(Basis.EXEMPT_PLAYER, authenticated.basis());
    assertEquals(Action.REFUSE, stranger.action(), "an unlisted name is refused at once");
  }

  @Test
  void anExemptionNeedsBothTheNameAndTheUuidToMatch() {
    final AdmissionEngine engine = engine(STAFF_EXEMPTION);

    assertEquals(Action.REFUSE,
        engine.decide(login("StaffMember", OTHER_UUID, DATACENTER_HOST), Phase.LOGIN).action(),
        "same name, other account: the name alone proves nothing");
    assertEquals(Action.REFUSE,
        engine.decide(login("Renamed", STAFF_UUID, DATACENTER_HOST), Phase.LOGIN).action(),
        "same account under another name: the UUID alone proves nothing either");
  }

  @Test
  void aUuidOnlyExemptionIsAConfigurationErrorAndExemptsNobody() throws IOException {
    // Arrange
    final List<String> warnings = new ArrayList<>();
    final List<String> errors = new ArrayList<>();

    // Act
    final AdmissionConfig parsed = AdmissionConfig.parseYaml(new StringReader("""
        exemptions:
          players:
            - uuid: 11111111-1111-1111-1111-111111111111
            - name: StaffMember
            - StaffMember
        """), warnings::add, errors::add);
    final AdmissionEngine engine = new AdmissionEngine(parsed, LISTS);

    // Assert
    assertEquals(3, errors.size(), "each incomplete entry is an error: " + errors);
    assertTrue(errors.getFirst().contains("a UUID alone is not an exemption"));
    assertEquals(List.of(), warnings);
    assertEquals(Action.REFUSE,
        engine.decide(login("Renamed", STAFF_UUID, DATACENTER_HOST), Phase.LOGIN).action());
    assertEquals(Action.REFUSE,
        engine.decide(java("StaffMember", DATACENTER_HOST), Phase.PRE_LOGIN).action(),
        "no valid entry names anyone, so nothing is deferred either");
  }

  @Test
  void anExemptedPlayerIsNotSparedAnotherPlayersObservation() {
    final AdmissionEngine engine = engine("mode: observe\n" + STAFF_EXEMPTION);

    final Verdict verdict =
        engine.decide(login("Other", OTHER_UUID, DATACENTER_HOST), Phase.LOGIN);

    assertEquals(Action.OBSERVE, verdict.action());
  }

  @Test
  void aBadExemptionEntryIsReportedAndExemptsNobody() throws IOException {
    final List<String> reports = new ArrayList<>();

    final AdmissionConfig parsed = AdmissionConfig.parseYaml(new StringReader("""
        exemptions:
          players:
            - uuid: not-a-uuid
            - {}
          networks: ['10.0.0.0/99']
        """), reports::add);

    assertEquals(3, reports.size());
    assertEquals(Action.REFUSE, new AdmissionEngine(parsed, LISTS)
        .decide(java("Steve", DATACENTER_HOST), Phase.PRE_LOGIN).action());
  }
}
