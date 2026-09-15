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

import com.btcvelocity.api.bridge.BridgeMessage;
import com.btcvelocity.proxy.bridge.BridgeIngressPolicy.Rejection;
import com.btcvelocity.proxy.bridge.BridgeIngressPolicy.SourceDecision;
import com.velocitypowered.api.proxy.server.ServerInfo;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Adversarial cases for the bridge ingress: forged client, valid backend, unknown backend,
 * usurped name, and messages that speak in another backend's name.
 */
class BridgeIngressPolicyTest {

  private static final ServerInfo LOBBY = new ServerInfo("lobby",
      new InetSocketAddress("10.0.0.10", 30200));
  private static final ServerInfo SKY = new ServerInfo("sky",
      new InetSocketAddress("10.0.0.11", 30201));

  /** The proxy's registry as the policy sees it: name -> registered info. */
  private static final Function<String, Optional<ServerInfo>> REGISTRY =
      name -> Optional.ofNullable(Map.of("lobby", LOBBY, "sky", SKY).get(name));

  @Test
  void forgedClientSourceIsNotABackend() {
    // A Player source yields no ServerInfo at all.
    final SourceDecision decision = BridgeIngressPolicy.authenticateSource(null, REGISTRY);
    assertFalse(decision.accepted());
    assertEquals(Rejection.NOT_A_BACKEND, decision.rejection());
  }

  @Test
  void registeredBackendIsAccepted() {
    final SourceDecision decision = BridgeIngressPolicy.authenticateSource(LOBBY, REGISTRY);
    assertTrue(decision.accepted());
    assertEquals("lobby", decision.sourceServer());
  }

  @Test
  void unknownBackendIsRejected() {
    final ServerInfo ghost = new ServerInfo("ghost", new InetSocketAddress("10.0.0.99", 1));
    final SourceDecision decision = BridgeIngressPolicy.authenticateSource(ghost, REGISTRY);
    assertFalse(decision.accepted());
    assertEquals(Rejection.UNREGISTERED_SOURCE, decision.rejection());
  }

  @Test
  void usurpedNameWithAnotherAddressIsRejected() {
    // Same name as a registered backend, but the connection points elsewhere: a name is not an
    // identity. This is the witness that name-only lookup would have accepted.
    final ServerInfo impostor = new ServerInfo("lobby", new InetSocketAddress("10.0.0.66", 30200));
    assertTrue(REGISTRY.apply("lobby").isPresent(), "temoin : le nom seul serait accepte");
    final SourceDecision decision = BridgeIngressPolicy.authenticateSource(impostor, REGISTRY);
    assertFalse(decision.accepted());
    assertEquals(Rejection.IDENTITY_MISMATCH, decision.rejection());
  }

  @Test
  void selfReportFromTheRightBackendPasses() {
    final BridgeMessage message = health("lobby", "lobby");
    assertEquals(Optional.empty(), BridgeIngressPolicy.verifyDeclaredIdentity("lobby", message));
  }

  @Test
  void envelopeClaimingAnotherSourceIsRejected() {
    final BridgeMessage message = health("sky", "lobby");
    assertEquals(Optional.of(Rejection.ENVELOPE_SOURCE_MISMATCH),
        BridgeIngressPolicy.verifyDeclaredIdentity("lobby", message));
  }

  @Test
  void healthReportAboutAnotherBackendIsRejected() {
    // Envelope is honest, but the report would poison another backend's registry entry.
    final BridgeMessage message = health("lobby", "sky");
    assertEquals(Optional.of(Rejection.DECLARED_NAME_MISMATCH),
        BridgeIngressPolicy.verifyDeclaredIdentity("lobby", message));
  }

  @Test
  void worldLoadedAboutAnotherBackendIsRejected() {
    final BridgeMessage message = new BridgeMessage.WorldLoaded(
        envelope("world_loaded", "lobby"), "sky", "world", 12L);
    assertEquals(Optional.of(Rejection.DECLARED_NAME_MISMATCH),
        BridgeIngressPolicy.verifyDeclaredIdentity("lobby", message));
  }

  @Test
  void commandNamingATargetIsNotASelfReport() {
    // A status request names the server it asks about; that is a target, not an identity claim.
    final BridgeMessage message = new BridgeMessage.RequestStatus(
        envelope("request_status", "lobby"), "sky");
    assertEquals(Optional.empty(), BridgeIngressPolicy.verifyDeclaredIdentity("lobby", message));
  }

  private static BridgeMessage.Envelope envelope(final String type, final String source) {
    final long now = System.currentTimeMillis();
    return new BridgeMessage.Envelope(BridgeMessage.VERSION, UUID.randomUUID(), type, source,
        "proxy", now, now + 10_000L);
  }

  private static BridgeMessage health(final String envelopeSource, final String reportedName) {
    return new BridgeMessage.Health(envelope("health", envelopeSource), reportedName,
        1.5d, 20.0d, 3, List.of("world"));
  }
}
