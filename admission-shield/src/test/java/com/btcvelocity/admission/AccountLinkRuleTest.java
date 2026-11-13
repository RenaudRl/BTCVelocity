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
import java.io.IOException;
import java.io.StringReader;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The pure account-link rule: {@link AdmissionEngine#decideLinks} and its helpers. */
class AccountLinkRuleTest {

  static final String ON = """
      links:
        enabled: true
        mode: %s
        database:
          jdbc-url: jdbc:postgresql://db.invalid:5432/sentinel
      """;

  private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");
  private static final InetAddress HOME = InetAddress.ofLiteral("198.51.100.7");
  private static final UUID BROTHER = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
  private static final UUID SISTER = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");
  private static final UUID BANNED = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
  private static final UUID NEWCOMER = UUID.fromString("cccccccc-0000-0000-0000-000000000001");
  private static final Map<UUID, Long> BANNED_ACTIVE = Map.of(BANNED, 42L);

  static AdmissionConfig config(final String yaml) {
    try {
      return AdmissionConfig.parseYaml(new StringReader(yaml), warning -> { });
    } catch (IOException malformed) {
      throw new IllegalStateException(malformed);
    }
  }

  private static AdmissionEngine engine(final String mode) {
    return new AdmissionEngine(config(ON.formatted(mode)), address -> Optional.empty());
  }

  private static Attempt login(final UUID uuid) {
    return Attempt.claimed("Player", HOME).authenticated("Player", uuid, false, null);
  }

  private static LinkFacts sameAddress(final Map<UUID, Instant> seen) {
    return new LinkFacts(seen, seen, Set.of());
  }

  private static Instant daysAgo(final int days) {
    return NOW.minus(Duration.ofDays(days));
  }

  @Test
  void siblingsOfOneHomeWithoutBanAreBothAdmittedSilently() {
    final AdmissionEngine engine = engine("enforce");

    final Verdict sister = engine.decideLinks(login(SISTER), HOME,
        sameAddress(Map.of(BROTHER, daysAgo(0))), Map.of(), NOW);

    assertEquals(List.of(new Link(BROTHER, Link.Strength.STRONG)),
        engine.linksOf(SISTER, sameAddress(Map.of(BROTHER, daysAgo(0))), NOW));
    assertEquals(Action.ADMIT, sister.action());
    assertEquals(Basis.CLEAN, sister.basis());
    assertFalse(sister.isJournaled());
  }

  @Test
  void declaredHouseholdWithOneBannedLetsTheOtherInUnsignalled() {
    final LinkFacts facts = new LinkFacts(Map.of(BANNED, daysAgo(1)), Map.of(BANNED, daysAgo(1)),
        Set.of(SISTER, BANNED));

    final Verdict sister = engine("enforce").decideLinks(login(SISTER), HOME, facts,
        BANNED_ACTIVE, NOW);

    assertEquals(Action.ADMIT, sister.action());
    assertFalse(sister.isJournaled(), "no routine alert inside a household");
  }

  @Test
  void bannedPlayerNewAccountSameAddressRefusedInEnforce() {
    final Verdict verdict = engine("enforce").decideLinks(login(NEWCOMER), HOME,
        sameAddress(Map.of(BANNED, daysAgo(2))), BANNED_ACTIVE, NOW);

    assertEquals(Action.REFUSE, verdict.action());
    assertEquals(Basis.LINKED_BAN, verdict.basis());
    assertEquals(Verdict.LINKED_BAN_CODE, verdict.causeCode());
    assertEquals(new LinkEvidence(Link.Strength.STRONG, BANNED, 42L, 2), verdict.links());
  }

  @Test
  void bannedPlayerNewAccountSameAddressOnlyObservedInObserve() {
    final Verdict observe = engine("observe").decideLinks(login(NEWCOMER), HOME,
        sameAddress(Map.of(BANNED, daysAgo(2))), BANNED_ACTIVE, NOW);
    final Verdict moduleObserve = new AdmissionEngine(
        config("mode: observe\n" + ON.formatted("enforce")), address -> Optional.empty())
        .decideLinks(login(NEWCOMER), HOME, sameAddress(Map.of(BANNED, daysAgo(2))),
            BANNED_ACTIVE, NOW);

    assertEquals(Action.OBSERVE, observe.action());
    assertEquals(Basis.LINKED_BAN, observe.basis());
    assertEquals(Action.OBSERVE, moduleObserve.action());
  }

  @Test
  void strangersBehindSharedAddressNeverRefused() {
    final Map<UUID, Instant> school = new HashMap<>();
    school.put(BANNED, daysAgo(1));
    for (int i = 0; i < 3; i++) {
      school.put(UUID.randomUUID(), daysAgo(1));
    }

    final AdmissionEngine engine = engine("enforce");
    final Verdict verdict = engine.decideLinks(login(NEWCOMER), HOME, sameAddress(school),
        BANNED_ACTIVE, NOW);

    assertTrue(engine.linksOf(NEWCOMER, sameAddress(school), NOW).stream()
        .noneMatch(link -> link.strength() == Link.Strength.STRONG), "5 accounts: shared");
    assertEquals(Action.ADMIT, verdict.action());
    assertEquals(Basis.LINKED_ACCOUNT, verdict.basis());
  }

  @Test
  void weakLinkOnlyAlerts() {
    final LinkFacts neighbour = new LinkFacts(Map.of(), Map.of(BANNED, daysAgo(1)), Set.of());
    final LinkFacts longAgo = sameAddress(Map.of(BANNED, daysAgo(20)));

    for (LinkFacts facts : List.of(neighbour, longAgo)) {
      final Verdict verdict = engine("enforce").decideLinks(login(NEWCOMER), HOME, facts,
          BANNED_ACTIVE, NOW);

      assertEquals(Action.ADMIT, verdict.action(), facts.toString());
      assertEquals(Basis.LINKED_ACCOUNT, verdict.basis());
      assertEquals(Link.Strength.WEAK, verdict.links().strength());
      assertTrue(verdict.isJournaled());
    }
  }

  @Test
  void noTransitivityOnlyTheAccountsOfItsOwnAddressAreLinked() {
    // C shares an address with B only; B once shared another address with the banned A. The
    // store also returns C's own sightings, which are never a link.
    final List<Link> links = engine("enforce").linksOf(NEWCOMER,
        sameAddress(Map.of(SISTER, daysAgo(1), NEWCOMER, daysAgo(2))), NOW);

    assertEquals(List.of(new Link(SISTER, Link.Strength.STRONG)), links);
  }

  @Test
  void manyAccountsOnOneAddressAreSignalledButAHouseholdCountsOnce() {
    final Map<UUID, Instant> crowd = new HashMap<>();
    for (int i = 0; i < 5; i++) {
      crowd.put(UUID.randomUUID(), daysAgo(3));
    }
    final AdmissionEngine engine = engine("enforce");

    final Verdict crowded = engine.decideLinks(login(NEWCOMER), HOME, sameAddress(crowd),
        Map.of(), NOW);
    final Verdict family = engine.decideLinks(login(NEWCOMER), HOME,
        new LinkFacts(crowd, crowd, crowd.keySet()), Map.of(), NOW);

    assertEquals(Action.ADMIT, crowded.action());
    assertEquals(Basis.ACCOUNTS_PER_ADDRESS, crowded.basis());
    assertEquals(6, crowded.links().accountsOnAddress());
    assertEquals(Basis.CLEAN, family.basis());
  }

  @Test
  void theRuleIsSilentWhenOffForBedrockWithoutRealAddressAndForExemptions() {
    final AdmissionEngine on = engine("enforce");
    final UUID staff = UUID.fromString("11111111-1111-1111-1111-111111111111");
    final AdmissionEngine exempting = new AdmissionEngine(config(ON.formatted("enforce") + """
        exemptions:
          players:
            - name: StaffMember
              uuid: 11111111-1111-1111-1111-111111111111
        """), address -> Optional.empty());

    assertEquals(Optional.of(HOME), on.linkSubject(login(NEWCOMER)));
    assertTrue(new AdmissionEngine(config(""), address -> Optional.empty())
        .linkSubject(login(NEWCOMER)).isEmpty(), "off by default");
    final AdmissionEngine withGeyser = new AdmissionEngine(
        config("geyser-addresses: ['198.51.100.9']\n" + ON.formatted("enforce")),
        address -> Optional.empty());
    final InetAddress geyser = InetAddress.ofLiteral("198.51.100.9");
    assertTrue(withGeyser.linkSubject(Attempt.claimed(".Bedrock", geyser)
        .authenticated(".Bedrock", NEWCOMER, true, null)).isEmpty(), "bedrock, real unknown");
    assertEquals(Optional.of(HOME), withGeyser.linkSubject(Attempt.claimed(".Bedrock", geyser)
        .authenticated(".Bedrock", NEWCOMER, true, HOME)), "bedrock, judged on its real one");
    assertTrue(on.linkSubject(Attempt.claimed("Lan", InetAddress.ofLiteral("192.168.1.2"))
        .authenticated("Lan", NEWCOMER, false, null)).isEmpty(), "not public");
    assertTrue(exempting.linkSubject(Attempt.claimed("StaffMember", HOME)
        .authenticated("StaffMember", staff, false, null)).isEmpty(), "exempted player");
  }
}
