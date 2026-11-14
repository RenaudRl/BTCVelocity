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
 * The sightings, the households and the known accounts in the Sentinel PostgreSQL database, in
 * three tables of their own ({@code <prefix>admission_sightings}, {@code <prefix>admission_households},
 * {@code <prefix>admission_known}). Nothing of Sentinel's own tables is written. The known accounts
 * hold an account and two times, never an address nor a digest of one.
 *
 * <p>Every value is bound; only the prefix, checked against {@link LinkSettings#PREFIX}, is pasted
 * into the statements.
 */
public final class JdbcLinkStore implements LinkStore {

  private static final int REASON_LENGTH = 255;

  private final DataSource database;
  private final String sightings;
  private final String households;
  private final String known;
  private volatile boolean tablesReady;

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
    this.known = prefix + "admission_known";
  }

  /**
   * The statements that create the three tables and their indexes; each one can run again. Only
   * additions: a database set up by an earlier version just gains the table of known accounts.
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
            + " (household_id)",
        "CREATE TABLE IF NOT EXISTS " + this.known + " (uuid VARCHAR(36) PRIMARY KEY,"
            + " first_seen BIGINT NOT NULL, last_seen BIGINT NOT NULL)",
        "CREATE INDEX IF NOT EXISTS " + this.known + "_seen ON " + this.known + " (last_seen)");
  }

  /**
   * Creates the three tables when they are missing. Every other call does it first until it has
   * succeeded once, so a database absent at start is set up on its first answer.
   *
   * @throws SQLException if the database refuses (the role must own the two tables)
   */
  public void createTables() throws SQLException {
    open().close();
  }

  private synchronized void ensureTables(final Connection connection) throws SQLException {
    if (this.tablesReady) {
      return;
    }
    try (Statement statement = connection.createStatement()) {
      for (String ddl : schema()) {
        statement.execute(ddl);
      }
    } catch (SQLException refused) {
      // A role without CREATE on the schema can still use tables that exist (made by hand).
      final boolean present;
      try {
        present = tablesExist(connection);
      } catch (SQLException unreadable) {
        refused.addSuppressed(unreadable);
        throw refused;
      }
      if (!present) {
        throw refused;
      }
    }
    this.tablesReady = true;
  }

  private boolean tablesExist(final Connection connection) throws SQLException {
    try (PreparedStatement query = connection.prepareStatement("SELECT to_regclass(?),"
        + " to_regclass(?), to_regclass(?)")) {
      query.setString(1, this.sightings);
      query.setString(2, this.households);
      query.setString(3, this.known);
      try (ResultSet rows = query.executeQuery()) {
        return rows.next() && rows.getString(1) != null && rows.getString(2) != null
            && rows.getString(3) != null;
      }
    }
  }

  /**
   * A connection that does not wait for the schema: the erasure and the purge must work whatever
   * the role may create.
   */
  private Connection openWithoutSchema() throws SQLException {
    return this.database.getConnection();
  }

  /** A connection on which the two tables exist. */
  private Connection open() throws SQLException {
    final Connection connection = this.database.getConnection();
    if (!this.tablesReady) {
      try {
        ensureTables(connection);
      } catch (SQLException | RuntimeException failed) {
        connection.close();
        throw failed;
      }
    }
    return connection;
  }

  @Override
  public LinkFacts factsFor(final Sighting entering, final Instant addressSince,
      final Instant networkSince) throws SQLException {
    try (Connection connection = open()) {
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

  /** The earlier of the known account's first time and its oldest sighting, if it has either. */
  private @Nullable Instant firstSeen(final Connection connection, final UUID account)
      throws SQLException {
    try (PreparedStatement query = connection.prepareStatement("SELECT MIN(seen) FROM (SELECT"
        + " first_seen AS seen FROM " + this.known + " WHERE uuid = ? UNION ALL SELECT first_seen"
        + " FROM " + this.sightings + " WHERE uuid = ?) AS firsts")) {
      query.setString(1, account.toString());
      query.setString(2, account.toString());
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
    try (Connection connection = open();
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
      upsertKnown(connection, sighting.account(), sighting.at());
    }
  }

  @Override
  public void recordKnown(final UUID account, final Instant at) throws SQLException {
    try (Connection connection = open()) {
      upsertKnown(connection, account, at);
    }
  }

  /**
   * Notes the account as known. A first row takes the oldest sighting the account already has, so
   * that an account recorded before this table existed keeps the date it was first seen.
   */
  private void upsertKnown(final Connection connection, final UUID account, final Instant at)
      throws SQLException {
    try (PreparedStatement upsert = connection.prepareStatement("INSERT INTO " + this.known
        + " (uuid, first_seen, last_seen) VALUES (?, COALESCE((SELECT MIN(first_seen) FROM "
        + this.sightings + " WHERE uuid = ?), ?), ?) ON CONFLICT (uuid) DO UPDATE SET"
        + " first_seen = LEAST(" + this.known + ".first_seen, EXCLUDED.first_seen),"
        + " last_seen = GREATEST(" + this.known + ".last_seen, EXCLUDED.last_seen)")) {
      upsert.setString(1, account.toString());
      upsert.setString(2, account.toString());
      upsert.setLong(3, at.toEpochMilli());
      upsert.setLong(4, at.toEpochMilli());
      upsert.executeUpdate();
    }
  }

  @Override
  public int forget(final UUID account) throws SQLException {
    try (Connection connection = openWithoutSchema()) {
      connection.setAutoCommit(false);
      try {
        final int removed = delete(connection, this.sightings, account)
            + delete(connection, this.households, account)
            + delete(connection, this.known, account);
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
    try (Connection connection = openWithoutSchema();
        PreparedStatement sightings = connection.prepareStatement("DELETE FROM " + this.sightings
            + " WHERE last_seen < ?");
        // A household declared before the cutoff with no member seen since then is kept no longer.
        PreparedStatement households = connection.prepareStatement("DELETE FROM "
            + this.households + " h WHERE h.declared_at < ? AND NOT EXISTS (SELECT 1 FROM "
            + this.households + " m JOIN " + this.sightings + " s ON s.uuid = m.uuid"
            + " WHERE m.household_id = h.household_id AND s.last_seen >= ?)")) {
      sightings.setLong(1, cutoff.toEpochMilli());
      households.setLong(1, cutoff.toEpochMilli());
      households.setLong(2, cutoff.toEpochMilli());
      return sightings.executeUpdate() + households.executeUpdate();
    }
  }

  @Override
  public int purgeKnownOlderThan(final Instant cutoff) throws SQLException {
    try (Connection connection = openWithoutSchema();
        PreparedStatement delete = connection.prepareStatement("DELETE FROM " + this.known
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
    try (Connection connection = open()) {
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
    try (Connection connection = open()) {
      return delete(connection, this.households, account) > 0;
    }
  }

  @Override
  public Map<UUID, Instant> sharingAddressWith(final UUID account, final Instant since)
      throws SQLException {
    final Map<UUID, Instant> others = new LinkedHashMap<>();
    try (Connection connection = open();
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
