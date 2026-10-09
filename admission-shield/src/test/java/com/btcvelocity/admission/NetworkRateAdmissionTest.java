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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.btcvelocity.admission.Verdict.Action;
import com.btcvelocity.admission.Verdict.Basis;
import java.io.IOException;
import java.io.StringReader;
import java.net.InetAddress;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The bound on attempts per network, as the engine applies it to a connection. */
class NetworkRateAdmissionTest {

  private static final UUID STAFF_UUID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID OTHER_UUID = UUID.fromString("22222222-2222-2222-2222-222222222222");

  /** Burst of 2 and a refill of one a minute, so that a test never refills by accident. */
  private static final String ENFORCING = """
      network-rate:
        mode: enforce
        attempts: 1
        window-seconds: 60
        burst: 2
      """;

  private static final String STAFF_EXEMPTION = """
      exemptions:
        players:
          - name: StaffMember
            uuid: 11111111-1111-1111-1111-111111111111
      """;

  private static final CidrSet LISTED = listOf("198.51.100.0/24");

  private final ManualClock clock = new ManualClock();

  private static CidrSet listOf(final String... entries) {
    final CidrSet.Builder builder = CidrSet.builder();
    for (String entry : entries) {
      builder.add(entry);
    }
    return builder.build();
  }

  private static InetAddress ip(final String literal) {
    return InetAddress.ofLiteral(literal);
  }

  private AdmissionEngine engine(final String yaml) {
    return engine(yaml, address -> Optional.empty());
  }

  private AdmissionEngine engine(final String yaml, final IpClassifier lists) {
    try {
      return new AdmissionEngine(
          AdmissionConfig.parseYaml(new StringReader(yaml), warning -> { }), lists,
          address -> Optional.empty(), this.clock);
    } catch (IOException malformed) {
      throw new IllegalStateException(malformed);
    }
  }

  private static Verdict attempt(final AdmissionEngine engine, final String name,
      final String from) {
    return engine.decidePreLogin(Attempt.claimed(name, ip(from)));
  }

  private static Attempt login(final String name, final UUID uuid, final String from) {
    return Attempt.claimed(name, ip(from)).authenticated(name, uuid, false, null);
  }

  // --- threshold ---------------------------------------------------------------------------

  @Test
  void anAttemptWithinTheLimitIsAdmittedAndTheOneOverItIsRefusedWithItsCode() {
    final AdmissionEngine engine = engine(ENFORCING);

    final Verdict first = attempt(engine, "Steve", "203.0.113.10");
    final Verdict second = attempt(engine, "Alex", "203.0.113.11");
    final Verdict third = attempt(engine, "Bot", "203.0.113.12");

    assertEquals(Action.ADMIT, first.action());
    assertEquals(Action.ADMIT, second.action());
    assertEquals(Action.REFUSE, third.action());
    assertEquals(Basis.RATE_NETWORK, third.basis());
    assertEquals("ADM-RATE-NET", third.causeCode());
    assertEquals(ip("203.0.113.12"), third.judged(), "the address the staff will search for");
    assertTrue(third.isJournaled());
  }

  @Test
  void aNetworkBelowTheLimitIsNeverTouched() {
    final AdmissionEngine engine = engine(ENFORCING);

    assertEquals(Action.ADMIT, attempt(engine, "Steve", "203.0.113.10").action());
    assertEquals(Action.ADMIT, attempt(engine, "Alex", "203.0.113.200").action());
    assertEquals(Action.ADMIT, attempt(engine, "Bob", "203.0.114.1").action(),
        "another /24 has its own bucket");
  }

  @Test
  void theDefaultIsObserveSoNothingIsRefusedBeforeTheLimitIsMeasured() {
    final AdmissionEngine engine = engine("");

    Verdict last = null;
    for (int i = 0; i < 21; i++) {
      last = attempt(engine, "Bot" + i, "203.0.113." + (i + 1));
    }

    assertEquals(Action.OBSERVE, last.action(), "the 21st attempt is over the default burst of 20");
    assertEquals(Basis.RATE_NETWORK, last.basis());
    assertTrue(last.isJournaled());
  }

