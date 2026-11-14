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

import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.api.scheduler.Scheduler;
import java.net.InetAddress;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The account links at work: reads the facts and the bans around a login, off the network
 * threads, and records a session once a server has accepted it.
 *
 * <p>Fails OPEN: a database that is slow (beyond {@code lookup-timeout-ms}), absent or full admits
 * the login unjudged, with at most one warning a minute. Nothing is recorded before a server has
 * accepted the player.
 */
public final class AccountLinks {

  static final Duration WARNING_PERIOD = Duration.ofMinutes(1);

  /** How long after the start the first purge runs: time for the database to come up. */
  static final Duration FIRST_PURGE_DELAY = Duration.ofMinutes(5);

  /** How often the purge runs after the first one. */
  static final Duration PURGE_PERIOD = Duration.ofDays(1);

  private final AdmissionEngine engine;
  private final LinkSettings settings;
  private final LinkKey key;
  private final LinkStore store;
  private final BanLookup bans;
  private final Executor executor;
  private final Executor sessionExecutor;
  private final Clock clock;
  private final Logger logger;
  private final AtomicReference<@Nullable Instant> lastWarning = new AtomicReference<>();
  private final OnlineAddresses online = new OnlineAddresses();

  /**
   * Creates the service.
   *
   * @param engine the rule ({@link AdmissionEngine#linkSubject}, {@link AdmissionEngine#decideLinks})
   * @param settings the {@code links} settings in force
   * @param key the address key
   * @param store the sightings and the households
   * @param bans the Sentinel bans, read only
   * @param executor where the lookups of a login wait on the database; never a network thread
   * @param sessionExecutor where the writes and the ban check of an accepted session, and the staff
   *     requests, wait on it: apart from {@code executor}, so that a wave of sessions cannot fill the
   *     queue the logins are judged through
   * @param clock the time
   * @param logger where the failures are told, without any address
   */
  public AccountLinks(final AdmissionEngine engine, final LinkSettings settings,
      final LinkKey key, final LinkStore store,
      final BanLookup bans, final Executor executor, final Executor sessionExecutor,
      final Clock clock, final Logger logger) {
    this.engine = engine;
    this.settings = settings;
    this.key = key;
    this.store = store;
    this.bans = bans;
    this.executor = executor;
    this.sessionExecutor = sessionExecutor;
    this.clock = clock;
    this.logger = logger;
  }

  /**
   * Starts the account links, or leaves them off when that fails: the rest of the module runs.
   *
   * @param start opens them
   * @param logger told why they are off, by the type of the failure only (a driver or pool message
   *     may carry the database URL)
   * @param undo releases what was opened before the failure
   * @return the links, or {@code null}
   */
  public static @Nullable AccountLinks startOrDisable(
      final Supplier<@Nullable AccountLinks> start, final Logger logger, final Runnable undo) {
    try {
      return start.get();
    } catch (RuntimeException | LinkageError failed) {
      // LinkageError: a JDBC driver or pool class absent from the proxy's classpath.
      undo.run();
      logger.error("links: could not start ({}); account links are DISABLED, the rest of the"
          + " admission shield runs.", failed.getClass().getSimpleName());
      return null;
    }
  }

