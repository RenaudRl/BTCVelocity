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
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry that tracks which worlds are currently loaded on which backend servers.
 *
 * <p>The registry is populated from {@link BridgeMessage.WorldLoaded} and
 * {@link BridgeMessage.WorldUnloaded} messages received over the {@code btc:bridge} channel.
 * It lets the proxy decide whether a world still needs to be preloaded before transferring a
 * queued player, and whether a server is already prepared to accept a player bound for a
 * specific world.</p>
 */
public final class WorldRegistry {

  private final Map<String, Set<String>> worldsByServer = new ConcurrentHashMap<>();

  /**
   * Records that a world has been loaded on the given server.
   *
   * @param message the world-loaded message
   */
  public void onWorldLoaded(final BridgeMessage.WorldLoaded message) {
    worldsByServer.computeIfAbsent(message.serverName(), k -> ConcurrentHashMap.newKeySet())
        .add(message.worldName());
  }

  /**
   * Bridge-message listener entry point suitable for registration with
   * {@link com.btcvelocity.api.bridge.BridgeChannel#registerListener}. It ignores every
   * message type other than {@link BridgeMessage.WorldLoaded}.
   *
   * @param sourceServer the name of the server that sent the message
   * @param message      the decoded bridge message
   */
  public void onWorldLoaded(final String sourceServer, final BridgeMessage message) {
    if (message instanceof BridgeMessage.WorldLoaded loaded) {
      onWorldLoaded(loaded);
    }
  }

  /**
   * Records that a world has been unloaded from the given server.
   *
   * @param message the world-unloaded message
   */
  public void onWorldUnloaded(final BridgeMessage.WorldUnloaded message) {
    final Set<String> worlds = worldsByServer.get(message.serverName());
    if (worlds != null) {
      worlds.remove(message.worldName());
      if (worlds.isEmpty()) {
        worldsByServer.remove(message.serverName(), worlds);
      }
    }
  }

  /**
   * Bridge-message listener entry point suitable for registration with
   * {@link com.btcvelocity.api.bridge.BridgeChannel#registerListener}. It ignores every
   * message type other than {@link BridgeMessage.WorldUnloaded}.
   *
   * @param sourceServer the name of the server that sent the message
   * @param message      the decoded bridge message
   */
  public void onWorldUnloaded(final String sourceServer, final BridgeMessage message) {
    if (message instanceof BridgeMessage.WorldUnloaded unloaded) {
      onWorldUnloaded(unloaded);
    }
  }

  /**
   * Returns whether the given world is currently loaded on the named server.
   *
   * @param serverName the name of the server to check
   * @param worldName  the world to check
   * @return {@code true} if the world is loaded on that server
   */
  public boolean isWorldLoaded(final String serverName, final String worldName) {
    final Set<String> worlds = worldsByServer.get(serverName);
    return worlds != null && worlds.contains(worldName);
  }

  /**
   * Returns an immutable snapshot of the worlds currently loaded on the named server.
   *
   * @param serverName the name of the server to look up
   * @return the set of loaded world names, never {@code null}
   */
  public Set<String> getLoadedWorlds(final String serverName) {
    final Set<String> worlds = worldsByServer.get(serverName);
    return worlds == null ? Collections.emptySet() : Set.copyOf(worlds);
  }

  /**
   * Clears all tracked worlds from the registry.
   */
  public void clear() {
    worldsByServer.clear();
  }
}