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

package com.btcvelocity.proxy.bridge;

import static com.btcvelocity.proxy.bridge.BackendCircuitBreaker.State.CLOSED;
import static com.btcvelocity.proxy.bridge.BackendCircuitBreaker.State.HALF_OPEN;
import static com.btcvelocity.proxy.bridge.BackendCircuitBreaker.State.OPEN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class BackendCircuitBreakerTest {

  private static final long WINDOW = 10_000L;
  private static final long OPEN_FOR = 20_000L;
  private static final String LOBBY = "Lobby-1";

  private final AtomicLong now = new AtomicLong(1_000_000L);
  private final BackendCircuitBreaker breaker =
      new BackendCircuitBreaker(3, WINDOW, OPEN_FOR, this.now::get);

  private void fail(final int times) {
    for (int i = 0; i < times; i++) {
      this.breaker.recordFailure(LOBBY);
    }
  }

  @Test
  void aBackendNeverSeenFailingIsClosed() {
    assertEquals(CLOSED, this.breaker.state(LOBBY));
    assertFalse(this.breaker.isAvoided(LOBBY));
  }

  @Test
  void failuresBelowTheThresholdLeaveItClosed() {
    fail(2);

    assertEquals(CLOSED, this.breaker.state(LOBBY));
    assertFalse(this.breaker.isAvoided(LOBBY));
  }

  @Test
  void theThresholdFailureOpensItAndSaysSo() {
    fail(2);

    assertTrue(this.breaker.recordFailure(LOBBY), "the failure that opens it is reported");
    assertEquals(OPEN, this.breaker.state(LOBBY));
    assertTrue(this.breaker.isAvoided(LOBBY));
  }

  @Test
  void failuresSpreadOverMoreThanTheWindowNeverOpenIt() {
    // Witness: a counter without a window would open on the third failure of a whole day.
    for (int i = 0; i < 10; i++) {
      this.breaker.recordFailure(LOBBY);
      this.now.addAndGet(WINDOW / 2 + 1);
    }

    assertEquals(CLOSED, this.breaker.state(LOBBY));
  }

  @Test
  void aFailureExactlyOneWindowOldNoLongerCounts() {
    fail(2);
    this.now.addAndGet(WINDOW);

    this.breaker.recordFailure(LOBBY);

    assertEquals(CLOSED, this.breaker.state(LOBBY), "two of the three are out of the window");
  }

  @Test
  void aFailureInsideTheWindowStillCounts() {
    fail(2);
    this.now.addAndGet(WINDOW - 1);

    this.breaker.recordFailure(LOBBY);

    assertEquals(OPEN, this.breaker.state(LOBBY));
  }

  @Test
  void itStaysOpenForTheDelayThenIsHalfOpen() {
    fail(3);

    this.now.addAndGet(OPEN_FOR - 1);
    assertEquals(OPEN, this.breaker.state(LOBBY));

    this.now.addAndGet(1);
    assertEquals(HALF_OPEN, this.breaker.state(LOBBY));
    assertFalse(this.breaker.isAvoided(LOBBY), "a half-open backend may be tried");
  }

  @Test
  void aSuccessWhileHalfOpenClosesItForGood() {
    fail(3);
    this.now.addAndGet(OPEN_FOR);

    assertTrue(this.breaker.recordSuccess(LOBBY), "the success that clears it is reported");

    assertEquals(CLOSED, this.breaker.state(LOBBY));
    // The old failures are gone: it takes the full threshold again to open.
    fail(2);
    assertEquals(CLOSED, this.breaker.state(LOBBY));
  }

  @Test
  void aFailureWhileHalfOpenOpensItAgainAtOnce() {
    fail(3);
    this.now.addAndGet(OPEN_FOR);

    assertTrue(this.breaker.recordFailure(LOBBY));

    assertEquals(OPEN, this.breaker.state(LOBBY), "one failed trial is enough, not three more");
    this.now.addAndGet(OPEN_FOR - 1);
    assertEquals(OPEN, this.breaker.state(LOBBY), "and for a whole delay again");
  }

  @Test
  void aSuccessClearsTheFailuresCountedSoFar() {
    fail(2);
    this.breaker.recordSuccess(LOBBY);
    fail(2);

    assertEquals(CLOSED, this.breaker.state(LOBBY), "failures are consecutive, not cumulative");
  }

  @Test
  void aSuccessOnAClosedBackendReportsNothing() {
    assertFalse(this.breaker.recordSuccess(LOBBY));
    fail(1);
    assertFalse(this.breaker.recordSuccess(LOBBY));
  }

  @Test
  void aLateFailureDoesNotExtendAnOpenCircuit() {
    // Witness: an attempt started before the circuit opened can still fail after it did.
    fail(3);
    this.now.addAndGet(OPEN_FOR - 1);

    assertFalse(this.breaker.recordFailure(LOBBY));
    this.now.addAndGet(1);

    assertEquals(HALF_OPEN, this.breaker.state(LOBBY));
  }

  @Test
  void backendsAreIndependentAndNamesIgnoreCase() {
    fail(3);

    assertTrue(this.breaker.isAvoided("lobby-1"), "Velocity compares names without case");
    assertFalse(this.breaker.isAvoided("Lobby-2"));
  }

  @Test
  void forgettingABackendClearsItsCircuit() {
    fail(3);

    this.breaker.forget(LOBBY);

    assertEquals(CLOSED, this.breaker.state(LOBBY));
  }

  @Test
  void zeroFailuresTurnsTheBreakerOff() {
    final BackendCircuitBreaker off = new BackendCircuitBreaker(0, 0, 0, this.now::get);

    for (int i = 0; i < 50; i++) {
      assertFalse(off.recordFailure(LOBBY));
    }

    assertFalse(off.isAvoided(LOBBY));
  }

  @Test
  void anActiveBreakerNeedsAPositiveWindowAndDelay() {
    assertThrows(IllegalArgumentException.class,
        () -> new BackendCircuitBreaker(3, 0, OPEN_FOR, this.now::get));
    assertThrows(IllegalArgumentException.class,
        () -> new BackendCircuitBreaker(3, WINDOW, 0, this.now::get));
  }

  @Test
  void concurrentFailuresOpenItOnceAndNeverLoseTheCount() throws InterruptedException {
    final int threads = 8;
    final AtomicInteger opened = new AtomicInteger();
    final Thread[] workers = new Thread[threads];
    for (int i = 0; i < threads; i++) {
      workers[i] = new Thread(() -> {
        if (this.breaker.recordFailure(LOBBY)) {
          opened.incrementAndGet();
        }
      });
    }
    for (final Thread worker : workers) {
      worker.start();
    }
    for (final Thread worker : workers) {
      worker.join();
    }

    assertEquals(OPEN, this.breaker.state(LOBBY));
    assertEquals(1, opened.get(), "exactly one failure is the one that opens it");
  }
}
