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

package com.btcvelocity.arrival;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

class PlayerCountsTest {

  private static final int CLUSTER = 40;
  private static final int LOCAL = 3;

  private final AtomicLong now = new AtomicLong(1_000_000L);
  private final AtomicInteger clusterCalls = new AtomicInteger();
  private final AtomicBoolean clusterDown = new AtomicBoolean();
  private final PlayerCounts counts = new PlayerCounts(name -> {
    this.clusterCalls.incrementAndGet();
    if (this.clusterDown.get()) {
      throw new IllegalStateException("redis is down");
    }
    return CLUSTER;
  }, name -> LOCAL, this.now::get, NOPLogger.NOP_LOGGER);

  @Test
  void theClusterCountIsUsedNotTheLocalOne() {
    assertEquals(CLUSTER, this.counts.of("Lobby-1"));
  }

  @Test
  void aCountIsReusedForAShortTimeThenAskedAgain() {
    this.counts.of("Lobby-1");
    this.counts.of("Lobby-1");
    assertEquals(1, this.clusterCalls.get(), "a sweep must not cost a round trip per player");

    this.now.addAndGet(PlayerCounts.CACHE_MILLIS);
    this.counts.of("Lobby-1");
    assertEquals(2, this.clusterCalls.get());
  }

  @Test
  void eachServerHasItsOwnCachedCount() {
    this.counts.of("Lobby-1");
    this.counts.of("Lobby-2");
    assertEquals(2, this.clusterCalls.get());
  }

  @Test
  void aFailingClusterFallsBackToTheLocalCount() {
    this.clusterDown.set(true);
    assertEquals(LOCAL, this.counts.of("Lobby-1"));
  }

  @Test
  void aFailingClusterIsNotAskedAgainBeforeTheDelay() {
    // Witness: without the delay every player of a sweep would wait for a Redis timeout.
    this.clusterDown.set(true);
    this.counts.of("Lobby-1");
    this.counts.of("Lobby-2");
    this.counts.of("Lobby-1");
    assertEquals(1, this.clusterCalls.get());
  }

  @Test
  void theClusterIsAskedAgainAfterTheDelayAndWinsOnceItAnswers() {
    this.clusterDown.set(true);
    this.counts.of("Lobby-1");

    this.now.addAndGet(PlayerCounts.FALLBACK_MILLIS);
    this.clusterDown.set(false);

    assertEquals(CLUSTER, this.counts.of("Lobby-1"));
    assertEquals(2, this.clusterCalls.get());
  }

  @Test
  void aClusterStillDownAfterTheDelayIsLeftAloneAgain() {
    this.clusterDown.set(true);
    this.counts.of("Lobby-1");
    this.now.addAndGet(PlayerCounts.FALLBACK_MILLIS);

    assertEquals(LOCAL, this.counts.of("Lobby-1"));
    this.counts.of("Lobby-1");

    assertEquals(2, this.clusterCalls.get(), "one retry per delay, not one per call");
  }

  @Test
  void aCountCachedBeforeAFailureIsNotServedStaleAfterIt() {
    this.counts.of("Lobby-1");
    this.clusterDown.set(true);
    this.now.addAndGet(PlayerCounts.CACHE_MILLIS);
    this.counts.of("Lobby-1");
    this.clusterDown.set(false);
    this.now.addAndGet(PlayerCounts.FALLBACK_MILLIS);

    // After recovery the cluster answers fresh, not with the value cached before the outage.
    assertEquals(CLUSTER, this.counts.of("Lobby-1"));
    assertEquals(3, this.clusterCalls.get());
  }
}
