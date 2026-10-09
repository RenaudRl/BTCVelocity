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

package com.btcvelocity.clientpolicy;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * The live sessions, keyed by account id but owned by a connection.
 *
 * <p>Two things go wrong with a bare map keyed by UUID. An event can arrive after its player has
 * already disconnected, and would then re-create a state nobody removes. And the same account can
 * reconnect before the old connection's disconnect is processed, in which case removing "by UUID"
 * would erase the newer connection's state. Each state therefore remembers the connection it
 * belongs to, and every removal names that connection.
 */
final class SessionRegistry {

  private final Map<UUID, ClientSession> sessions = new ConcurrentHashMap<>();

  /**
   * Finds or creates the state of a connection, unless it is already gone.
   *
   * <p>A state left by an earlier connection of the same account is replaced. Liveness is tested
   * <em>after</em> the state is in the map, then the state is withdrawn if the connection died in
   * between: testing first would leave a window in which a disconnect could run before the insert
   * and the entry would leak.
   *
   * @param id the account id
   * @param connection the connection object (the player), compared by identity
   * @param isActive whether the connection is still alive
   * @return the state, or empty when the connection is already gone
   */
  Optional<ClientSession> open(UUID id, Object connection, BooleanSupplier isActive) {
    ClientSession session = this.sessions.compute(id,
        (key, existing) -> existing != null && existing.isOwnedBy(connection)
            ? existing : new ClientSession(connection));
    if (!isActive.getAsBoolean()) {
      this.sessions.remove(id, session);
      return Optional.empty();
    }
    return Optional.of(session);
  }

  /**
   * Looks a state up without creating it.
   *
   * @param id the account id
   * @param connection the connection object
   * @return the state if it belongs to that connection
   */
  Optional<ClientSession> find(UUID id, Object connection) {
    return Optional.ofNullable(this.sessions.get(id)).filter(s -> s.isOwnedBy(connection));
  }

  /**
   * Forgets a connection's state, and only that connection's.
   *
   * @param id the account id
   * @param connection the connection that ended
   */
  void close(UUID id, Object connection) {
    this.sessions.computeIfPresent(id, (key, existing) ->
        existing.isOwnedBy(connection) ? null : existing);
  }

  /**
   * How many states are held.
   *
   * @return the count
   */
  int size() {
    return this.sessions.size();
  }
}
