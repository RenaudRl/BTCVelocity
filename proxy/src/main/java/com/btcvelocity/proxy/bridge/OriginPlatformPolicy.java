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

import com.btcvelocity.api.bridge.BridgeMessage;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Decides whether a player-scoped message may be dispatched on the strength of the platform it
 * claims. Pure: no Velocity type, no I/O, no clock.
 *
 * <p>The rule the whole class exists to enforce: a platform stated by a backend is a
 * <em>claim</em>, never evidence. It is either confirmed against the live session or it is refused.
 * Accepting an unverifiable claim would let any authenticated backend decide what a player is.
 *
 * <p>The conduct therefore depends on what this proxy can measure, which is why it reads as a
 * table rather than a chain of ifs:
 *
 * <table>
 *   <caption>Verdicts</caption>
 *   <tr><th>source</th><th>claim</th><th>verdict</th></tr>
 *   <tr><td>absent</td><td>absent</td><td>accepted — nothing is asserted and nothing is required
 *       (announced at startup)</td></tr>
 *   <tr><td>absent</td><td>present</td><td>refused {@code PLATFORM_UNRESOLVABLE} — an assertion
 *       that cannot be checked is not accepted on trust</td></tr>
 *   <tr><td>present</td><td>absent</td><td>refused {@code PLATFORM_MISMATCH} — a proxy that can
 *       verify does not waive verification</td></tr>
 *   <tr><td>present</td><td>no live session</td><td>refused — unverifiable, fail-closed</td></tr>
 *   <tr><td>present</td><td>differs</td><td>refused {@code PLATFORM_MISMATCH}</td></tr>
 *   <tr><td>present</td><td>matches</td><td>accepted</td></tr>
 * </table>
 *
 * <p>A party warp follows the same table, applied member by member, with one deliberate
 * difference: a member with no live session is skipped rather than refused. See
 * {@link #judgeParty}.
 */
public final class OriginPlatformPolicy {

  private final PlatformSource source;

  public OriginPlatformPolicy(final PlatformSource source) {
    this.source = Objects.requireNonNull(source, "source");
  }

  /** Why a message was accepted or refused. Journalled; never put on the wire as such. */
  public enum Reason {
    NOT_PLAYER_SCOPED,
    NOT_REQUIRED_HERE,
    CONFIRMED,
    CLAIM_UNVERIFIABLE_HERE,
    CLAIM_MISSING,
    NO_LIVE_SESSION,
    CLAIM_CONTRADICTED
  }

  /** The verdict, with the wire error to answer with when it refuses. */
  public record Verdict(boolean accepted, Reason reason,
                        BridgeMessage.@Nullable ErrorCode error) {
    public Verdict {
      Objects.requireNonNull(reason, "reason");
      if (accepted != (error == null)) {
        throw new IllegalArgumentException("a refusal carries an error code, an acceptance does not");
      }
    }
  }

  private static final Verdict ACCEPTED_NOT_PLAYER_SCOPED =
      new Verdict(true, Reason.NOT_PLAYER_SCOPED, null);
  private static final Verdict ACCEPTED_NOT_REQUIRED =
      new Verdict(true, Reason.NOT_REQUIRED_HERE, null);
  private static final Verdict ACCEPTED_CONFIRMED = new Verdict(true, Reason.CONFIRMED, null);

  /** Judges a message. Messages that concern nobody in particular pass untouched. */
  public Verdict judge(final BridgeMessage message) {
    Objects.requireNonNull(message, "message");
    return switch (message) {
      case BridgeMessage.QueueJoin value -> judgeClaim(value.uuid(), value.originPlatform());
      case BridgeMessage.QueueLeave value -> judgeClaim(value.uuid(), value.originPlatform());
      case BridgeMessage.ConnectRequest value -> judgeClaim(value.uuid(), value.originPlatform());
      case BridgeMessage.PartyWarp value -> judgeParty(value);
      default -> ACCEPTED_NOT_PLAYER_SCOPED;
    };
  }

  /**
   * Judges a party warp member by member, and refuses the <em>whole</em> group if a single stated
   * platform is contradicted — the same arbitration as {@code PartyWarpValidation}: a group that
   * arrives cut in half is worse than a group that did not move.
   *
   * <p>A member with no live session is not a fault of the message (0.6b): nothing can be
   * contradicted about somebody who is not here, and they were not going to be moved anyway. This
   * is the one place where the conduct differs from a player-scoped message, and it differs on
   * purpose.
   */
  private Verdict judgeParty(final BridgeMessage.PartyWarp warp) {
    final Map<UUID, BridgeMessage.Platform> stated = warp.memberPlatforms();
    if (!source.canResolve()) {
      return stated == null
          ? ACCEPTED_NOT_REQUIRED
          : new Verdict(false, Reason.CLAIM_UNVERIFIABLE_HERE,
              BridgeMessage.ErrorCode.PLATFORM_UNRESOLVABLE);
    }
    if (stated == null) {
      return new Verdict(false, Reason.CLAIM_MISSING, BridgeMessage.ErrorCode.PLATFORM_MISMATCH);
    }
    for (final UUID member : warp.members()) {
      final Optional<BridgeMessage.Platform> live = source.platformOf(member);
      if (live.isEmpty()) {
        continue;
      }
      final BridgeMessage.Platform claimed = stated.get(member);
      if (claimed == null) {
        return new Verdict(false, Reason.CLAIM_MISSING, BridgeMessage.ErrorCode.PLATFORM_MISMATCH);
      }
      if (claimed != live.get()) {
        return new Verdict(false, Reason.CLAIM_CONTRADICTED,
            BridgeMessage.ErrorCode.PLATFORM_MISMATCH);
      }
    }
    return ACCEPTED_CONFIRMED;
  }

  private Verdict judgeClaim(final UUID player, final BridgeMessage.@Nullable Platform claimed) {
    if (!source.canResolve()) {
      // This proxy measures nothing. It may not require a platform, and it may not believe one.
      return claimed == null
          ? ACCEPTED_NOT_REQUIRED
          : new Verdict(false, Reason.CLAIM_UNVERIFIABLE_HERE,
              BridgeMessage.ErrorCode.PLATFORM_UNRESOLVABLE);
    }
    if (claimed == null) {
      return new Verdict(false, Reason.CLAIM_MISSING, BridgeMessage.ErrorCode.PLATFORM_MISMATCH);
    }
    final Optional<BridgeMessage.Platform> live = source.platformOf(player);
    if (live.isEmpty()) {
      return new Verdict(false, Reason.NO_LIVE_SESSION, BridgeMessage.ErrorCode.PLATFORM_MISMATCH);
    }
    return live.get() == claimed
        ? ACCEPTED_CONFIRMED
        : new Verdict(false, Reason.CLAIM_CONTRADICTED, BridgeMessage.ErrorCode.PLATFORM_MISMATCH);
  }

  /** What to announce at startup, so an inert policy never reads like an enforced one. */
  public String announce() {
    return source.canResolve()
        ? "btc:bridge validates originPlatform against live sessions via " + source.describe()
        : "btc:bridge cannot resolve player platforms (" + source.describe()
            + "): originPlatform is neither required nor believed, and any message stating one is "
            + "refused";
  }
}
