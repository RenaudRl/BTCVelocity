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

package com.btcvelocity.proxy.bridge;

import com.btcvelocity.api.bridge.BridgeChannel;
import com.btcvelocity.api.bridge.BridgeCodec;
import com.btcvelocity.api.bridge.BridgeMessage;
import com.btcvelocity.api.bridge.BridgeMessageListener;
import com.velocitypowered.api.event.EventHandler;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.plugin.virtual.VelocityVirtualPlugin;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Proxy-side implementation of {@link BridgeChannel}.
 *
 * <p>On construction this implementation registers the {@code btc:bridge} channel with the
 * proxy's {@link com.velocitypowered.api.proxy.messages.ChannelRegistrar} and subscribes to
 * {@link PluginMessageEvent}. Incoming messages on the {@code btc:bridge} channel are decoded
 * by {@link BridgeCodec} and dispatched to every registered {@link BridgeMessageListener}.
 * Outgoing messages are encoded and forwarded to the target
 * {@link RegisteredServer}.</p>
 */
public final class VelocityBridgeChannel implements BridgeChannel {

  private static final Logger LOGGER = LogManager.getLogger(VelocityBridgeChannel.class);

  private final VelocityServer server;
  private final CopyOnWriteArrayList<BridgeMessageListener> listeners = new CopyOnWriteArrayList<>();

  /**
   * The event handler subscribed to {@link PluginMessageEvent}, retained so it can be
   * unregistered cleanly during shutdown.
   */
  private final EventHandler<PluginMessageEvent> pluginMessageHandler = this::onPluginMessage;

  /**
   * Creates the bridge channel, registers the {@code btc:bridge} plugin-messaging channel,
   * and subscribes to incoming plugin messages.
   *
   * @param server the owning proxy server
   */
  public VelocityBridgeChannel(final VelocityServer server) {
    this.server = server;
    this.server.getChannelRegistrar().register(CHANNEL_ID);
    this.server.getEventManager()
        .register(VelocityVirtualPlugin.INSTANCE, PluginMessageEvent.class, PostOrder.LAST,
            pluginMessageHandler);
    LOGGER.info("Registered btc:bridge channel");
  }

  @Override
  public void sendToServer(final String serverName, final BridgeMessage message) {
    final Optional<? extends RegisteredServer> opt = server.getServer(serverName);
    if (opt.isEmpty()) {
      LOGGER.warn("Tried to send bridge message {} to unknown server '{}'", message.type(),
          serverName);
      return;
    }
    sendToServer(opt.get(), message);
  }

  @Override
  public void sendToServer(final RegisteredServer serverObj, final BridgeMessage message) {
    final byte[] data = BridgeCodec.encode(message);
    final boolean sent = serverObj.sendPluginMessage(CHANNEL_ID, data);
    if (!sent && LOGGER.isDebugEnabled()) {
      LOGGER.debug("Failed to send bridge message {} to server '{}' (no players connected?)",
          message.type(), serverObj.getServerInfo().getName());
    }
  }

  @Override
  public void registerListener(final BridgeMessageListener listener) {
    listeners.addIfAbsent(listener);
  }

  @Override
  public void unregisterListener(final BridgeMessageListener listener) {
    listeners.remove(listener);
  }

  /**
   * Handles an incoming {@link PluginMessageEvent}, decoding and dispatching it when it
   * arrives on the {@code btc:bridge} channel.
   *
   * @param event the plugin message event
   */
  private void onPluginMessage(final PluginMessageEvent event) {
    if (!matchesChannel(event.getIdentifier())) {
      return;
    }

    // Mark the message handled so the proxy does not forward it to another sink.
    event.setResult(PluginMessageEvent.ForwardResult.handled());

    // 1. Who is talking? Identity is the backend connection the proxy opened, confronted with
    //    the registered server of the same name (name AND address), never the name alone.
    final BridgeIngressPolicy.SourceDecision source = BridgeIngressPolicy.authenticateSource(
        connectionInfo(event.getSource()),
        name -> server.getServer(name).map(RegisteredServer::getServerInfo));
    if (!source.accepted()) {
      // Player sources are the common case here and are not an incident: keep them at debug.
      if (source.rejection() == BridgeIngressPolicy.Rejection.NOT_A_BACKEND) {
        LOGGER.debug("Dropped btc:bridge message: {}", source.rejection());
      } else {
        LOGGER.warn("Dropped btc:bridge message: {}", source.rejection());
      }
      return;
    }
    final String sourceServer = source.sourceServer();

    // 2. Is the payload well-formed, current and bounded? The codec reports a category; the
    //    raw payload is never logged.
    final BridgeCodec.DecodeResult decoded;
    try {
      decoded = BridgeCodec.decodeResult(event.getData(), System.currentTimeMillis(),
          BridgeCodec.Limits.defaults());
    } catch (Exception e) {
      LOGGER.warn("Failed to decode btc:bridge message from '{}'", sourceServer, e);
      return;
    }
    if (!decoded.accepted()) {
      LOGGER.warn("Rejected btc:bridge message from '{}': {} (messageId {})", sourceServer,
          decoded.error(), decoded.messageId());
      return;
    }
    final BridgeMessage message = decoded.message();

    // 3. Does the message speak in someone else's name? A backend may only report about itself.
    final Optional<BridgeIngressPolicy.Rejection> claim =
        BridgeIngressPolicy.verifyDeclaredIdentity(sourceServer, message);
    if (claim.isPresent()) {
      LOGGER.warn("Rejected btc:bridge {} from '{}': {} (messageId {})", message.type(),
          sourceServer, claim.get(), message.envelope().messageId());
      return;
    }

    for (final BridgeMessageListener listener : listeners) {
      try {
        listener.onMessage(sourceServer, message);
      } catch (Exception e) {
        LOGGER.error("A btc:bridge listener threw while handling {} from '{}'",
            message.type(), sourceServer, e);
      }
    }
  }

  /**
   * Determines whether the given channel identifier refers to the {@code btc:bridge} channel.
   *
   * @param identifier the identifier to test
   * @return {@code true} if the identifier matches the bridge channel
   */
  private boolean matchesChannel(final com.velocitypowered.api.proxy.messages.ChannelIdentifier identifier) {
    return CHANNEL_ID.equals(identifier);
  }

  /**
   * Extracts the {@link ServerInfo} of a backend connection source.
   *
   * @param source the plugin message source
   * @return the connection's server info, or {@code null} when the source is a player (client
   *         originated, never an authenticated backend) or anything else
   */
  private static @Nullable ServerInfo connectionInfo(final Object source) {
    return source instanceof ServerConnection conn ? conn.getServerInfo() : null;
  }

  /**
   * Tears down the bridge channel, unregistering the event listener and the
   * {@code btc:bridge} channel from the proxy.
   */
  public void shutdown() {
    this.server.getEventManager().unregister(VelocityVirtualPlugin.INSTANCE, pluginMessageHandler);
    this.server.getChannelRegistrar().unregister(CHANNEL_ID);
    listeners.clear();
    LOGGER.info("Unregistered btc:bridge channel");
  }
}
