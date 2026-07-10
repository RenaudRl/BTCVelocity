/*
 * Copyright (C) 2018-2026 Velocity Contributors
 *
 * The Velocity API is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package com.btcvelocity.api.bridge;

import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Base interface for all {@code btc:bridge} messages.
 *
 * <p>Messages are serialized as JSON with a {@code "type"} discriminator field. The
 * sealed-interface hierarchy below enumerates every message exchanged between the proxy
 * and its backend servers over the {@code btc:bridge} plugin messaging channel. Each
 * record is immutable and safe to share across threads.</p>
 */
public sealed interface BridgeMessage {

  /**
   * Returns the string discriminator used to route this message during (de)serialization.
   *
   * @return the message type identifier
   */
  String type();

  /**
   * Proxy -&gt; Backend: a player joined the queue for a server.
   *
   * @param uuid         the player's unique id
   * @param username     the player's username
   * @param targetServer the name of the server the player queued for
   * @param targetWorld  an optional world the player intends to join, may be {@code null}
   */
  record QueueJoin(UUID uuid, String username, String targetServer,
                   @Nullable String targetWorld) implements BridgeMessage {
    @Override
    public String type() {
      return "queue_join";
    }
  }

  /**
   * Proxy -&gt; Backend: a player left the queue.
   *
   * @param uuid the player's unique id
   */
  record QueueLeave(UUID uuid) implements BridgeMessage {
    @Override
    public String type() {
      return "queue_leave";
    }
  }

  /**
   * Proxy -&gt; Backend: request that the backend report its current health status.
   *
   * @param serverName the name of the server being queried
   */
  record RequestStatus(String serverName) implements BridgeMessage {
    @Override
    public String type() {
      return "request_status";
    }
  }

  /**
   * Proxy -&gt; Backend: a player is about to be transferred, preload the given world.
   *
   * @param serverName the name of the destination server
   * @param worldName  the world to preload, or {@code null} if the default world should be used
   */
  record WorldPreload(String serverName, @Nullable String worldName) implements BridgeMessage {
    @Override
    public String type() {
      return "world_preload";
    }
  }

  /**
   * Backend -&gt; Proxy: a periodic health report from a backend server.
   *
   * @param serverName   the name of the reporting server
   * @param mspt         the milliseconds per tick the server is currently averaging
   * @param tps          the ticks per second the server is currently averaging
   * @param playerCount  the number of players connected to the backend
   * @param loadedWorlds the list of worlds currently loaded on the backend
   */
  record Health(String serverName, double mspt, double tps, int playerCount,
                List<String> loadedWorlds) implements BridgeMessage {
    @Override
    public String type() {
      return "health";
    }
  }

  /**
   * Backend -&gt; Proxy: a world has been loaded and is ready to accept players.
   *
   * @param serverName  the name of the server that loaded the world
   * @param worldName   the name of the world that was loaded
   * @param loadTimeMs  the time in milliseconds it took to load the world
   */
  record WorldLoaded(String serverName, String worldName, long loadTimeMs) implements BridgeMessage {
    @Override
    public String type() {
      return "world_loaded";
    }
  }

  /**
   * Backend -&gt; Proxy: a world has been unloaded.
   *
   * @param serverName the name of the server that unloaded the world
   * @param worldName  the name of the world that was unloaded
   */
  record WorldUnloaded(String serverName, String worldName) implements BridgeMessage {
    @Override
    public String type() {
      return "world_unloaded";
    }
  }

  /**
   * Backend -&gt; Proxy: a response to a queue-status query, reporting the backend's
   * own queue depth.
   *
   * @param serverName        the name of the responding server
   * @param backendQueueSize  the number of players waiting in the backend's own queue
   */
  record QueueStatusResponse(String serverName, int backendQueueSize) implements BridgeMessage {
    @Override
    public String type() {
      return "queue_status_response";
    }
  }
}
