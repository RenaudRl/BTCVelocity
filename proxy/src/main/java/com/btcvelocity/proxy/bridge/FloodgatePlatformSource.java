/*
 * Copyright (C) 2018-2026 Velocity Contributors
 *
 * The Velocity API is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package com.btcvelocity.proxy.bridge;

import com.btcvelocity.api.bridge.BridgeMessage;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.geysermc.floodgate.api.FloodgateApi;

/**
 * Resolves a live session's platform from Velocity plus Floodgate.
 *
 * <p>Two sources, because neither answers the question alone. Velocity knows who is <em>here</em>;
 * Floodgate knows which of them came through Bedrock. Asking Floodgate alone would make an offline
 * player indistinguishable from a Java one — both simply "not a Floodgate player" — and that
 * mistake would hand any backend a free way to have claims confirmed about players who are not
 * connected at all.
 *
 * <p>{@code FloodgateApi.isFloodgateId(UUID)} is deliberately not used: it reads the shape of a
 * UUID, which is the inference this contract forbids.
 *
 * <p>Compiled against the real Floodgate type, never against reflection; the class is only ever
 * instantiated after {@link #createIfPresent(ProxyServer)} has established that Floodgate is
 * actually loaded, so a proxy without it never links this class.
 */
public final class FloodgatePlatformSource implements PlatformSource {

  private static final String FLOODGATE_API = "org.geysermc.floodgate.api.FloodgateApi";

  private final ProxyServer proxy;
  private final FloodgateApi floodgate;

  private FloodgatePlatformSource(final ProxyServer proxy, final FloodgateApi floodgate) {
    this.proxy = Objects.requireNonNull(proxy, "proxy");
    this.floodgate = Objects.requireNonNull(floodgate, "floodgate");
  }

  /**
   * Returns a Floodgate-backed source, or {@link PlatformSource#UNAVAILABLE} when Floodgate is not
   * installed here — the production case today.
   *
   * <p>Absence is not an error and is not logged as one: it is a fact about this deployment, and
   * the caller announces it at startup.
   */
  public static PlatformSource createIfPresent(final ProxyServer proxy) {
    Objects.requireNonNull(proxy, "proxy");
    try {
      Class.forName(FLOODGATE_API, false, FloodgatePlatformSource.class.getClassLoader());
      final FloodgateApi api = FloodgateApi.getInstance();
      if (api == null) {
        // The class is on the path but the plugin has not finished starting, or failed to.
        // Claiming we can resolve would be worse than admitting we cannot.
        return PlatformSource.UNAVAILABLE;
      }
      return new FloodgatePlatformSource(proxy, api);
    } catch (ClassNotFoundException | LinkageError absent) {
      return PlatformSource.UNAVAILABLE;
    }
  }

  @Override
  public boolean canResolve() {
    return true;
  }

  @Override
  public Optional<BridgeMessage.Platform> platformOf(final UUID player) {
    Objects.requireNonNull(player, "player");
    if (proxy.getPlayer(player).isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(floodgate.isFloodgatePlayer(player)
        ? BridgeMessage.Platform.BEDROCK
        : BridgeMessage.Platform.JAVA);
  }

  @Override
  public String describe() {
    return "Floodgate";
  }
}
