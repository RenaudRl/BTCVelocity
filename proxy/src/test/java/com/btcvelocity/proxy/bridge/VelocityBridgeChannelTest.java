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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.btcvelocity.api.bridge.BridgeChannel;
import com.btcvelocity.api.bridge.BridgeCodec;
import com.btcvelocity.api.bridge.BridgeFrame;
import com.btcvelocity.api.bridge.BridgeMessage;
import com.btcvelocity.api.bridge.BridgePlatformSources;
import com.velocitypowered.api.event.EventHandler;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelMessageSink;
import com.velocitypowered.api.proxy.messages.ChannelMessageSource;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.config.VelocityConfiguration;
import com.velocitypowered.proxy.event.VelocityEventManager;
import com.velocitypowered.proxy.server.VelocityRegisteredServer;
import com.velocitypowered.proxy.util.VelocityChannelRegistrar;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@code onPluginMessage} end to end: a real {@link VelocityBridgeChannel} fed real
 * {@link PluginMessageEvent}s through the handler it registered on the event manager, with only the
 * Velocity edges mocked (server, registry, connections). Every refusal is paired with the same
 * montage accepting a valid frame, so a broken montage cannot make a refusal pass for free.
 */
class VelocityBridgeChannelTest {

  private static final byte[] SECRET =
      "btc-bridge-channel-test-secret-0001".getBytes(StandardCharsets.US_ASCII);
  private static final String PROXY_ID = "btc-proxy";

  private static final ServerInfo LOBBY =
      new ServerInfo("lobby", new InetSocketAddress("10.0.0.10", 30200));
  private static final ServerInfo SKY =
      new ServerInfo("sky", new InetSocketAddress("10.0.0.11", 30201));

  private final BridgeFrame key = BridgeFrame.fromSharedSecret(SECRET);
  private final List<String> dispatchedFrom = new CopyOnWriteArrayList<>();
  private final List<BridgeMessage> dispatched = new CopyOnWriteArrayList<>();

  private VelocityBridgeChannel channel;
  private EventHandler<PluginMessageEvent> handler;
  private ServerConnection lobbyConnection;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void montage() {
    BridgePlatformSources.reset();

    final VelocityConfiguration configuration = mock(VelocityConfiguration.class);
    when(configuration.getForwardingSecret()).thenReturn(SECRET);
    final VelocityEventManager events = mock(VelocityEventManager.class);
    final VelocityServer server = mock(VelocityServer.class);
    when(server.getConfiguration()).thenReturn(configuration);
    when(server.getEventManager()).thenReturn(events);
    when(server.getChannelRegistrar()).thenReturn(mock(VelocityChannelRegistrar.class));
    register(server, LOBBY);
    register(server, SKY);

    // lobby may command the proxy, and may send players to sky only.
    channel = new VelocityBridgeChannel(server, PROXY_ID,
        BridgeAuthorization.parse("lobby", "sky"));
    channel.registerListener((source, message) -> {
      dispatchedFrom.add(source);
      dispatched.add(message);
    });

    final ArgumentCaptor<EventHandler<PluginMessageEvent>> captured =
        ArgumentCaptor.forClass(EventHandler.class);
    verify(events).register(any(), eq(PluginMessageEvent.class), eq(PostOrder.LAST),
        captured.capture());
    handler = captured.getValue();

    lobbyConnection = connectionTo(LOBBY);
  }

  @AfterEach
  void cleanUp() {
    BridgePlatformSources.reset();
  }

  // ---------------------------------------------------------------- the five cases

