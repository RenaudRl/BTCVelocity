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

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers, per player and for the length of their proxy session, every server this router has
 * already sent them to.
 *
 * <p>This is the loop guard, and it is deliberately a memory rather than a timer or a counter: a
 * backend that kicks on sight can never be offered twice, so a redirect chain is bounded by the
 * number of destinations and ends in the proxy's own "no available servers" rather than in a
 * cycle. The cost is that a player kicked once is not sent back to that backend during the same
 * session — which is the behaviour we want anyway.
 *
 * <p>Entries are dropped when the player leaves the proxy; nothing else clears them.
 *
 * <p>Thread-safe: Velocity fires these events on connection event loops.
 */
public final class ArrivalAttempts {

  private final Map<UUID, Set<String>> attempted = new ConcurrentHashMap<>();

  /**
   * Records that a player was sent to a server.
   *
   * @param player the player's unique id
   * @param serverName the server they were sent to
   */
  public void record(UUID player, String serverName) {
    this.attempted.computeIfAbsent(player, ignored -> ConcurrentHashMap.newKeySet()).add(serverName);
  }

  /**
   * What a player has already been sent to.
   *
   * @param player the player's unique id
   * @return the server names, empty when the player is new
   */
  public Set<String> of(UUID player) {
    return this.attempted.getOrDefault(player, Set.of());
  }

  /**
   * Forgets a player entirely. Called when they leave, so the map never outgrows the proxy.
   *
   * @param player the player's unique id
   */
  public void forget(UUID player) {
    this.attempted.remove(player);
  }

  /**
   * How many players are currently tracked. Exists for the tests and for an operator asking
   * whether this map leaks.
   *
   * @return the number of tracked players
   */
  public int trackedPlayers() {
    return this.attempted.size();
  }
}