  /**
   * Judges an authenticated login on its links, and, under a cap of accounts online
   * ({@code max-online-per-address}), takes the session's place on its address in the same step as
   * the count it is judged on. When the lookup fails or is too slow the login is admitted unjudged
   * and takes its place all the same: it counts against the next ones.
   *
   * <p>The place is freed by {@link #sessionEnded}, which the caller must call for every session it
   * passed here, whatever became of the login.
   *
   * @param authenticated the login
   * @param session tells this session from any other, including another of the same account (the
   *     player object)
   * @return the verdict to apply and journal, or empty: nothing to say, or the lookup failed
   */
  public CompletableFuture<Optional<Verdict>> judge(final Attempt authenticated,
      final Object session) {
    final Optional<InetAddress> subject = this.engine.linkSubject(authenticated);
    if (subject.isEmpty()) {
      return CompletableFuture.completedFuture(Optional.empty());
    }
    final InetAddress judged = subject.get();
    try {
      // orTimeout completes this very future, and a supplyAsync task whose future is already
      // complete never runs: a lookup still queued when its login went in never reaches the base.
      return CompletableFuture.supplyAsync(() -> decide(authenticated, judged, session),
              this.executor)
          .orTimeout(this.settings.lookupTimeout().toMillis(), TimeUnit.MILLISECONDS)
          .exceptionally(failure -> {
            warn("the login of " + shortId(authenticated.uuid()) + " is admitted unjudged",
                failure);
            reserveUnjudged(authenticated, judged, session);
            return Optional.empty();
          });
    } catch (RejectedExecutionException full) {
      warn("the lookup queue is full; logins are admitted unjudged", full);
      reserveUnjudged(authenticated, judged, session);
      return CompletableFuture.completedFuture(Optional.empty());
    }
  }

  private void reserveUnjudged(final Attempt authenticated, final InetAddress judged,
      final Object session) {
    if (this.settings.maxOnlinePerAddress() > 0) {
      final Sighting entering = this.key.sighting(authenticated.uuid(), judged,
          this.clock.instant());
      this.online.reserve(entering.account(), entering.ipDigest(), session);
    }
  }

  private Optional<Verdict> decide(final Attempt authenticated, final InetAddress judged,
      final Object session) {
    final UUID self = authenticated.uuid();
    final Instant now = this.clock.instant();
    try {
      final Sighting entering = this.key.sighting(self, judged, now);
      final LinkFacts facts = this.store.factsFor(entering,
          now.minus(LinkSettings.ADDRESS_HISTORY), now.minus(this.settings.weakWindow()));
      final Set<UUID> linked = this.engine.linksOf(self, judged, facts, Map.of(), now).stream()
          .filter(link -> link.strength() != Link.Strength.HOUSEHOLD)
          .map(Link::account)
          .collect(Collectors.toSet());
      final Map<UUID, BanLookup.ActiveBan> banned = linked.isEmpty() ? Map.of()
          : this.bans.activeNetworkBans(linked, now);
      // Counted and reserved in one step: the place is held before any other login is judged.
      final Verdict verdict = this.settings.maxOnlinePerAddress() == 0
          ? this.engine.decideLinks(authenticated, judged, facts, banned, now, 0)
          : this.online.judgeAndReserve(self, entering.ipDigest(), session, facts.household(),
              others -> this.engine.decideLinks(authenticated, judged, facts, banned, now, others));
      return verdict.basis() == Verdict.Basis.CLEAN ? Optional.empty() : Optional.of(verdict);
    } catch (SQLException failed) {
      throw new CompletionException(failed);
    }
  }

  /**
   * Records that a server accepted the player, off the calling thread. The account is noted as
   * known (no address) for every login but an account under a network ban in force (let in by an
   * exemption or a ban pronounced while it was online: its sightings would only stretch its links).
   * Its sighting (the address digests) is recorded only when the links judge the login: not an
   * exempted one, not a Floodgate player without a real address, and not a client of the Geyser
   * listener that is not a Floodgate player (no attested address: Geyser's own, if it is not
   * listed, would be shared by every Bedrock player).
   *
   * @param authenticated the login
   */
  public void recordAccepted(final Attempt authenticated) {
    final UUID account = authenticated.uuid();
    if (account == null || !this.settings.enabled()) {
      return;
    }
    final Optional<InetAddress> subject = this.engine.linkSubject(authenticated);
    final Instant now = this.clock.instant();
    final @Nullable Sighting sighting = subject.isEmpty()
        || authenticated.viaGeyserListener() && !authenticated.floodgatePlayer() ? null
        : this.key.sighting(account, subject.get(), now);
    try {
      this.sessionExecutor.execute(() -> {
        try {
          if (!this.bans.activeNetworkBans(Set.of(account), now).isEmpty()) {
            return;
          }
          if (sighting != null) {
            this.store.record(sighting);
          } else {
            this.store.recordKnown(account, now);
          }
        } catch (SQLException | RuntimeException failed) {
          warn("a session was not recorded", failed);
        }
      });
    } catch (RejectedExecutionException full) {
      warn("the session queue is full; a session was not recorded", full);
    }
  }

