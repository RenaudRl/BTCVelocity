/*
 * Copyright (C) 2018-2026 Velocity Contributors
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

package com.btcvelocity.proxy.redis.depot.proxy;

import com.btcvelocity.proxy.redis.LogThrottle;
import com.btcvelocity.proxy.redis.VelocityRedis;
import com.btcvelocity.proxy.redis.depot.AbstractDepotService;
import com.btcvelocity.proxy.redis.depot.player.PlayerEntry;
import com.btcvelocity.proxy.redis.provider.RedisProvider.ReapOutcome;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.proxy.plugin.virtual.VelocityVirtualPlugin;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

/**
 * Represents an extension of the {@link AbstractDepotService} for the proxy depot, including
 * functionality to track certain information about a single proxy, or multiple proxies.
 */
public final class ProxyDepotService extends AbstractDepotService<String, ProxyEntry> {

  private static final Logger LOGGER = LogManager.getLogger(ProxyDepotService.class);

  /**
   * How often each proxy publishes its heartbeat to Redis.
   */
  public static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(1);

  /**
   * How long a heartbeat key lives in Redis before it expires.
   * A proxy that stops publishing (e.g. killed with {@code kill -9}) will be reaped by
   * any surviving proxy once this TTL elapses without a renewal.
   */
  public static final Duration HEARTBEAT_TTL = Duration.ofSeconds(5);

  /**
   * How long the reaper lease lives unless its holder renews it. It is renewed every
   * {@link #HEARTBEAT_INTERVAL}, so a holder survives two missed renewals, and it stays shorter
   * than {@link #HEARTBEAT_TTL}: when the holder dies, another proxy has taken the lease over
   * before the dead holder's own heartbeat expires, so the takeover delays no reaping.
   */
  public static final Duration REAPER_LEASE_TTL = Duration.ofSeconds(3);

  /**
   * Redis key of the reaper lease: its value is the proxy id of the one proxy allowed to reap.
   */
  static final String REAPER_LEASE_KEY = "reaper-lease";

  /**
   * Redis key prefix for per-proxy heartbeat keys. The full key is {@code heartbeat:<proxyId>}.
   */
  private static final String HEARTBEAT_KEY_PREFIX = "heartbeat:";

  /**
   * Redis key prefix of the drain mark, {@code draining:<proxyId>}. The mark lives as long as the
   * heartbeat does: the heartbeat renews it, so a drained proxy that dies leaves no mark behind.
   */
  static final String DRAIN_KEY_PREFIX = "draining:";

  /**
   * The Redis manager used to interact with proxy-related data stored in Redis.
   */
  private final VelocityRedis redis;

  /**
   * Scheduled task that publishes this proxy's heartbeat key to Redis every {@link #HEARTBEAT_INTERVAL}.
   */
  private final ScheduledTask heartbeatTask;

  /**
   * Scheduled task that checks for proxies whose heartbeat has expired and reaps their stale data.
   */
  private final ScheduledTask reapDeadProxiesTask;

  /**
   * Serializes the reap passes with the lease release of {@link #teardown()}, so a pass cannot
   * renew the lease after it was released. A pass takes it with {@code tryLock()} and gives up
   * when it is busy: the scheduler hands every run to a cached pool, so after a freeze the late
   * runs arrive in a burst, and queuing them would replay the missed passes one after the other.
   */
  private final ReentrantLock reaperLock = new ReentrantLock();

  /**
   * Set by {@link #stopHeartbeat()}. Guarded by {@code this}, like the heartbeat itself.
   */
  private boolean heartbeatStopped;

  /**
   * Whether this proxy is drained: its heartbeat renews the drain mark while it is. Guarded by
   * {@code this}, like the heartbeat itself.
   */
  private boolean draining;

  /**
   * Throttle and outage flag of the failures of {@link #publishHeartbeat()}.
   */
  private final LogThrottle heartbeatFailures = new LogThrottle(LogThrottle.DEFAULT_INTERVAL);
  private final AtomicBoolean heartbeatFailing = new AtomicBoolean();

  /**
   * Throttle and outage flag of the failures of {@link #reapDeadProxies()}.
   */
  private final LogThrottle reapFailures = new LogThrottle(LogThrottle.DEFAULT_INTERVAL);
  private final AtomicBoolean reapFailing = new AtomicBoolean();

