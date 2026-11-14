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
import com.velocitypowered.api.scheduler.Scheduler;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The account links end to end, over a store in memory: what a login meets, what is kept. */
class AccountLinksTest {

  private static final InetAddress HOME = InetAddress.ofLiteral("198.51.100.7");
  private static final InetAddress ELSEWHERE = InetAddress.ofLiteral("203.0.113.9");
  private static final UUID BROTHER = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
  private static final UUID SISTER = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");
  private static final UUID BANNED = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
  private static final UUID NEWCOMER = UUID.fromString("cccccccc-0000-0000-0000-000000000001");

  private final ManualClock clock = new ManualClock();
  private final MemoryLinkStore store = new MemoryLinkStore();
  private final Set<UUID> askedAbout = new HashSet<>();
  private final RecordingLogger logger = new RecordingLogger();
  private Instant bannedAt = this.clock.instant();
  private final BanLookup bans = (accounts, now) -> {
    this.askedAbout.addAll(accounts);
    return accounts.contains(BANNED)
        ? Map.of(BANNED, new BanLookup.ActiveBan(42L, this.bannedAt)) : Map.of();
  };

  private AccountLinks links(final String mode, final LinkStore over) {
    return links(mode, over, Runnable::run);
  }

  private AccountLinks links(final LinkStore over, final Executor executor) {
    return links("enforce", over, executor);
  }

  private AccountLinks links(final String mode, final LinkStore over, final Executor executor) {
    return links(mode, over, executor, executor);
  }

  private AccountLinks links(final String mode, final LinkStore over, final Executor logins,
      final Executor sessions) {
    final AdmissionConfig config = AccountLinkRuleTest.config(AccountLinkRuleTest.ON
        .formatted(mode) + "    lookup-timeout-ms: 100\n");
    return new AccountLinks(new AdmissionEngine(config, address -> Optional.empty()),
        config.links(), LinkKeyTest.key(LinkKeyTest.TEST_KEY), over, this.bans, logins,
        sessions, this.clock, this.logger.logger());
  }

  private static Attempt login(final UUID uuid, final InetAddress from) {
    return Attempt.claimed("Player", from).authenticated("Player", uuid, false, null);
  }

