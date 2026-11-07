/*
 * Copyright (C) 2018-2026 Velocity Contributors
 *
 * The Velocity API is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package com.btcvelocity.api.connection;

import io.netty.channel.Channel;

/**
 * Gives a plugin the Netty pipeline of every client connection, in the place where it can add
 * handlers around the Minecraft codec without reflecting on the proxy.
 *
 * <p>Register it with {@link com.velocitypowered.api.proxy.ProxyServer#addClientChannelHook}. The
 * proxy calls it once for each channel a client opens, on every listener (the Geyser one included),
 * from the channel's event loop, as soon as the proxy has built its own pipeline and before the
 * first byte is read. The handlers named {@code minecraft-decoder} and {@code minecraft-encoder}
 * are in the pipeline then, so {@code addBefore} on them puts a handler ahead of the codec.
 *
 * <p>Channels that are already open when a hook is registered are not passed to it. Connections the
 * proxy opens to a backend server are not passed either.
 *
 * <p>Hooks run in the order they were registered. A hook that throws is logged and skipped: the
 * other hooks and the connection go on. A hook must not block, as it runs on the event loop.
 */
@FunctionalInterface
public interface ClientChannelHook {

  /**
   * Called for a new client channel.
   *
   * @param channel the channel, with the proxy's pipeline already built
   * @throws Exception if the hook cannot do its work; the proxy logs it and keeps the connection
   */
  void initChannel(Channel channel) throws Exception;
}