  @Test
  void aPlayerSourceIsHandledNeverDispatchedAndCounted() {
    // A perfectly valid signed frame: only the source can be what refuses it.
    final BridgeMessage request = connectRequest("lobby");
    final Player player = mock(Player.class);

    final PluginMessageEvent event = deliver(player, signed(request));

    assertHandled(event);
    assertTrue(dispatched.isEmpty(), "nothing may reach the listeners");
    assertEquals(1, channel.metrics().count(BridgeMetrics.Event.RECEIVED));
    assertEquals(1, channel.metrics().count(BridgeMetrics.Event.REJECTED_SOURCE));
    assertEquals(0, channel.metrics().count(BridgeMetrics.Event.DISPATCHED));
    // No answer to a forged source: it would confirm the channel is live.
    verifyNoInteractions(player);

    assertWitnessIsDispatched();
  }

  @Test
  void anUnsignedRawJsonFrameFromABackendIsRefusedAsUnsigned() {
    // What a Typewriter extension emits today: the V2 JSON as is, no 0xB7, no MAC.
    final BridgeMessage request = connectRequest("lobby");
    final byte[] raw = BridgeCodec.encode(request);
    assertNotEquals(BridgeFrame.MAGIC, raw[0]);
    assertTrue(BridgeCodec.decodeResult(raw, System.currentTimeMillis(),
        BridgeCodec.Limits.defaults()).accepted(), "the payload itself is valid");
    assertEquals(BridgeFrame.Rejection.UNSIGNED, key.open(raw).rejection());

    final PluginMessageEvent event = deliver(lobbyConnection, raw);

    assertHandled(event);
    assertTrue(dispatched.isEmpty());
    assertEquals(1, channel.metrics().count(BridgeMetrics.Event.REJECTED_SIGNATURE));
    assertEquals(0, channel.metrics().count(BridgeMetrics.Event.DISPATCHED));
    // Nothing is answered to an unauthenticated frame.
    verify(lobbyConnection, never()).sendPluginMessage(any(), any(byte[].class));

    assertWitnessIsDispatched();
  }

  @Test
  void aSignedValidFrameFromAnAllowedBackendIsDispatchedOnceAndAcknowledged() {
    final BridgeMessage request = connectRequest("lobby");

    final PluginMessageEvent event = deliver(lobbyConnection, signed(request));

    assertHandled(event);
    assertEquals(List.of("lobby"), dispatchedFrom);
    assertEquals(List.of(request), dispatched);
    assertEquals(1, channel.metrics().count(BridgeMetrics.Event.DISPATCHED));
    assertEquals(1, channel.metrics().count(BridgeMetrics.Event.ACKNOWLEDGED));
    final BridgeMessage.Ack ack = assertInstanceOf(BridgeMessage.Ack.class,
        onlyResponse(lobbyConnection));
    assertEquals(request.messageId(), ack.messageId(), "an ack reuses the id of its request");
    assertEquals(PROXY_ID, ack.sourceBackend());
    assertEquals("lobby", ack.envelope().targetBackend());
    assertFalse(ack.duplicate());
  }

  @Test
  void theSameFrameReplayedIsDispatchedOnceAndAcknowledgedAsDuplicate() {
    final byte[] frame = signed(connectRequest("lobby"));

    deliver(lobbyConnection, frame);
    deliver(lobbyConnection, frame);

    assertEquals(1, dispatched.size(), "a redelivery is never executed twice");
    assertEquals(1, channel.metrics().count(BridgeMetrics.Event.DISPATCHED));
    assertEquals(1, channel.metrics().count(BridgeMetrics.Event.DUPLICATE));
    assertEquals(2, channel.metrics().count(BridgeMetrics.Event.ACKNOWLEDGED));
    final ArgumentCaptor<byte[]> sent = ArgumentCaptor.forClass(byte[].class);
    verify(lobbyConnection, times(2)).sendPluginMessage(eq(BridgeChannel.CHANNEL_ID),
        sent.capture());
    assertFalse(assertInstanceOf(BridgeMessage.Ack.class, open(sent.getAllValues().get(0)))
        .duplicate());
    assertTrue(assertInstanceOf(BridgeMessage.Ack.class, open(sent.getAllValues().get(1)))
        .duplicate());

    assertWitnessIsDispatched();
  }

