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
import java.net.InetAddress;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
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
    final AdmissionConfig config = AccountLinkRuleTest.config(AccountLinkRuleTest.ON
        .formatted(mode) + "    lookup-timeout-ms: 100\n");
    return new AccountLinks(new AdmissionEngine(config, address -> Optional.empty()),
        config.links(), LinkKeyTest.key(LinkKeyTest.TEST_KEY), over, this.bans, executor,
        this.clock, this.logger.logger());
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
    return links.judge(attempt).join();
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
        this.bans, pool, this.clock, this.logger.logger());
    try {
      final long start = System.nanoTime();
      assertEquals(Optional.empty(), links.judge(login(NEWCOMER, HOME)).get(2, TimeUnit.SECONDS));
      assertTrue(System.nanoTime() - start < Duration.ofSeconds(1).toNanos(), "the login waited");
      assertEquals(Optional.empty(), links.judge(login(SISTER, HOME)).get(2, TimeUnit.SECONDS));
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
  void forgetRemovesAccount() throws SQLException {
    final AccountLinks links = links("enforce", this.store);
    seenBeforeTheBan(BANNED, HOME);
    this.store.declareHousehold(BANNED, BROTHER, "console", null, this.clock.instant());

    assertEquals(2, links.run(kept -> kept.forget(BANNED)).join());
    assertTrue(this.store.rows.isEmpty());
    assertTrue(this.store.households.keySet().stream().noneMatch(BANNED::equals));
    assertEquals(Optional.empty(), now(links, login(NEWCOMER, HOME)), "nothing left to link to");
  }

  @Test
  void anAccountUnderANetworkBanIsNeverRecorded() {
    links("observe", this.store).recordAccepted(login(BANNED, HOME));

    assertTrue(this.store.rows.isEmpty());
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
      assertEquals(Optional.empty(), links.judge(login(NEWCOMER, HOME)).get(2, TimeUnit.SECONDS));
      assertEquals(Optional.empty(), links.judge(login(SISTER, HOME)).get(2, TimeUnit.SECONDS));
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

      assertEquals(Optional.empty(), links.judge(login(NEWCOMER, HOME)).get(1, TimeUnit.SECONDS));
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

  private static void await(final CountDownLatch latch) {
    try {
      latch.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
