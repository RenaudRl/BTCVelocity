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

package com.btcvelocity.arrival;

/**
 * A server the proxy could send a player to, reduced to what the policy needs to choose.
 *
 * <p>The count is what <em>this</em> proxy sees on that server. With several proxies in front of
 * the same backends each one balances on its own view, which spreads players without any of them
 * asking the node a thing. Worth knowing before reading a balance as network-wide truth.
 *
 * @param name the Velocity server name
 * @param playerCount how many players this proxy currently has on it
 */
public record ArrivalCandidate(String name, int playerCount) {
}
