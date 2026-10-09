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

package com.btcvelocity.admission;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.UUID;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.player.FloodgatePlayer;
import org.geysermc.floodgate.api.player.PropertyKey;

/**
 * Reads a Bedrock player's real address from Floodgate's {@code SOCKET_ADDRESS} property.
 *
 * <p><strong>Not yet measured (task 13.1).</strong> The Floodgate API exposes no dedicated
 * "real IP" accessor; this reads the property Floodgate documents for the player's socket address.
 * Whether Floodgate fills it, and with the Bedrock client's address rather than Geyser's, on this
 * proxy is exactly what the bench has to establish. Until then every doubtful answer is treated
 * as "unknown", which the engine turns into "admitted, and said so": the property missing, not a
 * socket address, or unresolved never produces a refusal.
 *
 * <p>Lives in its own class so that the plugin can run without Floodgate: this class is only
 * loaded when Floodgate is present.
 */
public final class FloodgateAddressSource implements BedrockAddressSource {

  private final FloodgateApi floodgate;

  /**
   * Wraps a running Floodgate.
   *
   * @param floodgate the Floodgate API
   */
  public FloodgateAddressSource(final FloodgateApi floodgate) {
    this.floodgate = floodgate;
  }

  @Override
  public boolean isFloodgatePlayer(final UUID uuid) {
    try {
      return this.floodgate.isFloodgatePlayer(uuid);
    } catch (RuntimeException | LinkageError unreadable) {
      return false;
    }
  }

  @Override
  public Optional<InetAddress> realAddressOf(final UUID uuid) {
    final FloodgatePlayer player = this.floodgate.getPlayer(uuid);
    if (player == null) {
      return Optional.empty();
    }
    final Object property;
    try {
      property = player.getProperty(PropertyKey.SOCKET_ADDRESS);
    } catch (RuntimeException | LinkageError unreadable) {
      return Optional.empty();
    }
    if (property instanceof InetSocketAddress socket && socket.getAddress() != null) {
      return Optional.of(socket.getAddress());
    }
    return Optional.empty();
  }
}
