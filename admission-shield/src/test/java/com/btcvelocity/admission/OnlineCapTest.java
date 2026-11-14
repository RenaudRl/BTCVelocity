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
import java.net.InetAddress;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The cap on accounts online at once on one address: off by default, never applied to a declared
 * household, never to Geyser's address, and a Bedrock player counts on its real address like any
 * Java account of the same home.
 */
class OnlineCapTest {

  private static final String CAP = """
      links:
        enabled: true
        mode: enforce
        max-online-per-address: %d
        online-mode: %s
        database:
          jdbc-url: jdbc:postgresql://db.invalid:5432/sentinel
      """;

  private static final InetAddress HOME = InetAddress.ofLiteral("198.51.100.7");
  private static final InetAddress GEYSER = InetAddress.ofLiteral("198.51.100.50");
  private static final UUID BROTHER = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
  private static final UUID SISTER = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");
  private static final UUID COUSIN = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000003");
  private static final UUID NEWCOMER = UUID.fromString("cccccccc-0000-0000-0000-000000000001");
  private static final UUID BEDROCK = new UUID(0L, 2535400000000001L);
  private static final UUID BEDROCK_TWO = new UUID(0L, 2535400000000002L);

  private final ManualClock clock = new ManualClock();
  private final MemoryLinkStore store = new MemoryLinkStore();
  private final RecordingLogger logger = new RecordingLogger();

  private AccountLinks links(final int cap, final String onlineMode, final String extra) {
    final AdmissionConfig config = AccountLinkRuleTest.config(extra
        + CAP.formatted(cap, onlineMode));
    return new AccountLinks(new AdmissionEngine(config, address -> Optional.empty()),
        config.links(), LinkKeyTest.key(LinkKeyTest.TEST_KEY), this.store,
        (accounts, now) -> Map.of(), Runnable::run, Runnable::run, this.clock,
        this.logger.logger());
  }

  private AccountLinks links(final int cap, final String onlineMode) {
    return links(cap, onlineMode, "");
  }

  private static Attempt java(final UUID uuid, final InetAddress from) {
    return Attempt.claimed("Player", from).authenticated("Player", uuid, false, null);
  }

  /** A Floodgate player of the Geyser listener, with the real address Floodgate recorded. */
  private static Attempt bedrock(final UUID uuid, final InetAddress real) {
    return Attempt.claimed(".Player", GEYSER, true).authenticated(".Player", uuid, true, real);
  }

  private Object online(final AccountLinks links, final Attempt attempt) {
    final Object session = new Object();
    links.recordAccepted(attempt, session);
    return session;
  }

  private static Optional<Verdict> judge(final AccountLinks links, final Attempt attempt) {
    return links.judge(attempt).join();
  }

  @Test
  void anExtraAccountOnOneAddressIsRefusedInEnforceWithItsOwnCode() {
    final AccountLinks links = links(2, "enforce");
    online(links, java(BROTHER, HOME));
    assertEquals(Optional.empty(), judge(links, java(SISTER, HOME)), "one other online: fine");
    online(links, java(SISTER, HOME));

    final Verdict verdict = judge(links, java(NEWCOMER, HOME)).orElseThrow();

    assertEquals(Action.REFUSE, verdict.action());
    assertEquals(Basis.ONLINE_PER_ADDRESS, verdict.basis());
    assertEquals("ADM-ALT-MAX", verdict.causeCode());
    assertEquals(2, verdict.links().accountsOnAddress());
  }

  @Test
  void theCapIsOnlyObservedInObserveModeAndWhenTheModuleIsInObserve() {
    final AccountLinks observing = links(1, "observe");
    online(observing, java(BROTHER, HOME));
    assertEquals(Action.OBSERVE, judge(observing, java(NEWCOMER, HOME)).orElseThrow().action());

    final AdmissionConfig held = AccountLinkRuleTest.config("mode: observe\n" + CAP.formatted(1,
        "enforce"));
    assertEquals(AdmissionConfig.Mode.OBSERVE, held.onlineMode());
    assertEquals(AdmissionConfig.Mode.ENFORCE, AccountLinkRuleTest.config(CAP.formatted(1,
        "enforce")).onlineMode());
  }

  @Test
  void theCapIsOffByDefaultWhateverTheNumberOfAccounts() {
    final AccountLinks links = links(0, "enforce");
    // Four: more than five distinct accounts in 30 days would trip the staff signal, a different rule.
    for (int i = 1; i <= 4; i++) {
      online(links, java(new UUID(7L, i), HOME));
    }

    assertEquals(Optional.empty(), judge(links, java(NEWCOMER, HOME)));
    assertEquals(0, AccountLinkRuleTest.config(AccountLinkRuleTest.ON.formatted("enforce"))
        .links().maxOnlinePerAddress());
    assertEquals(AdmissionConfig.Mode.OBSERVE, AccountLinkRuleTest.config(
        AccountLinkRuleTest.ON.formatted("enforce")).links().onlineMode());
  }

  @Test
  void aDeclaredHouseholdDoesNotCountAgainstItself() throws SQLException {
    final AccountLinks links = links(1, "enforce");
    this.store.declareHousehold(BROTHER, SISTER, "console", "fratrie", this.clock.instant());
    online(links, java(BROTHER, HOME));

    assertEquals(Optional.empty(), judge(links, java(SISTER, HOME)), "the sister plays too");
    assertEquals(Action.REFUSE, judge(links, java(NEWCOMER, HOME)).orElseThrow().action(),
        "a stranger does not ride on the household");
  }

