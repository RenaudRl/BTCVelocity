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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** The registry is where connection outcomes land and where routing asks. */
class BackendHealthRegistryCircuitTest {

  private static final String SERVER = "Lobby-1";

  private final AtomicLong now = new AtomicLong(5_000_000L);
  private final BackendHealthRegistry registry = new BackendHealthRegistry(
      BackendHealthRegistry.DEFAULT_FRESHNESS_MILLIS, this.now::get,
      new BackendCircuitBreaker(2, 10_000L, 20_000L, this.now::get));

  @Test
  void failedConnectionsMakeRoutingAvoidTheBackendThenTryItAgain() {
    this.registry.recordConnectionFailure(SERVER);
    assertFalse(this.registry.isAvoided(SERVER));

    this.registry.recordConnectionFailure(SERVER);
    assertTrue(this.registry.isAvoided(SERVER));

    this.now.addAndGet(20_000L);
    assertFalse(this.registry.isAvoided(SERVER), "half-open: tried again");
    assertEquals(BackendCircuitBreaker.State.HALF_OPEN, this.registry.circuitState(SERVER));
  }

  @Test
  void theNextSuccessRehabilitatesIt() {
    this.registry.recordConnectionFailure(SERVER);
    this.registry.recordConnectionFailure(SERVER);
    this.now.addAndGet(20_000L);

    this.registry.recordConnectionSuccess(SERVER);

    assertEquals(BackendCircuitBreaker.State.CLOSED, this.registry.circuitState(SERVER));
  }

  @Test
  void forgettingAServerForgetsItsCircuitToo() {
    // Witness: a service restarted under the same name must not inherit the dead one's failures.
    this.registry.recordConnectionFailure(SERVER);
    this.registry.recordConnectionFailure(SERVER);

    this.registry.forget(SERVER);

    assertFalse(this.registry.isAvoided(SERVER));
  }

  @Test
  void aHealthReportAndTheCircuitAreIndependentEvidence() {
    this.registry.recordConnectionFailure(SERVER);
    this.registry.recordConnectionFailure(SERVER);

    // A circuit says nothing about load, and a missing report says nothing about connections.
    assertEquals(BackendHealthRegistry.UNKNOWN_MSPT, this.registry.getMspt(SERVER));
    assertTrue(this.registry.isAvoided(SERVER));
    assertFalse(this.registry.isAvoided("Lobby-2"));
  }

  @Test
  void theDefaultRegistryStartsWithTheDefaultThresholds() {
    final BackendHealthRegistry byDefault = new BackendHealthRegistry();

    for (int i = 0; i < BackendCircuitBreaker.DEFAULT_FAILURES - 1; i++) {
      byDefault.recordConnectionFailure(SERVER);
    }
    assertFalse(byDefault.isAvoided(SERVER));

    byDefault.recordConnectionFailure(SERVER);
    assertTrue(byDefault.isAvoided(SERVER));
  }
}
