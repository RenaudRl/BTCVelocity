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
import com.btcvelocity.api.bridge.BridgeMessageListener;
import com.btcvelocity.proxy.cluster.VelocityClusterPlayer;
import com.btcvelocity.proxy.cluster.VelocityClusterPlayerService;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Handles social transport requests from backend servers.
 *
 * <p>Backends (specifically the social Typewriter extensions) send
 * {@link BridgeMessage.ConnectRequest} and {@link BridgeMessage.PartyWarp} messages to move
 * players between servers. Because the moves are resolved through the proxy's cluster player
 * registry, they work for any player known to the cluster — including players connected to a
 * different backend than the one that issued the request. This is the proxy-driven
 * alternative to the plain {@code Connect}/{@code ConnectOther} plugin-messaging path.</p>
 */
public final class SocialTransferHandler implements BridgeMessageListener {

  private static final Logger LOGGER = LogManager.getLogger(SocialTransferHandler.class);

  private final VelocityClusterPlayerService clusterPlayerService;

  /**
   * Creates the handler.
   *
   * @param clusterPlayerService the cluster player registry used to resolve and move players
   */
  public SocialTransferHandler(final VelocityClusterPlayerService clusterPlayerService) {
    this.clusterPlayerService = clusterPlayerService;
  }

  @Override
  public void onMessage(final String sourceServer, final BridgeMessage message) {
    switch (message) {
      case BridgeMessage.ConnectRequest req -> move(req.uuid(), req.targetServer());
      case BridgeMessage.PartyWarp warp -> {
        if (warp.members() != null) {
          for (final UUID member : warp.members()) {
            move(member, warp.targetServer());
          }
        }
      }
      default -> {
        // Not a social transport message; ignore.
      }
    }
  }

  /**
   * Moves the player with the given UUID to the target server, if the cluster knows them.
   *
   * @param uuid         the player's unique id
   * @param targetServer the destination server name
   */
  private void move(final UUID uuid, final String targetServer) {
    if (uuid == null || targetServer == null || targetServer.isBlank()) {
      return;
    }
    clusterPlayerService.getPlayer(uuid).ifPresentOrElse(
        (VelocityClusterPlayer player) -> player.move(targetServer),
        () -> LOGGER.debug("Social transfer: player {} not found in cluster", uuid));
  }
}
