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
import com.btcvelocity.api.bridge.BridgeMessageListener;
import com.btcvelocity.proxy.cluster.VelocityClusterPlayer;
import com.btcvelocity.proxy.cluster.VelocityClusterPlayerService;
import com.velocitypowered.proxy.VelocityServer;
import java.util.Optional;
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

  /**
   * The party size ceiling, read from the codec rather than restated.
   *
   * <p>The decoder already enforces it, so this is defence in depth — but a copied number is a
   * number that drifts, and the two checks disagreeing would let a warp through the door the codec
   * believes it closed.
   */
  private static final int MAX_PARTY_MEMBERS = BridgeCodec.Limits.defaults().maxPartyMembers();

  private final VelocityClusterPlayerService clusterPlayerService;
  private final VelocityServer server;

  /**
   * Creates the handler.
   *
   * @param clusterPlayerService the cluster player registry used to resolve and move players
   */
  public SocialTransferHandler(final VelocityClusterPlayerService clusterPlayerService,
                               final VelocityServer server) {
    this.clusterPlayerService = clusterPlayerService;
    this.server = server;
  }

  @Override
  public void onMessage(final String sourceServer, final BridgeMessage message) {
    switch (message) {
      case BridgeMessage.ConnectRequest req -> move(req.uuid(), req.targetServer());
      case BridgeMessage.PartyWarp warp -> warp(warp);
      default -> {
        // Not a social transport message; ignore.
      }
    }
  }

  /**
   * Applies a party warp, or refuses it whole.
   *
   * <p>Validated once, before anybody moves. Previously each member was validated inside its own
   * move, so a single malformed message was rediscovered up to {@code maxPartyMembers} times — one
   * log line per member, from a list the sender controls — and a warp the proxy should have refused
   * could still move whoever happened to pass. A party that arrives split is worse than a party
   * that did not move.
   *
   * <p>Members the cluster does not know are not a defect of the message: they are counted and
   * reported, and the rest of the party still moves. A warp where nobody is known says so, instead
   * of passing for a success.
   */
  private void warp(final BridgeMessage.PartyWarp warp) {
    final PartyWarpValidation.Verdict verdict = PartyWarpValidation.validate(
        warp.members(), warp.targetServer(), MAX_PARTY_MEMBERS);
    if (!verdict.accepted()) {
      LOGGER.warn("Refused party warp of {} member(s): {}",
          warp.members().size(), verdict.rejection());
      return;
    }
    if (server.getServer(warp.targetServer()).isEmpty()) {
      LOGGER.warn("Refused party warp to unregistered server '{}'", warp.targetServer());
      return;
    }

    int unknown = 0;
    for (final UUID member : verdict.members()) {
      final Optional<VelocityClusterPlayer> player = clusterPlayerService.getPlayer(member);
      if (player.isEmpty()) {
        unknown++;
        continue;
      }
      player.get().move(warp.targetServer());
    }
    if (unknown > 0) {
      // Reported as a count, never as a list: the UUIDs come from the other end of the wire and
      // have no business growing the log by the sender's choosing.
      LOGGER.info("Party warp to '{}': {} of {} member(s) were not in the cluster",
          warp.targetServer(), unknown, verdict.members().size());
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
    if (server.getServer(targetServer).isEmpty()) {
      LOGGER.warn("Rejected social transfer to unregistered server '{}'", targetServer);
      return;
    }
    clusterPlayerService.getPlayer(uuid).ifPresentOrElse(
        (VelocityClusterPlayer player) -> player.move(targetServer),
        () -> LOGGER.debug("Social transfer: player {} not found in cluster", uuid));
  }
}
