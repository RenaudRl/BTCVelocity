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

import io.netty.channel.embedded.EmbeddedChannel;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

/** A channel whose TCP peer is the given address, as a socket would report it. */
final class PeerChannel extends EmbeddedChannel {

  private final SocketAddress peer;

  PeerChannel(final String peer) {
    super();
    this.peer = new InetSocketAddress(InetAddress.ofLiteral(peer), 40000);
  }

  @Override
  protected SocketAddress remoteAddress0() {
    return this.peer;
  }
}
