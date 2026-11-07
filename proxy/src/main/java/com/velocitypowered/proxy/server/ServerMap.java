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

package com.velocitypowered.proxy.server;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.velocitypowered.api.event.proxy.server.ServerRegisteredEvent;
import com.velocitypowered.api.event.proxy.server.ServerUnregisteredEvent;
import com.velocitypowered.api.proxy.config.BackendServerConfig;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.util.AddressUtil;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Implements the Velocity server registry.
 */
public class ServerMap {

  private final @Nullable VelocityServer server;

  private final Map<String, VelocityRegisteredServer> servers = new ConcurrentHashMap<>();

  public ServerMap(final @Nullable VelocityServer server) {
    this.server = server;
  }

  /**
   * Returns the server associated with the given name.
   *
   * @param name the name to look up
   * @return the server, if it exists
   */
  public Optional<VelocityRegisteredServer> getServer(final String name) {
    Preconditions.checkNotNull(name, "server");
    String lowerName = name.toLowerCase(Locale.US);
    return Optional.ofNullable(servers.get(lowerName));
  }

  public Collection<VelocityRegisteredServer> getAllServers() {
    return ImmutableList.copyOf(servers.values());
  }

  /**
   * Creates a raw implementation of a {@link VelocityRegisteredServer} without tying it to the internal
   * server map.
   *
   * @param serverInfo the server to create a registered server with
   * @return the {@link VelocityRegisteredServer} built from the {@link ServerInfo}
   */
  public VelocityRegisteredServer createRawRegisteredServer(final ServerInfo serverInfo) {
    return new VelocityRegisteredServer(server, serverInfo);
  }

  /**
   * Registers a server with the proxy.
   *
   * @param serverInfo the server to register
   * @return the registered server
   */
  public VelocityRegisteredServer register(final ServerInfo serverInfo) {
    Preconditions.checkNotNull(serverInfo, "serverInfo");
    String lowerName = serverInfo.getName().toLowerCase(Locale.US);
    VelocityRegisteredServer rs = createRawRegisteredServer(serverInfo);

    VelocityRegisteredServer existing = servers.putIfAbsent(lowerName, rs);
    if (existing != null && !existing.getServerInfo().equals(serverInfo)) {
      throw new IllegalArgumentException(
          "Server with name " + serverInfo.getName() + " already registered");
    } else if (existing == null) {
      if (server != null) {
        server.getEventManager().fireAndForget(new ServerRegisteredEvent(rs));
      }

      return rs;
    } else {
      return existing;
    }
  }

  /**
   * Brings the servers that come from {@code velocity.toml} in line with a reloaded configuration.
   *
   * <p>Only servers named in {@code previous} (the configuration being replaced) can be removed:
   * a server registered through the plugin API, such as the ones of btc-cloudnet-registry, is not
   * ours to drop. A toml server that shares its name with an API server replaces it, as upstream
   * Velocity does.</p>
   *
   * @param previous the backend servers of the configuration being replaced
   * @param next the backend servers of the new configuration
   * @return the players of removed or replaced servers, who must be moved elsewhere
   */
  public Collection<ConnectedPlayer> applyConfiguredServers(
      final Map<String, BackendServerConfig> previous,
      final Map<String, BackendServerConfig> next) {
    ImmutableList.Builder<ConnectedPlayer> evacuate = ImmutableList.builder();
    removeDroppedServers(previous, next, evacuate);
    registerOrReplaceServers(next, evacuate);
    return evacuate.build();
  }

  private void removeDroppedServers(final Map<String, BackendServerConfig> previous,
      final Map<String, BackendServerConfig> next, final ImmutableList.Builder<ConnectedPlayer> evacuate) {
    for (String oldName : previous.keySet()) {
      if (next.keySet().stream().anyMatch(name -> name.equalsIgnoreCase(oldName))) {
        continue;
      }
      getServer(oldName).ifPresent(removed -> {
        evacuate.addAll(removed.getPlayersConnected());
        unregister(removed.getServerInfo());
      });
    }
  }

  private void registerOrReplaceServers(final Map<String, BackendServerConfig> next,
      final ImmutableList.Builder<ConnectedPlayer> evacuate) {
    for (Map.Entry<String, BackendServerConfig> entry : next.entrySet()) {
      ServerInfo info = new ServerInfo(entry.getKey(),
          AddressUtil.parseAddress(entry.getValue().address()), entry.getValue().forwardingMode());
      Optional<VelocityRegisteredServer> existing = getServer(entry.getKey());
      if (existing.isEmpty()) {
        register(info);
      } else if (!existing.get().getServerInfo().equals(info)) {
        evacuate.addAll(existing.get().getPlayersConnected());
        unregister(existing.get().getServerInfo());
        register(info);
      }
    }
  }

  /**
   * Unregisters the specified server from the proxy.
   *
   * @param serverInfo the server to unregister
   */
  public void unregister(final ServerInfo serverInfo) {
    Preconditions.checkNotNull(serverInfo, "serverInfo");
    String lowerName = serverInfo.getName().toLowerCase(Locale.US);
    VelocityRegisteredServer rs = servers.get(lowerName);
    if (rs == null) {
      throw new IllegalArgumentException(
          "Server with name " + serverInfo.getName() + " is not registered!");
    }

    Preconditions.checkArgument(rs.getServerInfo().equals(serverInfo),
        "Trying to remove server %s with differing information", serverInfo.getName());
    Preconditions.checkState(servers.remove(lowerName, rs),
        "Server with name %s replaced whilst unregistering", serverInfo.getName());

    if (server != null) {
      // A gone service takes its failures with it: one registered again under this name is new.
      if (server.getBackendHealthRegistry() != null) {
        server.getBackendHealthRegistry().forget(serverInfo.getName());
      }
      server.getEventManager().fireAndForget(new ServerUnregisteredEvent(rs));
    }
  }
}
