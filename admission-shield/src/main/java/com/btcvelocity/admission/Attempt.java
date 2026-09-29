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
 * @param bedrockReal the real address of a Bedrock player as Floodgate recorded it, when known
 */
public record Attempt(
    String username, @Nullable UUID uuid, InetAddress remote, @Nullable InetAddress bedrockReal) {
}
