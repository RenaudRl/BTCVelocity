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

package com.velocitypowered.proxy.connection.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.event.player.PlayerChannelRegisterEvent;
import com.velocitypowered.api.event.player.PlayerChannelUnregisterEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.backend.VelocityServerConnection;
import com.velocitypowered.proxy.event.VelocityEventManager;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.util.collect.CappedSet;
import com.velocitypowered.proxy.protocol.packet.PluginMessagePacket;
import com.velocitypowered.proxy.util.VelocityChannelRegistrar;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ClientChannelUpdatesTest {

  private static final ProtocolVersion CLIENT = ProtocolVersion.MINECRAFT_1_21_4;

  private static final ChannelIdentifier FABRIC_A = MinecraftChannelIdentifier.from("fabric:a");

  private static final ChannelIdentifier FABRIC_B = MinecraftChannelIdentifier.from("fabric:b");

  private static PluginMessagePacket nulJoined(final String channel, final String... ids) {
    return new PluginMessagePacket(channel, Unpooled.copiedBuffer(String.join("\0", ids),
        StandardCharsets.UTF_8));
  }

  private static ByteBuf commonRegisterPayload(final int version, final String phase,
                                               final String... ids) {
    final ByteBuf buf = Unpooled.buffer();
    ProtocolUtils.writeVarInt(buf, version);
    ProtocolUtils.writeString(buf, phase);
    ProtocolUtils.writeVarInt(buf, ids.length);
    for (final String id : ids) {
      ProtocolUtils.writeString(buf, id);
    }
    return buf;
  }

  private static PluginMessagePacket commonRegister(final String phase, final String... ids) {
    return new PluginMessagePacket("c:register", commonRegisterPayload(1, phase, ids));
  }

  private static ClientChannelUpdates.Update expectUpdate(
      final Optional<ClientChannelUpdates.Update> result) {
    assertTrue(result.isPresent(), "an update was expected");
    return result.get();
  }

  @Test
  void registerIsRecordedAndReported() {
    final Set<ChannelIdentifier> known = new HashSet<>();

    final var update = expectUpdate(ClientChannelUpdates.apply(
        nulJoined("minecraft:register", "fabric:a", "fabric:b"), known, CLIENT, null));

    assertEquals(ClientChannelUpdates.Kind.REGISTER, update.kind());
    assertEquals(List.of(FABRIC_A, FABRIC_B), update.channels());
    assertEquals(Set.of(FABRIC_A, FABRIC_B), known);
  }

  @Test
  void aRepeatedRegisterReportsEveryChannelAgainButStoresThemOnce() {
    final Set<ChannelIdentifier> known = new HashSet<>();
    ClientChannelUpdates.apply(nulJoined("minecraft:register", "fabric:a"), known, CLIENT, null);

    final var again = expectUpdate(ClientChannelUpdates.apply(
        nulJoined("minecraft:register", "fabric:a"), known, CLIENT, null));

    assertEquals(List.of(FABRIC_A), again.channels());
    assertEquals(Set.of(FABRIC_A), known);
  }

  @Test
  void partialOverlapReportsAllTheChannelsOfThePacket() {
    final Set<ChannelIdentifier> known = new HashSet<>(Set.of(FABRIC_A));

    final var update = expectUpdate(ClientChannelUpdates.apply(
        nulJoined("minecraft:register", "fabric:a", "fabric:b"), known, CLIENT, null));

    assertEquals(List.of(FABRIC_A, FABRIC_B), update.channels());
    assertEquals(Set.of(FABRIC_A, FABRIC_B), known);
  }

  @Test
  void unregisterThenRegisterAgainIsReportedAsNew() {
    final Set<ChannelIdentifier> known = new HashSet<>(Set.of(FABRIC_A));

    final var removed = expectUpdate(ClientChannelUpdates.apply(
        nulJoined("minecraft:unregister", "fabric:a"), known, CLIENT, null));
    assertEquals(ClientChannelUpdates.Kind.UNREGISTER, removed.kind());
    assertEquals(List.of(FABRIC_A), removed.channels());
    assertTrue(known.isEmpty());

    final var again = expectUpdate(ClientChannelUpdates.apply(
        nulJoined("minecraft:register", "fabric:a"), known, CLIENT, null));
    assertEquals(List.of(FABRIC_A), again.channels());
  }

  @Test
  void commonRegisterOfThePlayPhaseIsRecordedAndReported() {
    final Set<ChannelIdentifier> known = new HashSet<>();

    final var update = expectUpdate(ClientChannelUpdates.apply(
        commonRegister("play", "fabric:a", "fabric:b"), known, CLIENT, null));

    assertEquals(ClientChannelUpdates.Kind.REGISTER, update.kind());
    assertEquals(List.of(FABRIC_A, FABRIC_B), update.channels());
    assertEquals(Set.of(FABRIC_A, FABRIC_B), known);
  }

  @Test
  void commonRegisterAndPlainRegisterShareTheSameKnownSet() {
    final Set<ChannelIdentifier> known = new HashSet<>();
    ClientChannelUpdates.apply(commonRegister("play", "fabric:a"), known, CLIENT, null);

    ClientChannelUpdates.apply(
        nulJoined("minecraft:register", "fabric:a", "fabric:b"), known, CLIENT, null);

    assertEquals(Set.of(FABRIC_A, FABRIC_B), known);
  }

  @Test
  void commonRegisterOfTheConfigurationPhaseIsReportedButNotStored() {
    final Set<ChannelIdentifier> known = new HashSet<>();

    final var update = expectUpdate(ClientChannelUpdates.apply(
        commonRegister("configuration", "fabric:a"), known, CLIENT, null));

    assertEquals(List.of(FABRIC_A), update.channels());
    assertTrue(known.isEmpty(), "the stored set is replayed to the backend as play channels");
  }

  @Test
  void commonRegisterOverTheChannelCapIsRejected() {
    final int tooMany = ConnectedPlayer.MAX_CLIENTSIDE_PLUGIN_CHANNELS + 1;
    final String[] ids = new String[tooMany];
    for (int i = 0; i < tooMany; i++) {
      ids[i] = "fabric:c" + i;
    }
    final Set<ChannelIdentifier> known = new HashSet<>();

    assertTrue(ClientChannelUpdates.apply(commonRegister("play", ids), known, CLIENT, null)
        .isEmpty());
    assertTrue(known.isEmpty());
  }

  @Test
  void commonRegisterDoesNotConsumeThePayloadSoItCanStillBeRelayed() {
    final PluginMessagePacket packet = commonRegister("play", "fabric:a");
    final int readerIndex = packet.content().readerIndex();

    ClientChannelUpdates.apply(packet, new HashSet<>(), CLIENT, null);

    assertEquals(readerIndex, packet.content().readerIndex());
  }

  @Test
  void undecodableCommonRegisterIsIgnoredWithoutError() {
    final Set<ChannelIdentifier> known = new HashSet<>();

    final ByteBuf truncated = commonRegisterPayload(1, "play", "fabric:a");
    final PluginMessagePacket cut = new PluginMessagePacket("c:register",
        truncated.slice(0, truncated.readableBytes() - 3));
    final PluginMessagePacket futureVersion = new PluginMessagePacket("c:register",
        commonRegisterPayload(2, "play", "fabric:a"));
    final PluginMessagePacket badIdentifier = commonRegister("play", "NOT VALID");
    final PluginMessagePacket empty = new PluginMessagePacket("c:register", Unpooled.EMPTY_BUFFER);

    for (final PluginMessagePacket packet : List.of(cut, futureVersion, badIdentifier, empty)) {
      assertTrue(ClientChannelUpdates.apply(packet, known, CLIENT, null).isEmpty());
    }
    assertTrue(known.isEmpty());
  }

  @Test
  void commonRegisterIsIgnoredForClientsOlderThanConfigurationPhase() {
    final Set<ChannelIdentifier> known = new HashSet<>();

    assertTrue(ClientChannelUpdates.apply(commonRegister("play", "fabric:a"), known,
        ProtocolVersion.MINECRAFT_1_20, null).isEmpty());
    assertTrue(known.isEmpty());
  }

  @Test
  void otherPluginMessagesAreNotChannelAnnouncements() {
    final Set<ChannelIdentifier> known = new HashSet<>();

    assertTrue(ClientChannelUpdates.apply(nulJoined("minecraft:brand", "vanilla"), known, CLIENT,
        null).isEmpty());
    assertTrue(ClientChannelUpdates.apply(nulJoined("fabric:a", "x"), known, CLIENT, null)
        .isEmpty());
  }

  @Test
  void malformedPlainRegisterStillFails() {
    assertThrows(RuntimeException.class, () -> ClientChannelUpdates.apply(
        nulJoined("minecraft:register", "NOT VALID"), new HashSet<>(), CLIENT, null));
  }

  private static VelocityServer serverWith(final VelocityEventManager events) {
    final VelocityServer server = mock(VelocityServer.class);
    when(server.getEventManager()).thenReturn(events);
    return server;
  }

  private static ConnectedPlayer playerWith(final Set<ChannelIdentifier> known) {
    final ConnectedPlayer player = mock(ConnectedPlayer.class);
    when(player.getClientsideChannels()).thenReturn(known);
    when(player.getProtocolVersion()).thenReturn(CLIENT);
    return player;
  }

  @Test
  void handleFiresTheRegisterEventWithoutWaiting() {
    final VelocityEventManager events = mock(VelocityEventManager.class);
    final Set<ChannelIdentifier> known = new HashSet<>();
    final ConnectedPlayer player = playerWith(known);

    ClientChannelUpdates.handle(serverWith(events), player, commonRegister("play", "fabric:a"));

    final ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
    verify(events).fireAndForget(event.capture());
    final PlayerChannelRegisterEvent fired = (PlayerChannelRegisterEvent) event.getValue();
    assertEquals(List.of(FABRIC_A), fired.getChannels());
    assertEquals(Set.of(FABRIC_A), known);
  }

  @Test
  void handleFiresAgainForAnAlreadyKnownChannelLikeUpstream() {
    final VelocityEventManager events = mock(VelocityEventManager.class);
    final ConnectedPlayer player = playerWith(new HashSet<>(Set.of(FABRIC_A)));

    ClientChannelUpdates.handle(serverWith(events), player,
        nulJoined("minecraft:register", "fabric:a"));

    final ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
    verify(events).fireAndForget(event.capture());
    assertEquals(List.of(FABRIC_A),
        ((PlayerChannelRegisterEvent) event.getValue()).getChannels());
  }

  @Test
  void strictHandleStillThrowsOnAnInvalidRegister() {
    final VelocityEventManager events = mock(VelocityEventManager.class);
    final ConnectedPlayer player = playerWith(new HashSet<>());

    assertThrows(RuntimeException.class, () -> ClientChannelUpdates.handle(serverWith(events),
        player, nulJoined("minecraft:register", "NOT VALID")));
    verify(events, never()).fireAndForget(any());
  }

  @Test
  void lenientHandleSwallowsAnInvalidRegister() {
    final VelocityEventManager events = mock(VelocityEventManager.class);
    final ConnectedPlayer player = playerWith(new HashSet<>());

    ClientChannelUpdates.handleLenient(serverWith(events), player,
        nulJoined("minecraft:register", "NOT VALID"));

    verify(events, never()).fireAndForget(any());
  }

  @Test
  void configurationHandlerRelaysAnInvalidRegisterInsteadOfDroppingTheConnection() {
    final VelocityEventManager events = mock(VelocityEventManager.class);
    final VelocityServer server = serverWith(events);
    when(server.getChannelRegistrar()).thenReturn(mock(VelocityChannelRegistrar.class));
    final ConnectedPlayer player = playerWith(new HashSet<>());
    final VelocityServerConnection backend = mock(VelocityServerConnection.class);
    final MinecraftConnection backendConnection = mock(MinecraftConnection.class);
    when(backend.ensureConnected()).thenReturn(backendConnection);
    when(player.getConnectionInFlight()).thenReturn(backend);
    final PluginMessagePacket packet = nulJoined("minecraft:register", "NOT VALID");

    assertTrue(new ClientConfigSessionHandler(server, player).handle(packet));

    verify(backendConnection).write(packet);
    verify(events, never()).fireAndForget(any());
  }

  @Test
  void configurationHandlerRelaysARegisterThatOverflowsTheChannelCap() {
    final VelocityEventManager events = mock(VelocityEventManager.class);
    final VelocityServer server = serverWith(events);
    when(server.getChannelRegistrar()).thenReturn(mock(VelocityChannelRegistrar.class));
    final Set<ChannelIdentifier> full =
        CappedSet.create(ConnectedPlayer.MAX_CLIENTSIDE_PLUGIN_CHANNELS);
    for (int i = 0; i < ConnectedPlayer.MAX_CLIENTSIDE_PLUGIN_CHANNELS; i++) {
      full.add(MinecraftChannelIdentifier.from("fabric:c" + i));
    }
    final ConnectedPlayer player = playerWith(full);
    final VelocityServerConnection backend = mock(VelocityServerConnection.class);
    final MinecraftConnection backendConnection = mock(MinecraftConnection.class);
    when(backend.ensureConnected()).thenReturn(backendConnection);
    when(player.getConnectionInFlight()).thenReturn(backend);
    final PluginMessagePacket packet = nulJoined("minecraft:register", "fabric:one-more");

    assertTrue(new ClientConfigSessionHandler(server, player).handle(packet));

    verify(backendConnection).write(packet);
    assertEquals(ConnectedPlayer.MAX_CLIENTSIDE_PLUGIN_CHANNELS, full.size());
  }

  private static ClientPlaySessionHandler playHandlerWithBackend(
      final VelocityServer server, final ConnectedPlayer player,
      final MinecraftConnection backendConnection) {
    final VelocityServerConnection backend = mock(VelocityServerConnection.class);
    when(backend.getConnection()).thenReturn(backendConnection);
    when(backendConnection.getState()).thenReturn(StateRegistry.PLAY);
    when(player.getConnectedServer()).thenReturn(backend);
    return new ClientPlaySessionHandler(server, player);
  }

  @Test
  void playHandlerReportsEveryChannelOfARegisterAgainAndRelaysIt() {
    final VelocityEventManager events = mock(VelocityEventManager.class);
    final Set<ChannelIdentifier> known = new HashSet<>(Set.of(FABRIC_A));
    final ConnectedPlayer player = playerWith(known);
    final MinecraftConnection backendConnection = mock(MinecraftConnection.class);
    final ClientPlaySessionHandler handler =
        playHandlerWithBackend(serverWith(events), player, backendConnection);
    final PluginMessagePacket packet = nulJoined("minecraft:register", "fabric:a", "fabric:b");

    assertTrue(handler.handle(packet));

    final ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
    verify(events).fireAndForget(event.capture());
    assertEquals(List.of(FABRIC_A, FABRIC_B),
        ((PlayerChannelRegisterEvent) event.getValue()).getChannels());
    verify(backendConnection).write(packet);
  }

  @Test
  void playHandlerStillHandlesUnregisterAndRelaysIt() {
    final VelocityEventManager events = mock(VelocityEventManager.class);
    final Set<ChannelIdentifier> known = new HashSet<>(Set.of(FABRIC_A));
    final ConnectedPlayer player = playerWith(known);
    final MinecraftConnection backendConnection = mock(MinecraftConnection.class);
    final ClientPlaySessionHandler handler =
        playHandlerWithBackend(serverWith(events), player, backendConnection);
    final PluginMessagePacket packet = nulJoined("minecraft:unregister", "fabric:a");

    assertTrue(handler.handle(packet));

    verify(events).fireAndForget(any(PlayerChannelUnregisterEvent.class));
    assertTrue(known.isEmpty());
    verify(backendConnection).write(packet);
  }

  @Test
  void playHandlerTracksACommonRegisterSentInGame() {
    final VelocityEventManager events = mock(VelocityEventManager.class);
    final Set<ChannelIdentifier> known = new HashSet<>();
    final ConnectedPlayer player = playerWith(known);
    final ClientPlaySessionHandler handler =
        new ClientPlaySessionHandler(serverWith(events), player);

    handler.handle(commonRegister("play", "fabric:a"));

    assertEquals(Set.of(FABRIC_A), known);
    verify(events).fireAndForget(any(PlayerChannelRegisterEvent.class));
  }

  @Test
  void playHandlerStillRejectsAnInvalidRegister() {
    final VelocityEventManager events = mock(VelocityEventManager.class);
    final ConnectedPlayer player = playerWith(new HashSet<>());
    final MinecraftConnection backendConnection = mock(MinecraftConnection.class);
    final ClientPlaySessionHandler handler =
        playHandlerWithBackend(serverWith(events), player, backendConnection);

    assertThrows(RuntimeException.class,
        () -> handler.handle(nulJoined("minecraft:register", "NOT VALID")));
  }

  @Test
  void handleFiresTheUnregisterEvent() {
    final VelocityEventManager events = mock(VelocityEventManager.class);
    final ConnectedPlayer player = playerWith(new HashSet<>(Set.of(FABRIC_A)));

    ClientChannelUpdates.handle(serverWith(events), player,
        nulJoined("minecraft:unregister", "fabric:a"));

    final ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
    verify(events).fireAndForget(event.capture());
    assertEquals(List.of(FABRIC_A),
        ((PlayerChannelUnregisterEvent) event.getValue()).getChannels());
  }

  @Test
  void configurationHandlerTracksAndStillRelaysRegisterToTheBackend() {
    final VelocityEventManager events = mock(VelocityEventManager.class);
    final VelocityServer server = serverWith(events);
    when(server.getChannelRegistrar()).thenReturn(mock(VelocityChannelRegistrar.class));
    final Set<ChannelIdentifier> known = new HashSet<>();
    final ConnectedPlayer player = playerWith(known);
    final VelocityServerConnection backend = mock(VelocityServerConnection.class);
    final MinecraftConnection backendConnection = mock(MinecraftConnection.class);
    when(backend.ensureConnected()).thenReturn(backendConnection);
    when(player.getConnectionInFlight()).thenReturn(backend);
    final PluginMessagePacket packet = nulJoined("minecraft:register", "fabric:a");

    final boolean handled = new ClientConfigSessionHandler(server, player).handle(packet);

    assertTrue(handled);
    assertEquals(Set.of(FABRIC_A), known);
    verify(events).fireAndForget(any(PlayerChannelRegisterEvent.class));
    verify(backendConnection).write(packet);
  }

  @Test
  void configurationHandlerTracksEvenBeforeAnyBackendConnectionExists() {
    final VelocityEventManager events = mock(VelocityEventManager.class);
    final Set<ChannelIdentifier> known = new HashSet<>();
    final ConnectedPlayer player = playerWith(known);
    when(player.getConnectionInFlight()).thenReturn(null);

    final boolean handled = new ClientConfigSessionHandler(serverWith(events), player)
        .handle(commonRegister("play", "fabric:a"));

    assertTrue(handled);
    assertEquals(Set.of(FABRIC_A), known);
    verify(events).fireAndForget(any(PlayerChannelRegisterEvent.class));
  }
}