  @Test
  void observeNeverRefusesWhateverTheTraffic() {
    final AdmissionEngine engine = engine(ENFORCING.replace("enforce", "observe"));

    for (int i = 0; i < 50; i++) {
      final Action action = attempt(engine, "Bot" + i, "203.0.113.50").action();
      assertTrue(action == Action.ADMIT || action == Action.OBSERVE,
          "attempt " + i + " was " + action);
    }
    assertEquals(Action.OBSERVE, attempt(engine, "Bot", "203.0.113.50").action());
  }

  @Test
  void theModuleWideObserveHoldsBackAnEnforcingBound() {
    final AdmissionEngine engine = engine("mode: observe\n" + ENFORCING);

    attempt(engine, "A", "203.0.113.1");
    attempt(engine, "B", "203.0.113.1");

    assertEquals(Action.OBSERVE, attempt(engine, "C", "203.0.113.1").action());
  }

  @Test
  void theBoundCanBeSwitchedOff() {
    final AdmissionEngine engine = engine(ENFORCING + "  enabled: false\n");

    for (int i = 0; i < 30; i++) {
      assertEquals(Action.ADMIT, attempt(engine, "Bot" + i, "203.0.113.50").action());
    }
  }

  // --- which network -----------------------------------------------------------------------

  @Test
  void aTunnelledAddressIsCountedOnTheIpv4NetworkItCarries() {
    final AdmissionEngine engine = engine(ENFORCING);
    attempt(engine, "A", "203.0.113.1");
    attempt(engine, "B", "::ffff:203.0.113.2");

    // 6to4 of 203.0.113.9: 2002:cb00:7109::
    final Verdict viaSixToFour = attempt(engine, "C", "2002:cb00:7109::1");

    assertEquals(Action.REFUSE, viaSixToFour.action(),
        "one /24 reached through IPv4, IPv4-mapped and 6to4 is one network");
  }

  @Test
  void hostsOfOneIpv6Slash48ShareTheBucket() {
    final AdmissionEngine engine = engine(ENFORCING);
    attempt(engine, "A", "2001:db8:aaaa:1::1");
    attempt(engine, "B", "2001:db8:aaaa:2::1");

    assertEquals(Action.REFUSE, attempt(engine, "C", "2001:db8:aaaa:3::1").action());
    assertEquals(Action.ADMIT, attempt(engine, "D", "2001:db8:aaab:1::1").action());
  }

  @Test
  void anAddressThatIsNotPublicIsNeverCounted() {
    final AdmissionEngine engine = engine(ENFORCING);

    for (String inside : new String[] {"127.0.0.1", "10.1.2.3", "192.168.1.5", "100.64.7.7",
        "fd00::1"}) {
      for (int i = 0; i < 10; i++) {
        assertEquals(Action.ADMIT, attempt(engine, "Bot" + i, inside).action(), inside);
      }
    }
    assertEquals(0, engine.rateLimiter().trackedNetworks());
  }

  // --- who is spared -----------------------------------------------------------------------

  @Test
  void aGeyserAddressIsNeverCountedEvenWhenFloodedAndItsNeighbourIs() {
    final AdmissionEngine engine = engine("geyser-addresses: ['198.51.100.200']\n" + ENFORCING);

    for (int i = 0; i < 40; i++) {
      final Verdict verdict = attempt(engine, ".Bedrock" + i, "198.51.100.200");
      assertEquals(Basis.GEYSER_ORIGIN, verdict.basis(), "left to the login, as before: " + i);
      assertEquals(Action.DEFER, verdict.action());
    }
    assertEquals(0, engine.rateLimiter().trackedNetworks(), "not even followed");
    attempt(engine, "A", "198.51.100.7");
    attempt(engine, "B", "198.51.100.8");
    assertEquals(Action.REFUSE, attempt(engine, "C", "198.51.100.9").action(),
        "the Geyser address is spared, not its /24");
  }

  @Test
  void aGeyserNetworkWrittenAsCidrIsNeverCounted() {
    final AdmissionEngine engine = engine("geyser-addresses: ['198.51.100.0/24']\n" + ENFORCING);

    for (int i = 0; i < 40; i++) {
      assertEquals(Action.DEFER, attempt(engine, "B" + i, "198.51.100." + (i + 1)).action());
    }
    assertEquals(0, engine.rateLimiter().trackedNetworks());
  }

