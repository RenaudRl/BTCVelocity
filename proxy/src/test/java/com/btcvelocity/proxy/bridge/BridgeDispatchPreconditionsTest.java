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
import static org.junit.jupiter.api.Assertions.assertNull;

import com.btcvelocity.api.bridge.BridgeMessage;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/** A command the proxy knows it cannot carry out is refused before dispatch, never acknowledged. */
class BridgeDispatchPreconditionsTest {

  private static final long NOW = 1_700_000_000_000L;
  private static final Predicate<String> KNOWN = Set.of("btc", "tycoon")::contains;

  private static BridgeMessage.Envelope envelope(final String kind) {
    return new BridgeMessage.Envelope(BridgeMessage.VERSION, UUID.randomUUID(), kind, "btc",
        "proxy", NOW, NOW + 10_000L);
  }

  private static BridgeMessage.ConnectRequest connect(final String target) {
    return new BridgeMessage.ConnectRequest(envelope("connect_request"), UUID.randomUUID(), target);
  }

  private static BridgeMessage.PartyWarp warp(final List<UUID> members, final String target) {
    return new BridgeMessage.PartyWarp(envelope("party_warp"), members, target);
  }

  @Test
  void connectRequestTowardsKnownServerMayBeAttempted() {
    // The positive control: without it, a precondition that refuses everything would pass the
    // tests below and silently disable every transfer.
    assertNull(BridgeDispatchPreconditions.refuse(connect("tycoon"), KNOWN));
  }

  @Test
  void connectRequestTowardsUnknownServerIsRefused() {
    // The bench case of 16/09: `btcgracefulstop nowhere` was acknowledged, and the backend waited
    // out its whole deadline for a transfer the proxy had already decided not to make.
    assertEquals(BridgeMessage.ErrorCode.TARGET_NOT_ALLOWED,
        BridgeDispatchPreconditions.refuse(connect("nowhere"), KNOWN));
  }

  @Test
  void serverNamesAreMatchedExactly() {
    assertEquals(BridgeMessage.ErrorCode.TARGET_NOT_ALLOWED,
        BridgeDispatchPreconditions.refuse(connect("Tycoon"), KNOWN));
    assertEquals(BridgeMessage.ErrorCode.TARGET_NOT_ALLOWED,
        BridgeDispatchPreconditions.refuse(connect("tycoon "), KNOWN));
  }

  @Test
  void wellFormedPartyTowardsKnownServerMayBeAttempted() {
    assertNull(BridgeDispatchPreconditions.refuse(
        warp(List.of(UUID.randomUUID(), UUID.randomUUID()), "tycoon"), KNOWN));
  }

  @Test
  void partyTowardsUnknownServerIsRefused() {
    assertEquals(BridgeMessage.ErrorCode.TARGET_NOT_ALLOWED, BridgeDispatchPreconditions.refuse(
        warp(List.of(UUID.randomUUID()), "nowhere"), KNOWN));
  }

  @Test
  void malformedPartyIsRefusedAsMalformedPayload() {
    final UUID alice = UUID.randomUUID();
    assertEquals(BridgeMessage.ErrorCode.INVALID_ENVELOPE, BridgeDispatchPreconditions.refuse(
        warp(List.of(alice, alice), "tycoon"), KNOWN));
    assertEquals(BridgeMessage.ErrorCode.INVALID_ENVELOPE, BridgeDispatchPreconditions.refuse(
        warp(List.of(), "tycoon"), KNOWN));
  }

  @Test
  void partyWithoutDestinationIsRefusedAsTargetProblem() {
    assertEquals(BridgeMessage.ErrorCode.TARGET_NOT_ALLOWED, BridgeDispatchPreconditions.refuse(
        warp(List.of(UUID.randomUUID()), " "), KNOWN));
  }

  @Test
  void messagesWithoutDestinationAreNeverRefusedHere() {
    // A health report names its own server, not a destination: it must not be refused because the
    // proxy does not (yet) know that name, or backends could never announce themselves.
    final BridgeMessage.Health health = new BridgeMessage.Health(envelope("health"), "unknown-yet",
        5.0, 20.0, 0, List.of());
    assertNull(BridgeDispatchPreconditions.refuse(health, KNOWN));
  }
}
