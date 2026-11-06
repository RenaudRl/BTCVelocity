/*
 * Copyright (C) 2018-2026 Velocity Contributors
 *
 * The Velocity API is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package com.btcvelocity.api.connection;

import com.velocitypowered.api.proxy.InboundConnection;
import java.util.Optional;

/**
 * Implemented by every inbound connection of the proxy: tells which named listener accepted it.
 *
 * <p>The listener is known from the socket, before any packet: it can be read at the
 * {@code PreLoginEvent} (the connection there is an {@link InboundConnection}) with
 * {@link #of(InboundConnection)}. The main {@code bind} listener has no name.
 */
public interface ListenerOrigin {

  /** The listener Geyser connects to ({@code advanced.geyser-listener}), PROXY protocol always on. */
  String GEYSER = "geyser";

  /**
   * The name of the listener that accepted this connection.
   *
   * @return the name, or empty for the main listener
   */
  Optional<String> getListenerName();

  /**
   * Reads the listener name of any inbound connection or player.
   *
   * @param connection the connection, as a plugin gets it from an event
   * @return the listener name, or empty for the main listener or a connection of another origin
   */
  static Optional<String> of(final InboundConnection connection) {
    return connection instanceof ListenerOrigin origin ? origin.getListenerName() : Optional.empty();
  }
}
