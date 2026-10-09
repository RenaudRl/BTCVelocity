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
import java.util.Optional;
import java.util.UUID;

/** Who is a Bedrock player, and where their real address can be read, if anywhere. */
public interface BedrockAddressSource {

  /** A source that knows nothing: nobody is a known Bedrock player. */
  BedrockAddressSource NONE = new BedrockAddressSource() {
    @Override
    public boolean isFloodgatePlayer(final UUID uuid) {
      return false;
    }

    @Override
    public Optional<InetAddress> realAddressOf(final UUID uuid) {
      return Optional.empty();
    }
  };

  /**
   * Whether Floodgate knows this player as a Bedrock player.
   *
   * @param uuid the player's UUID as the proxy knows it
   * @return {@code true} for a player that came through Geyser and Floodgate
   */
  boolean isFloodgatePlayer(UUID uuid);

  /**
   * The address a Bedrock player really connected from.
   *
   * @param uuid the player's UUID as the proxy knows it
   * @return the address, or empty when it cannot be read
   */
  Optional<InetAddress> realAddressOf(UUID uuid);
}
