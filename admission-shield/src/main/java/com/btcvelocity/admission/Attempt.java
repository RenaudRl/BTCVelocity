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
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What is known about a connection at the moment it is judged.
 *
 * @param username the username; claimed at the pre-login, authenticated at the login
 * @param uuid the account UUID, known from the login on
 * @param remote the address the connection comes from, as the proxy sees it
 * @param floodgatePlayer whether Floodgate knows this player as a Bedrock player (login only)
 * @param bedrockReal the real address of a Bedrock player as Floodgate recorded it, when known
 */
public record Attempt(
    String username,
    @Nullable UUID uuid,
    InetAddress remote,
    boolean floodgatePlayer,
    @Nullable InetAddress bedrockReal) {

  /**
   * A connection at the pre-login: an address and a claimed name, nothing proven.
   *
   * @param username the claimed username
   * @param remote the address of the connection
   * @return the attempt
   */
  public static Attempt claimed(final String username, final InetAddress remote) {
    return new Attempt(username, null, remote, false, null);
  }

  /**
   * The same connection once the account is authenticated.
   *
   * @param authenticatedName the authenticated username
   * @param authenticatedUuid the account UUID
   * @param isFloodgatePlayer whether Floodgate knows the player as Bedrock
   * @param realAddress Floodgate's record of the player's real address, if any
   * @return the attempt
   */
  public Attempt authenticated(final String authenticatedName, final UUID authenticatedUuid,
      final boolean isFloodgatePlayer, final @Nullable InetAddress realAddress) {
    return new Attempt(authenticatedName, authenticatedUuid, this.remote, isFloodgatePlayer,
        realAddress);
  }
}
