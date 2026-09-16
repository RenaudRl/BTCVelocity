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
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Nullable;

/**
 * Who may command the proxy, and where they may send players.
 *
 * <p>Authentication and authorization are two questions, answered by two classes.
 * {@link BridgeIngressPolicy} establishes <em>who is talking</em> from the connection itself; this
 * one answers <em>whether they may</em>, from a declared list. An allowlist authorizes a backend;
 * it never authenticates one — a name in a list proves nothing on its own.</p>
 *
 * <p><b>An absent list makes the policy inert, and the proxy says so at startup.</b> This is the
 * house convention (see {@code BTC_ARRIVAL_SERVERS}): a policy nobody declared must not silently
 * refuse the traffic that worked yesterday. The backend takes the opposite default — an empty
 * {@code allowed-backends} refuses everyone — because a backend that loads worlds on command has
 * more to lose from an open door than a proxy has from a missing list.</p>
 */
public record BridgeAuthorization(Set<String> allowedSources, Set<String> allowedTargets) {

  /** Environment variable declaring the backends allowed to command the proxy. */
  public static final String SOURCES_ENV = "BTC_BRIDGE_ALLOWED_BACKENDS";

  /** Environment variable declaring the servers a command may name as a destination. */
  public static final String TARGETS_ENV = "BTC_BRIDGE_ALLOWED_TARGETS";

  public BridgeAuthorization {
    allowedSources = Set.copyOf(allowedSources);
    allowedTargets = Set.copyOf(allowedTargets);
  }

  /**
   * Parses a policy from two comma-separated declarations.
   *
   * @param sources the declaration of allowed source backends, or {@code null} when absent
   * @param targets the declaration of allowed destinations, or {@code null} when absent
   * @return the parsed policy; an absent or blank declaration yields an inert half
   */
  public static BridgeAuthorization parse(final @Nullable String sources,
                                          final @Nullable String targets) {
    return new BridgeAuthorization(split(sources), split(targets));
  }

  /** Reads the policy from the process environment. */
  public static BridgeAuthorization fromEnvironment() {
    return parse(System.getenv(SOURCES_ENV), System.getenv(TARGETS_ENV));
  }

  /** Whether any source is declared. When none is, every authenticated backend may command. */
  public boolean filtersSources() {
    return !allowedSources.isEmpty();
  }

  /** Whether any destination is declared. When none is, a command may name any server. */
  public boolean filtersTargets() {
    return !allowedTargets.isEmpty();
  }

  /**
   * Decides whether a command may be executed.
   *
   * @param sourceServer the authenticated source backend
   * @param message      the decoded command
   * @return the refusal category, or {@code null} when the command is authorized
   */
  public BridgeMessage.@Nullable ErrorCode refuse(final String sourceServer,
                                                  final BridgeMessage message) {
    if (filtersSources() && !allowedSources.contains(sourceServer)) {
      return BridgeMessage.ErrorCode.BACKEND_NOT_ALLOWED;
    }
    final String destination = destinationOf(message);
    if (destination != null && filtersTargets() && !allowedTargets.contains(destination)) {
      return BridgeMessage.ErrorCode.TARGET_NOT_ALLOWED;
    }
    return null;
  }

  /**
   * The server a command wants to send players to.
   *
   * <p>Deliberately <em>not</em> the envelope's {@code targetBackend}: that field names the proxy
   * itself, and the only backend emitter in production spells it {@code "proxy"} in its own code
   * while the proxy may declare another identity. Reading it as a destination would refuse valid
   * traffic for a spelling. The destination is the one inside the payload, which is also the one
   * a player actually ends up on.</p>
   */
  private static @Nullable String destinationOf(final BridgeMessage message) {
    return switch (message) {
      case BridgeMessage.ConnectRequest value -> value.targetServer();
      case BridgeMessage.PartyWarp value -> value.targetServer();
      case BridgeMessage.QueueJoin value -> value.targetServer();
      default -> null;
    };
  }

  private static Set<String> split(final @Nullable String declaration) {
    if (declaration == null || declaration.isBlank()) {
      return Set.of();
    }
    return Arrays.stream(declaration.split(","))
        .map(String::trim)
        .filter(entry -> !entry.isEmpty())
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }
}