  @Test
  void aFrameSignedWithTheWrongKeyIsRefusedOnItsSignature() {
    final byte[] forged = BridgeFrame.fromSharedSecret(
        "not-the-forwarding-secret".getBytes(StandardCharsets.US_ASCII))
        .seal(BridgeCodec.encode(connectRequest("lobby")));

    final PluginMessageEvent event = deliver(lobbyConnection, forged);

    assertHandled(event);
    assertTrue(dispatched.isEmpty());
    assertEquals(1, channel.metrics().count(BridgeMetrics.Event.REJECTED_SIGNATURE));
    assertEquals(0, channel.metrics().count(BridgeMetrics.Event.DISPATCHED));
    verify(lobbyConnection, never()).sendPluginMessage(any(), any(byte[].class));

    assertWitnessIsDispatched();
  }

  // ---------------------------------------------------------------- montage

  private static void register(final VelocityServer server, final ServerInfo info) {
    final VelocityRegisteredServer registered = mock(VelocityRegisteredServer.class);
    when(registered.getServerInfo()).thenReturn(info);
    when(server.getServer(info.getName())).thenReturn(Optional.of(registered));
  }

  private static ServerConnection connectionTo(final ServerInfo info) {
    final ServerConnection connection = mock(ServerConnection.class);
    when(connection.getServerInfo()).thenReturn(info);
    when(connection.sendPluginMessage(any(), any(byte[].class))).thenReturn(true);
    return connection;
  }

  /** A connect_request from {@code sourceBackend} sending a random player to sky, no platform. */
  private static BridgeMessage connectRequest(final String sourceBackend) {
    final long now = System.currentTimeMillis();
    return new BridgeMessage.ConnectRequest(
        new BridgeMessage.Envelope(BridgeMessage.VERSION, UUID.randomUUID(), "connect_request",
            sourceBackend, PROXY_ID, now - 1_000L, now + 30_000L),
        UUID.randomUUID(), "sky", null);
  }

  private byte[] signed(final BridgeMessage message) {
    return key.seal(BridgeCodec.encode(message));
  }

  private PluginMessageEvent deliver(final ChannelMessageSource source, final byte[] data) {
    final PluginMessageEvent event = new PluginMessageEvent(source, mock(ChannelMessageSink.class),
        BridgeChannel.CHANNEL_ID, data);
    handler.execute(event);
    return event;
  }

  private static void assertHandled(final PluginMessageEvent event) {
    assertFalse(event.getResult().isAllowed(), "the message must not be forwarded anywhere");
  }

  /** Opens and decodes the one response the proxy wrote on a connection. */
  private BridgeMessage onlyResponse(final ServerConnection connection) {
    final ArgumentCaptor<byte[]> sent = ArgumentCaptor.forClass(byte[].class);
    verify(connection).sendPluginMessage(eq(BridgeChannel.CHANNEL_ID), sent.capture());
    return open(sent.getValue());
  }

  private BridgeMessage open(final byte[] wire) {
    final BridgeFrame.Opened opened = key.open(wire);
    assertTrue(opened.accepted(), "the proxy signs what it sends");
    final BridgeCodec.DecodeResult decoded = BridgeCodec.decodeResult(opened.payload(),
        System.currentTimeMillis(), BridgeCodec.Limits.defaults());
    assertTrue(decoded.accepted(), "the proxy sends well-formed messages");
    return decoded.message();
  }

  /**
   * The witness: the montage as it stands still dispatches a valid frame from the allowed backend
   * (one more message, the right one). Without it, a montage that refuses everything would pass.
   */
  private void assertWitnessIsDispatched() {
    final int before = dispatched.size();
    final BridgeMessage witness = connectRequest("lobby");

    deliver(connectionTo(LOBBY), signed(witness));

    assertEquals(before + 1, dispatched.size(), "witness: a valid frame must be dispatched");
    assertEquals(witness, dispatched.get(before));
  }
}
