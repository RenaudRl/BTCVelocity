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

package com.btcvelocity.proxy.network;

import com.btcvelocity.api.connection.ClientChannelHook;
import io.netty.channel.Channel;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The {@link ClientChannelHook}s plugins registered, run for each new client channel.
 *
 * <p>A hook that throws is logged and skipped: a plugin must not be able to refuse a connection
 * or stop the hooks after it.
 */
public final class ClientChannelHooks {

  private static final Logger LOGGER = LogManager.getLogger(ClientChannelHooks.class);

  // copy-on-write: hooks are added once at startup, then read on every connection
  private final List<ClientChannelHook> hooks = new CopyOnWriteArrayList<>();

  public void add(final ClientChannelHook hook) {
    this.hooks.add(Objects.requireNonNull(hook, "hook"));
  }

  public void remove(final ClientChannelHook hook) {
    this.hooks.remove(hook);
  }

  /**
   * Runs every hook, in registration order, on a channel whose proxy pipeline is built.
   *
   * @param channel the new client channel
   */
  public void run(final Channel channel) {
    for (final ClientChannelHook hook : this.hooks) {
      try {
        hook.initChannel(channel);
      } catch (final Exception | LinkageError e) {
        // a plugin built for another API (NoSuchMethodError) must not close the channel either
        LOGGER.warn("Client channel hook {} failed on {}; the connection goes on", hook, channel, e);
      }
    }
  }
}
