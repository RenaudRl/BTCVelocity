/*
 * Copyright (C) 2018-2026 Velocity Contributors
 *
 * The Velocity API is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package com.btcvelocity.api.connection;

import com.velocitypowered.api.proxy.InboundConnection;
import io.netty.channel.Channel;
import java.util.Optional;

/**
 * Implemented by every inbound connection and player of the proxy: gives the Netty channel of the
 * client, the same one a {@link ClientChannelHook} was called with when it opened.
 *
 * <p>It lets a plugin that added handlers in a hook find the channel of a player later, for
 * instance at the {@code PostLoginEvent}.
 */
public interface ConnectionChannel {

  /**
   * The channel of the client side of this connection.
   *
   * @return the channel
   */
  Channel getChannel();

  /**
   * Reads the channel of any inbound connection or player.
   *
   * @param connection the connection, as a plugin gets it from an event
   * @return the channel, or empty for a connection of another origin
   */
  static Optional<Channel> of(final InboundConnection connection) {
    return connection instanceof ConnectionChannel owner ? Optional.of(owner.getChannel()) : Optional.empty();
  }
}
