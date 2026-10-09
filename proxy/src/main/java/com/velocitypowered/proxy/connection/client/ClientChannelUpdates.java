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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Tracks the plugin channels a client announces through (un)register plugin messages, shared by
 * the configuration and the play session handlers so both phases update the same state and
 * fire the same events.
 *
 * <p>Two announcement formats exist:
 * <ul>
 *   <li>{@code minecraft:register} / {@code minecraft:unregister}: channel ids joined by NUL;</li>
 *   <li>{@code c:register} (Fabric API networking, 1.20.2+, sent in configuration and possibly
 *       in play): VarInt version, UTF-8 string phase ({@code play} or {@code configuration}),
 *       then a VarInt-prefixed list of identifier strings. There is no {@code c:unregister}.</li>
 * </ul>
 *
 * <p>As upstream does, every register packet fires its event with <em>all</em> the channels the
 * packet carries, even those already known (a client re-sends them on each server switch, and a
 * subscriber may rely on one event per packet): subscribers must be idempotent. The known set
 * ({@code getClientsideChannels()}) is a set, so it never holds a channel twice. Channels of a
 * {@code c:register} whose phase is {@code configuration} are reported but not stored, because
 * the stored set is replayed to the backend as play channels when the player joins.
 */
final class ClientChannelUpdates {

  private static final Logger LOGGER = LogManager.getLogger(ClientChannelUpdates.class);

  /** Channel of the Fabric API common registration packet (version, phase, channels). */
  static final String COMMON_REGISTER_CHANNEL = "c:register";

  /** Phase name of a {@code c:register} that announces the channels used while playing. */
  static final String PLAY_PHASE = "play";

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
   * @param channels every channel the packet carries
   */
  record Update(Kind kind, List<ChannelIdentifier> channels) {
  }

  /** A decoded {@code c:register}. */
  private record CommonRegister(String phase, List<ChannelIdentifier> channels) {
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
   * Same as {@link #handle} but never throws: an announcement the proxy cannot accept is logged
   * at debug level and skipped, so that the caller can still relay the packet. Used where the
   * packet used to be relayed untouched (configuration phase, {@code c:register}).
   *
   * @param server the proxy
   * @param player the client that sent the packet
   * @param packet the plugin message
   */
  static void handleLenient(final VelocityServer server, final ConnectedPlayer player,
                            final PluginMessagePacket packet) {
    try {
      handle(server, player, packet);
    } catch (RuntimeException e) {
      LOGGER.debug("Ignoring channel announcement {} from {}: {}", packet.getChannel(), player,
          e.toString());
    }
  }

  /**
   * Computes and applies the state change announced by a plugin message, without any event.
   *
   * @param packet          the plugin message
   * @param known           the channels already known for the client, updated in place
   * @param protocolVersion the client protocol version
   * @param server          the proxy, forwarded to {@link PluginMessageUtil#getChannels}
   * @return the change to report, empty if the packet is not a channel announcement
   */
  static Optional<Update> apply(final PluginMessagePacket packet,
                                final Collection<ChannelIdentifier> known,
                                final ProtocolVersion protocolVersion,
                                final VelocityServer server) {
    if (PluginMessageUtil.isRegister(packet)) {
      final List<ChannelIdentifier> channels = PluginMessageUtil.getChannels(known.size(), packet,
          protocolVersion, server);
      known.addAll(channels);
      return Optional.of(new Update(Kind.REGISTER, ImmutableList.copyOf(channels)));
    }
    if (PluginMessageUtil.isUnregister(packet)) {
      final List<ChannelIdentifier> channels =
          PluginMessageUtil.getChannels(0, packet, protocolVersion, server);
      known.removeAll(channels);
      return Optional.of(new Update(Kind.UNREGISTER, ImmutableList.copyOf(channels)));
    }
    if (isCommonRegister(packet) && protocolVersion.noLessThan(ProtocolVersion.MINECRAFT_1_20_2)) {
      return decodeCommonRegister(packet.content()).map(decoded -> {
        if (PLAY_PHASE.equals(decoded.phase())) {
          known.addAll(decoded.channels());
        }
        return new Update(Kind.REGISTER, decoded.channels());
      });
    }
    return Optional.empty();
  }

  /**
   * Decodes a {@code c:register} payload without consuming the buffer. An unreadable payload, or
   * a version this proxy does not know, yields nothing instead of an error (logged at debug): the
   * packet is relayed to the backend untouched either way, and an unknown future format must not
   * disconnect the client.
   *
   * @param content the packet content, read through a duplicate
   * @return the phase and channels, empty if the payload is rejected
   */
  private static Optional<CommonRegister> decodeCommonRegister(final ByteBuf content) {
    final ByteBuf buf = content.duplicate();
    try {
      final int version = ProtocolUtils.readVarInt(buf);
      if (version != COMMON_REGISTER_VERSION) {
        LOGGER.debug("Ignoring c:register: unsupported version {}", version);
        return Optional.empty();
      }
      final String phase = ProtocolUtils.readString(buf, COMMON_REGISTER_PHASE_MAX_LENGTH);
      final int count = ProtocolUtils.readVarInt(buf);
      if (count < 0 || count > ConnectedPlayer.MAX_CLIENTSIDE_PLUGIN_CHANNELS) {
        LOGGER.debug("Ignoring c:register: {} channels (maximum {})", count,
            ConnectedPlayer.MAX_CLIENTSIDE_PLUGIN_CHANNELS);
        return Optional.empty();
      }
      final ImmutableList.Builder<ChannelIdentifier> channels =
          ImmutableList.builderWithExpectedSize(count);
      for (int i = 0; i < count; i++) {
        channels.add(MinecraftChannelIdentifier.from(
            ProtocolUtils.readString(buf, COMMON_REGISTER_CHANNEL_MAX_LENGTH)));
      }
      return Optional.of(new CommonRegister(phase, channels.build()));
    } catch (RuntimeException e) {
      // Truncated buffer, bad VarInt, over-long string or invalid identifier.
      LOGGER.debug("Ignoring c:register: undecodable payload ({})", e.toString());
      return Optional.empty();
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
