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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.btcvelocity.api.bridge.BridgeCodec;
import com.btcvelocity.api.bridge.BridgeMessage;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The proxy's half of the acknowledgement contract: correlation by message id, addressing, refusal
 * categories, and which messages are owed an answer at all.
 */
class BridgeResponsesTest {

  private static final String PROXY = "btc-proxy";
  private static final String BACKEND = "lobby";
  private static final long NOW = 1_700_000_000_000L;

  private static BridgeMessage.ConnectRequest connectRequest() {
    final BridgeMessage.Envelope envelope = new BridgeMessage.Envelope(BridgeMessage.VERSION,
        UUID.randomUUID(), "connect_request", BACKEND, PROXY, NOW - 100L, NOW + 10_000L);
    return new BridgeMessage.ConnectRequest(envelope, UUID.randomUUID(), "sky", null);
  }

  @Test
  void anAcknowledgementReusesTheMessageIdOfTheRequest() {
    // This is the correlation convention the backend already uses. A fresh id would leave the
    // backend with an answer it cannot tie to anything.
    final BridgeMessage.ConnectRequest request = connectRequest();

    final BridgeMessage.Ack ack = BridgeResponses.ack(request, PROXY, false, NOW);

    assertEquals(request.messageId(), ack.messageId());
    assertEquals("ack", ack.type());
    assertFalse(ack.duplicate());
  }

  @Test
  void anAcknowledgementIsAddressedBackToItsSender() {
    final BridgeMessage.ConnectRequest request = connectRequest();

    final BridgeMessage.Ack ack = BridgeResponses.ack(request, PROXY, true, NOW);

    assertEquals(PROXY, ack.sourceBackend(), "the proxy answers under its declared identity");
    assertEquals(BACKEND, ack.targetBackend(), "the answer goes back to the backend that asked");
    assertTrue(ack.duplicate());
  }

  @Test
  void anAcknowledgementSurvivesAnEncodeDecodeRoundTrip() {
    // The response must be readable by the other end with the shared codec, bounds included.
    final BridgeMessage.ConnectRequest request = connectRequest();
    final BridgeMessage.Ack ack = BridgeResponses.ack(request, PROXY, false, NOW);

    final BridgeCodec.DecodeResult decoded = BridgeCodec.decodeResult(BridgeCodec.encode(ack), NOW,
        BridgeCodec.Limits.defaults());

    assertTrue(decoded.accepted());
    assertEquals(request.messageId(), decoded.messageId());
    assertEquals(ack, decoded.message());
  }

  @Test
  void aRefusalNamesTheMessageItRefuses() {
    final UUID refused = UUID.randomUUID();

    final BridgeMessage.Nack nack = BridgeResponses.nack(refused, BACKEND, PROXY,
        BridgeMessage.ErrorCode.EXPIRED, NOW);

    assertEquals(refused, nack.messageId());
    assertEquals(BridgeMessage.ErrorCode.EXPIRED, nack.error());
    assertEquals(BACKEND, nack.targetBackend());
  }

  @Test
  void decodeFailuresCollapseToInvalidEnvelopeExceptTheActionableOnes() {
    assertEquals(BridgeMessage.ErrorCode.PAYLOAD_TOO_LARGE,
        BridgeResponses.categoryOf(BridgeCodec.DecodeError.PAYLOAD_TOO_LARGE));
    assertEquals(BridgeMessage.ErrorCode.EXPIRED,
        BridgeResponses.categoryOf(BridgeCodec.DecodeError.EXPIRED));
    assertEquals(BridgeMessage.ErrorCode.EXPIRED,
        BridgeResponses.categoryOf(BridgeCodec.DecodeError.NOT_YET_VALID));
    assertEquals(BridgeMessage.ErrorCode.UNSUPPORTED,
        BridgeResponses.categoryOf(BridgeCodec.DecodeError.UNKNOWN_KIND));

    // Witness: telling a sender precisely how its payload was malformed tells an attacker where
    // the parser stops. These must NOT keep their own category.
    assertEquals(BridgeMessage.ErrorCode.INVALID_ENVELOPE,
        BridgeResponses.categoryOf(BridgeCodec.DecodeError.MALFORMED));
    assertEquals(BridgeMessage.ErrorCode.INVALID_ENVELOPE,
        BridgeResponses.categoryOf(BridgeCodec.DecodeError.INVALID_UTF8));
    assertEquals(BridgeMessage.ErrorCode.INVALID_ENVELOPE,
        BridgeResponses.categoryOf(BridgeCodec.DecodeError.UNKNOWN_FIELD));
  }

  @Test
  void anUnauthenticatedSourceGetsNoAnswerAtAll() {
    // Answering would confirm the channel exists to whoever forged the message.
    assertNull(BridgeResponses.categoryOf(BridgeIngressPolicy.Rejection.NOT_A_BACKEND));
    assertNull(BridgeResponses.categoryOf(BridgeIngressPolicy.Rejection.UNREGISTERED_SOURCE));
    assertNull(BridgeResponses.categoryOf(BridgeIngressPolicy.Rejection.IDENTITY_MISMATCH));

    // Witness: an authenticated backend that lies about its name DOES get a categorized refusal,
    // otherwise the two branches above would be indistinguishable from "never answer".
    assertNotNull(BridgeResponses.categoryOf(BridgeIngressPolicy.Rejection.ENVELOPE_SOURCE_MISMATCH));
    assertEquals(BridgeMessage.ErrorCode.BACKEND_NOT_ALLOWED,
        BridgeResponses.categoryOf(BridgeIngressPolicy.Rejection.DECLARED_NAME_MISMATCH));
  }

  @Test
  void onlyExecutedCommandsAreAcknowledged() {
    final BridgeMessage.Envelope health = new BridgeMessage.Envelope(BridgeMessage.VERSION,
        UUID.randomUUID(), "health", BACKEND, PROXY, NOW - 100L, NOW + 10_000L);
    final BridgeMessage.Envelope warp = new BridgeMessage.Envelope(BridgeMessage.VERSION,
        UUID.randomUUID(), "party_warp", BACKEND, PROXY, NOW - 100L, NOW + 10_000L);

    assertTrue(BridgeResponses.isAcknowledgeable(connectRequest()));
    assertTrue(BridgeResponses.isAcknowledgeable(
        new BridgeMessage.PartyWarp(warp, List.of(UUID.randomUUID()), "sky", null)));

    // A self-report is not a command: acknowledging it would double the health task's traffic.
    assertFalse(BridgeResponses.isAcknowledgeable(
        new BridgeMessage.Health(health, BACKEND, 1.0d, 20.0d, 0, List.of())));
  }
}
