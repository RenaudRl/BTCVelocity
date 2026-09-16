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
import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Builds the proxy's acknowledgements, and maps a refusal to the category the backend will read.
 *
 * <p>Pure on purpose: every rule below is decided without a running proxy, so the tests that cover
 * them need no server. The correlation convention is <em>not</em> invented here — it is the one the
 * backend already uses: <b>a response reuses the message id of the request it answers</b>
 * ({@code BridgeMessageHandler.responseEnvelope}). A response with a fresh id would reach a backend
 * that has no way to tie it to anything.</p>
 *
 * <p>The proxy speaks under a declared identity ({@code sourceBackend}), which each backend must
 * list in {@code bridge.allowed-backends}. It is a fixed name, never the name of an ephemeral
 * CloudNet service: an allowlist that had to follow service names would have to become a pattern,
 * and a pattern is not an allowlist.</p>
 */
public final class BridgeResponses {

  /** How long one of our responses stays valid on the wire; the backend uses the same value. */
  public static final long RESPONSE_LIFETIME_MILLIS = 30_000L;

  private BridgeResponses() {
  }

  /**
   * Builds the acknowledgement of a command the proxy accepted.
   *
   * @param request   the message being answered
   * @param proxyId   the proxy's declared bridge identity
   * @param duplicate whether the command had already been executed inside the deduplication window
   * @param nowMillis the current time, in milliseconds
   * @return the acknowledgement to send back on the source connection
   */
  public static BridgeMessage.Ack ack(final BridgeMessage request, final String proxyId,
                                      final boolean duplicate, final long nowMillis) {
    Objects.requireNonNull(request, "request");
    return new BridgeMessage.Ack(
        envelope("ack", request.messageId(), proxyId, request.sourceBackend(), nowMillis),
        duplicate);
  }

  /**
   * Builds the refusal of a message the proxy will not act on.
   *
   * @param messageId     the id of the refused message
   * @param targetBackend the backend the refusal is addressed to — the authenticated source
   * @param proxyId       the proxy's declared bridge identity
   * @param error         the refusal category
   * @param nowMillis     the current time, in milliseconds
   * @return the refusal to send back on the source connection
   */
  public static BridgeMessage.Nack nack(final UUID messageId, final String targetBackend,
                                        final String proxyId, final BridgeMessage.ErrorCode error,
                                        final long nowMillis) {
    Objects.requireNonNull(messageId, "messageId");
    Objects.requireNonNull(error, "error");
    return new BridgeMessage.Nack(
        envelope("nack", messageId, proxyId, targetBackend, nowMillis), error);
  }

  /**
   * The category a backend should read for a payload the codec refused.
   *
   * <p>Only the errors that tell the backend something actionable keep their own category; every
   * other decoding failure collapses into {@link BridgeMessage.ErrorCode#INVALID_ENVELOPE}, because
   * naming precisely how a payload was malformed tells an attacker where the parser stops.</p>
   *
   * @param error the decoder's verdict
   * @return the category to put in the refusal
   */
  public static BridgeMessage.ErrorCode categoryOf(final BridgeCodec.DecodeError error) {
    return switch (error) {
      case PAYLOAD_TOO_LARGE -> BridgeMessage.ErrorCode.PAYLOAD_TOO_LARGE;
      case EXPIRED, NOT_YET_VALID -> BridgeMessage.ErrorCode.EXPIRED;
      case UNKNOWN_KIND -> BridgeMessage.ErrorCode.UNSUPPORTED;
      default -> BridgeMessage.ErrorCode.INVALID_ENVELOPE;
    };
  }

  /**
   * The category a backend should read for a message refused at ingress.
   *
   * @param rejection the ingress verdict
   * @return the category to put in the refusal, or {@code null} when no refusal may be sent —
   *         a source that is not an authenticated backend gets no answer at all, since answering
   *         would confirm the channel exists to whoever forged the message
   */
  public static BridgeMessage.@Nullable ErrorCode categoryOf(
      final BridgeIngressPolicy.Rejection rejection) {
    return switch (rejection) {
      case NOT_A_BACKEND, UNREGISTERED_SOURCE, IDENTITY_MISMATCH -> null;
      case ENVELOPE_SOURCE_MISMATCH, DECLARED_NAME_MISMATCH ->
          BridgeMessage.ErrorCode.BACKEND_NOT_ALLOWED;
    };
  }

  /**
   * Whether the proxy owes an acknowledgement for this message.
   *
   * <p>Only commands the proxy <em>executes</em> are acknowledged. A self-report (health, world
   * state, queue status) is not a command, and acknowledging it would double the traffic of the
   * health task for nothing. A response never answers a response.</p>
   *
   * @param message the decoded message
   * @return {@code true} when an acknowledgement must follow
   */
  public static boolean isAcknowledgeable(final BridgeMessage message) {
    return switch (message) {
      case BridgeMessage.ConnectRequest ignored -> true;
      case BridgeMessage.PartyWarp ignored -> true;
      default -> false;
    };
  }

  private static BridgeMessage.Envelope envelope(final String kind, final UUID messageId,
                                                 final String sourceBackend,
                                                 final String targetBackend, final long nowMillis) {
    final long expiresAt;
    try {
      expiresAt = Math.addExact(nowMillis, RESPONSE_LIFETIME_MILLIS);
    } catch (ArithmeticException exception) {
      throw new IllegalStateException("clock is beyond the end of time", exception);
    }
    return new BridgeMessage.Envelope(BridgeMessage.VERSION, messageId, kind, sourceBackend,
        targetBackend, nowMillis, expiresAt);
  }
}
