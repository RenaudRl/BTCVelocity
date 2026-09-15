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
import com.velocitypowered.api.proxy.server.ServerInfo;
import java.util.Optional;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

/**
 * Pure ingress policy for the {@code btc:bridge} control plane.
 *
 * <p>Two questions are answered here, and nowhere else, so that they can be tested without a
 * running proxy:</p>
 * <ol>
 *   <li><b>Who is talking?</b> The source identity is the {@link ServerInfo} of the backend
 *   connection the proxy itself opened. It is accepted only if the proxy currently has a
 *   registered server whose {@link ServerInfo} is <em>equal</em> to it (name <em>and</em>
 *   address). A registered name alone is not an identity: two different backends could be
 *   registered under the same name over time.</li>
 *   <li><b>Does the message claim to be someone else?</b> The V2 envelope carries
 *   {@code sourceBackend}, and self-reports (health, worlds, queue status) carry a
 *   {@code serverName}. Both must match the authenticated source; a message that speaks in
 *   another backend's name is rejected, so a backend can never poison another backend's
 *   registry entry.</li>
 * </ol>
 *
 * <p>Authorization (allowlisting a backend) is a separate concern layered on top of this
 * identity check; this policy establishes <em>who</em>, not <em>whether they may</em>.</p>
 */
public final class BridgeIngressPolicy {

  /** Why a message was refused at ingress. Categories are stable and safe to log. */
  public enum Rejection {
    /** The message did not come from a backend connection opened by the proxy. */
    NOT_A_BACKEND,
    /** The connection names a server the proxy no longer has registered. */
    UNREGISTERED_SOURCE,
    /** A server with that name is registered, but its address differs from the connection. */
    IDENTITY_MISMATCH,
    /** The envelope {@code sourceBackend} is not the authenticated source. */
    ENVELOPE_SOURCE_MISMATCH,
    /** A self-report names a {@code serverName} other than the authenticated source. */
    DECLARED_NAME_MISMATCH
  }

  /** Outcome of the identity check: either a source server name or a rejection. */
  public record SourceDecision(@Nullable String sourceServer, @Nullable Rejection rejection) {
    public boolean accepted() {
      return rejection == null && sourceServer != null;
    }

    static SourceDecision accept(final String sourceServer) {
      return new SourceDecision(sourceServer, null);
    }

    static SourceDecision reject(final Rejection rejection) {
      return new SourceDecision(null, rejection);
    }
  }

  private BridgeIngressPolicy() {
  }

  /**
   * Authenticates the source of an incoming message.
   *
   * @param connectionInfo the {@link ServerInfo} of the backend connection the message arrived
   *                       on, or {@code null} when the source is not a backend connection
   * @param registered     lookup of the currently registered server by name
   * @return the accepted source server name, or the rejection reason
   */
  public static SourceDecision authenticateSource(
      final @Nullable ServerInfo connectionInfo,
      final Function<String, Optional<ServerInfo>> registered) {
    if (connectionInfo == null) {
      return SourceDecision.reject(Rejection.NOT_A_BACKEND);
    }
    final String name = connectionInfo.getName();
    final Optional<ServerInfo> known = registered.apply(name);
    if (known.isEmpty()) {
      return SourceDecision.reject(Rejection.UNREGISTERED_SOURCE);
    }
    // ServerInfo equality covers both the name and the socket address.
    if (!known.get().equals(connectionInfo)) {
      return SourceDecision.reject(Rejection.IDENTITY_MISMATCH);
    }
    return SourceDecision.accept(name);
  }

  /**
   * Verifies that a decoded message does not claim another backend's identity.
   *
   * @param sourceServer the authenticated source server name
   * @param message      the decoded message
   * @return the rejection reason, or {@link Optional#empty()} when the claims match
   */
  public static Optional<Rejection> verifyDeclaredIdentity(final String sourceServer,
                                                           final BridgeMessage message) {
    if (!sourceServer.equals(message.sourceBackend())) {
      return Optional.of(Rejection.ENVELOPE_SOURCE_MISMATCH);
    }
    final String declared = selfReportedName(message);
    if (declared != null && !sourceServer.equals(declared)) {
      return Optional.of(Rejection.DECLARED_NAME_MISMATCH);
    }
    return Optional.empty();
  }

  /**
   * The {@code serverName} a backend reports <em>about itself</em>. Commands that name a
   * target (status requests, preloads) are not self-reports and are not checked here.
   */
  private static @Nullable String selfReportedName(final BridgeMessage message) {
    return switch (message) {
      case BridgeMessage.Health value -> value.serverName();
      case BridgeMessage.WorldLoaded value -> value.serverName();
      case BridgeMessage.WorldLoadFailed value -> value.serverName();
      case BridgeMessage.WorldUnloaded value -> value.serverName();
      case BridgeMessage.QueueStatusResponse value -> value.serverName();
      default -> null;
    };
  }
}