  /** A sighting kept from before the account was banned (a banned account is not recorded). */
  private void seenBeforeTheBan(final UUID account, final InetAddress address) {
    try {
      this.store.record(LinkKeyTest.key(LinkKeyTest.TEST_KEY)
          .sighting(account, address, this.clock.instant()));
    } catch (SQLException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static Optional<Verdict> now(final AccountLinks links, final Attempt attempt) {
    return links.judge(attempt, new Object()).join();
  }

  @Test
  void siblingsOfOneHomeComeAndGoWithoutAWord() {
    final AccountLinks links = links("enforce", this.store);
    links.recordAccepted(login(BROTHER, HOME));

    assertEquals(Optional.empty(), now(links, login(SISTER, HOME)));
    links.recordAccepted(login(SISTER, HOME));
    assertEquals(Optional.empty(), now(links, login(BROTHER, HOME)));
    assertEquals(2, this.store.rows.size());
  }

  @Test
  void aNonFloodgateClientOnTheGeyserListenerIsNeverRecordedButAFloodgatePlayerIs() {
    // Geyser's address is public and not listed: recording it would link every Bedrock player.
    final InetAddress geyser = InetAddress.ofLiteral("198.51.100.50");
    final AccountLinks links = links("enforce", this.store);

    links.recordAccepted(Attempt.claimed("Java", geyser, true)
        .authenticated("Java", SISTER, false, null));
    assertEquals(0, this.store.rows.size(), "no sighting for a non-Floodgate client");

    links.recordAccepted(Attempt.claimed(".Bedrock", geyser, true)
        .authenticated(".Bedrock", BROTHER, true, HOME));
    assertEquals(1, this.store.rows.size(), "a Floodgate player is recorded on its real address");
  }

  @Test
  void aBannedJavaAccountsNewBedrockAccountIsRefusedOnItsRealAddressNeverOnGeyserAddress() {
    final InetAddress geyser = InetAddress.ofLiteral("198.51.100.50");
    final UUID bedrockAlt = new UUID(0L, 2535400000000009L);
    // A banned Java account once seen from Geyser's own address (a client on the Geyser host).
    seenBeforeTheBan(BANNED, HOME);
    final AccountLinks links = links("enforce", this.store);

    final Attempt withRealAddress = Attempt.claimed(".Alt", geyser, true)
        .authenticated(".Alt", bedrockAlt, true, HOME);
    // Not on the geyser listener, no geyser-addresses: the connection address is still Geyser's.
    final Attempt withoutIt = Attempt.claimed(".Alt", geyser)
        .authenticated(".Alt", bedrockAlt, true, null);
    seenBeforeTheBan(BANNED, geyser);

    assertEquals(Action.REFUSE, now(links, withRealAddress).orElseThrow().action());
    assertEquals(Optional.empty(), now(links, withoutIt), "Geyser's address links nobody");
  }

  @Test
  void aBannedPlayersNewAccountIsRefusedInEnforceAndOnlyObservedInObserve() {
    seenBeforeTheBan(BANNED, HOME);

    final Verdict enforced = now(links("enforce", this.store), login(NEWCOMER, HOME)).orElseThrow();
    final Verdict observed = now(links("observe", this.store), login(NEWCOMER, HOME)).orElseThrow();

    assertEquals(Action.REFUSE, enforced.action());
    assertEquals(Verdict.LINKED_BAN_CODE, enforced.causeCode());
    assertEquals(Action.OBSERVE, observed.action());
    assertEquals(Basis.LINKED_BAN, observed.basis());
  }

  @Test
  void aSisterWhoPlayedBeforeTheBanIsAdmittedAndAnAccountMadeAfterItIsRefused() {
    final AccountLinks links = links("enforce", this.store);
    links.recordAccepted(login(SISTER, HOME));
    seenBeforeTheBan(BANNED, HOME);
    this.clock.advance(Duration.ofDays(10));
    this.bannedAt = this.clock.instant();
    this.clock.advance(Duration.ofHours(1));
    links.recordAccepted(login(NEWCOMER, ELSEWHERE));
    this.clock.advance(Duration.ofHours(1));

    final Verdict sister = now(links, login(SISTER, HOME)).orElseThrow();
    final Verdict alt = now(links, login(NEWCOMER, HOME)).orElseThrow();

    assertEquals(Action.ADMIT, sister.action(), "no household declared, known before the ban");
    assertEquals(Basis.LINKED_ACCOUNT, sister.basis());
    assertEquals(Action.REFUSE, alt.action(), "first seen an hour after the ban");
  }

  @Test
  void aDeclaredHouseholdIsNeverRefusedForItsBannedMember() throws SQLException {
    final AccountLinks links = links("enforce", this.store);
    seenBeforeTheBan(BANNED, HOME);
    this.store.declareHousehold(SISTER, BANNED, "console", "fratrie", this.clock.instant());

    assertEquals(Optional.empty(), now(links, login(SISTER, HOME)));
    assertFalse(this.askedAbout.contains(BANNED), "the household is not even looked up");
  }

  @Test
  void noTransitivityAcrossTwoAddresses() {
    final AccountLinks links = links("enforce", this.store);
    seenBeforeTheBan(BANNED, HOME);
    links.recordAccepted(login(BROTHER, HOME));
    links.recordAccepted(login(BROTHER, ELSEWHERE));

    assertEquals(Optional.empty(), now(links, login(NEWCOMER, ELSEWHERE)),
        "linked to the brother only, not to whom the brother met elsewhere");
    assertEquals(Set.of(BROTHER), this.askedAbout);
  }

  @Test
  void nothingIsRecordedBeforeAServerAcceptsThePlayer() {
    final AccountLinks links = links("observe", this.store);

    now(links, login(NEWCOMER, HOME));
    assertTrue(this.store.rows.isEmpty());
    links.recordAccepted(login(NEWCOMER, HOME));
    assertEquals(1, this.store.rows.size());
  }

  @Test
  void aSlowDatabaseAdmitsTheLoginUnjudgedAndWarnsOnce() throws Exception {
    final CountDownLatch release = new CountDownLatch(1);
    final ExecutorService pool = Executors.newSingleThreadExecutor();
    final LinkStore slow = new MemoryLinkStore() {
      @Override
      public synchronized LinkFacts factsFor(final Sighting entering,
          final java.time.Instant addressSince, final java.time.Instant networkSince) {
        try {
          release.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
        return LinkFacts.NONE;
      }
    };
    final AdmissionConfig config = AccountLinkRuleTest.config(AccountLinkRuleTest.ON
        .formatted("enforce") + "    lookup-timeout-ms: 100\n");
    final AccountLinks links = new AccountLinks(new AdmissionEngine(config,
        address -> Optional.empty()), config.links(), LinkKeyTest.key(LinkKeyTest.TEST_KEY), slow,
        this.bans, pool, pool, this.clock, this.logger.logger());
    try {
      final long start = System.nanoTime();
      assertEquals(Optional.empty(), links.judge(login(NEWCOMER, HOME), new Object()).get(2, TimeUnit.SECONDS));
      assertTrue(System.nanoTime() - start < Duration.ofSeconds(1).toNanos(), "the login waited");
      assertEquals(Optional.empty(), links.judge(login(SISTER, HOME), new Object()).get(2, TimeUnit.SECONDS));
      assertEquals(1, this.logger.lines().size(), this.logger.lines().toString());
      assertTrue(this.logger.has("WARN", "admitted unjudged"), this.logger.lines().toString());
      assertFalse(this.logger.lines().getFirst().contains("198.51"), "no address in the warning");
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void anAbsentDatabaseAdmitsTheLoginAndLosesOnlyTheSighting() {
    final AccountLinks links = links("enforce", new MemoryLinkStore() {
      @Override
      public synchronized LinkFacts factsFor(final Sighting entering,
          final java.time.Instant addressSince, final java.time.Instant networkSince)
          throws SQLException {
        throw new SQLException("connection refused");
      }

      @Override
      public synchronized void record(final Sighting sighting) throws SQLException {
        throw new SQLException("connection refused");
      }
    });

    assertEquals(Optional.empty(), now(links, login(NEWCOMER, HOME)));
    links.recordAccepted(login(NEWCOMER, HOME));
    assertTrue(this.logger.has("WARN", "connection refused"), this.logger.lines().toString());
  }

  @Test
  void purgeAt180Days() {
    final AccountLinks links = links("observe", this.store);
    links.recordAccepted(login(NEWCOMER, HOME));

    this.clock.advance(Duration.ofDays(180));
    assertEquals(0, links.purge(), "kept up to the day");
    this.clock.advance(Duration.ofMinutes(1));
    assertEquals(1, links.purge());
    assertTrue(this.store.rows.isEmpty());
  }

  @Test
  void aHouseholdNobodyWasSeenInSinceTheRetentionIsPurgedToo() throws SQLException {
    final AccountLinks links = links("observe", this.store);
    this.store.declareHousehold(BROTHER, SISTER, "console", null, this.clock.instant());
    this.store.declareHousehold(NEWCOMER, BANNED, "console", null, this.clock.instant());
    seenBeforeTheBan(BROTHER, HOME);
    seenBeforeTheBan(NEWCOMER, HOME);

    this.clock.advance(Duration.ofDays(100));
    seenBeforeTheBan(BROTHER, ELSEWHERE);
    this.clock.advance(Duration.ofDays(81));

    assertEquals(4, links.purge(), "two old sightings and the household nobody came back to");
    assertEquals(Set.of(BROTHER, SISTER), this.store.households.keySet());
  }

  @Test
  void theFirstPurgeRunsMinutesAfterTheStartThenEveryDay() {
    final Map<String, Duration> asked = new HashMap<>();
    final Scheduler.TaskBuilder builder = (Scheduler.TaskBuilder) Proxy.newProxyInstance(
        Scheduler.TaskBuilder.class.getClassLoader(), new Class<?>[] {Scheduler.TaskBuilder.class},
        (self, method, args) -> {
          if (args != null && (method.getName().equals("delay")
              || method.getName().equals("repeat"))) {
            asked.put(method.getName(), args[0] instanceof Duration given ? given
                : Duration.of((Long) args[0], ((TimeUnit) args[1]).toChronoUnit()));
            return self;
          }
          return null;
        });

    AccountLinks.schedulePurge(builder);

    assertTrue(asked.get("delay").compareTo(Duration.ofHours(1)) < 0,
        "a proxy restarted every day must still purge: " + asked);
    assertEquals(Duration.ofDays(1), asked.get("repeat"));
  }

  @Test
  void aPurgeWithoutDatabaseWarnsAndBreaksNothing() {
    final LinkStore down = new MemoryLinkStore() {
      @Override
      public synchronized int purgeOlderThan(final Instant cutoff) throws SQLException {
        throw new SQLException("connection refused");
      }
    };

    assertEquals(-1, links("observe", down).purge());
    assertTrue(this.logger.has("WARN", "the purge of old sightings failed"),
        this.logger.lines().toString());
  }

  @Test
  void aSightingsPurgeThatFailsDoesNotSkipTheKnownAccountsPurge() {
    final MemoryLinkStore half = new MemoryLinkStore() {
      @Override
      public synchronized int purgeOlderThan(final Instant cutoff) throws SQLException {
        throw new SQLException("table locked");
      }
    };
    final AccountLinks links = links("observe", half);
    links.recordAccepted(login(SISTER, HOME));
    this.clock.advance(Duration.ofDays(800));

    assertEquals(-1, links.purge(), "told that one of the two failed");

    assertTrue(half.known.isEmpty(), "the other one was run all the same");
    assertTrue(this.logger.has("WARN", "the purge of old sightings failed"));
  }

  @Test
  void theKnownAccountsPurgeThatFailsDoesNotHideTheSightingsOne() {
    final MemoryLinkStore half = new MemoryLinkStore() {
      @Override
      public synchronized int purgeKnownOlderThan(final Instant cutoff) {
        throw new IllegalStateException("table locked");
      }
    };
    final AccountLinks links = links("observe", half);
    links.recordAccepted(login(SISTER, HOME));
    this.clock.advance(Duration.ofDays(181));

    assertEquals(-1, links.purge());

    assertTrue(half.rows.isEmpty(), "the sightings were purged");
    assertTrue(this.logger.has("WARN", "the purge of old known accounts failed"),
        this.logger.lines().toString());
  }

  @Test
  void theScheduledPurgeRunsOnTheSessionExecutorNotOnTheCaller() {
    final List<Runnable> parked = new ArrayList<>();
    final AccountLinks links = links("observe", this.store, Runnable::run, parked::add);
    links("observe", this.store).recordAccepted(login(SISTER, HOME));
    this.clock.advance(Duration.ofDays(181));

    final CompletableFuture<Integer> purged = links.purgeOnSessions();

    assertEquals(1, parked.size(), "handed to the session executor");
    assertFalse(purged.isDone());
    assertFalse(this.store.rows.isEmpty(), "nothing touched on the calling thread");
    parked.get(0).run();
    assertEquals(1, purged.join());
    assertTrue(this.store.rows.isEmpty());
  }

  @Test
  void aPurgeTurnedAwayByAFullSessionQueueWaitsForTomorrow() {
    final AccountLinks links = links("observe", this.store, Runnable::run, task -> {
      throw new RejectedExecutionException("full");
    });

    assertEquals(-1, links.purgeOnSessions().join());
    assertTrue(this.logger.has("WARN", "the session queue is full"), this.logger.lines().toString());
  }

  @Test
  void aFloodgateRealAddressFromAnUnlistedOriginWarnsOnceAMinuteWithoutAnyAddress() {
    final AccountLinks links = links("observe", this.store);
    final InetAddress unlisted = InetAddress.ofLiteral("192.0.2.77");
    final InetAddress real = InetAddress.ofLiteral("203.0.113.44");
    final Attempt bedrock = Attempt.claimed("Bedrocker", unlisted)
        .authenticated("Bedrocker", SISTER, true, real);

    now(links, bedrock);
    now(links, bedrock);

    assertEquals(1, this.logger.lines().stream().filter(line -> line.startsWith("WARN")
        && line.contains("geyser-addresses")).count(), this.logger.lines().toString());
    assertTrue(this.logger.lines().stream().noneMatch(line -> line.contains("192.0.2.77")
        || line.contains("203.0.113.44")), "no address in the log");

    this.clock.advance(Duration.ofMinutes(2));
    now(links, bedrock);
    assertEquals(2, this.logger.lines().stream().filter(line -> line.contains("geyser-addresses"))
        .count(), "and again a minute later");
  }

  @Test
  void aFloodgatePlayerOfTheGeyserListenerDoesNotWarnAboutItsOrigin() {
    final AccountLinks links = links("observe", this.store);
    final Attempt bedrock = Attempt.claimed("Bedrocker", InetAddress.ofLiteral("192.0.2.77"), true)
        .authenticated("Bedrocker", SISTER, true, InetAddress.ofLiteral("203.0.113.44"));

    now(links, bedrock);

    assertTrue(this.logger.lines().stream().noneMatch(line -> line.contains("geyser-addresses")),
        this.logger.lines().toString());
  }

  @Test
  void forgetRemovesAccount() throws SQLException {
    final AccountLinks links = links("enforce", this.store);
    seenBeforeTheBan(BANNED, HOME);
    this.store.declareHousehold(BANNED, BROTHER, "console", null, this.clock.instant());

    assertEquals(3, links.run(kept -> kept.forget(BANNED)).join(), "sighting, household, known");
    assertTrue(this.store.rows.isEmpty());
    assertTrue(this.store.households.keySet().stream().noneMatch(BANNED::equals));
    assertEquals(Optional.empty(), now(links, login(NEWCOMER, HOME)), "nothing left to link to");
  }

  @Test
  void anAccountUnderANetworkBanIsNeverRecorded() {
    links("observe", this.store).recordAccepted(login(BANNED, HOME));

    assertTrue(this.store.rows.isEmpty());
    assertTrue(this.store.known.isEmpty(), "not even as a known account");
  }

  @Test
  void aSisterErasedByForgetIsNewAgainBecauseAnErasureAskedForLeavesNoRow() {
    final AccountLinks links = links("enforce", this.store);
    links.recordAccepted(login(SISTER, HOME));
    seenBeforeTheBan(BANNED, HOME);
    this.clock.advance(Duration.ofDays(2));
    this.bannedAt = this.clock.instant();
    this.clock.advance(Duration.ofHours(1));

    links.run(kept -> kept.forget(SISTER)).join();
    final Verdict sister = now(links, login(SISTER, HOME)).orElseThrow();

    assertTrue(this.store.rows.stream().noneMatch(row -> row.account().equals(SISTER)),
        "her sightings are gone");
    assertFalse(this.store.known.containsKey(SISTER), "and so is the row that dated her");
    assertEquals(Action.REFUSE, sister.action(), "erased on request: she is new again, on purpose");
    assertEquals(Basis.LINKED_BAN, sister.basis());
  }

  @Test
  void aSightingsPurgeLeavesTheAccountKnownUntilItsOwnRetentionEnds() {
    final AccountLinks links = links("observe", this.store);
    links.recordAccepted(login(SISTER, HOME));

    this.clock.advance(Duration.ofDays(180).plusMinutes(1));
    assertEquals(1, links.purge(), "the sighting, not the account");
    assertTrue(this.store.rows.isEmpty());
    assertTrue(this.store.known.containsKey(SISTER));

    this.clock.advance(Duration.ofDays(550));
    assertEquals(1, links.purge(), "two years after her last visit she is as good as new");
    assertTrue(this.store.known.isEmpty());
  }

  @Test
  void aBedrockPlayerWithoutARealAddressIsKnownThoughNeverSeenOnAnAddress() {
    final InetAddress geyser = InetAddress.ofLiteral("198.51.100.50");
    final UUID bedrockSister = new UUID(0L, 2535400000000011L);
    final AccountLinks links = links("enforce", this.store);

    links.recordAccepted(Attempt.claimed(".Sis", geyser)
        .authenticated(".Sis", bedrockSister, true, null));
    assertTrue(this.store.rows.isEmpty(), "Geyser's address is never kept for her");
    assertTrue(this.store.known.containsKey(bedrockSister));
    seenBeforeTheBan(BANNED, HOME);
    this.clock.advance(Duration.ofDays(1));
    this.bannedAt = this.clock.instant();
    this.clock.advance(Duration.ofHours(1));

    final Verdict back = now(links, Attempt.claimed(".Sis", geyser)
        .authenticated(".Sis", bedrockSister, true, HOME)).orElseThrow();

    assertEquals(Action.ADMIT, back.action(), "she was there before the ban");
    assertEquals(Basis.LINKED_ACCOUNT, back.basis());
  }

  @Test
  void aWaveOfSessionsNeverFillsTheQueueTheLoginsAreJudgedThrough() throws Exception {
    final CountDownLatch release = new CountDownLatch(1);
    // A database that is slow to write: every session write stays stuck on it.
    final MemoryLinkStore slowWrites = new MemoryLinkStore() {
      @Override
      public void record(final Sighting sighting) throws SQLException {
        await(release);
        super.record(sighting);
      }
    };
    final ThreadPoolExecutor sessions = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(1));
    final ThreadPoolExecutor logins = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(1));
    try {
      seenBeforeTheBan(BANNED, HOME);
      slowWrites.rows.addAll(this.store.rows);
      final AccountLinks links = links("enforce", slowWrites, logins, sessions);

      // One write stuck, one queued, the third turned away: the session queue is full.
      for (UUID account : Set.of(BROTHER, SISTER, UUID.randomUUID())) {
        links.recordAccepted(login(account, ELSEWHERE));
      }
      final Verdict alt = links.judge(login(NEWCOMER, HOME), new Object()).get(2, TimeUnit.SECONDS)
          .orElseThrow();

      assertTrue(this.logger.has("WARN", "session queue is full"), this.logger.lines().toString());
      assertEquals(Action.REFUSE, alt.action(), "still judged: the session writes queue elsewhere");
    } finally {
      release.countDown();
      sessions.shutdownNow();
      logins.shutdownNow();
    }
  }

  @Test
  void aLookupQueuedPastItsDeadlineNeverReachesTheDatabase() throws Exception {
    final CountDownLatch release = new CountDownLatch(1);
    final AtomicInteger asked = new AtomicInteger();
    final ExecutorService pool = Executors.newSingleThreadExecutor();
    final LinkStore slow = new MemoryLinkStore() {
      @Override
      public LinkFacts factsFor(final Sighting entering, final Instant addressSince,
          final Instant networkSince) {
        asked.incrementAndGet();
        await(release);
        return LinkFacts.NONE;
      }
    };
    final AccountLinks links = links(slow, pool);
    try {
      assertEquals(Optional.empty(), links.judge(login(NEWCOMER, HOME), new Object()).get(2, TimeUnit.SECONDS));
      assertEquals(Optional.empty(), links.judge(login(SISTER, HOME), new Object()).get(2, TimeUnit.SECONDS));
    } finally {
      release.countDown();
      pool.shutdown();
      assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS));
    }

    assertEquals(1, asked.get(), "the second lookup was dropped once its login had gone in");
  }

  @Test
  void aFullQueueAdmitsRecordsNothingAndAnswersTheStaffWithAFailure() throws Exception {
    final CountDownLatch release = new CountDownLatch(1);
    final ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(1));
    try {
      pool.execute(() -> await(release));
      pool.execute(() -> { });
      final AccountLinks links = links(this.store, pool);

      assertEquals(Optional.empty(), links.judge(login(NEWCOMER, HOME), new Object()).get(1, TimeUnit.SECONDS));
      links.recordAccepted(login(NEWCOMER, HOME));
      final CompletableFuture<Integer> staff = links.run(kept -> kept.forget(NEWCOMER));

      assertTrue(staff.isCompletedExceptionally(), "a full queue is an answer, never a throw");
      assertTrue(this.logger.has("WARN", "queue is full"), this.logger.lines().toString());
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
    assertTrue(this.store.rows.isEmpty());
  }

  @Test
  void aRoleThatCannotCreateTheTablesAdmitsUnjudgedWithAWarningAndNoSecret() {
    final FakeJdbc db = new FakeJdbc();
    db.failingExecutes = 100;
    final AccountLinks links = links("enforce", new JdbcLinkStore(db.dataSource(), "btc_"));

    assertEquals(Optional.empty(), now(links, login(NEWCOMER, HOME)));

    assertTrue(this.logger.has("WARN", "admitted unjudged"), this.logger.lines().toString());
    assertFalse(this.logger.lines().toString().contains("jdbc:"), "no URL in the log");
  }

  @Test
  void aFailedStartLeavesOnlyTheLinksOffAndNeverLogsTheUrl() {
    final AtomicBoolean undone = new AtomicBoolean();

    final AccountLinks none = AccountLinks.startOrDisable(() -> {
      throw new IllegalStateException("no driver for jdbc:postgresql://db.invalid:5432/sentinel");
    }, this.logger.logger(), () -> undone.set(true));

    assertNull(none);
    assertTrue(undone.get(), "what was opened is closed");
    assertTrue(this.logger.has("ERROR", "DISABLED"), this.logger.lines().toString());
    assertFalse(this.logger.lines().toString().contains("db.invalid"), "no URL in the log");
  }

  @Test
  void aMissingDriverLeavesOnlyTheLinksOff() {
    final AtomicBoolean undone = new AtomicBoolean();

    final AccountLinks none = AccountLinks.startOrDisable(() -> {
      throw new NoClassDefFoundError("org/postgresql/Driver");
    }, this.logger.logger(), () -> undone.set(true));

    assertNull(none);
    assertTrue(undone.get(), "what was opened is closed");
    assertTrue(this.logger.has("ERROR", "NoClassDefFoundError"), this.logger.lines().toString());
    assertTrue(this.logger.has("ERROR", "DISABLED"), this.logger.lines().toString());
  }

  private static void await(final CountDownLatch latch) {
    try {
      latch.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
