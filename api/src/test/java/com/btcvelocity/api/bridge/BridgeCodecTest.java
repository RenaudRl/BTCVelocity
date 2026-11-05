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

  // ---- Refusals of the version 2 codec, each paired with its positive witness ----

  private static final long NOW = 1_800_000_000_000L;
  private static final String VALID_PAYLOAD =
      "{\"uuid\":\"00000000-0000-0000-0000-000000000001\",\"targetServer\":\"btc\"}";

  private static String json(final int version, final String type, final String source,
                             final long issuedAt, final long expiresAt, final String payload) {
    return "{\"version\":" + version + ",\"messageId\":\"00000000-0000-0000-0000-0000000000aa\""
        + ",\"type\":\"" + type + "\",\"sourceBackend\":\"" + source
        + "\",\"targetBackend\":\"btc-proxy\",\"issuedAt\":" + issuedAt
        + ",\"expiresAt\":" + expiresAt + ",\"payload\":" + payload + "}";
  }

  private static String validJson() {
    return json(2, "connect_request", "btc", NOW - 1000L, NOW + 30_000L, VALID_PAYLOAD);
  }

  private static String validJson(final long issuedAt, final long expiresAt) {
    return json(2, "connect_request", "btc", issuedAt, expiresAt, VALID_PAYLOAD);
  }

  private static BridgeCodec.DecodeResult decode(final String json) {
    return decode(json.getBytes(StandardCharsets.UTF_8));
  }

  private static BridgeCodec.DecodeResult decode(final byte[] bytes) {
    return BridgeCodec.decodeResult(bytes, NOW, BridgeCodec.Limits.defaults());
  }

  private static byte[] paddedTo(final String json, final int size) {
    final byte[] raw = json.getBytes(StandardCharsets.UTF_8);
    final byte[] padded = new byte[size];
    System.arraycopy(raw, 0, padded, 0, raw.length);
    java.util.Arrays.fill(padded, raw.length, size, (byte) ' ');
    return padded;
  }

  @Test
  void acceptsAPayloadOfExactlyTheSizeLimit() {
    assertTrue(decode(paddedTo(validJson(), 32 * 1024)).accepted());
  }

  @Test
  void refusesAPayloadOneByteOverTheSizeLimitAsTooLarge() {
    assertEquals(BridgeCodec.DecodeError.PAYLOAD_TOO_LARGE,
        decode(paddedTo(validJson(), 32 * 1024 + 1)).error());
  }

  @Test
  void refusesEmptyInputAsEmpty() {
    assertEquals(BridgeCodec.DecodeError.EMPTY, decode(new byte[0]).error());
  }

  @Test
  void refusesInvalidUtf8AsInvalidUtf8() {
    final byte[] broken = {(byte) 0xC3, (byte) 0x28};

    assertEquals(BridgeCodec.DecodeError.INVALID_UTF8, decode(broken).error());
  }

  @Test
  void refusesTruncatedJsonAsMalformed() {
    final String valid = validJson();

    assertTrue(decode(valid).accepted());
    assertEquals(BridgeCodec.DecodeError.MALFORMED,
        decode(valid.substring(0, valid.length() - 10)).error());
  }

  @Test
  void refusesANonObjectRootAsMalformed() {
    assertEquals(BridgeCodec.DecodeError.MALFORMED, decode("[]").error());
  }

  @Test
  void refusesAMissingEnvelopeFieldAsUnknownField() {
    final String withoutTarget = validJson().replace(",\"targetBackend\":\"btc-proxy\"", "");

    assertEquals(BridgeCodec.DecodeError.UNKNOWN_FIELD, decode(withoutTarget).error());
  }

  @Test
  void refusesAnExtraEnvelopeFieldAsUnknownField() {
    final String extra = validJson().replace("{\"version\"", "{\"extra\":1,\"version\"");

    assertEquals(BridgeCodec.DecodeError.UNKNOWN_FIELD, decode(extra).error());
  }

  @Test
  void refusesAMissingPayloadFieldAsInvalidField() {
    final String json = json(2, "connect_request", "btc", NOW - 1000L, NOW + 30_000L,
        "{\"uuid\":\"00000000-0000-0000-0000-000000000001\"}");

    assertEquals(BridgeCodec.DecodeError.INVALID_FIELD, decode(json).error());
  }

  @Test
  void refusesAnExtraPayloadFieldAsInvalidField() {
    final String json = json(2, "connect_request", "btc", NOW - 1000L, NOW + 30_000L,
        "{\"uuid\":\"00000000-0000-0000-0000-000000000001\",\"targetServer\":\"btc\",\"x\":1}");

    assertEquals(BridgeCodec.DecodeError.INVALID_FIELD, decode(json).error());
  }

  @Test
  void refusesAnUnknownVersionAndKeepsTheMessageId() {
    final BridgeCodec.DecodeResult result = decode(
        json(3, "connect_request", "btc", NOW - 1000L, NOW + 30_000L, VALID_PAYLOAD));

    assertEquals(BridgeCodec.DecodeError.UNSUPPORTED_VERSION, result.error());
    assertEquals(UUID.fromString("00000000-0000-0000-0000-0000000000aa"), result.messageId());
    assertTrue(decode(validJson()).accepted());
  }

  @Test
  void refusesAnUnknownKindAsUnknownKind() {
    assertEquals(BridgeCodec.DecodeError.UNKNOWN_KIND, decode(
        json(2, "teleport_all", "btc", NOW - 1000L, NOW + 30_000L, VALID_PAYLOAD)).error());
  }

  @Test
  void refusesAMessageExpiredByOneMillisecondAndAcceptsOneExpiringNow() {
    assertEquals(BridgeCodec.DecodeError.EXPIRED, decode(validJson(NOW - 5000L, NOW - 1L)).error());
    assertTrue(decode(validJson(NOW - 5000L, NOW)).accepted());
  }

  @Test
  void acceptsClockSkewOfThirtySecondsAndRefusesOneMillisecondMoreAsNotYetValid() {
    assertTrue(decode(validJson(NOW + 30_000L, NOW + 60_000L)).accepted());
    assertEquals(BridgeCodec.DecodeError.NOT_YET_VALID,
        decode(validJson(NOW + 30_001L, NOW + 60_000L)).error());
  }

  @Test
  void acceptsALifetimeOfThreeHundredSecondsAndRefusesOneMillisecondMoreAsInvalidField() {
    assertTrue(decode(validJson(NOW - 1000L, NOW + 299_000L)).accepted());
    assertEquals(BridgeCodec.DecodeError.INVALID_FIELD,
        decode(validJson(NOW - 1000L, NOW + 299_001L)).error());
  }

  @Test
  void refusesAMessageThatExpiresBeforeOrWhenItIsIssuedAsInvalidField() {
    assertEquals(BridgeCodec.DecodeError.INVALID_FIELD,
        decode(validJson(NOW - 1000L, NOW - 1000L)).error());
  }

  @Test
  void acceptsAStringAtTheLengthLimitAndRefusesOneCharacterMoreAsInvalidField() {
    assertTrue(decode(json(2, "connect_request", "a".repeat(128), NOW - 1000L, NOW + 30_000L,
        VALID_PAYLOAD)).accepted());
    assertEquals(BridgeCodec.DecodeError.INVALID_FIELD, decode(json(2, "connect_request",
        "a".repeat(129), NOW - 1000L, NOW + 30_000L, VALID_PAYLOAD)).error());
  }
}
