/*
 * Copyright (C) 2018-2026 Velocity Contributors
 *
 * The Velocity API is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package com.btcvelocity.api.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BridgeCodecTest {

  @Test
  void roundTripsV2SocialRequestAndPreservesEnvelope() {
    final long now = System.currentTimeMillis();
    final UUID messageId = UUID.randomUUID();
    final BridgeMessage.ConnectRequest request = new BridgeMessage.ConnectRequest(
        new BridgeMessage.Envelope(BridgeMessage.VERSION, messageId, "connect_request", "proxy",
            "btc", now - 1000L, now + 30_000L), UUID.randomUUID(), "btc-copy", null);

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

  /**
   * The literals here are pinned identically in BTC-CORE's {@code BridgeCodecPlatformCheck}. What
   * this guards is not a wrong verdict but <em>divergence</em>: two codecs that disagree on the
   * field name or on the spelling of a value produce a pair that compiles, starts, and then refuses
   * every message on a live server. Either build must fail first.
   */
  @Test
  void theWireNameAndSpellingMatchTheBackendCopy() {
    final long now = System.currentTimeMillis();
    final String encoded = new String(BridgeCodec.encode(new BridgeMessage.ConnectRequest(
        envelope("connect_request", now), UUID.randomUUID(), "btc",
        BridgeMessage.Platform.BEDROCK)), StandardCharsets.UTF_8);

    assertTrue(encoded.contains("\"originPlatform\":\"BEDROCK\""));
  }

  @Test
  void aStatedPlatformSurvivesTheRoundTrip() {
    final long now = System.currentTimeMillis();
    final BridgeMessage.ConnectRequest request = new BridgeMessage.ConnectRequest(
        envelope("connect_request", now), UUID.randomUUID(), "btc",
        BridgeMessage.Platform.BEDROCK);

    final BridgeCodec.DecodeResult result = BridgeCodec.decodeResult(
        BridgeCodec.encode(request), now, BridgeCodec.Limits.defaults());

    assertTrue(result.accepted());
    assertEquals(BridgeMessage.Platform.BEDROCK,
        assertInstanceOf(BridgeMessage.ConnectRequest.class, result.message()).originPlatform());
  }

  /**
   * The compatibility direction that matters: a sender that predates the field keeps being
   * understood. Without this, shipping the reader first would take the whole bridge down.
   */
  @Test
  void aPayloadWithoutThePlatformFieldIsStillAccepted() {
    final long now = System.currentTimeMillis();
    final String withoutField = "{\"version\":2,\"messageId\":\"" + UUID.randomUUID()
        + "\",\"type\":\"connect_request\",\"sourceBackend\":\"btc\",\"targetBackend\":\"btc-proxy\""
        + ",\"issuedAt\":" + (now - 1000L) + ",\"expiresAt\":" + (now + 30_000L)
        + ",\"payload\":{\"uuid\":\"" + UUID.randomUUID() + "\",\"targetServer\":\"btc\"}}";

    final BridgeCodec.DecodeResult result = BridgeCodec.decodeResult(
        withoutField.getBytes(StandardCharsets.UTF_8), now, BridgeCodec.Limits.defaults());

    assertTrue(result.accepted());
    assertNull(assertInstanceOf(BridgeMessage.ConnectRequest.class, result.message())
        .originPlatform());
  }

  @Test
  void aPartyCarriesOnePlatformPerMemberThroughTheRoundTrip() {
    final long now = System.currentTimeMillis();
    final UUID alice = UUID.randomUUID();
    final UUID bob = UUID.randomUUID();
    final BridgeMessage.PartyWarp warp = new BridgeMessage.PartyWarp(envelope("party_warp", now),
        List.of(alice, bob), "btc",
        Map.of(alice, BridgeMessage.Platform.JAVA, bob, BridgeMessage.Platform.BEDROCK));

    final BridgeCodec.DecodeResult result = BridgeCodec.decodeResult(
        BridgeCodec.encode(warp), now, BridgeCodec.Limits.defaults());

    assertTrue(result.accepted());
    final BridgeMessage.PartyWarp decoded =
        assertInstanceOf(BridgeMessage.PartyWarp.class, result.message());
    assertEquals(BridgeMessage.Platform.JAVA, decoded.memberPlatforms().get(alice));
    assertEquals(BridgeMessage.Platform.BEDROCK, decoded.memberPlatforms().get(bob));
  }

  /** A party from a sender that predates the field keeps being understood. */
  @Test
  void aPartyWithoutStatedPlatformsIsStillAccepted() {
    final long now = System.currentTimeMillis();
    final BridgeMessage.PartyWarp warp = new BridgeMessage.PartyWarp(envelope("party_warp", now),
        List.of(UUID.randomUUID()), "btc", null);

    final BridgeCodec.DecodeResult result = BridgeCodec.decodeResult(
        BridgeCodec.encode(warp), now, BridgeCodec.Limits.defaults());

    assertTrue(result.accepted());
    assertNull(assertInstanceOf(BridgeMessage.PartyWarp.class, result.message()).memberPlatforms());
  }

  /** A platform outside the closed set is refused, never mapped onto a default. */
  @Test
  void aPlatformOutsideTheClosedSetIsRefused() {
    assertEquals(BridgeCodec.DecodeError.INVALID_FIELD, decodePlatformLiteral("\"SWITCH\""));
  }

  /**
   * An explicit null is refused too: writing the field is claiming to know, and "I wrote it but it
   * means nothing" is exactly the silent unknown the contract forbids.
   */
  @Test
  void anExplicitlyNullPlatformIsRefused() {
    assertEquals(BridgeCodec.DecodeError.INVALID_FIELD, decodePlatformLiteral("null"));
  }

  private static BridgeCodec.DecodeError decodePlatformLiteral(final String literal) {
    final long now = System.currentTimeMillis();
    final String json = "{\"version\":2,\"messageId\":\"" + UUID.randomUUID()
        + "\",\"type\":\"connect_request\",\"sourceBackend\":\"btc\",\"targetBackend\":\"btc-proxy\""
        + ",\"issuedAt\":" + (now - 1000L) + ",\"expiresAt\":" + (now + 30_000L)
        + ",\"payload\":{\"uuid\":\"" + UUID.randomUUID() + "\",\"targetServer\":\"btc\""
        + ",\"originPlatform\":" + literal + "}}";
    return BridgeCodec.decodeResult(json.getBytes(StandardCharsets.UTF_8), now,
        BridgeCodec.Limits.defaults()).error();
  }

  private static BridgeMessage.Envelope envelope(final String kind, final long now) {
    return new BridgeMessage.Envelope(BridgeMessage.VERSION, UUID.randomUUID(), kind, "btc",
        "btc-proxy", now - 1000L, now + 30_000L);
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
