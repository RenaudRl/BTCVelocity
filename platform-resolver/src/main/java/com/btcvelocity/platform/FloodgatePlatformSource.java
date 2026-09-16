/*
 * Copyright (C) 2026 Velocity Contributors
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

package com.btcvelocity.platform;

import com.btcvelocity.api.bridge.BridgeMessage;
import com.btcvelocity.api.bridge.PlatformSource;
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
 * player indistinguishable from a Java one — both are simply "not a Floodgate player" — and that
 * would hand any backend a free way to have claims confirmed about players who are not connected.
 *
 * <p>{@code FloodgateApi.isFloodgateId(UUID)} is deliberately not used: it reads the shape of a
 * UUID, which is the inference this contract forbids.
 *
 * <p>This class lives in a plugin rather than in the proxy core for a reason that is structural,
 * not stylistic: the core's classloader is the parent of every plugin's, so the core cannot see
 * Floodgate's classes at all. A plugin sees both the API and Floodgate, which is what makes
 * compiling against the real type possible here — and impossible there.
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
   * <p>Called from this plugin's own startup, so there is nothing to defer: by then Velocity has
   * loaded every plugin, and the declared dependency guarantees Floodgate came up first. Absence is
   * not an error and is not logged as one: it is a fact about this deployment, which the bridge
   * announces.
   */
  public static PlatformSource createIfPresent(final ProxyServer proxy) {
    Objects.requireNonNull(proxy, "proxy");
    try {
      Class.forName(FLOODGATE_API, false, FloodgatePlatformSource.class.getClassLoader());
      final FloodgateApi api = FloodgateApi.getInstance();
      if (api == null) {
        // On the classpath but not started, or failed to start. Claiming we can resolve would be
        // worse than admitting we cannot.
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