  @Test
  void leavingFreesTheSlotButAnEarlierSessionEndingLateDoesNot() {
    final AccountLinks links = links(1, "enforce");
    final Object first = online(links, java(BROTHER, HOME));
    assertEquals(Action.REFUSE, judge(links, java(NEWCOMER, HOME)).orElseThrow().action());

    final Object second = online(links, java(BROTHER, HOME));
    links.sessionEnded(BROTHER, first);
    assertEquals(Action.REFUSE, judge(links, java(NEWCOMER, HOME)).orElseThrow().action(),
        "the old session ending after the new one began leaves the account online");

    links.sessionEnded(BROTHER, second);
    assertEquals(Optional.empty(), judge(links, java(NEWCOMER, HOME)));
  }

  @Test
  void anAccountIsNeverCountedAgainstItself() {
    final AccountLinks links = links(1, "enforce");
    online(links, java(BROTHER, HOME));

    assertEquals(Optional.empty(), judge(links, java(BROTHER, HOME)), "a second login of the same");
  }

  @Test
  void bedrockPlayersBehindGeyserAreNeverCountedOnGeyserAddress() {
    final AccountLinks links = links(1, "enforce", "geyser-addresses: [198.51.100.50]\n");
    // Floodgate recorded no real address: Geyser's own one links nobody.
    online(links, bedrock(BEDROCK, null));
    online(links, bedrock(BEDROCK_TWO, null));

    assertEquals(Optional.empty(), judge(links, bedrock(new UUID(0L, 2535400000000003L), null)));
    assertEquals(Optional.empty(), judge(links, java(NEWCOMER, GEYSER)),
        "nor a client that shows Geyser's address");
  }

  @Test
  void aFloodgatePlayerIsNeverJudgedOnTheConnectionAddressEvenWhenGeyserIsNotListed() {
    // No geyser-addresses, not the geyser listener: a misconfiguration the module must survive.
    final AccountLinks links = links(2, "enforce");
    online(links, java(BROTHER, GEYSER));
    final Attempt floodgate = Attempt.claimed(".Player", GEYSER)
        .authenticated(".Player", BEDROCK, true, null);

    online(links, floodgate);

    assertEquals(Optional.empty(), judge(links, floodgate), "nothing to judge it on");
    assertEquals(Optional.empty(), judge(links, java(NEWCOMER, GEYSER)),
        "the Floodgate player is not one of the two online on that address");
  }

  @Test
  void aBedrockPlayerCountsOnItsRealAddressWithTheJavaAccountsOfTheSameHome() {
    final AccountLinks links = links(1, "enforce", "geyser-addresses: [198.51.100.50]\n");
    online(links, java(BROTHER, HOME));

    final Verdict bedrockAlt = judge(links, bedrock(BEDROCK, HOME)).orElseThrow();
    assertEquals(Action.REFUSE, bedrockAlt.action());
    assertEquals(Basis.ONLINE_PER_ADDRESS, bedrockAlt.basis());
    assertEquals(Verdict.Origin.GEYSER_FLOODGATE, bedrockAlt.origin());

    online(links, bedrock(BEDROCK, HOME));
    assertEquals(Action.REFUSE, judge(links, java(COUSIN, HOME)).orElseThrow().action(),
        "the Java side meets the Bedrock account too");
  }

  @Test
  void theCapNeverOutranksALinkedBanVerdictAndCountsTheOthersOnly() {
    final AdmissionConfig config = AccountLinkRuleTest.config(CAP.formatted(2, "enforce"));
    final AdmissionEngine engine = new AdmissionEngine(config, address -> Optional.empty());
    final LinkFacts none = new LinkFacts(Map.of(), Map.of(), java.util.Set.of());
    final java.time.Instant now = this.clock.instant();

    assertEquals(Basis.CLEAN, engine.decideLinks(java(NEWCOMER, HOME), HOME, none, Map.of(), now, 1)
        .basis());
    final Verdict over = engine.decideLinks(java(NEWCOMER, HOME), HOME, none, Map.of(), now, 2);
    assertEquals(Basis.ONLINE_PER_ADDRESS, over.basis());
    assertTrue(over.isJournaled());
    assertEquals(Action.REFUSE, over.action());
  }

  @Test
  void theRefusedPlayerReadsTheOnlineTextWithItsCodeAndTheStaffReadsTheCapInTheStatus() {
    final AdmissionConfig config = AccountLinkRuleTest.config(CAP.formatted(2, "enforce"));
    final AdmissionEngine engine = new AdmissionEngine(config, address -> Optional.empty());
    final Verdict refused = engine.decideLinks(java(NEWCOMER, HOME), HOME,
        new LinkFacts(Map.of(), Map.of(), java.util.Set.of()), Map.of(), this.clock.instant(), 2);

    final String text = config.refusalText(refused);

    assertTrue(text.contains("ADM-ALT-MAX") && text.contains("Too many accounts online"), text);
    assertTrue(AdmissionCommand.linkStatus(config).contains("2 (enforce, code ADM-ALT-MAX)"));
    assertTrue(AdmissionCommand.linkStatus(AccountLinkRuleTest.config(CAP.formatted(0, "observe")))
        .contains("plafond de comptes en ligne par adresse : aucun"));
  }

  @Test
  void outOfRangeCapFallsBackToOffWithAWarning() {
    final List<String> warnings = new ArrayList<>();
    final AdmissionConfig config;
    try {
      config = AdmissionConfig.parseYaml(new java.io.StringReader(CAP.formatted(500, "loud")),
          warnings::add);
    } catch (java.io.IOException malformed) {
      throw new IllegalStateException(malformed);
    }

    assertEquals(0, config.links().maxOnlinePerAddress());
    assertEquals(AdmissionConfig.Mode.OBSERVE, config.links().onlineMode());
    assertEquals(2, warnings.stream().filter(w -> w.contains("online")).count());
  }
}
