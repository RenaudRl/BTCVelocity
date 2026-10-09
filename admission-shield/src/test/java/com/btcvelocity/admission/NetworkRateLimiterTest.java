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

import java.net.InetAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class NetworkRateLimiterTest {

  private final ManualClock clock = new ManualClock();

  private static InetAddress ip(final String literal) {
    return InetAddress.ofLiteral(literal);
  }

  private static NetworkRateSettings settings(final int burst, final int maxNetworks) {
    return new NetworkRateSettings(true, AdmissionConfig.Mode.ENFORCE, 10,
        Duration.ofSeconds(10), burst, 24, 48, maxNetworks, "refused {code}");
  }

  private NetworkRateLimiter limiter(final int burst, final int maxNetworks) {
    return new NetworkRateLimiter(settings(burst, maxNetworks), this.clock);
  }

  private static int admitted(final NetworkRateLimiter limiter, final InetAddress address,
      final int attempts) {
    int admitted = 0;
    for (int i = 0; i < attempts; i++) {
      if (limiter.tryAcquire(address)) {
        admitted++;
      }
    }
    return admitted;
  }

  @Test
  void aNetworkMayAttemptItsBurstAndNotOneMore() {
    final NetworkRateLimiter limiter = limiter(20, 1_000);

    assertEquals(20, admitted(limiter, ip("203.0.113.5"), 20), "the burst passes");
    assertFalse(limiter.tryAcquire(ip("203.0.113.5")), "the 21st is over the limit");
  }

  @Test
  void aNetworkBelowItsBurstIsNeverLimited() {
    final NetworkRateLimiter limiter = limiter(20, 1_000);

    assertEquals(19, admitted(limiter, ip("203.0.113.5"), 19));
    assertFalse(limiter.isLimited(ip("203.0.113.5")));
    assertEquals(0, limiter.limitedAttempts());
  }

  @Test
  void theBucketRefillsAtTheSustainedRate() {
    final NetworkRateLimiter limiter = limiter(20, 1_000);
    admitted(limiter, ip("203.0.113.5"), 20);

    this.clock.advance(Duration.ofSeconds(1));

    assertEquals(1, admitted(limiter, ip("203.0.113.5"), 5), "10 attempts / 10 s is one a second");
    this.clock.advance(Duration.ofSeconds(60));
    assertEquals(20, admitted(limiter, ip("203.0.113.5"), 30), "never more than the burst");
  }

  @Test
  void aRefusedAttemptSpendsNothingSoTheNetworkIsNotStarvedByTheWave() {
    final NetworkRateLimiter limiter = limiter(20, 1_000);
    admitted(limiter, ip("203.0.113.5"), 20);

    admitted(limiter, ip("203.0.113.5"), 500);
    this.clock.advance(Duration.ofSeconds(2));

    assertEquals(2, admitted(limiter, ip("203.0.113.5"), 5));
    assertEquals(500 + 3, limiter.limitedAttempts());
  }

  @Test
  void hostsOfOneSlash24ShareABucketAndAnotherSlash24DoesNot() {
    final NetworkRateLimiter limiter = limiter(20, 1_000);

    int admitted = 0;
    for (int host = 1; host <= 40; host++) {
      if (limiter.tryAcquire(ip("203.0.113." + host))) {
        admitted++;
      }
    }

    assertEquals(20, admitted, "40 different addresses of one /24 draw on one bucket");
    assertTrue(limiter.tryAcquire(ip("203.0.114.1")), "the next /24 has its own");
    assertEquals(2, limiter.trackedNetworks());
  }

  @Test
  void hostsOfOneIpv6Slash48ShareABucketAcrossSlash64sAndAnotherSlash48DoesNot() {
    final NetworkRateLimiter limiter = limiter(20, 1_000);

    int admitted = 0;
    for (int subnet = 1; subnet <= 40; subnet++) {
      if (limiter.tryAcquire(ip("2001:db8:aaaa:" + Integer.toHexString(subnet) + "::1"))) {
        admitted++;
      }
    }

    assertEquals(20, admitted, "40 /64s of one /48 draw on one bucket");
    assertTrue(limiter.tryAcquire(ip("2001:db8:aaab:1::1")), "the next /48 has its own");
    assertEquals(2, limiter.trackedNetworks());
  }

  @Test
  void anIpv4AndAnIpv6NetworkAreNeverTheSameNetwork() {
    final NetworkRateLimiter limiter = limiter(1, 1_000);

    assertTrue(limiter.tryAcquire(ip("203.0.113.5")));
    assertTrue(limiter.tryAcquire(ip("2001:db8:aaaa::1")));
    assertEquals(2, limiter.trackedNetworks());
  }

  @Test
  void theTableIsBoundedAndTheLeastRecentlySeenNetworkIsForgotten() {
    final NetworkRateLimiter limiter = limiter(2, 3);
    admitted(limiter, ip("198.51.100.1"), 2);
    assertFalse(limiter.tryAcquire(ip("198.51.100.1")), "A is exhausted");
    limiter.tryAcquire(ip("198.51.101.1"));
    limiter.tryAcquire(ip("198.51.102.1"));

    limiter.tryAcquire(ip("198.51.103.1"));

    assertEquals(3, limiter.trackedNetworks(), "never more than the bound");
    assertTrue(limiter.tryAcquire(ip("198.51.100.1")),
        "A was forgotten and starts again with a full bucket: eviction can only let through");
  }

  @Test
  void aNetworkThatKeepsAttemptingIsNotTheOneForgotten() {
    final NetworkRateLimiter limiter = limiter(2, 3);
    admitted(limiter, ip("198.51.100.1"), 2);
    limiter.tryAcquire(ip("198.51.101.1"));
    limiter.tryAcquire(ip("198.51.102.1"));

    assertFalse(limiter.tryAcquire(ip("198.51.100.1")), "A is seen again: now the newest");
    limiter.tryAcquire(ip("198.51.103.1"));

    assertFalse(limiter.tryAcquire(ip("198.51.100.1")), "A is still tracked and still limited");
  }

  @Test
  void askingWhetherANetworkIsLimitedSpendsNothingAndTracksNothing() {
    final NetworkRateLimiter limiter = limiter(2, 1_000);

    assertFalse(limiter.isLimited(ip("198.51.100.1")));
    assertEquals(0, limiter.trackedNetworks());
    admitted(limiter, ip("198.51.100.1"), 2);
    assertTrue(limiter.isLimited(ip("198.51.100.1")));
    this.clock.advance(Duration.ofSeconds(1));
    assertFalse(limiter.isLimited(ip("198.51.100.1")), "one token is back");
    assertTrue(limiter.tryAcquire(ip("198.51.100.1")), "and asking did not spend it");
  }

  @Test
  void anotherGroupingIsOneConstructorArgumentAway() {
    // The ASN extension point: whatever maps an address to a key defines the network.
    final NetworkRateLimiter byFirstOctet = new NetworkRateLimiter(settings(2, 1_000), this.clock,
        address -> "first-octet-" + (address.getAddress()[0] & 0xff));

    assertTrue(byFirstOctet.tryAcquire(ip("198.51.100.1")));
    assertTrue(byFirstOctet.tryAcquire(ip("198.7.7.7")));
    assertFalse(byFirstOctet.tryAcquire(ip("198.200.1.1")));
  }

  @Test
  void concurrentAttemptsFromOneNetworkNeverExceedTheBurst() throws Exception {
    final NetworkRateLimiter limiter = limiter(20, 1_000);
    final int threads = 8;
    final AtomicInteger admitted = new AtomicInteger();
    final CountDownLatch start = new CountDownLatch(1);
    final ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      final java.util.List<Future<?>> done = new java.util.ArrayList<>();
      for (int thread = 0; thread < threads; thread++) {
        final int offset = thread;
        done.add(pool.submit(() -> {
          start.await();
          for (int i = 0; i < 100; i++) {
            if (limiter.tryAcquire(ip("203.0.113." + (offset * 10 + i % 10 + 1)))) {
              admitted.incrementAndGet();
            }
          }
          return null;
        }));
      }

      start.countDown();
      for (Future<?> future : done) {
        future.get(10, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }

    assertEquals(20, admitted.get(), "800 attempts from one /24, exactly the burst got through");
    assertEquals(780, limiter.limitedAttempts());
    assertEquals(1, limiter.trackedNetworks());
  }
}
