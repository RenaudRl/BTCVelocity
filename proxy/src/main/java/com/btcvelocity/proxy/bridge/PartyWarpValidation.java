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

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Decides whether a party warp may be applied, before anybody is moved.
 *
 * <p>Deliberately free of any Velocity type so the rule can be exercised without a proxy: what
 * makes a warp acceptable is a property of the message, not of the server that received it.
 *
 * <p>The defect this closes is not "an invalid warp was applied" but "an invalid warp was applied
 * <em>partly</em>". The dispatch loop used to validate inside the per-member move, so a malformed
 * message was discovered again for every member — moving whoever happened to pass, and writing one
 * log line per member for a single bad message. A party that arrives split is worse than a party
 * that did not move: keeping the group together is the entire reason this message exists.
 */
public final class PartyWarpValidation {

  private PartyWarpValidation() {
  }

  /** Why a warp is refused as a whole. */
  public enum Rejection {
    /** No member to move. Accepting it would report success for a move that never happened. */
    NO_MEMBERS,
    /** More members than the codec allows — checked again here, where the move actually happens. */
    TOO_MANY_MEMBERS,
    /**
     * The same player appears twice.
     *
     * <p>Refused rather than silently de-duplicated. De-duplicating would work, and would hide the
     * sender's inconsistent idea of the party for as long as nobody looked: a list holding the same
     * player twice means the party itself is wrong upstream, and a move derived from a wrong party
     * is exactly the "it mostly worked" this bridge refuses. One log line, fixable at the source.
     */
    DUPLICATE_MEMBER,
    /** No destination. The proxy never picks one: the payload carries it or there is no warp. */
    BLANK_TARGET
  }

  /**
   * The outcome of validating a warp.
   *
   * @param rejection why the warp was refused, or {@code null} when it was accepted
   * @param members   the members to move, empty when the warp was refused
   */
  public record Verdict(@Nullable Rejection rejection, List<UUID> members) {

    public Verdict {
      members = List.copyOf(members);
    }

    /** Whether the warp may be applied. */
    public boolean accepted() {
      return rejection == null;
    }
  }

  /**
   * Validates a warp as a whole.
   *
   * <p>Null members are not checked: {@code BridgeMessage.PartyWarp} copies the list through
   * {@link List#copyOf}, which rejects them at construction. Re-checking here would be dead code.
   *
   * @param members      the party, as the sender declared it
   * @param targetServer the destination carried by the payload
   * @param maxMembers   the party size ceiling, the same one the codec enforces
   * @return the verdict; never {@code null}
   */
  public static Verdict validate(final List<UUID> members, final @Nullable String targetServer,
                                 final int maxMembers) {
    if (targetServer == null || targetServer.isBlank()) {
      return new Verdict(Rejection.BLANK_TARGET, List.of());
    }
    if (members.isEmpty()) {
      return new Verdict(Rejection.NO_MEMBERS, List.of());
    }
    if (members.size() > maxMembers) {
      return new Verdict(Rejection.TOO_MANY_MEMBERS, List.of());
    }
    final Set<UUID> seen = new HashSet<>(members.size());
    for (final UUID member : members) {
      if (!seen.add(member)) {
        return new Verdict(Rejection.DUPLICATE_MEMBER, List.of());
      }
    }
    return new Verdict(null, members);
  }
}