  @Test
  void anExemptedNetworkIsNeverCounted() {
    final AdmissionEngine engine = engine(
        "exemptions:\n  networks:\n    - {network: 203.0.113.0/24, expires: 2099-12-31}\n"
            + ENFORCING);

    for (int i = 0; i < 40; i++) {
      assertEquals(Action.ADMIT, attempt(engine, "Bot" + i, "203.0.113.77").action());
    }
    assertEquals(0, engine.rateLimiter().trackedNetworks());
  }

  @Test
  void aNameSomeExemptionCarriesDefersTheDecisionToTheLoginInsteadOfExcusingTheAttempt() {
    final AdmissionEngine engine = engine(STAFF_EXEMPTION + ENFORCING);
    attempt(engine, "Bot1", "203.0.113.1");
    attempt(engine, "Bot2", "203.0.113.2");

    final Verdict typedByAnyone = attempt(engine, "StaffMember", "203.0.113.3");
    final Verdict stranger = attempt(engine, "Steve", "203.0.113.4");

    assertEquals(Action.DEFER, typedByAnyone.action(),
        "a typed name proves nothing: neither refused nor excused yet");
    assertEquals(Basis.AWAITING_IDENTITY, typedByAnyone.basis());
    assertEquals(Action.REFUSE, stranger.action());
  }

  @Test
  void theAuthenticatedExemptedPlayerEntersAndAnImpostorWithTheSameNameDoesNot() {
    final AdmissionEngine engine = engine(STAFF_EXEMPTION + ENFORCING);
    attempt(engine, "Bot1", "203.0.113.1");
    attempt(engine, "Bot2", "203.0.113.2");
    attempt(engine, "StaffMember", "203.0.113.3");

    final Optional<Verdict> staff =
        engine.decideAtLogin(login("StaffMember", STAFF_UUID, "203.0.113.3"));
    final Optional<Verdict> impostor =
        engine.decideAtLogin(login("StaffMember", OTHER_UUID, "203.0.113.3"));

    assertTrue(staff.isEmpty(), "name and UUID match an exemption");
    assertEquals(Action.REFUSE, impostor.orElseThrow().action());
    assertEquals(Basis.RATE_NETWORK, impostor.orElseThrow().basis());
  }

  @Test
  void aLoginDoesNotSpendTheBucketAgain() {
    final AdmissionEngine engine = engine(STAFF_EXEMPTION + ENFORCING);
    attempt(engine, "StaffMember", "203.0.113.3");

    for (int i = 0; i < 10; i++) {
      assertTrue(engine.decideAtLogin(login("StaffMember", OTHER_UUID, "203.0.113.3")).isEmpty(),
          "one token left: the network is not over its limit");
    }
  }

  @Test
  void inObserveModeNothingIsDeferred() {
    final AdmissionEngine engine =
        engine(STAFF_EXEMPTION + ENFORCING.replace("enforce", "observe"));
    attempt(engine, "Bot1", "203.0.113.1");
    attempt(engine, "Bot2", "203.0.113.2");

    assertEquals(Action.OBSERVE, attempt(engine, "StaffMember", "203.0.113.3").action());
    assertTrue(engine.decideAtLogin(login("Steve", OTHER_UUID, "203.0.113.3")).isEmpty());
  }

  // --- with the lists ----------------------------------------------------------------------

  @Test
  void aListedAddressKeepsItsListCodeWhenItIsAlsoOverTheRateLimit() {
    final IpClassifier lists = address -> LISTED.contains(address)
        ? Optional.of(new IpClassifier.Match(IpClass.DATACENTER, "dc-list")) : Optional.empty();
    final AdmissionEngine engine = engine(ENFORCING, lists);
    attempt(engine, "A", "198.51.100.1");
    attempt(engine, "B", "198.51.100.2");

    final Verdict verdict = attempt(engine, "C", "198.51.100.3");

    assertEquals(Action.REFUSE, verdict.action());
    assertEquals(Basis.LISTED, verdict.basis());
    assertEquals("ADM-VPN-DC", verdict.causeCode());
  }

  @Test
  void aCleanAddressIsStillAdmittedByTheListsAndOnlyTheRateCanRefuseIt() {
    final AdmissionEngine engine = engine(ENFORCING);

    assertEquals(Basis.CLEAN, attempt(engine, "A", "203.0.113.1").basis());
    assertEquals(Action.ADMIT, engine.decide(Attempt.claimed("B", ip("203.0.113.1")),
        Phase.PRE_LOGIN).action(), "decide() is the lists alone and spends nothing");
  }
}
