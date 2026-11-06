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

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class BackendCircuitBreakerTest {

  private static final long WINDOW = 10_000L;
  private static final long OPEN_FOR = 20_000L;
  private static final String LOBBY = "Lobby-1";
  private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
  private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
  private static final UUID CAROL = UUID.fromString("00000000-0000-0000-0000-00000000000c");
  private static final UUID DAVE = UUID.fromString("00000000-0000-0000-0000-00000000000d");

  private final AtomicLong now = new AtomicLong(1_000_000L);
  private final BackendCircuitBreaker breaker =
      new BackendCircuitBreaker(3, WINDOW, OPEN_FOR, this.now::get);

  /** {@code times} failures from as many different players (ALICE, BOB, DAVE), one each. */
  private void fail(final int times) {
    final UUID[] players = {ALICE, BOB, DAVE};
    for (int i = 0; i < times; i++) {
      this.breaker.recordFailure(LOBBY, players[i]);
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

    assertTrue(this.breaker.recordFailure(LOBBY, CAROL), "the failure that opens it is reported");
    assertEquals(OPEN, this.breaker.state(LOBBY));
    assertTrue(this.breaker.isAvoided(LOBBY));
  }

  @Test
  void onePlayerFailingOverAndOverNeverOpensIt() {
    // Witness: a broken client or a mod mismatch must not take a healthy backend out of routing.
    for (int i = 0; i < 10; i++) {
      assertFalse(this.breaker.recordFailure(LOBBY, ALICE));
    }

    assertEquals(CLOSED, this.breaker.state(LOBBY));
    assertFalse(this.breaker.isAvoided(LOBBY));
  }

  @Test
  void aThirdPlayerFailingAfterManyFailuresOfTheFirstTwoOpensIt() {
    // Witness: the ten failures of ALICE count once, so two players are not enough.
    for (int i = 0; i < 10; i++) {
      this.breaker.recordFailure(LOBBY, ALICE);
    }
    assertFalse(this.breaker.recordFailure(LOBBY, BOB), "two players are not three");
    assertEquals(CLOSED, this.breaker.state(LOBBY));

    assertTrue(this.breaker.recordFailure(LOBBY, CAROL), "the third player opens it");
    assertEquals(OPEN, this.breaker.state(LOBBY));
  }

  @Test
  void threeFailuresOfOnePlayerThenOneOfAnotherStayClosedUntilAThirdPlayerFails() {
    for (int i = 0; i < 3; i++) {
      assertFalse(this.breaker.recordFailure(LOBBY, ALICE));
    }
    assertFalse(this.breaker.recordFailure(LOBBY, BOB), "A counts once, so A and B are two");
    assertEquals(CLOSED, this.breaker.state(LOBBY));

    assertTrue(this.breaker.recordFailure(LOBBY, CAROL), "C is the third player: it opens");
    assertEquals(OPEN, this.breaker.state(LOBBY));
  }

  @Test
  void failuresSpreadOverMoreThanTheWindowNeverOpenIt() {
    // Witness: a counter without a window would open on the third failure of a whole day.
    for (int i = 0; i < 10; i++) {
      this.breaker.recordFailure(LOBBY, i % 2 == 0 ? ALICE : BOB);
      this.now.addAndGet(WINDOW / 2 + 1);
    }

    assertEquals(CLOSED, this.breaker.state(LOBBY));
  }

  @Test
  void aFailureExactlyOneWindowOldNoLongerCounts() {
    fail(2);
    this.now.addAndGet(WINDOW);

    this.breaker.recordFailure(LOBBY, CAROL);

    assertEquals(CLOSED, this.breaker.state(LOBBY), "two of the three are out of the window");
  }

  @Test
  void aFailureInsideTheWindowStillCounts() {
    fail(2);
    this.now.addAndGet(WINDOW - 1);

    this.breaker.recordFailure(LOBBY, CAROL);

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
  void theFailureOfANewPlayerWhileHalfOpenOpensItAgainAtOnce() {
    fail(3);
    this.now.addAndGet(OPEN_FOR);

    assertTrue(this.breaker.recordFailure(LOBBY, CAROL));

    assertEquals(OPEN, this.breaker.state(LOBBY), "one failed trial is enough, not three more");
    this.now.addAndGet(OPEN_FOR - 1);
    assertEquals(OPEN, this.breaker.state(LOBBY), "and for a whole delay again");
  }

  @Test
  void theFailureOfAPlayerAlreadyCountedWhileHalfOpenDoesNotOpenItAgain() {
    // Witness: the players who opened it retrying must not keep it shut on their own.
    fail(3);
    this.now.addAndGet(OPEN_FOR);

    assertFalse(this.breaker.recordFailure(LOBBY, ALICE));
    assertFalse(this.breaker.recordFailure(LOBBY, BOB));

    assertEquals(HALF_OPEN, this.breaker.state(LOBBY));
  }

  @Test
  void aPlayerWhoReopenedItIsCountedFromThenOn() {
    fail(3);
    this.now.addAndGet(OPEN_FOR);
    this.breaker.recordFailure(LOBBY, CAROL);
    this.now.addAndGet(OPEN_FOR);

    assertFalse(this.breaker.recordFailure(LOBBY, CAROL), "already counted at the last trial");
    assertEquals(HALF_OPEN, this.breaker.state(LOBBY));
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

    assertFalse(this.breaker.recordFailure(LOBBY, CAROL));
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
      assertFalse(off.recordFailure(LOBBY, UUID.randomUUID()));
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
        if (this.breaker.recordFailure(LOBBY, UUID.randomUUID())) {
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
