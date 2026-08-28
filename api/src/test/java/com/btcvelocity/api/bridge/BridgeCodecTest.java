/*
 * Copyright (C) 2018-2026 Velocity Contributors
 *
 * The Velocity API is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package com.btcvelocity.api.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BridgeCodecTest {

  @Test
  void roundTripsV2SocialRequestAndPreservesEnvelope() {
    final long now = System.currentTimeMillis();
    final UUID messageId = UUID.randomUUID();
    final BridgeMessage.ConnectRequest request = new BridgeMessage.ConnectRequest(
        new BridgeMessage.Envelope(BridgeMessage.VERSION, messageId, "connect_request", "proxy",
            "btc", now - 1000L, now + 30_000L), UUID.randomUUID(), "btc-copy");

    final BridgeCodec.DecodeResult result = BridgeCodec.decodeResult(
        BridgeCodec.encode(request), now, BridgeCodec.Limits.defaults());

    assertTrue(result.accepted());
    final BridgeMessage.ConnectRequest decoded = assertInstanceOf(
        BridgeMessage.ConnectRequest.class, result.message());
    assertEquals(messageId, decoded.messageId());
    assertEquals("proxy", decoded.sourceBackend());
    assertEquals("btc", decoded.targetBackend());
    assertEquals("btc-copy", decoded.targetServer());
  }

  @Test
  void rejectsLegacyFlatPayload() {
    final String legacy = "{\"type\":\"connect_request\",\"uuid\":\""
        + UUID.randomUUID() + "\",\"targetServer\":\"btc-copy\"}";

    final BridgeCodec.DecodeResult result = BridgeCodec.decodeResult(
        legacy.getBytes(StandardCharsets.UTF_8), System.currentTimeMillis(),
        BridgeCodec.Limits.defaults());

    assertEquals(BridgeCodec.DecodeError.UNKNOWN_FIELD, result.error());
  }
}
