/*
 * Copyright (C) 2018-2026 Velocity Contributors
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

package com.btcvelocity.proxy.permission;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;

/** {@link PermissionTables} over the read-only PostgreSQL pool. */
final class JdbcPermissionTables implements PermissionTables {

  private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

  private final DataSource source;
  private final String tablePrefix;

  JdbcPermissionTables(final DataSource source, final String tablePrefix) {
    this.source = source;
    this.tablePrefix = tablePrefix;
  }

  @Override
  public NativePermissionSnapshot readPlayer(final UUID subject) throws SQLException {
    try (Connection connection = source.getConnection();
         var statement = connection.prepareStatement(
             "SELECT revision, payload FROM " + tablePrefix + "players "
                 + "WHERE player_uuid = ? AND profile_id IS NULL")) {
      statement.setString(1, subject.toString());
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          return null;
        }
        final NativePermissionSnapshot snapshot =
            GSON.fromJson(result.getString("payload"), NativePermissionSnapshot.class);
        snapshot.revision = Math.max(snapshot.revision, result.getLong("revision"));
        return snapshot;
      }
    }
  }

  @Override
  public Map<String, NativePermissionSnapshot.Group> readGroups() throws SQLException {
    final Map<String, NativePermissionSnapshot.Group> groups = new HashMap<>();
    try (Connection connection = source.getConnection();
         var statement = connection.createStatement();
         ResultSet result = statement.executeQuery(
             "SELECT group_name, payload FROM " + tablePrefix + "groups")) {
      while (result.next()) {
        final NativePermissionSnapshot.Group group =
            GSON.fromJson(result.getString("payload"), NativePermissionSnapshot.Group.class);
        if (group != null && group.name != null && !group.name.isBlank()) {
          groups.put(result.getString("group_name").toLowerCase(), group);
        }
      }
    }
    return Map.copyOf(groups);
  }
}
