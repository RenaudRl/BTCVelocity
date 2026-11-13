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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/**
 * The sightings and the households in the Sentinel PostgreSQL database, in two tables of their own
 * ({@code <prefix>admission_sightings}, {@code <prefix>admission_households}). Nothing of
 * Sentinel's own tables is written.
 *
 * <p>Every value is bound; only the prefix, checked against {@link LinkSettings#PREFIX}, is pasted
 * into the statements.
 */
public final class JdbcLinkStore implements LinkStore {

  private static final int REASON_LENGTH = 255;

  private final DataSource database;
  private final String sightings;
  private final String households;

  /**
   * A store over a database.
   *
   * @param database the pool
   * @param prefix the table prefix, already checked against {@link LinkSettings#PREFIX}
   */
  public JdbcLinkStore(final DataSource database, final String prefix) {
    if (!LinkSettings.PREFIX.matcher(prefix).matches()) {
      throw new IllegalArgumentException("table prefix does not match " + LinkSettings.PREFIX);
    }
    this.database = database;
    this.sightings = prefix + "admission_sightings";
    this.households = prefix + "admission_households";
  }

  /**
   * The statements that create the two tables and their indexes; each one can run again.
   *
   * @return the statements, in order
   */
  List<String> schema() {
    return List.of(
        "CREATE TABLE IF NOT EXISTS " + this.sightings + " (uuid VARCHAR(36) NOT NULL,"
            + " ip_digest BYTEA NOT NULL, net_digest BYTEA NOT NULL, first_seen BIGINT NOT NULL,"
            + " last_seen BIGINT NOT NULL, PRIMARY KEY (uuid, ip_digest))",
        "CREATE INDEX IF NOT EXISTS " + this.sightings + "_ip ON " + this.sightings
            + " (ip_digest, last_seen)",
        "CREATE INDEX IF NOT EXISTS " + this.sightings + "_net ON " + this.sightings
            + " (net_digest, last_seen)",
        "CREATE INDEX IF NOT EXISTS " + this.sightings + "_seen ON " + this.sightings
            + " (last_seen)",
        "CREATE TABLE IF NOT EXISTS " + this.households + " (uuid VARCHAR(36) PRIMARY KEY,"
            + " household_id VARCHAR(36) NOT NULL, declared_by VARCHAR(36) NOT NULL,"
            + " declared_at BIGINT NOT NULL, reason VARCHAR(255))",
        "CREATE INDEX IF NOT EXISTS " + this.households + "_id ON " + this.households
            + " (household_id)");
  }

  /**
   * Creates the two tables when they are missing.
   *
   * @throws SQLException if the database refuses (the role must own the two tables)
   */
  public void createTables() throws SQLException {
    try (Connection connection = this.database.getConnection();
        Statement statement = connection.createStatement()) {
      for (String ddl : schema()) {
        statement.execute(ddl);
      }
    }
  }

  @Override
  public LinkFacts factsFor(final Sighting entering, final Instant addressSince,
      final Instant networkSince) throws SQLException {
    try (Connection connection = this.database.getConnection()) {
      final Map<UUID, Instant> onAddress = seen(connection, "SELECT uuid, last_seen FROM "
          + this.sightings + " WHERE ip_digest = ? AND last_seen >= ?"
          + " ORDER BY last_seen DESC LIMIT ?", entering.ipDigest(), addressSince);
      final Map<UUID, Instant> onNetwork = seen(connection, "SELECT uuid, MAX(last_seen) FROM "
          + this.sightings + " WHERE net_digest = ? AND last_seen >= ?"
          + " GROUP BY uuid ORDER BY MAX(last_seen) DESC LIMIT ?", entering.netDigest(),
          networkSince);
      final Set<UUID> household = new HashSet<>();
      try (PreparedStatement query = connection.prepareStatement("SELECT other.uuid FROM "
          + this.households + " other JOIN " + this.households + " own"
          + " ON own.household_id = other.household_id WHERE own.uuid = ? AND other.uuid <> ?"
          + " LIMIT ?")) {
        query.setString(1, entering.account().toString());
        query.setString(2, entering.account().toString());
        query.setInt(3, MAX_ACCOUNTS_PER_LOOKUP);
        try (ResultSet rows = query.executeQuery()) {
          while (rows.next()) {
            household.add(UUID.fromString(rows.getString(1)));
          }
        }
      }
      return new LinkFacts(onAddress, onNetwork, household, firstSeen(connection,
          entering.account()));
    }
  }

  private @Nullable Instant firstSeen(final Connection connection, final UUID account)
      throws SQLException {
    try (PreparedStatement query = connection.prepareStatement("SELECT MIN(first_seen) FROM "
        + this.sightings + " WHERE uuid = ?")) {
      query.setString(1, account.toString());
      try (ResultSet rows = query.executeQuery()) {
        if (!rows.next()) {
          return null;
        }
        final long first = rows.getLong(1);
        return rows.wasNull() ? null : Instant.ofEpochMilli(first);
      }
    }
  }

  private static Map<UUID, Instant> seen(final Connection connection, final String sql,
      final byte[] digest, final Instant since) throws SQLException {
    final Map<UUID, Instant> seen = new LinkedHashMap<>();
    try (PreparedStatement query = connection.prepareStatement(sql)) {
      query.setBytes(1, digest);
      query.setLong(2, since.toEpochMilli());
      query.setInt(3, MAX_ACCOUNTS_PER_LOOKUP);
      try (ResultSet rows = query.executeQuery()) {
        while (rows.next()) {
          seen.put(UUID.fromString(rows.getString(1)), Instant.ofEpochMilli(rows.getLong(2)));
        }
      }
    }
    return seen;
  }