  /**
   * A session ended, whatever became of its login (refused by anyone, a duplicate connection, a
   * client gone before it reached a server): its place under the cap is freed, and it can no longer
   * take one. Only this session's own place goes: another session of the same account keeps its.
   *
   * @param session the session {@link #judge} was given
   */
  public void sessionEnded(final Object session) {
    if (this.settings.maxOnlinePerAddress() > 0) {
      this.online.leave(session);
    }
  }

  /**
   * Deletes the sightings older than the retention, and the known accounts absent for longer than
   * their own. Blocks: for a scheduler thread.
   *
   * @return the rows removed, or -1 when the database could not be reached
   */
  public int purge() {
    try {
      final Instant now = this.clock.instant();
      return this.store.purgeOlderThan(now.minus(this.settings.retention()))
          + this.store.purgeKnownOlderThan(now.minus(this.settings.knownRetention()));
    } catch (SQLException | RuntimeException failed) {
      warn("the purge of old sightings failed; it is tried again in 24 h", failed);
      return -1;
    }
  }

  /**
   * Schedules the purge soon after the start, then daily: a proxy restarted every day still
   * keeps the retention it announces.
   *
   * @param purge the task that calls {@link #purge()}
   * @return the scheduled task
   */
  static ScheduledTask schedulePurge(final Scheduler.TaskBuilder purge) {
    return purge.delay(FIRST_PURGE_DELAY).repeat(PURGE_PERIOD).schedule();
  }

  /**
   * Which of these accounts are under a network ban in force now. Blocks: for {@link #run}.
   *
   * @param accounts the accounts
   * @return the banned ones
   * @throws SQLException if the sanctions cannot be read
   */
  public Set<UUID> bannedAmong(final Set<UUID> accounts) throws SQLException {
    return this.bans.activeNetworkBans(accounts, this.clock.instant()).keySet();
  }

  /**
   * The store, for the staff command; its calls block.
   *
   * @return the store
   */
  public LinkStore store() {
    return this.store;
  }

  /**
   * Runs a staff request off the calling thread, on the session executor: never in the way of a\n   * login.
   *
   * @param request the request
   * @param <T> its answer
   * @return its answer, or the failure (a full queue included: never thrown at the caller)
   */
  public <T> CompletableFuture<T> run(final StoreCall<T> request) {
    try {
      return CompletableFuture.supplyAsync(() -> {
        try {
          return request.call(this.store);
        } catch (SQLException failed) {
          throw new CompletionException(failed);
        }
      }, this.sessionExecutor);
    } catch (RejectedExecutionException full) {
      return CompletableFuture.failedFuture(full);
    }
  }

  /**
   * A call on the store that may fail.
   *
   * @param <T> its answer
   */
  @FunctionalInterface
  public interface StoreCall<T> {
    /**
     * Calls the store.
     *
     * @param store the store
     * @return the answer
     * @throws SQLException if the database fails
     */
    T call(LinkStore store) throws SQLException;
  }

  private void warn(final String what, final Throwable failure) {
    final Instant now = this.clock.instant();
    final Instant last = this.lastWarning.get();
    if (last != null && now.isBefore(last.plus(WARNING_PERIOD))
        || !this.lastWarning.compareAndSet(last, now)) {
      return;
    }
    final Throwable cause = failure instanceof CompletionException && failure.getCause() != null
        ? failure.getCause() : failure;
    this.logger.warn("Account links: {} ({}: {}); at most one such warning a minute.", what,
        cause.getClass().getSimpleName(), cause.getMessage());
  }

  private static String shortId(final @Nullable UUID uuid) {
    return uuid == null ? "?" : uuid.toString().substring(0, 8);
  }
}
