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

package com.btcvelocity.proxy.cluster.redis;

import com.btcvelocity.proxy.cluster.VelocityClusterPlayer;
import com.btcvelocity.proxy.cluster.VelocityClusterPlayerService;
import com.btcvelocity.proxy.redis.VelocityRedis;
import com.btcvelocity.proxy.redis.data.VelocityAlert;
import com.btcvelocity.proxy.redis.depot.player.PlayerDepotService;
import com.btcvelocity.proxy.redis.depot.player.PlayerEntry;
import com.velocitypowered.api.proxy.player.PlayerSettings;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;

/**
 * Redis-backed implementation of {@link VelocityClusterPlayerService}.
 */
public final class RedisClusterPlayerService implements VelocityClusterPlayerService {

  private final VelocityServer server;
  private final VelocityRedis redis;

  public RedisClusterPlayerService(final VelocityServer server, final VelocityRedis redis) {
    this.server = server;
    this.redis = redis;
  }

  private PlayerDepotService playerService() {
    return this.redis.getPlayerService();
  }

  @Override
  public int getTotalPlayerCount() {
    return playerService().getTotalPlayerCount();
  }

  /**
   * Served from the background snapshot, never from Redis: this runs on Netty loops (server
   * selection, BungeeCord {@code PlayerCount}) and in {@code arrival-router}. When the snapshot is
   * too old, Redis is presumed down and the count falls back to this proxy's own players.
   */
  @Override
  public int getPlayersOnServerCount(final String serverName) {
    return playerService().getCachedPlayersOnServer(serverName)
        .orElseGet(() -> this.server.getServer(serverName)
            .map(rs -> rs.getPlayersConnected().size())
            .orElse(0));
  }

  /**
   * Served from the background snapshot (at most {@link PlayerDepotService#REFRESH_INTERVAL} old
   * while Redis answers): the server list ping samples it on a Netty loop.
   */
  @Override
  public Collection<VelocityClusterPlayer> getAllPlayers() {
    return playerService().getCachedEntries().stream()
        .<VelocityClusterPlayer>map(this::toRedisPlayer)
        .toList();
  }

  @Override
  public Collection<VelocityClusterPlayer> getPlayersOnServer(final String serverName) {
    return playerService().getPlayerEntriesInServer(serverName).stream()
        .<VelocityClusterPlayer>map(this::toRedisPlayer)
        .toList();
  }

  @Override
  public Collection<VelocityClusterPlayer> getPlayersOnProxy(final String proxyId) {
    return playerService().getPlayerEntriesOnProxy(proxyId).stream()
        .<VelocityClusterPlayer>map(this::toRedisPlayer)
        .toList();
  }

  @Override
  public Optional<VelocityClusterPlayer> getPlayer(final String username) {
    final PlayerEntry entry = playerService().getPlayerEntry(username);
    return Optional.ofNullable(entry).map(this::toRedisPlayer);
  }

  @Override
  public Optional<VelocityClusterPlayer> getPlayer(final UUID uniqueId) {
    final PlayerEntry entry = playerService().getPlayerEntry(uniqueId);
    return Optional.ofNullable(entry).map(this::toRedisPlayer);
  }

  @Override
  public boolean isPlayerOnline(final String username) {
    return playerService().isPlayerOnline(username);
  }

  @Override
  public CompletableFuture<Boolean> onPlayerConnect(final ConnectedPlayer player) {
    return playerService().onPlayerConnect(player);
  }

  @Override
  public void onPlayerDisconnect(final ConnectedPlayer player) {
    playerService().onPlayerDisconnect(player);
  }

  @Override
  public void onPlayerSwitchServer(final ConnectedPlayer player, final String serverName) {
    playerService().onPlayerSwitchServer(player, serverName);
  }

  @Override
  public void onPlayerSettingsChange(final ConnectedPlayer player, final PlayerSettings settings) {
    playerService().onPlayerSettingsChange(player, settings);
  }

  @Override
  public Collection<String> getPlayerNames() {
    return playerService().getAll().stream()
        .map(PlayerEntry::getUsername)
        .toList();
  }

  @Override
  public void broadcastAlert(final Component message) {
    redis.publish(new VelocityAlert(message));
  }

  private RedisClusterPlayer toRedisPlayer(PlayerEntry playerEntry) {
    return new RedisClusterPlayer(this.server, playerEntry);
  }
}
