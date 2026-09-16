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
import com.btcvelocity.api.bridge.PlatformSource;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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

  private static final UUID BOB = UUID.randomUUID();

  private static BridgeMessage warp(final List<UUID> members,
                                    final Map<UUID, BridgeMessage.Platform> stated) {
    return new BridgeMessage.PartyWarp(envelope("party_warp"), members, "btc", stated);
  }

  private static OriginPlatformPolicy twoLiveSessions() {
    return new OriginPlatformPolicy(new KnownSessions(Map.of(
        ALICE, BridgeMessage.Platform.JAVA, BOB, BridgeMessage.Platform.BEDROCK)));
  }

  @Test
  void aPartyWhoseStatedPlatformsAllHoldIsAccepted() {
    final OriginPlatformPolicy.Verdict verdict = twoLiveSessions().judge(warp(List.of(ALICE, BOB),
        Map.of(ALICE, BridgeMessage.Platform.JAVA, BOB, BridgeMessage.Platform.BEDROCK)));

    assertTrue(verdict.accepted());
    assertEquals(OriginPlatformPolicy.Reason.CONFIRMED, verdict.reason());
  }

  /**
   * One contradicted member sinks the whole group — the same arbitration as PartyWarpValidation:
   * a group that arrives cut in half is worse than a group that did not move.
   */
  @Test
  void oneContradictedMemberRefusesTheWholeParty() {
    final OriginPlatformPolicy.Verdict verdict = twoLiveSessions().judge(warp(List.of(ALICE, BOB),
        Map.of(ALICE, BridgeMessage.Platform.JAVA, BOB, BridgeMessage.Platform.JAVA)));

    assertFalse(verdict.accepted());
    assertEquals(OriginPlatformPolicy.Reason.CLAIM_CONTRADICTED, verdict.reason());
    assertEquals(BridgeMessage.ErrorCode.PLATFORM_MISMATCH, verdict.error());
  }

  /** A member who is not connected is not a fault of the message (0.6b): nothing contradicts. */
  @Test
  void aMemberWithNoLiveSessionDoesNotSinkTheParty() {
    final OriginPlatformPolicy.Verdict verdict = twoLiveSessions().judge(warp(
        List.of(ALICE, OFFLINE), Map.of(ALICE, BridgeMessage.Platform.JAVA)));

    assertTrue(verdict.accepted());
  }

  @Test
  void aPartyThatOmitsAConnectedMemberIsRefused() {
    final OriginPlatformPolicy.Verdict verdict = twoLiveSessions().judge(warp(List.of(ALICE, BOB),
        Map.of(ALICE, BridgeMessage.Platform.JAVA)));

    assertFalse(verdict.accepted());
    assertEquals(OriginPlatformPolicy.Reason.CLAIM_MISSING, verdict.reason());
  }

  @Test
  void aProxyThatCannotResolveRefusesAPartyThatStatesPlatforms() {
    final OriginPlatformPolicy.Verdict verdict = blind().judge(warp(List.of(ALICE),
        Map.of(ALICE, BridgeMessage.Platform.JAVA)));

    assertFalse(verdict.accepted());
    assertEquals(BridgeMessage.ErrorCode.PLATFORM_UNRESOLVABLE, verdict.error());
  }

  /**
   * The bench defect of 17/09, in a test: the bridge channel is built before the proxy loads any
   * plugin, so the source is installed long after the policy exists. A policy holding its source
   * would freeze "nothing installed" for the life of the proxy.
   */
  @Test
  void aSourceInstalledAfterTheFactIsUsed() {
    final AtomicReference<PlatformSource> installed =
        new AtomicReference<>(PlatformSource.UNAVAILABLE);
    final OriginPlatformPolicy policy = new OriginPlatformPolicy(installed::get);

    assertTrue(policy.judge(connect(ALICE, null)).accepted(), "nothing installed: nothing required");
    assertTrue(policy.announce().contains("cannot resolve"));

    installed.set(new KnownSessions(Map.of(ALICE, BridgeMessage.Platform.JAVA)));

    assertTrue(policy.announce().contains("validates"), "the plugin came up; the policy sees it");
    assertFalse(policy.judge(connect(ALICE, BridgeMessage.Platform.BEDROCK)).accepted());
  }

  /** An inert policy that stays silent reads exactly like an enforced one. */
  @Test
  void aProxyThatCannotResolveSaysSoAtStartup() {
    assertTrue(blind().announce().contains("cannot resolve"));
    assertTrue(resolving(BridgeMessage.Platform.JAVA).announce().contains("validates"));
  }
}
