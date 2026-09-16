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

  /**
   * Environment variable holding the proxy's declared bridge identity. A variable, not a file:
   * the working directory of a CloudNet service is recreated from its template at every start,
   * so a file written there is as volatile as memory.
   */
  private static final String PROXY_ID_ENV = "BTC_BRIDGE_PROXY_ID";

  /** The identity used when the environment declares none. Backends allowlist this name. */
  private static final String DEFAULT_PROXY_ID = "btc-proxy";

  private final VelocityServer server;
  private final CopyOnWriteArrayList<BridgeMessageListener> listeners = new CopyOnWriteArrayList<>();
  private final BridgeDeduplication deduplication = BridgeDeduplication.defaults();
  private final String proxyId;
  private final BridgeAuthorization authorization;
  private final BridgeMetrics metrics = new BridgeMetrics();

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
    this(server, System.getenv(PROXY_ID_ENV), BridgeAuthorization.fromEnvironment());
  }

  /**
   * Creates the bridge channel with an explicit identity and authorization policy.
   *
   * @param server        the owning proxy server
   * @param proxyId       the proxy's declared bridge identity, or {@code null} / blank for the
   *                      default
   * @param authorization who may command the proxy, and where players may be sent
   */
  public VelocityBridgeChannel(final VelocityServer server, final @Nullable String proxyId,
                               final BridgeAuthorization authorization) {
    this.server = server;
    this.proxyId = proxyId == null || proxyId.isBlank() ? DEFAULT_PROXY_ID : proxyId.trim();
    this.authorization = authorization;
    this.server.getChannelRegistrar().register(CHANNEL_ID);
    this.server.getEventManager()
        .register(VelocityVirtualPlugin.INSTANCE, PluginMessageEvent.class, PostOrder.LAST,
            pluginMessageHandler);
    LOGGER.info("Registered btc:bridge channel as '{}'", this.proxyId);
    // An undeclared policy is inert, and saying so is the point: a silent inert policy reads
    // exactly like an enforced one until the day someone counts on it.
    if (this.authorization.filtersSources()) {
      LOGGER.info("btc:bridge commands are restricted to {} backend(s)",
          this.authorization.allowedSources().size());
    } else {
      LOGGER.info("btc:bridge accepts commands from any authenticated backend (no {} declared)",
          BridgeAuthorization.SOURCES_ENV);
    }
    if (this.authorization.filtersTargets()) {
      LOGGER.info("btc:bridge destinations are restricted to {} server(s)",
          this.authorization.allowedTargets().size());
    }
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
    metrics.record(BridgeMetrics.Event.RECEIVED);

    final ServerConnection connection =
        event.getSource() instanceof ServerConnection conn ? conn : null;

    // 1. Who is talking? Identity is the backend connection the proxy opened, confronted with
    //    the registered server of the same name (name AND address), never the name alone.
    final BridgeIngressPolicy.SourceDecision source = BridgeIngressPolicy.authenticateSource(
        connection == null ? null : connection.getServerInfo(),
        name -> server.getServer(name).map(RegisteredServer::getServerInfo));
    if (!source.accepted()) {
      metrics.record(BridgeMetrics.Event.REJECTED_SOURCE);
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
      metrics.record(BridgeMetrics.Event.REJECTED_PAYLOAD);
      LOGGER.warn("Failed to decode btc:bridge message from '{}'", sourceServer, e);
      return;
    }
    if (!decoded.accepted()) {
      metrics.record(BridgeMetrics.Event.REJECTED_PAYLOAD);
      LOGGER.warn("Rejected btc:bridge message from '{}': {} (messageId {})", sourceServer,
          decoded.error(), decoded.messageId());
      // A payload whose id could not even be read cannot be answered: a refusal has to name the
      // message it refuses, and the backend has nothing to tie a fresh id to.
      if (decoded.messageId() != null) {
        refuse(connection, decoded.messageId(), sourceServer,
            BridgeResponses.categoryOf(decoded.error()));
      }
      return;
    }
    final BridgeMessage message = decoded.message();

    // 3. Does the message speak in someone else's name? A backend may only report about itself.
    final Optional<BridgeIngressPolicy.Rejection> claim =
        BridgeIngressPolicy.verifyDeclaredIdentity(sourceServer, message);
    if (claim.isPresent()) {
      metrics.record(BridgeMetrics.Event.REJECTED_IDENTITY);
      LOGGER.warn("Rejected btc:bridge {} from '{}': {} (messageId {})", message.type(),
          sourceServer, claim.get(), message.envelope().messageId());
      final BridgeMessage.ErrorCode category = BridgeResponses.categoryOf(claim.get());
      if (category != null) {
        refuse(connection, message.messageId(), sourceServer, category);
      }
      return;
    }

    // 4. Authenticated, and speaking for itself. Is it allowed to ask this? Authorization is a
    //    separate question from identity, and it is asked before anything is executed.
    final boolean acknowledgeable = BridgeResponses.isAcknowledgeable(message);
    final BridgeMessage.ErrorCode refusal = authorization.refuse(sourceServer, message);
    if (refusal != null) {
      metrics.record(BridgeMetrics.Event.REJECTED_AUTHORIZATION);
      LOGGER.warn("Refused btc:bridge {} from '{}': {} (messageId {})", message.type(),
          sourceServer, refusal, message.messageId());
      if (acknowledgeable) {
        refuse(connection, message.messageId(), sourceServer, refusal);
      }
      return;
    }

    // 5. Has this exact command already been executed? A redelivery is acknowledged again with
    //    duplicate = true, and is never executed a second time.
    if (acknowledgeable
        && deduplication.alreadySeen(message.messageId(), System.currentTimeMillis())) {
      metrics.record(BridgeMetrics.Event.DUPLICATE);
      LOGGER.debug("Duplicate btc:bridge {} from '{}' (messageId {})", message.type(),
          sourceServer, message.messageId());
      acknowledge(connection, message, true);
      return;
    }

    metrics.record(BridgeMetrics.Event.DISPATCHED);
    for (final BridgeMessageListener listener : listeners) {
      try {
        listener.onMessage(sourceServer, message);
      } catch (Exception e) {
        metrics.record(BridgeMetrics.Event.LISTENER_FAILED);
        LOGGER.error("A btc:bridge listener threw while handling {} from '{}'",
            message.type(), sourceServer, e);
      }
    }

    if (acknowledgeable) {
      acknowledge(connection, message, false);
    }
  }

  /**
   * Sends an acknowledgement back on the very connection the command arrived on.
   *
   * @param connection the source backend connection
   * @param request    the command being acknowledged
   * @param duplicate  whether the command had already been executed
   */
  private void acknowledge(final @Nullable ServerConnection connection,
                           final BridgeMessage request, final boolean duplicate) {
    metrics.record(BridgeMetrics.Event.ACKNOWLEDGED);
    respond(connection, BridgeResponses.ack(request, proxyId, duplicate,
        System.currentTimeMillis()), request.type());
  }

  /**
   * Sends a categorized refusal back on the source connection.
   *
   * @param connection   the source backend connection
   * @param messageId    the id of the refused message
   * @param sourceServer the authenticated source, which is the addressee of the refusal
   * @param error        the refusal category
   */
  private void refuse(final @Nullable ServerConnection connection, final java.util.UUID messageId,
                      final String sourceServer, final BridgeMessage.ErrorCode error) {
    metrics.record(BridgeMetrics.Event.REFUSED);
    respond(connection, BridgeResponses.nack(messageId, sourceServer, proxyId, error,
        System.currentTimeMillis()), "nack");
  }

  /**
   * Writes a response on the source connection.
   *
   * <p>The response goes back on the connection that carried the request, never through a lookup
   * by name: the name has already been confronted with this connection at ingress, and resolving
   * it a second time would open the door the identity check just closed.</p>
   *
   * @param connection the source backend connection
   * @param response   the response to write
   * @param about      the type of the message being answered, for the log line only
   */
  private void respond(final @Nullable ServerConnection connection, final BridgeMessage response,
                       final String about) {
    if (connection == null) {
      return;
    }
    try {
      connection.sendPluginMessage(CHANNEL_ID, BridgeCodec.encode(response));
    } catch (Exception e) {
      // Never let a failed response break the handling of the request it answers.
      metrics.record(BridgeMetrics.Event.RESPONSE_FAILED);
      LOGGER.warn("Could not answer btc:bridge {} on '{}'", about,
          connection.getServerInfo().getName(), e);
    }
  }

  /**
   * The bridge counters, for an operator surface.
   *
   * @return the live metrics of this channel
   */
  public BridgeMetrics metrics() {
    return metrics;
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
