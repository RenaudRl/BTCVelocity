/*
 * Copyright (C) 2026 Velocity Contributors
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.btcvelocity.proxy.security.AdmissionStats;
import com.btcvelocity.proxy.security.ConnectionAdmission;
import com.btcvelocity.proxy.security.HandshakeHosts;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.network.limiter.SimpleBytesPerSecondLimiter;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.netty.MinecraftVarintFrameDecoder;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * A client that goes past the packet limiter is told so, counted and logged, instead of getting
 * the generic internal error. The frames go through the real frame decoder and the connection.
 */
class PacketLimitConnectionTest {

  private static final String KICK_KEY = "velocity.error.packet-limit-exceeded";
  private static final int WINDOW_SECONDS = 1;
  private static final int FRAMES = 50;
  private static final byte[] FRAME = {2, 0x10, 0x20};

  private final AdmissionStats stats = new AdmissionStats();

  private PeerChannel channelWith(final ConnectedPlayer player) {
    final VelocityServer server = mock(VelocityServer.class);
    when(server.getConnectionAdmission()).thenReturn(new ConnectionAdmission(
        HandshakeHosts.of(HandshakeHosts.Mode.OFF, List.of(), List.of()), stats));
    final PeerChannel channel = new PeerChannel("203.0.113.9");
    final MinecraftVarintFrameDecoder frames =
        new MinecraftVarintFrameDecoder(ProtocolUtils.Direction.SERVERBOUND);
    frames.setState(StateRegistry.PLAY);
    frames.setPacketLimiter(new SimpleBytesPerSecondLimiter(1, -1, WINDOW_SECONDS));
    final MinecraftConnection connection = new MinecraftConnection(channel, server);
    channel.pipeline().addLast(frames, connection);
    if (player != null) {
      connection.setAssociation(player);
    }
    return channel;
  }

  private static void flood(final PeerChannel channel) {
    for (int i = 0; i < FRAMES && channel.isOpen(); i++) {
      channel.writeInbound(Unpooled.wrappedBuffer(FRAME));
    }
  }

  @Test
  void aPlayerOverTheLimitGetsTheExplicitMessageOnceAndIsCounted() {
    final ConnectedPlayer player = mock(ConnectedPlayer.class);
    when(player.getUsername()).thenReturn("Steve");
    final PeerChannel channel = channelWith(player);

    flood(channel);

    final ArgumentCaptor<Component> reason = ArgumentCaptor.forClass(Component.class);
    verify(player, times(1)).disconnect(reason.capture());
    assertEquals(Component.translatable(KICK_KEY, NamedTextColor.RED), reason.getValue());
    assertEquals(1, stats.count(AdmissionStats.Result.PACKET_LIMIT), "once per connection");
    assertFalse(channel.isOpen(), "the decoder kept failing: the connection is closed outright");
  }

  @Test
  void aConnectionWithoutAPlayerIsClosedAndCounted() {
    final PeerChannel channel = channelWith(null);

    flood(channel);

    assertFalse(channel.isOpen());
    assertEquals(1, stats.count(AdmissionStats.Result.PACKET_LIMIT));
  }

  @Test
  void aClientWithinTheLimitIsLeftAlone() {
    final ConnectedPlayer player = mock(ConnectedPlayer.class);
    final PeerChannel channel = channelWith(player);

    channel.writeInbound(Unpooled.wrappedBuffer(FRAME));

    assertTrue(channel.isOpen());
    verify(player, never()).disconnect(org.mockito.ArgumentMatchers.any(Component.class));
    assertEquals(0, stats.count(AdmissionStats.Result.PACKET_LIMIT));
  }

  @Test
  void theMessageIsTranslatedInEnglishAndFrench() throws IOException {
    final Properties english = bundle("messages.properties");
    final Properties french = bundle("messages_fr_FR.properties");

    assertTrue(english.getProperty(KICK_KEY).contains("too many packets"));
    assertTrue(french.getProperty(KICK_KEY).startsWith("Vous envoyez trop de paquets"));
  }

  private static Properties bundle(final String name) throws IOException {
    final Properties properties = new Properties();
    try (InputStream in = PacketLimitConnectionTest.class.getResourceAsStream(
        "/com/velocitypowered/proxy/l10n/" + name)) {
      properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
    }
    return properties;
  }
}
