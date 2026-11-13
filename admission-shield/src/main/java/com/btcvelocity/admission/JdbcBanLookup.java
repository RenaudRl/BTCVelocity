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

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Reads Sentinel's {@code <prefix>sanctions} table, never writes it.
 *
 * <p>A ban in force is the predicate of Sentinel's own {@code SanctionService}: {@code type} is
 * {@code BAN}, {@code scope} is {@code NETWORK}, {@code network_id} is this network,
 * {@code revoked_at} is null and {@code expires_at} (epoch millis) is null or later than now.
 * {@code created_at} (epoch millis) dates the oldest and the latest of them.
 */
public final class JdbcBanLookup implements BanLookup {

  private final DataSource database;
  private final String sql;
  private final String networkId;

  /**
   * A lookup over the Sentinel database.
   *
   * @param database the pool
   * @param prefix the table prefix, already checked against {@link LinkSettings#PREFIX}
   * @param networkId the network the bans must belong to
   */
  public JdbcBanLookup(final DataSource database, final String prefix, final String networkId) {
    if (!LinkSettings.PREFIX.matcher(prefix).matches()) {
      throw new IllegalArgumentException("table prefix does not match " + LinkSettings.PREFIX);
    }
    this.database = database;
    this.networkId = networkId;
    this.sql = "SELECT player_uuid, MAX(id), MIN(created_at), MAX(created_at) FROM " + prefix
        + "sanctions"
        + " WHERE player_uuid = ANY (?) AND type = 'BAN' AND scope = 'NETWORK'"
        + " AND network_id = ? AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > ?)"
        + " GROUP BY player_uuid";
  }

  @Override
  public Map<UUID, ActiveBan> activeNetworkBans(final Set<UUID> accounts, final Instant now)
      throws SQLException {
    final Map<UUID, ActiveBan> banned = new HashMap<>();
    if (accounts.isEmpty()) {
      return banned;
    }
    try (Connection connection = this.database.getConnection();
        PreparedStatement query = connection.prepareStatement(this.sql)) {
      final Array uuids = connection.createArrayOf("varchar",
          accounts.stream().map(UUID::toString).toArray());
      query.setArray(1, uuids);
      query.setString(2, this.networkId);
      query.setLong(3, now.toEpochMilli());
      try (ResultSet rows = query.executeQuery()) {
        while (rows.next()) {
          banned.put(UUID.fromString(rows.getString(1)),
              new ActiveBan(rows.getLong(2), Instant.ofEpochMilli(rows.getLong(3)),
                  Instant.ofEpochMilli(rows.getLong(4))));
        }
      } finally {
        uuids.free();
      }
    }
    return banned;
  }
}
