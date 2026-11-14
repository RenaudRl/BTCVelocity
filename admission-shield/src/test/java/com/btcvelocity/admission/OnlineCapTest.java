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
import java.util.concurrent.Executor;
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
  private volatile boolean storeDown;
  private final MemoryLinkStore store = new MemoryLinkStore() {
    @Override
    public synchronized LinkFacts factsFor(final Sighting entering,
        final java.time.Instant addressSince, final java.time.Instant networkSince)
        throws SQLException {
      if (OnlineCapTest.this.storeDown) {
        throw new SQLException("connection refused");
      }
      return super.factsFor(entering, addressSince, networkSince);
    }
  };
  private final RecordingLogger logger = new RecordingLogger();

  private AccountLinks links(final int cap, final String onlineMode, final String extra,
      final Executor logins) {
    final AdmissionConfig config = AccountLinkRuleTest.config(extra
        + CAP.formatted(cap, onlineMode));
    return new AccountLinks(new AdmissionEngine(config, address -> Optional.empty()),
        config.links(), LinkKeyTest.key(LinkKeyTest.TEST_KEY), this.store,
        (accounts, now) -> Map.of(), logins, Runnable::run, this.clock, this.logger.logger());
  }

  private AccountLinks links(final int cap, final String onlineMode, final String extra) {
    return links(cap, onlineMode, extra, Runnable::run);
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

  /**
   * A login that is judged and goes in: its place is taken by the judgement itself, no server has
   * to be reached for it to count. Returns the session.
   */
  private Object online(final AccountLinks links, final Attempt attempt) {
    final Object session = new Object();
    assertEquals(Optional.empty(), links.judge(attempt, session).join(), "admitted without a word");
    return session;
  }

  private static Optional<Verdict> judge(final AccountLinks links, final Attempt attempt) {
    return links.judge(attempt, new Object()).join();
  }

  @Test
  void anExtraAccountOnOneAddressIsRefusedInEnforceWithItsOwnCode() {
    final AccountLinks links = links(2, "enforce");
    online(links, java(BROTHER, HOME));
    online(links, java(SISTER, HOME));

    final Verdict verdict = judge(links, java(NEWCOMER, HOME)).orElseThrow();

    assertEquals(Action.REFUSE, verdict.action());
    assertEquals(Basis.ONLINE_PER_ADDRESS, verdict.basis());
    assertEquals("ADM-ALT-MAX", verdict.causeCode());
    assertEquals(2, verdict.links().accountsOnAddress());
  }

  @Test
  void theCapIsOnlyObservedInObserveModeAndWhenTheModuleIsInObserve() {
    final AccountLinks observing = links(2, "observe");
    online(observing, java(BROTHER, HOME));
    online(observing, java(SISTER, HOME));
    assertEquals(Action.OBSERVE, judge(observing, java(NEWCOMER, HOME)).orElseThrow().action());

    final AdmissionConfig held = AccountLinkRuleTest.config("mode: observe\n" + CAP.formatted(2,
        "enforce"));
    assertEquals(AdmissionConfig.Mode.OBSERVE, held.onlineMode());
    assertEquals(AdmissionConfig.Mode.ENFORCE, AccountLinkRuleTest.config(CAP.formatted(2,
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
    final AccountLinks links = links(2, "enforce");
    this.store.declareHousehold(BROTHER, SISTER, "console", "fratrie", this.clock.instant());
    online(links, java(BROTHER, HOME));
    online(links, java(COUSIN, HOME));

    assertEquals(Optional.empty(), judge(links, java(SISTER, HOME)),
        "the brother is her household: only the cousin counts, one under two");
    assertEquals(Action.REFUSE, judge(links, java(NEWCOMER, HOME)).orElseThrow().action(),
        "a stranger does not ride on the household");
  }

  @Test
  void leavingFreesTheSlotButAnotherSessionOfTheAccountKeepsItsOwn() {
    final AccountLinks links = links(2, "enforce");
    final Object first = online(links, java(BROTHER, HOME));
    final Object second = online(links, java(BROTHER, HOME));
    online(links, java(SISTER, HOME));
    assertEquals(Action.REFUSE, judge(links, java(NEWCOMER, HOME)).orElseThrow().action());

    links.sessionEnded(first);
    assertEquals(Action.REFUSE, judge(links, java(NEWCOMER, HOME)).orElseThrow().action(),
        "the old session ending after the new one began leaves the account online");

    links.sessionEnded(second);
    assertEquals(Optional.empty(), judge(links, java(NEWCOMER, HOME)));
  }

  @Test
  void anAccountIsNeverCountedAgainstItself() {
    final AccountLinks links = links(2, "enforce");
    online(links, java(BROTHER, HOME));
    online(links, java(BROTHER, HOME));

    assertEquals(Optional.empty(), judge(links, java(BROTHER, HOME)), "a third login of the same");
  }

  @Test
  void tenLoginsJudgedInARowBeforeAnyReachesAServerCannotAllGetIn() {
    final AccountLinks links = links(2, "enforce");
    int admitted = 0;

    for (int i = 1; i <= 10; i++) {
      // No server is ever reached: the client drags its configuration out.
      if (judge(links, java(new UUID(9L, i), HOME)).isEmpty()) {
        admitted++;
      }
    }

    assertEquals(2, admitted, "the cap holds from the judgement on, not from the first server");
  }

  @Test
  void tenLoginsJudgedAtOnceOnSeveralThreadsLetExactlyTheCapIn() throws Exception {
    final java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(8);
    try {
      final AccountLinks links = links(2, "enforce", "", pool);
      final List<java.util.concurrent.CompletableFuture<Optional<Verdict>>> pending =
          new ArrayList<>();
      for (int i = 1; i <= 10; i++) {
        pending.add(links.judge(java(new UUID(9L, i), HOME), new Object()));
      }
      long admitted = 0;
      for (java.util.concurrent.CompletableFuture<Optional<Verdict>> verdict : pending) {
        if (verdict.get(5, java.util.concurrent.TimeUnit.SECONDS).isEmpty()) {
          admitted++;
        }
      }

      assertEquals(2, admitted);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void aDuplicateConnectionOfALiveAccountNeverFreesTheLiveSessionsPlace() {
    final AccountLinks links = links(2, "enforce");
    online(links, java(BROTHER, HOME));
    online(links, java(SISTER, HOME));

    final Object duplicate = online(links, java(BROTHER, HOME));
    links.sessionEnded(duplicate);

    assertEquals(Action.REFUSE, judge(links, java(NEWCOMER, HOME)).orElseThrow().action(),
        "the brother is still online: the refused duplicate only took its own place");
  }

  @Test
  void aSessionThatEndsBeforeReachingAServerFreesItsPlace() {
    final AccountLinks links = links(2, "enforce");
    online(links, java(BROTHER, HOME));
    final Object gone = online(links, java(SISTER, HOME));
    assertEquals(Action.REFUSE, judge(links, java(NEWCOMER, HOME)).orElseThrow().action());

    links.sessionEnded(gone);

    assertEquals(Optional.empty(), judge(links, java(NEWCOMER, HOME)));
  }

  @Test
  void aSessionWhoseEndIsHeardBeforeItsJudgementNeverHoldsAPlaceForEver() {
    final AccountLinks links = links(2, "enforce");
    final Object late = new Object();

    links.sessionEnded(late);
    assertEquals(Optional.empty(), links.judge(java(BROTHER, HOME), late).join());
    online(links, java(SISTER, HOME));

    assertEquals(Optional.empty(), judge(links, java(NEWCOMER, HOME)),
        "the brother left first: only the sister is on the address");
  }

  @Test
  void aLoginAdmittedUnjudgedStillTakesItsPlace() {
    final AccountLinks links = links(2, "enforce");
    this.storeDown = true;
    online(links, java(BROTHER, HOME));
    online(links, java(SISTER, HOME));
    this.storeDown = false;

    assertEquals(Action.REFUSE, judge(links, java(NEWCOMER, HOME)).orElseThrow().action(),
        "a database that was down does not hide who is online");
  }

  @Test
  void aFullLookupQueueAdmitsUnjudgedAndStillTakesTheLoginsPlace() {
    final java.util.concurrent.atomic.AtomicBoolean full =
        new java.util.concurrent.atomic.AtomicBoolean(true);
    final AccountLinks links = links(2, "enforce", "", command -> {
      if (full.get()) {
        throw new java.util.concurrent.RejectedExecutionException("full");
      }
      command.run();
    });
    online(links, java(BROTHER, HOME));
    online(links, java(SISTER, HOME));
    full.set(false);

    assertEquals(Action.REFUSE, judge(links, java(NEWCOMER, HOME)).orElseThrow().action(),
        "the queue that was full does not hide who is online");
    assertTrue(this.logger.has("WARN", "queue is full"), this.logger.lines().toString());
  }

  @Test
  void bedrockPlayersBehindGeyserAreNeverCountedOnGeyserAddress() {
    final AccountLinks links = links(2, "enforce", "geyser-addresses: [198.51.100.50]\n");
    // Floodgate recorded no real address: Geyser's own one links nobody.
    online(links, bedrock(BEDROCK, null));
    online(links, bedrock(BEDROCK_TWO, null));
    online(links, bedrock(new UUID(0L, 2535400000000004L), null));

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
    final AccountLinks links = links(2, "enforce", "geyser-addresses: [198.51.100.50]\n");
    online(links, java(BROTHER, HOME));
    online(links, java(COUSIN, HOME));

    final Verdict bedrockAlt = judge(links, bedrock(BEDROCK, HOME)).orElseThrow();
    assertEquals(Action.REFUSE, bedrockAlt.action());
    assertEquals(Basis.ONLINE_PER_ADDRESS, bedrockAlt.basis());
    assertEquals(Verdict.Origin.GEYSER_FLOODGATE, bedrockAlt.origin());

    final AccountLinks other = links(2, "enforce", "geyser-addresses: [198.51.100.50]\n");
    online(other, java(BROTHER, HOME));
    online(other, bedrock(BEDROCK, HOME));
    assertEquals(Action.REFUSE, judge(other, java(COUSIN, HOME)).orElseThrow().action(),
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
  void aCapOfOneIsRefusedAsASettingBecauseItWouldTurnAwayASiblingPair() {
    final List<String> warnings = new ArrayList<>();
    final AdmissionConfig config;
    try {
      config = AdmissionConfig.parseYaml(new java.io.StringReader(CAP.formatted(1, "enforce")),
          warnings::add);
    } catch (java.io.IOException malformed) {
      throw new IllegalStateException(malformed);
    }

    assertEquals(0, config.links().maxOnlinePerAddress(), "back to the default: no cap");
    assertEquals(1, warnings.size(), warnings.toString());
    assertTrue(warnings.getFirst().contains("max-online-per-address"), warnings.getFirst());
    assertEquals(2, AccountLinkRuleTest.config(CAP.formatted(2, "enforce")).links()
        .maxOnlinePerAddress(), "two is the smallest cap: two siblings play together");
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
