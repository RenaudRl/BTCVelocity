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

import java.net.InetAddress;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * Bounds how often one network may attempt to connect, with a token bucket per network.
 *
 * <p>A network is whatever the {@link Grouping} says: by default an IPv4 /24 or an IPv6 /48, so
 * that a carrier-grade NAT, where thousands of subscribers share a few addresses, is judged as the
 * crowd it is and not address by address (the technique of Gate by Minekube). A bucket holds
 * {@code burst} tokens and refills at {@code attempts} per {@code window}. A refused attempt
 * spends nothing: the players of a throttled network get through at the sustained rate while a
 * bot wave is turned away, instead of starving the network for as long as the wave lasts.
 *
 * <p>The caller passes <em>public</em> addresses only, already unwrapped
 * ({@link AddressPolicy#classifiable}); what is exempted is the caller's business too. This class
 * is arithmetic and memory.
 *
 * <p>Memory is bounded: at most {@code maxNetworks} networks are followed and the least recently
 * seen is forgotten first. A forgotten network starts again with a full bucket, so pressure on the
 * table can only let attempts through, never refuse a network that has done nothing. Idle buckets
 * need no timer: they refill as time passes and are simply recomputed at the next attempt.
 *
 * <p>Safe to call from several threads (Velocity delivers events concurrently); one lock guards
 * the table and each call holds it for a few arithmetic operations.
 *
 * <p>An ASN-based bound would be a second instance with another {@link Grouping} and its own
 * settings; nothing here knows what a network is made of.
 */
public final class NetworkRateLimiter {

  /** What makes two addresses the same network. */
  @FunctionalInterface
  public interface Grouping {

    /**
     * The key of the network a public address belongs to.
     *
     * @param publicAddress an address that {@link AddressPolicy#classifiable} let through
     * @return a key, equal for every address of the same network
     */
    String keyOf(InetAddress publicAddress);

    /**
     * Networks of a fixed prefix length.
     *
     * @param ipv4Prefix the prefix kept of an IPv4 address
     * @param ipv6Prefix the prefix kept of an IPv6 address
     * @return the grouping
     */
    static Grouping byPrefix(final int ipv4Prefix, final int ipv6Prefix) {
      return address -> Networks.of(address, ipv4Prefix, ipv6Prefix);
    }
  }

  private static final class Bucket {
    private double tokens;
    private long refilledAtMillis;

    private Bucket(final double tokens, final long refilledAtMillis) {
      this.tokens = tokens;
      this.refilledAtMillis = refilledAtMillis;
    }
  }

  private final Clock clock;
  private final Grouping grouping;
  private final double burst;
  private final double tokensPerMillisecond;
  private final Object lock = new Object();
  private final Map<String, Bucket> buckets;
  private final LongAdder limited = new LongAdder();

  /**
   * Creates a limiter grouping addresses by the prefixes of the settings.
   *
   * @param settings the rate, the burst, the prefixes and the memory bound
   * @param clock the source of time
   */
  public NetworkRateLimiter(final NetworkRateSettings settings, final Clock clock) {
    this(settings, clock, Grouping.byPrefix(settings.ipv4Prefix(), settings.ipv6Prefix()));
  }

  /**
   * Creates a limiter with its own idea of what a network is.
   *
   * @param settings the rate, the burst and the memory bound (the prefixes are not used)
   * @param clock the source of time
   * @param grouping what makes two addresses the same network
   */
  public NetworkRateLimiter(final NetworkRateSettings settings, final Clock clock,
      final Grouping grouping) {
    this.clock = clock;
    this.grouping = grouping;
    this.burst = settings.burst();
    this.tokensPerMillisecond = (double) settings.attempts() / settings.window().toMillis();
    final int maxNetworks = settings.maxNetworks();
    this.buckets = new LinkedHashMap<>(16, 0.75f, true) {
      @Override
      protected boolean removeEldestEntry(final Map.Entry<String, Bucket> eldest) {
        return size() > maxNetworks;
      }
    };
  }

  /**
   * Counts one attempt from a network.
   *
   * @param publicAddress the address of the connection, public and unwrapped
   * @return {@code true} if the network is within its limit, {@code false} if this attempt is
   *     over it (and spends nothing)
   */
  public boolean tryAcquire(final InetAddress publicAddress) {
    final String key = this.grouping.keyOf(publicAddress);
    final long now = this.clock.millis();
    synchronized (this.lock) {
      Bucket bucket = this.buckets.get(key);
      if (bucket == null) {
        bucket = new Bucket(this.burst, now);
        this.buckets.put(key, bucket);
      } else {
        bucket.tokens = refilled(bucket, now);
        bucket.refilledAtMillis = Math.max(bucket.refilledAtMillis, now);
      }
      if (bucket.tokens >= 1) {
        bucket.tokens -= 1;
        return true;
      }
    }
    this.limited.increment();
    return false;
  }

  /**
   * Whether the network would be over its limit if it attempted now. Spends nothing and starts
   * no tracking.
   *
   * @param publicAddress the address of the connection, public and unwrapped
   * @return {@code true} if an attempt now would be refused
   */
  public boolean isLimited(final InetAddress publicAddress) {
    final String key = this.grouping.keyOf(publicAddress);
    final long now = this.clock.millis();
    synchronized (this.lock) {
      final Bucket bucket = this.buckets.get(key);
      return bucket != null && refilled(bucket, now) < 1;
    }
  }

  /**
   * How many networks are followed right now.
   *
   * @return the number of buckets held
   */
  public int trackedNetworks() {
    synchronized (this.lock) {
      return this.buckets.size();
    }
  }

  /**
   * How many attempts have been found over the limit since the limiter was created.
   *
   * @return the count, observed and refused alike
   */
  public long limitedAttempts() {
    return this.limited.sum();
  }

  private double refilled(final Bucket bucket, final long now) {
    // A clock that steps back must not drain the bucket.
    final long elapsed = Math.max(0, now - bucket.refilledAtMillis);
    return Math.min(this.burst, bucket.tokens + elapsed * this.tokensPerMillisecond);
  }
}