  /**
   * The clock of the lease validity ({@link System#nanoTime()}, replaced in tests).
   */
  private final LongSupplier nanoClock;

  /**
   * How many proxies this one reaped since startup.
   */
  private final AtomicLong reaped = new AtomicLong();

  /**
   * {@link #nanoClock} reading when this proxy last took the lease, or last had it in doubt (a
   * failed renewal), or started. A holder reaps only once a heartbeat interval has elapsed since,
   * in time rather than in passes: after a freeze the late passes run back to back, and the other
   * proxies have not published again yet. Guarded by {@link #reaperLock}.
   */
  private long leaseAcquiredAt;

  /**
   * Constructs a new {@link ProxyDepotService}.
   *
   * @param redis the {@link VelocityRedis} instance
   */
  public ProxyDepotService(final @NotNull VelocityRedis redis) {
    this(redis, System::nanoTime);
  }

  ProxyDepotService(final @NotNull VelocityRedis redis, final @NotNull LongSupplier nanoClock) {
    super(ProxyEntry.class, redis.getProvider());

    this.redis = redis;
    this.nanoClock = nanoClock;
    this.leaseAcquiredAt = nanoClock.getAsLong();

    this.depot.upsert(new ProxyEntry(redis.getServer()));

    this.heartbeatTask = redis.getServer().getScheduler()
            .buildTask(VelocityVirtualPlugin.INSTANCE, this::publishHeartbeat)
            .repeat(HEARTBEAT_INTERVAL)
            .schedule();

    this.reapDeadProxiesTask = redis.getServer().getScheduler()
            .buildTask(VelocityVirtualPlugin.INSTANCE, this::reapDeadProxies)
            .repeat(HEARTBEAT_INTERVAL)
            .schedule();
  }

  @Override
  public void teardown() {
    if (this.heartbeatTask != null) {
      this.heartbeatTask.cancel();
    }

    if (this.reapDeadProxiesTask != null) {
      this.reapDeadProxiesTask.cancel();
    }

    // The lease goes first, so another proxy takes the reaping over at its next pass instead of
    // waiting for the lease to expire. A no-op when another proxy holds it.
    releaseReaperLease();

    // Locked against a heartbeat already in flight, which would register this proxy again.
    synchronized (this) {
      // Delete own heartbeat key so surviving proxies don't try to reap us while we're cleaning up.
      this.redis.getProvider().deleteKey(HEARTBEAT_KEY_PREFIX + this.redis.getProxyId());

      final ProxyEntry proxyEntry = this.get(this.redis.getServer().getProxyId());
      if (proxyEntry != null) {
        proxyEntry.remove();
      }
    }
  }

  /**
   * Releases the reaper lease if this proxy holds it. Serialized with the reap passes, so none can
   * renew it afterwards.
   */
  private void releaseReaperLease() {
    this.reaperLock.lock();
    try {
      this.redis.getProvider().releaseLease(REAPER_LEASE_KEY, this.redis.getProxyId());
    } catch (RuntimeException e) {
      LOGGER.warn("Could not release the reaper lease ({}); it expires on its own within {} s",
          e.toString(), REAPER_LEASE_TTL.toSeconds());
    } finally {
      this.reaperLock.unlock();
    }
  }

  /**
   * Stops publishing this proxy's heartbeat, leaving the rest of the shutdown alone, and leaves
   * the cluster cleanly: the heartbeat key is deleted and the reaper lease released, so the
   * others reap whatever this proxy still has in Redis without waiting for the TTL, and this
   * proxy, no longer beating, cannot take the lease again. Called once the players were released.
   */
  public void stopHeartbeat() {
    if (this.heartbeatTask != null) {
      this.heartbeatTask.cancel();
    }

    // Locked against a heartbeat in flight, and read by the runs already handed to the pool.
    synchronized (this) {
      this.heartbeatStopped = true;
      try {
        this.redis.getProvider().deleteKey(HEARTBEAT_KEY_PREFIX + this.redis.getProxyId());
      } catch (RuntimeException e) {
        LOGGER.warn("Could not delete the heartbeat ({}); it expires on its own within {} s",
            e.toString(), HEARTBEAT_TTL.toSeconds());
      }
    }
    releaseReaperLease();
  }

  /**
   * Get a list of all the {@link ProxyEntry proxy} IDs currently present in the depot.
   *
   * @return the list of all proxy IDs, sorted alphabetically
   */
  public List<String> getAllProxyIds() {
    return this.depot.keys().stream().sorted().toList();
  }

