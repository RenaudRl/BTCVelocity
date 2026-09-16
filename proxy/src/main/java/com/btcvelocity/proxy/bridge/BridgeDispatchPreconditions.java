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

import com.btcvelocity.api.bridge.BridgeCodec;
import com.btcvelocity.api.bridge.BridgeMessage;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;

/**
 * Decides, before dispatch, whether a transport command can be carried out at all.
 *
 * <p>An acknowledgement used to mean "received and dispatched", never "done": a
 * {@code connect_request} towards a server the proxy does not know was logged as rejected by the
 * transfer handler and then acknowledged anyway. Seen on the bench on 16/09: the backend, which
 * rightly trusts only the player's departure, waited out its whole shutdown deadline and reported
 * nobody refused — while the proxy had known from the first millisecond that it would move no one.
 *
 * <p>Asked at the same place as authorization, and for the same reason: a command the proxy will
 * not execute must be refused before anything runs, so that an acknowledgement can only follow a
 * command that was actually attempted.
 *
 * <p>No new error category on purpose. Adding one changes the wire format on both sides at once;
 * an unknown destination is a destination the proxy will not send anyone to, which
 * {@link BridgeMessage.ErrorCode#TARGET_NOT_ALLOWED} already says, and a malformed party is a
 * malformed payload, which the codec already reports as
 * {@link BridgeMessage.ErrorCode#INVALID_ENVELOPE}.
 */
public final class BridgeDispatchPreconditions {

  private BridgeDispatchPreconditions() {
  }

  /**
   * Returns why a transport command cannot be executed, or {@code null} when it can be attempted.
   *
   * <p>Messages other than {@code connect_request} and {@code party_warp} are never refused here:
   * they carry no destination.
   *
   * @param message            the authenticated, authorized command
   * @param isRegisteredServer whether the proxy knows a server by that name
   * @return the refusal category, or {@code null}
   */
  public static BridgeMessage.@Nullable ErrorCode refuse(final BridgeMessage message,
                                                        final Predicate<String> isRegisteredServer) {
    return switch (message) {
      case BridgeMessage.ConnectRequest request -> destination(request.targetServer(),
          isRegisteredServer);
      case BridgeMessage.PartyWarp warp -> {
        final PartyWarpValidation.Verdict verdict = PartyWarpValidation.validate(warp.members(),
            warp.targetServer(), BridgeCodec.Limits.defaults().maxPartyMembers());
        if (!verdict.accepted()) {
          yield verdict.rejection() == PartyWarpValidation.Rejection.BLANK_TARGET
              ? BridgeMessage.ErrorCode.TARGET_NOT_ALLOWED
              : BridgeMessage.ErrorCode.INVALID_ENVELOPE;
        }
        yield destination(warp.targetServer(), isRegisteredServer);
      }
      default -> null;
    };
  }

  private static BridgeMessage.@Nullable ErrorCode destination(final @Nullable String target,
      final Predicate<String> isRegisteredServer) {
    if (target == null || target.isBlank() || !isRegisteredServer.test(target)) {
      return BridgeMessage.ErrorCode.TARGET_NOT_ALLOWED;
    }
    return null;
  }
}
