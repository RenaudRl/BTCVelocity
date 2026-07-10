/*
 * Copyright (C) 2018-2026 Velocity Contributors
 *
 * The Velocity API is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package com.btcvelocity.api.bridge;

import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;

/**
 * Service for sending and receiving {@code btc:bridge} messages between the proxy and
 * its backend servers.
 *
 * <p>The bridge channel uses the {@code btc:bridge} plugin-messaging identifier. Outgoing
 * messages are encoded by {@link BridgeCodec} and forwarded to the target
 * {@link RegisteredServer}. Incoming messages from a backend are decoded and dispatched to
 * every registered {@link BridgeMessageListener}.</p>
 */
public interface BridgeChannel {

  /**
   * The channel identifier used for all bridge traffic.
   */
  MinecraftChannelIdentifier CHANNEL_ID = MinecraftChannelIdentifier.create("btc", "bridge");

  /**
   * Sends a bridge message to the named backend server.
   *
   * <p>If no server with the given name is registered, the message is silently dropped.</p>
   *
   * @param serverName the name of the target server
   * @param message    the message to send
   */
  void sendToServer(String serverName, BridgeMessage message);

  /**
   * Sends a bridge message to the given backend server.
   *
   * @param server  the target server
   * @param message the message to send
   */
  void sendToServer(RegisteredServer server, BridgeMessage message);

  /**
   * Registers a listener that will be notified of every incoming bridge message.
   *
   * @param listener the listener to register
   */
  void registerListener(BridgeMessageListener listener);

  /**
   * Removes a previously registered listener. Has no effect if the listener was not registered.
   *
   * @param listener the listener to remove
   */
  void unregisterListener(BridgeMessageListener listener);
}
