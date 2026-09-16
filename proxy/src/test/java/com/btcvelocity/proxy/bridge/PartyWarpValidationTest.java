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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.btcvelocity.api.bridge.BridgeCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A party warp is judged as a whole, before anybody moves. */
class PartyWarpValidationTest {

  private static final int MAX = 64;

  private static List<UUID> party(final int size) {
    final List<UUID> members = new ArrayList<>(size);
    for (int i = 0; i < size; i++) {
      members.add(UUID.randomUUID());
    }
    return List.copyOf(members);
  }

  @Test
  void aWellFormedWarpIsAcceptedWithItsMembersIntact() {
    final List<UUID> members = party(8);

    final PartyWarpValidation.Verdict verdict =
        PartyWarpValidation.validate(members, "creatif", MAX);

    assertTrue(verdict.accepted());
    assertNull(verdict.rejection());
    assertEquals(members, verdict.members());
  }

  @Test
  void aRefusedWarpCarriesNobody() {
    // The property that matters: a refusal must not leave a half-party behind for a caller that
    // reads members() without checking accepted() first.
    for (final PartyWarpValidation.Verdict verdict : List.of(
        PartyWarpValidation.validate(party(8), "  ", MAX),
        PartyWarpValidation.validate(List.of(), "creatif", MAX),
        PartyWarpValidation.validate(party(MAX + 1), "creatif", MAX))) {
      assertFalse(verdict.accepted());
      assertTrue(verdict.members().isEmpty(), "a refused warp moves nobody");
    }
  }

  @Test
  void aWarpWithoutMembersIsRefusedRatherThanReportedAsDone() {
    final PartyWarpValidation.Verdict verdict =
        PartyWarpValidation.validate(List.of(), "creatif", MAX);

    assertEquals(PartyWarpValidation.Rejection.NO_MEMBERS, verdict.rejection());
  }

  @Test
  void aWarpWithoutADestinationIsRefusedAndTheProxyNeverPicksOne() {
    // House rule: the orchestrator never decides a player's destination. No target, no warp.
    assertEquals(PartyWarpValidation.Rejection.BLANK_TARGET,
        PartyWarpValidation.validate(party(2), null, MAX).rejection());
    assertEquals(PartyWarpValidation.Rejection.BLANK_TARGET,
        PartyWarpValidation.validate(party(2), "   ", MAX).rejection());
  }

  @Test
  void theSizeCeilingIsCheckedAgainWhereTheMoveHappens() {
    assertTrue(PartyWarpValidation.validate(party(MAX), "creatif", MAX).accepted(),
        "witness: exactly at the ceiling is still allowed");
    assertEquals(PartyWarpValidation.Rejection.TOO_MANY_MEMBERS,
        PartyWarpValidation.validate(party(MAX + 1), "creatif", MAX).rejection());
  }

  @Test
  void theSameMemberTwiceRefusesTheWholeWarp() {
    // Refused, not silently de-duplicated: a party holding the same player twice is wrong upstream,
    // and quietly repairing it here would hide the sender's defect for as long as nobody looked.
    final UUID alice = UUID.randomUUID();
    final UUID bob = UUID.randomUUID();

    final PartyWarpValidation.Verdict verdict =
        PartyWarpValidation.validate(List.of(alice, bob, alice), "creatif", MAX);

    assertEquals(PartyWarpValidation.Rejection.DUPLICATE_MEMBER, verdict.rejection());
    assertTrue(verdict.members().isEmpty(), "nobody moves on a malformed party");
  }

  @Test
  void aDistinctPartyIsNotMistakenForADuplicatedOne() {
    // The negative witness for the check above: were it comparing anything but identity, a party of
    // distinct players would be refused too, and party warp would simply stop working.
    assertTrue(PartyWarpValidation.validate(party(MAX), "creatif", MAX).accepted());
  }

  @Test
  void theCeilingUsedAtDispatchIsTheCodecsOwn() {
    // A copied constant is a constant that drifts. If these two ever disagree, a warp the codec
    // believes it refused would be accepted at dispatch, or vice versa.
    assertEquals(BridgeCodec.Limits.defaults().maxPartyMembers(), MAX);
  }

  @Test
  void theVerdictCannotBeMutatedThroughTheListItWasGiven() {
    final List<UUID> members = new ArrayList<>(party(3));

    final PartyWarpValidation.Verdict verdict =
        PartyWarpValidation.validate(members, "creatif", MAX);
    members.clear();

    assertEquals(3, verdict.members().size(), "the verdict kept its own copy");
    assertThrows(UnsupportedOperationException.class,
        () -> verdict.members().add(UUID.randomUUID()));
  }
}
