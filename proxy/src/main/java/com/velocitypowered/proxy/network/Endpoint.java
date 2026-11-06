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

package com.velocitypowered.proxy.network;

import com.google.common.base.Preconditions;
import com.velocitypowered.api.network.ListenerType;
import io.netty.channel.Channel;

/**
 * Represents a listener endpoint.
 */
public final class Endpoint {

  private final Channel channel;

  private final ListenerType type;

  private final boolean announced;

  public Endpoint(final Channel channel, final ListenerType type) {
    this(channel, type, true);
  }

  /**
   * Creates an endpoint.
   *
   * @param channel the server channel
   * @param type the type of listener
   * @param announced whether plugins are told when it opens and closes ({@code ListenerBoundEvent},
   *     {@code ListenerCloseEvent}); a listener that is not the proxy's Minecraft listener must not
   *     be taken for it (Geyser starts on the bound event of a Minecraft listener)
   */
  public Endpoint(final Channel channel, final ListenerType type, final boolean announced) {
    this.channel = Preconditions.checkNotNull(channel, "channel");
    this.type = Preconditions.checkNotNull(type, "type");
    this.announced = announced;
  }

  public boolean isAnnounced() {
    return announced;
  }

  public Channel getChannel() {
    return channel;
  }

  public ListenerType getType() {
    return type;
  }
}
