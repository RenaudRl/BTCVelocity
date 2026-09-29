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

/** Where the real address of a Bedrock player can be read, if anywhere. */
@FunctionalInterface
public interface BedrockAddressSource {

  /** A source that knows nothing: every Bedrock player's address stays unknown. */
  BedrockAddressSource NONE = uuid -> Optional.empty();

  /**
   * The address a Bedrock player really connected from.
   *
   * @param uuid the player's UUID as the proxy knows it
   * @return the address, or empty when it cannot be read
   */
  Optional<InetAddress> realAddressOf(UUID uuid);
}