  /**
   * Get a list of all the {@link ProxyEntry proxy} IDs currently present in the depot, in lower case.
   *
   * @return the list of all proxy IDs in lower case, sorted alphabetically
   */
  public List<String> getAllProxyIdsLowerCase() {
    return this.depot.keys().stream().map(String::toLowerCase).sorted().toList();
  }

  /**
   * Whether a proxy still beats: its heartbeat key has not expired. Blocking Redis read.
   *
   * @param proxyId the proxy to check
   * @return {@code true} if its heartbeat key exists
   */
  public boolean isAlive(final @NotNull String proxyId) {
    return this.redis.getProvider().existsKey(HEARTBEAT_KEY_PREFIX + proxyId);
  }

  /**
   * Whether a proxy may be handed players: it beats and is not drained. Blocking Redis reads.
   *
   * @param proxyId the proxy to check
   * @return {@code true} if it is alive and carries no drain mark
   */
  public boolean isTransferTarget(final @NotNull String proxyId) {
    return isAlive(proxyId) && !this.redis.getProvider().existsKey(DRAIN_KEY_PREFIX + proxyId);
  }

  /**
   * Marks this proxy as drained, or clears the mark. The mark expires with the heartbeat unless
   * renewed, and the heartbeat renews it while this proxy is drained. Blocking Redis write.
   *
   * @param draining {@code true} to publish the mark, {@code false} to remove it
   * @throws RuntimeException if Redis does not answer; the state is kept, so the next heartbeat
   *         publishes the mark (or lets it expire) all the same
   */
  public synchronized void setDraining(final boolean draining) {
    this.draining = draining;
    final String key = DRAIN_KEY_PREFIX + this.redis.getProxyId();
    if (draining) {
      this.redis.getProvider().setWithExpiry(key, "1", HEARTBEAT_TTL.toSeconds());
    } else {
      this.redis.getProvider().deleteKey(key);
    }
  }

  /**
   * Publishes this proxy's heartbeat key to Redis with a TTL of {@link #HEARTBEAT_TTL}.
   * Called every {@link #HEARTBEAT_INTERVAL} by the scheduler. The shutdown flag is set before
   * {@link #teardown()}, which takes the same lock, so no beat can follow the teardown.
   */
  private synchronized void publishHeartbeat() {
    if (this.redis.isShutdown() || this.heartbeatStopped) {
      return;
    }

    try {
      this.redis.getProvider().setWithExpiry(
              HEARTBEAT_KEY_PREFIX + this.redis.getProxyId(),
              "1",
              HEARTBEAT_TTL.toSeconds()
      );
      if (this.draining) {
        this.redis.getProvider().setWithExpiry(
                DRAIN_KEY_PREFIX + this.redis.getProxyId(),
                "1",
                HEARTBEAT_TTL.toSeconds()
        );
      }

      // A lapse longer than the TTL (pause, Redis hiccup) lets a peer reap this live proxy.
      if (!this.depot.contains(this.redis.getProxyId())) {
        LOGGER.warn("This proxy was reaped from redis while still running; registering it again.");
        this.depot.upsert(new ProxyEntry(this.redis.getServer()));
      }
      this.heartbeatFailing.set(false);
    } catch (RuntimeException e) {
      logFailure(this.heartbeatFailures, this.heartbeatFailing, "The heartbeat", e);
    }
  }

  /**
   * Logs a failed periodic task once per throttle interval, instead of one stack trace per second
   * for as long as Redis is down. The first failure of an outage keeps its stack trace; the next
   * ones are one line with the number of failures left out since.
   */
  private void logFailure(final LogThrottle throttle, final AtomicBoolean failing,
                          final String task, final RuntimeException failure) {
    // The scheduler cancels a pass in flight at shutdown: that is not an outage.
    if (this.redis.isShutdown() || isInterruption(failure)) {
      LOGGER.debug("{} was interrupted by the shutdown: {}", task, failure.toString());
      return;
    }
    final long quiet = throttle.acquire();
    if (quiet < 0) {
      return;
    }
    if (failing.compareAndSet(false, true)) {
      LOGGER.error("{} failed (Redis unreachable?); further failures are logged once per {} s",
          task, LogThrottle.DEFAULT_INTERVAL.toSeconds(), failure);
    } else {
      LOGGER.error("{} is still failing: {} ({} similar failures not logged)", task, failure,
          quiet);
    }
  }

