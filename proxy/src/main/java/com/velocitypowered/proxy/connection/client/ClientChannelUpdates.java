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

import com.google.common.collect.ImmutableList;
import com.velocitypowered.api.event.player.PlayerChannelRegisterEvent;
import com.velocitypowered.api.event.player.PlayerChannelUnregisterEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.packet.PluginMessagePacket;
import com.velocitypowered.proxy.protocol.util.PluginMessageUtil;
import io.netty.buffer.ByteBuf;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Tracks the plugin channels a client announces through (un)register plugin messages, shared by
 * the configuration and the play session handlers so both phases update the same state and
 * fire the same events.
 *
 * <p>Two announcement formats exist:
 * <ul>
 *   <li>{@code minecraft:register} / {@code minecraft:unregister}: channel ids joined by NUL;</li>
 *   <li>{@code c:register} (Fabric API networking, 1.20.2+, sent during configuration):
 *       VarInt version, UTF-8 string phase ({@code play} or {@code configuration}), then a
 *       VarInt-prefixed list of identifier strings. There is no {@code c:unregister}.</li>
 * </ul>
 *
 * <p>A channel the client already announced is never reported twice: the event of a register
 * only carries the channels that were not already known.
 */
final class ClientChannelUpdates {

  /** Channel of the Fabric API common registration packet (version, phase, channels). */
  static final String COMMON_REGISTER_CHANNEL = "c:register";

  /** Highest {@code c:register} version this decoder understands. */
  private static final int COMMON_REGISTER_VERSION = 1;

  /** Longest phase name accepted in a {@code c:register}; real values are 4 and 13 chars. */
  private static final int COMMON_REGISTER_PHASE_MAX_LENGTH = 32;

  /** Longest identifier string accepted in a {@code c:register}. */
  private static final int COMMON_REGISTER_CHANNEL_MAX_LENGTH = 256;

  private ClientChannelUpdates() {
    throw new AssertionError();
  }

  /** The kind of change a client announced. */
  enum Kind {
    REGISTER,
    UNREGISTER
  }

  /**
   * A change of the channels registered by a client.
   *
   * @param kind     whether channels were registered or unregistered
   * @param channels the channels to report (for a register, only the newly known ones)
   */
  record Update(Kind kind, List<ChannelIdentifier> channels) {
  }

  /**
   * Whether this packet is a {@code c:register} common registration packet.
   *
   * @param packet the plugin message
   * @return true for {@code c:register}
   */
  static boolean isCommonRegister(final PluginMessagePacket packet) {
    return COMMON_REGISTER_CHANNEL.equals(packet.getChannel());
  }

  /**
   * Applies a (un)register plugin message to the known channels of a client, then fires the
   * matching event without waiting for its listeners.
   *
   * @param server the proxy
   * @param player the client that sent the packet
   * @param packet the plugin message; ignored when it is not a channel announcement
   * @throws IllegalArgumentException if a {@code minecraft:(un)register} payload is malformed
   * @throws IllegalStateException    if the client exceeds the channel cap
   */
  static void handle(final VelocityServer server, final ConnectedPlayer player,
                     final PluginMessagePacket packet) {
    apply(packet, player.getClientsideChannels(), player.getProtocolVersion(), server)
        .ifPresent(update -> fire(server, player, update));
  }

  /**
   * Computes and applies the state change announced by a plugin message, without any event.
   *
   * @param packet          the plugin message
   * @param known           the channels already known for the client, updated in place
   * @param protocolVersion the client protocol version
   * @param server          the proxy, forwarded to {@link PluginMessageUtil#getChannels}
   * @return the change to report, empty if the packet is not a channel announcement or does
   *         not change anything
   */
  static Optional<Update> apply(final PluginMessagePacket packet,
                                final Collection<ChannelIdentifier> known,
                                final ProtocolVersion protocolVersion,
                                final VelocityServer server) {
    if (PluginMessageUtil.isRegister(packet)) {
      return register(known, PluginMessageUtil.getChannels(known.size(), packet,
          protocolVersion, server));
    }
    if (PluginMessageUtil.isUnregister(packet)) {
      final List<ChannelIdentifier> channels =
          PluginMessageUtil.getChannels(0, packet, protocolVersion, server);
      known.removeAll(channels);
      return Optional.of(new Update(Kind.UNREGISTER, ImmutableList.copyOf(channels)));
    }
    if (isCommonRegister(packet) && protocolVersion.noLessThan(ProtocolVersion.MINECRAFT_1_20_2)) {
      return register(known, decodeCommonRegister(packet.content()));
    }
    return Optional.empty();
  }

  private static Optional<Update> register(final Collection<ChannelIdentifier> known,
                                           final List<ChannelIdentifier> announced) {
    final ImmutableList.Builder<ChannelIdentifier> added = ImmutableList.builder();
    for (final ChannelIdentifier channel : announced) {
      if (known.add(channel)) {
        added.add(channel);
      }
    }
    final List<ChannelIdentifier> newlyKnown = added.build();
    return newlyKnown.isEmpty() ? Optional.empty() : Optional.of(new Update(Kind.REGISTER, newlyKnown));
  }

  /**
   * Decodes the channels of a {@code c:register} payload without consuming the buffer.
   * An unreadable payload, or a version this proxy does not know, yields no channel instead of
   * an error: the packet is relayed to the backend untouched either way, and an unknown future
   * format must not disconnect the client.
   *
   * @param content the packet content, read through a duplicate
   * @return the announced channels, empty if the payload cannot be decoded
   */
  static List<ChannelIdentifier> decodeCommonRegister(final ByteBuf content) {
    final ByteBuf buf = content.duplicate();
    try {
      if (ProtocolUtils.readVarInt(buf) != COMMON_REGISTER_VERSION) {
        return List.of();
      }
      ProtocolUtils.readString(buf, COMMON_REGISTER_PHASE_MAX_LENGTH);
      final int count = ProtocolUtils.readVarInt(buf);
      if (count < 0 || count > ConnectedPlayer.MAX_CLIENTSIDE_PLUGIN_CHANNELS) {
        return List.of();
      }
      final ImmutableList.Builder<ChannelIdentifier> channels =
          ImmutableList.builderWithExpectedSize(count);
      for (int i = 0; i < count; i++) {
        channels.add(MinecraftChannelIdentifier.from(
            ProtocolUtils.readString(buf, COMMON_REGISTER_CHANNEL_MAX_LENGTH)));
      }
      return channels.build();
    } catch (RuntimeException e) {
      // Truncated buffer, bad VarInt, over-long string or invalid identifier.
      return List.of();
    }
  }

  private static void fire(final VelocityServer server, final ConnectedPlayer player,
                           final Update update) {
    if (update.kind() == Kind.REGISTER) {
      server.getEventManager().fireAndForget(
          new PlayerChannelRegisterEvent(player, update.channels()));
    } else {
      server.getEventManager().fireAndForget(
          new PlayerChannelUnregisterEvent(player, update.channels()));
    }
  }
}