  @Override
  public void record(final Sighting sighting) throws SQLException {
    try (Connection connection = this.database.getConnection();
        PreparedStatement upsert = connection.prepareStatement("INSERT INTO " + this.sightings
            + " (uuid, ip_digest, net_digest, first_seen, last_seen) VALUES (?, ?, ?, ?, ?)"
            + " ON CONFLICT (uuid, ip_digest) DO UPDATE SET net_digest = EXCLUDED.net_digest,"
            + " last_seen = GREATEST(" + this.sightings + ".last_seen, EXCLUDED.last_seen)")) {
      final long at = sighting.at().toEpochMilli();
      upsert.setString(1, sighting.account().toString());
      upsert.setBytes(2, sighting.ipDigest());
      upsert.setBytes(3, sighting.netDigest());
      upsert.setLong(4, at);
      upsert.setLong(5, at);
      upsert.executeUpdate();
    }
  }

  @Override
  public int forget(final UUID account) throws SQLException {
    try (Connection connection = this.database.getConnection()) {
      connection.setAutoCommit(false);
      try {
        final int removed = delete(connection, this.sightings, account)
            + delete(connection, this.households, account);
        connection.commit();
        return removed;
      } catch (SQLException | RuntimeException failed) {
        connection.rollback();
        throw failed;
      } finally {
        connection.setAutoCommit(true);
      }
    }
  }

  private static int delete(final Connection connection, final String table, final UUID account)
      throws SQLException {
    try (PreparedStatement delete = connection.prepareStatement("DELETE FROM " + table
        + " WHERE uuid = ?")) {
      delete.setString(1, account.toString());
      return delete.executeUpdate();
    }
  }

  @Override
  public int purgeOlderThan(final Instant cutoff) throws SQLException {
    try (Connection connection = this.database.getConnection();
        PreparedStatement delete = connection.prepareStatement("DELETE FROM " + this.sightings
            + " WHERE last_seen < ?")) {
      delete.setLong(1, cutoff.toEpochMilli());
      return delete.executeUpdate();
    }
  }

  @Override
  public boolean declareHousehold(final UUID first, final UUID second, final String declaredBy,
      final @Nullable String reason, final Instant at) throws SQLException {
    if (first.equals(second)) {
      throw new IllegalArgumentException("a household needs two different accounts");
    }
    try (Connection connection = this.database.getConnection()) {
      connection.setAutoCommit(false);
      try {
        final boolean declared = declareIn(connection, first, second, declaredBy, reason, at);
        connection.commit();
        return declared;
      } catch (SQLException | RuntimeException failed) {
        connection.rollback();
        throw failed;
      } finally {
        connection.setAutoCommit(true);
      }
    }
  }

  private boolean declareIn(final Connection connection, final UUID first, final UUID second,
      final String declaredBy, final @Nullable String reason, final Instant at)
      throws SQLException {
    final Map<UUID, String> current = new HashMap<>();
    try (PreparedStatement query = connection.prepareStatement("SELECT uuid, household_id FROM "
        + this.households + " WHERE uuid IN (?, ?) FOR UPDATE")) {
      query.setString(1, first.toString());
      query.setString(2, second.toString());
      try (ResultSet rows = query.executeQuery()) {
        while (rows.next()) {
          current.put(UUID.fromString(rows.getString(1)), rows.getString(2));
        }
      }
    }
    final String ofFirst = current.get(first);
    final String ofSecond = current.get(second);
    if (ofFirst != null && ofSecond != null) {
      return ofFirst.equals(ofSecond);
    }
    final String household = ofFirst != null ? ofFirst
        : ofSecond != null ? ofSecond : UUID.randomUUID().toString();
    try (PreparedStatement insert = connection.prepareStatement("INSERT INTO " + this.households
        + " (uuid, household_id, declared_by, declared_at, reason) VALUES (?, ?, ?, ?, ?)")) {
      for (UUID account : List.of(first, second)) {
        if (current.containsKey(account)) {
          continue;
        }
        insert.setString(1, account.toString());
        insert.setString(2, household);
        insert.setString(3, declaredBy);
        insert.setLong(4, at.toEpochMilli());
        insert.setString(5, reason == null || reason.length() <= REASON_LENGTH ? reason
            : reason.substring(0, REASON_LENGTH));
        insert.executeUpdate();
      }
    }
    return true;
  }

  @Override
  public boolean leaveHousehold(final UUID account) throws SQLException {
    try (Connection connection = this.database.getConnection()) {
      return delete(connection, this.households, account) > 0;
    }
  }

  @Override
  public Map<UUID, Instant> sharingAddressWith(final UUID account, final Instant since)
      throws SQLException {
    final Map<UUID, Instant> others = new LinkedHashMap<>();
    try (Connection connection = this.database.getConnection();
        PreparedStatement query = connection.prepareStatement("SELECT other.uuid,"
            + " MAX(other.last_seen) FROM " + this.sightings + " own JOIN " + this.sightings
            + " other ON other.ip_digest = own.ip_digest WHERE own.uuid = ? AND other.uuid <> ?"
            + " AND other.last_seen >= ? GROUP BY other.uuid ORDER BY MAX(other.last_seen) DESC"
            + " LIMIT ?")) {
      query.setString(1, account.toString());
      query.setString(2, account.toString());
      query.setLong(3, since.toEpochMilli());
      query.setInt(4, MAX_ACCOUNTS_PER_LOOKUP);
      try (ResultSet rows = query.executeQuery()) {
        while (rows.next()) {
          others.put(UUID.fromString(rows.getString(1)), Instant.ofEpochMilli(rows.getLong(2)));
        }
      }
    }
    return others;
  }
}
