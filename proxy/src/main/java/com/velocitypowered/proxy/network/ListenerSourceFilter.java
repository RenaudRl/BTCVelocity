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

import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.config.VelocityConfiguration;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Handler of a listener's <em>server</em> channel: refuses, before anything else sees it, a
 * connection that may not send a PROXY protocol header.
 *
 * <p>A server channel hands every accepted child to this handler before Netty's acceptor registers
 * it and runs the child initializer. The refused child is closed here, unregistered: no pipeline
 * is built and no plugin that wraps the initializer (Floodgate) is ever called, so a stranger
 * cannot make the proxy log a thing per connection beyond the one line below.
 *
 * <p>The decision follows the configuration current at the connection, and is recorded on the child
 * ({@link Connections#PROXY_PROTOCOL}) for the initializer.
 */
@ChannelHandler.Sharable
final class ListenerSourceFilter extends ChannelInboundHandlerAdapter {

  private static final Logger LOGGER = LogManager.getLogger(ListenerSourceFilter.class);

  private final VelocityServer server;
  private final @Nullable String listener;
  private final AtomicBoolean warned = new AtomicBoolean();

  /**
   * Creates the filter of one listener.
   *
   * @param server the server, for its current configuration
   * @param listener the name of the listener, {@code null} for the main one
   */
  ListenerSourceFilter(final VelocityServer server, final @Nullable String listener) {
    this.server = server;
    this.listener = listener;
  }

  /**
   * Whether a connection accepted by this listener may go on.
   *
   * @param child the accepted connection, not registered yet
   * @return {@code false} if it must send a PROXY header and its source is not trusted
   */
  boolean admits(final Channel child) {
    // One read of the configuration: the decision is stored on the child, and the initializer
    // applies it without reading the configuration again (a reload in between changes nothing).
    final VelocityConfiguration configuration = this.server.getConfiguration();
    final boolean expectsProxy =
        ServerChannelInitializer.expectsProxyProtocol(this.listener, configuration.isProxyProtocol());
    final boolean admitted = !expectsProxy
        || child.remoteAddress() instanceof InetSocketAddress peer
            && ServerChannelInitializer.trustedSources(configuration, this.listener)
                .contains(peer.getAddress());
    if (admitted) {
      child.attr(Connections.PROXY_PROTOCOL).set(expectsProxy);
    }
    return admitted;
  }

  @Override
  public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
    if (msg instanceof Channel child && !admits(child)) {
      refuse(child);
      return;
    }
    ctx.fireChannelRead(msg);
  }

  private void refuse(final Channel child) {
    final String name = this.listener == null ? "main" : this.listener;
    final String key = this.listener == null
        ? "haproxy-protocol-trusted-proxies" : "geyser-listener-trusted-proxies";
    // First refusal of this listener at warn so that a misconfigured relay is visible; later ones
    // at debug, since anyone can open connections and fill the log.
    if (this.warned.compareAndSet(false, true)) {
      LOGGER.warn("Refusing PROXY protocol connection on the {} listener from untrusted source {} "
          + "(add your relay to {}; further refusals on this listener are logged at debug)", name,
          child.remoteAddress(), key);
    } else {
      LOGGER.debug("Refusing PROXY protocol connection on the {} listener from untrusted source {}",
          name, child.remoteAddress());
    }
    child.unsafe().closeForcibly();
  }
}
