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

import static com.btcvelocity.proxy.bridge.BackendConnectionOutcome.FAILURE;
import static com.btcvelocity.proxy.bridge.BackendConnectionOutcome.IGNORE;
import static com.btcvelocity.proxy.bridge.BackendConnectionOutcome.SUCCESS;
import static com.btcvelocity.proxy.bridge.BackendConnectionOutcome.classify;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BackendConnectionOutcomeTest {

  // Arguments: successful, threw, abandoned, proxySideFailure, closedByBackend.

  @Test
  void aConnectedPlayerIsASuccess() {
    assertEquals(SUCCESS, classify(true, false, false, false, false));
  }

  @Test
  void anExceptionFromTheBackendIsAFailure() {
    assertEquals(FAILURE, classify(false, true, false, false, false));
  }

  @Test
  void aBackendThatAnsweredAndRefusedIsNeither() {
    // Full, whitelist, maintenance: the result is a plain disconnect, with no exception.
    assertEquals(IGNORE, classify(false, false, false, false, false));
  }

  @Test
  void anExceptionRaisedByTheProxyOrAPluginIsIgnored() {
    // Witness: a ServerConnectedEvent handler that throws must not take a healthy backend out.
    assertEquals(IGNORE, classify(false, true, false, true, false));
  }

  @Test
  void aPlayerWhoLeftOrWasCutByTheProxyIsIgnoredWhateverTheCause() {
    assertEquals(IGNORE, classify(false, true, true, false, false));
    assertEquals(IGNORE, classify(false, false, true, false, true));
  }

  @Test
  void aBackendThatClosedWithoutAPacketDuringConfigOrTransitionIsAFailure() {
    // Same event as the login phase, which fails with an exception; here the result is normal.
    assertEquals(FAILURE, classify(false, false, false, false, true));
  }

  @Test
  void aSuccessIsNeverWithdrawnByLeftoverMarkers() {
    assertEquals(SUCCESS, classify(true, false, true, true, true));
  }
}
