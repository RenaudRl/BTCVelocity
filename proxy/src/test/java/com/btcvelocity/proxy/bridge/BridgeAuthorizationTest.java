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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.btcvelocity.api.bridge.BridgeMessage;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Authorization is asked separately from identity, and an undeclared policy stays inert. */
class BridgeAuthorizationTest {

  private static final long NOW = 1_700_000_000_000L;

  private static BridgeMessage.ConnectRequest connect(final String source, final String target) {
    final BridgeMessage.Envelope envelope = new BridgeMessage.Envelope(BridgeMessage.VERSION,
        UUID.randomUUID(), "connect_request", source, "proxy", NOW, NOW + 10_000L);
    return new BridgeMessage.ConnectRequest(envelope, UUID.randomUUID(), target, null);
  }

  private static BridgeMessage.PartyWarp warp(final String source, final String target) {
    final BridgeMessage.Envelope envelope = new BridgeMessage.Envelope(BridgeMessage.VERSION,
        UUID.randomUUID(), "party_warp", source, "proxy", NOW, NOW + 10_000L);
    return new BridgeMessage.PartyWarp(envelope, List.of(UUID.randomUUID()), target, null);
  }

  @Test
  void anUndeclaredPolicyRefusesNothing() {
    // The house convention: a policy nobody declared must not silently refuse the traffic that
    // worked yesterday. It is announced at startup instead.
    final BridgeAuthorization policy = BridgeAuthorization.parse(null, null);

    assertFalse(policy.filtersSources());
    assertFalse(policy.filtersTargets());
    assertNull(policy.refuse("n-importe-qui", connect("n-importe-qui", "n-importe-ou")));
  }

  @Test
  void aBlankDeclarationIsTheSameAsNone() {
    final BridgeAuthorization policy = BridgeAuthorization.parse("  ", ",, ,");

    assertFalse(policy.filtersSources());
    assertFalse(policy.filtersTargets());
  }

  @Test
  void aDeclaredSourceListRefusesEveryoneElse() {
    final BridgeAuthorization policy = BridgeAuthorization.parse("lobby, sky", null);

    assertNull(policy.refuse("lobby", connect("lobby", "creatif")), "témoin : celui-ci passe");
    assertEquals(BridgeMessage.ErrorCode.BACKEND_NOT_ALLOWED,
        policy.refuse("creatif", connect("creatif", "lobby")));
  }

  @Test
  void aDeclaredTargetListRefusesAnyOtherDestination() {
    final BridgeAuthorization policy = BridgeAuthorization.parse(null, "lobby");

    assertNull(policy.refuse("sky", connect("sky", "lobby")), "témoin : celui-ci passe");
    assertEquals(BridgeMessage.ErrorCode.TARGET_NOT_ALLOWED,
        policy.refuse("sky", connect("sky", "creatif")));
    assertEquals(BridgeMessage.ErrorCode.TARGET_NOT_ALLOWED,
        policy.refuse("sky", warp("sky", "creatif")), "un party warp nomme aussi une destination");
  }

  @Test
  void theDestinationIsTheOneInThePayloadNotTheEnvelopeTarget() {
    // The only backend emitter in production spells the envelope's targetBackend "proxy" in its
    // own code, while the proxy may declare another identity. Reading that field as a destination
    // would refuse valid traffic for a spelling.
    final BridgeAuthorization policy = BridgeAuthorization.parse(null, "lobby");
    final BridgeMessage.ConnectRequest request = connect("sky", "lobby");

    assertEquals("proxy", request.targetBackend());
    assertNull(policy.refuse("sky", request));
  }

  @Test
  void aSelfReportIsNeverRefusedForItsDestination() {
    // Health and world reports name no destination: filtering them on one would drop the very
    // messages that keep the registries fresh.
    final BridgeMessage.Envelope envelope = new BridgeMessage.Envelope(BridgeMessage.VERSION,
        UUID.randomUUID(), "health", "sky", "proxy", NOW, NOW + 10_000L);
    final BridgeMessage.Health health =
        new BridgeMessage.Health(envelope, "sky", 1.0d, 20.0d, 0, List.of());
    final BridgeAuthorization policy = BridgeAuthorization.parse(null, "lobby");

    assertNull(policy.refuse("sky", health));
  }

  @Test
  void bothHalvesApplyIndependently() {
    final BridgeAuthorization policy = BridgeAuthorization.parse("lobby", "creatif");

    assertNull(policy.refuse("lobby", connect("lobby", "creatif")));
    assertEquals(BridgeMessage.ErrorCode.BACKEND_NOT_ALLOWED,
        policy.refuse("sky", connect("sky", "creatif")), "source refusée d'abord");
    assertEquals(BridgeMessage.ErrorCode.TARGET_NOT_ALLOWED,
        policy.refuse("lobby", connect("lobby", "sky")), "source admise, cible refusée");
  }

  @Test
  void theDeclarationIsTrimmedAndOrderPreserved() {
    final BridgeAuthorization policy = BridgeAuthorization.parse(" lobby , sky ", null);

    assertTrue(policy.allowedSources().contains("lobby"));
    assertTrue(policy.allowedSources().contains("sky"));
    assertEquals(2, policy.allowedSources().size());
  }
}
