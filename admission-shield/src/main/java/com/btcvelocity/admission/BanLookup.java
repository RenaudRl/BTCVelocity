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

import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The network bans in force, read from Sentinel's sanctions, READ ONLY.
 */
public interface BanLookup {

  /**
   * One account's network ban in force.
   *
   * @param id the id of one of its bans in force
   * @param earliest when the oldest of them was pronounced: an account first seen after it is new
   * @param latest when the latest of them was pronounced: the strong window runs from it
   */
  record ActiveBan(long id, Instant earliest, Instant latest) {

    /**
     * A single ban in force.
     *
     * @param id its id
     * @param createdAt when it was pronounced
     */
    public ActiveBan(final long id, final Instant createdAt) {
      this(id, createdAt, createdAt);
    }
  }

  /**
   * Which of these accounts carry a ban of network scope in force now: not revoked, not expired.
   * A ban limited to one server never counts.
   *
   * @param accounts the accounts to ask about (only the linked ones, never the whole table)
   * @param now the time "in force" is judged at
   * @return each banned account with its ban
   * @throws SQLException if the sanctions cannot be read
   */
  Map<UUID, ActiveBan> activeNetworkBans(Set<UUID> accounts, Instant now) throws SQLException;
}
