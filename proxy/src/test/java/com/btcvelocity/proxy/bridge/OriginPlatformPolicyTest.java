/*
 * Copyright (C) 2018-2026 Velocity Contributors
 *
 * The Velocity API is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package com.btcvelocity.proxy.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.btcvelocity.api.bridge.BridgeMessage;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** A platform a backend states is a claim, and a claim is either confirmed or refused. */
class OriginPlatformPolicyTest {

  private static final UUID ALICE = UUID.randomUUID();
  private static final UUID OFFLINE = UUID.randomUUID();

  /** A source that knows about exactly the sessions it was given. */
  private static final class KnownSessions implements PlatformSource {
    private final Map<UUID, BridgeMessage.Platform> live;
    private final AtomicInteger questions = new AtomicInteger();

    KnownSessions(final Map<UUID, BridgeMessage.Platform> live) {
      this.live = live;
    }

    @Override
    public boolean canResolve() {
      return true;
    }

    @Override
    public Optional<BridgeMessage.Platform> platformOf(final UUID player) {
      questions.incrementAndGet();
      return Optional.ofNullable(live.get(player));
    }

    @Override
    public String describe() {
      return "test sessions";
    }
  }

  private static OriginPlatformPolicy resolving(final BridgeMessage.Platform alice) {
    return new OriginPlatformPolicy(new KnownSessions(Map.of(ALICE, alice)));
  }

  private static OriginPlatformPolicy blind() {
    return new OriginPlatformPolicy(PlatformSource.UNAVAILABLE);
  }

  private static BridgeMessage.Envelope envelope(final String kind) {
    final long now = System.currentTimeMillis();
    return new BridgeMessage.Envelope(BridgeMessage.VERSION, UUID.randomUUID(), kind, "btc",
        "btc-proxy", now - 1000L, now + 30_000L);
  }

  private static BridgeMessage connect(final UUID player,
                                       final BridgeMessage.Platform claimed) {
    return new BridgeMessage.ConnectRequest(envelope("connect_request"), player, "btc", claimed);
  }

  @Test
  void aJavaSessionConfirmsAJavaClaim() {
    final OriginPlatformPolicy.Verdict verdict =
        resolving(BridgeMessage.Platform.JAVA).judge(connect(ALICE, BridgeMessage.Platform.JAVA));

    assertTrue(verdict.accepted());
    assertEquals(OriginPlatformPolicy.Reason.CONFIRMED, verdict.reason());
  }

  @Test
  void aBedrockSessionConfirmsABedrockClaim() {
    final OriginPlatformPolicy.Verdict verdict = resolving(BridgeMessage.Platform.BEDROCK)
        .judge(connect(ALICE, BridgeMessage.Platform.BEDROCK));

    assertTrue(verdict.accepted());
  }

  /** The spec's own scenario: a backend declares BEDROCK for a live Java session. */
  @Test
  void aBackendThatSpoofsBedrockOverAJavaSessionIsRefused() {
    final OriginPlatformPolicy.Verdict verdict = resolving(BridgeMessage.Platform.JAVA)
        .judge(connect(ALICE, BridgeMessage.Platform.BEDROCK));

    assertFalse(verdict.accepted());
    assertEquals(OriginPlatformPolicy.Reason.CLAIM_CONTRADICTED, verdict.reason());
    assertEquals(BridgeMessage.ErrorCode.PLATFORM_MISMATCH, verdict.error());
  }

  /** Nobody connected means nothing to confirm against — fail closed, never assume. */
  @Test
  void aClaimAboutSomebodyWhoIsNotConnectedIsRefused() {
    final OriginPlatformPolicy.Verdict verdict = resolving(BridgeMessage.Platform.JAVA)
        .judge(connect(OFFLINE, BridgeMessage.Platform.JAVA));

    assertFalse(verdict.accepted());
    assertEquals(OriginPlatformPolicy.Reason.NO_LIVE_SESSION, verdict.reason());
  }

  @Test
  void aProxyThatCanCheckDoesNotWaiveTheCheck() {
    final OriginPlatformPolicy.Verdict verdict =
        resolving(BridgeMessage.Platform.JAVA).judge(connect(ALICE, null));

    assertFalse(verdict.accepted());
    assertEquals(OriginPlatformPolicy.Reason.CLAIM_MISSING, verdict.reason());
  }

  /** The production case today: nothing installed can answer, so nothing is required. */
  @Test
  void aProxyThatCannotResolveRequiresNothing() {
    final OriginPlatformPolicy.Verdict verdict = blind().judge(connect(ALICE, null));

    assertTrue(verdict.accepted());
    assertEquals(OriginPlatformPolicy.Reason.NOT_REQUIRED_HERE, verdict.reason());
  }

  /** …and believes nothing either: an unverifiable assertion is refused, not taken on trust. */
  @Test
  void aProxyThatCannotResolveRefusesToBelieveAStatedPlatform() {
    final OriginPlatformPolicy.Verdict verdict =
        blind().judge(connect(ALICE, BridgeMessage.Platform.BEDROCK));

    assertFalse(verdict.accepted());
    assertEquals(BridgeMessage.ErrorCode.PLATFORM_UNRESOLVABLE, verdict.error());
  }

  /** A party is a group: the rule does not apply, and the source is not even asked. */
  @Test
  void aPartyWarpIsOutsideThisRuleAndAsksTheSourceNothing() {
    final KnownSessions sessions = new KnownSessions(Map.of(ALICE, BridgeMessage.Platform.JAVA));
    final BridgeMessage warp = new BridgeMessage.PartyWarp(envelope("party_warp"),
        List.of(ALICE, OFFLINE), "btc");

    final OriginPlatformPolicy.Verdict verdict = new OriginPlatformPolicy(sessions).judge(warp);

    assertTrue(verdict.accepted());
    assertEquals(OriginPlatformPolicy.Reason.NOT_PLAYER_SCOPED, verdict.reason());
    assertEquals(0, sessions.questions.get());
  }

  /** An inert policy that stays silent reads exactly like an enforced one. */
  @Test
  void aProxyThatCannotResolveSaysSoAtStartup() {
    assertTrue(blind().announce().contains("cannot resolve"));
    assertTrue(resolving(BridgeMessage.Platform.JAVA).announce().contains("validates"));
  }
}