  private static boolean isInterruption(final Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof InterruptedException) {
        return true;
      }
    }
    return false;
  }

  /**
   * Gets how many dead proxies this one reaped since startup.
   *
   * @return the count
   */
  public long getReapedProxyCount() {
    return this.reaped.get();
  }

  /**
   * Checks all known proxies in Redis for a live heartbeat key. Any proxy whose heartbeat
   * has expired is considered dead and its player and proxy entries are removed from Redis.
   * Called every {@link #HEARTBEAT_INTERVAL} by the scheduler.
   *
   * <p>Only the proxy holding the reaper lease reaps, so a pause that expires every heartbeat at
   * once does not make the proxies reap one another. The lease is renewed first; a holder whose
   * renewal fails (it was frozen, the lease expired) takes the lease again and reaps only once a
   * full {@link #HEARTBEAT_INTERVAL} has elapsed since, so the other proxies have had the time to
   * publish again. A pass that finds another one running gives up instead of waiting for it.</p>
   */
  private void reapDeadProxies() {
    if (!this.reaperLock.tryLock()) {
      return;
    }
    try {
      if (this.redis.isShutdown()) {
        return;
      }
      final boolean held = renewReaperLease();
      this.reapFailing.set(false); // Redis answered: the next outage logs its stack trace again
      if (!held) {
        return;
      }

      if (this.nanoClock.getAsLong() - this.leaseAcquiredAt < HEARTBEAT_INTERVAL.toNanos()) {
        return; // Just taken over: wait for the others' next heartbeat.
      }

      for (final String proxyId : this.getAllProxyIds()) {
        if (proxyId.equalsIgnoreCase(this.redis.getProxyId())) {
          continue; // Never reap ourselves.
        }

        // The lease and the heartbeat are checked again inside the script that deletes, so a pass
        // frozen since its renewal, or a proxy that beat again meanwhile, reaps nothing.
        final ReapOutcome outcome = reapProxy(proxyId);
        if (outcome == ReapOutcome.NOT_HOLDER) {
          return;
        }
      }
    } catch (RuntimeException e) {
      logFailure(this.reapFailures, this.reapFailing, "The dead-proxy check", e);
    } finally {
      this.reaperLock.unlock();
    }
  }

  /**
   * Renews the reaper lease, or tries to take it.
   *
   * @return {@code true} if this proxy held the lease up to this call and still holds it; {@code
   *         false} if it just took the lease, or does not hold it
   */
  private boolean renewReaperLease() {
    final String proxyId = this.redis.getProxyId();
    try {
      // Renewed only while this proxy beats: one that was reaped itself must not keep reaping.
      if (this.redis.getProvider().renewLease(REAPER_LEASE_KEY, proxyId, REAPER_LEASE_TTL,
          HEARTBEAT_KEY_PREFIX + proxyId)) {
        return true;
      }
      // Lost or never held: the others get a heartbeat interval from now, taken or not.
      this.leaseAcquiredAt = this.nanoClock.getAsLong();
      // Taken only while this proxy beats, like the renewal.
      this.redis.getProvider().acquireLease(REAPER_LEASE_KEY, proxyId, REAPER_LEASE_TTL,
          HEARTBEAT_KEY_PREFIX + proxyId);
      return false;
    } catch (RuntimeException e) {
      this.leaseAcquiredAt = this.nanoClock.getAsLong(); // Redis unreachable: the lease is in doubt
      throw e;
    }
  }

  /**
   * Removes all Redis entries belonging to the given dead proxy, its player entries and its proxy
   * entry, in one Lua script that also checks the lease and the heartbeat. Owner-guarded: a
   * player that already moved to a live proxy keeps that proxy's entry.
   *
   * @param proxyId the ID of the proxy to reap
   */
  private ReapOutcome reapProxy(final @NotNull String proxyId) {
    final ReapOutcome outcome = this.redis.getProvider().reapProxy(REAPER_LEASE_KEY,
        this.redis.getProxyId(), HEARTBEAT_KEY_PREFIX + proxyId, proxyId, PlayerEntry.class,
        ProxyEntry.class);
    if (outcome == ReapOutcome.REAPED) {
      LOGGER.warn("Reaping proxy {}: its heartbeat expired.", proxyId);
      this.reaped.incrementAndGet();
    }
    return outcome;
  }
}
