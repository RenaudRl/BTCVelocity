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

import com.btcvelocity.api.connection.ClientChannelHook;
import io.netty.util.AttributeKey;

/**
 * Constants used for the pipeline. The names a plugin may rely on come from the API
 * ({@link ClientChannelHook}), so that both cannot drift apart.
 */
public final class Connections {

  /** Name of the listener that accepted a channel; absent on the main listener. */
  public static final AttributeKey<String> LISTENER = AttributeKey.valueOf("btc-listener");

  /**
   * Whether the source filter admitted a channel as a PROXY protocol sender; absent or false means
   * no PROXY decoder. Set once on the boss thread, so the pipeline never re-reads the configuration.
   */
  public static final AttributeKey<Boolean> PROXY_PROTOCOL =
      AttributeKey.valueOf("btc-proxy-protocol");

  public static final String CIPHER_DECODER = "cipher-decoder";

  public static final String CIPHER_ENCODER = "cipher-encoder";

  public static final String COMPRESSION_DECODER = ClientChannelHook.COMPRESSION_DECODER;

  public static final String COMPRESSION_ENCODER = ClientChannelHook.COMPRESSION_ENCODER;

  public static final String FLOW_HANDLER = "flow-handler";

  public static final String FRAME_DECODER = "frame-decoder";

  public static final String FRAME_ENCODER = "frame-encoder";

  public static final String HANDLER = "handler";

  public static final String LEGACY_PING_DECODER = "legacy-ping-decoder";

  public static final String LEGACY_PING_ENCODER = "legacy-ping-encoder";

  public static final String MINECRAFT_DECODER = ClientChannelHook.MINECRAFT_DECODER;

  public static final String MINECRAFT_ENCODER = ClientChannelHook.MINECRAFT_ENCODER;

  public static final String READ_TIMEOUT = "read-timeout";

  public static final String PLAY_PACKET_QUEUE_OUTBOUND = "play-packet-queue-outbound";

  public static final String PLAY_PACKET_QUEUE_INBOUND = "play-packet-queue-inbound";

  private Connections() {
    throw new AssertionError();
  }
}
