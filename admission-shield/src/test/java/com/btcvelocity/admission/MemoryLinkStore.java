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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import org.jspecify.annotations.Nullable;

/** {@link JdbcLinkStore}'s semantics in memory, for the scenarios. */
class MemoryLinkStore implements LinkStore {

  /** One stored row of the sightings table. */
  record Row(UUID account, byte[] ip, byte[] net, Instant first, Instant last) {
  }

  final List<Row> rows = new ArrayList<>();
  final Map<UUID, String> households = new HashMap<>();
  final Map<UUID, Instant> declaredAt = new HashMap<>();

  @Override
  public synchronized LinkFacts factsFor(final Sighting entering, final Instant addressSince,
      final Instant networkSince) throws SQLException {
    final Set<UUID> household = new HashSet<>();
    final String own = this.households.get(entering.account());
    this.households.forEach((account, id) -> {
      if (id.equals(own) && !account.equals(entering.account())) {
        household.add(account);
      }
    });
    return new LinkFacts(
        seen((row, since) -> Arrays.equals(row.ip(), entering.ipDigest()), addressSince),
        seen((row, since) -> Arrays.equals(row.net(), entering.netDigest()), networkSince),
        household,
        this.rows.stream().filter(row -> row.account().equals(entering.account()))
            .map(Row::first).min(Instant::compareTo).orElse(null));
  }

  private Map<UUID, Instant> seen(final BiPredicate<Row, Instant> matches, final Instant since) {
    final Map<UUID, Instant> seen = new HashMap<>();
    for (Row row : this.rows) {
      if (matches.test(row, since) && !row.last().isBefore(since)) {
        seen.merge(row.account(), row.last(), (a, b) -> a.isAfter(b) ? a : b);
      }
    }
    return seen;
  }

  @Override
  public synchronized void record(final Sighting sighting) throws SQLException {
    for (int i = 0; i < this.rows.size(); i++) {
      final Row row = this.rows.get(i);
      if (row.account().equals(sighting.account()) && Arrays.equals(row.ip(), sighting.ipDigest())) {
        this.rows.set(i, new Row(row.account(), row.ip(), sighting.netDigest(), row.first(),
            row.last().isAfter(sighting.at()) ? row.last() : sighting.at()));
        return;
      }
    }
    this.rows.add(new Row(sighting.account(), sighting.ipDigest(), sighting.netDigest(),
        sighting.at(), sighting.at()));
  }

  @Override
  public synchronized int forget(final UUID account) {
    final int before = this.rows.size();
    this.rows.removeIf(row -> row.account().equals(account));
    return before - this.rows.size() + (this.households.remove(account) == null ? 0 : 1);
  }

  @Override
  public synchronized int purgeOlderThan(final Instant cutoff) throws SQLException {
    final int before = this.rows.size() + this.households.size();
    this.rows.removeIf(row -> row.last().isBefore(cutoff));
    final Set<String> seen = new HashSet<>();
    this.rows.forEach(row -> {
      if (this.households.containsKey(row.account())) {
        seen.add(this.households.get(row.account()));
      }
    });
    this.households.entrySet().removeIf(member -> !seen.contains(member.getValue())
        && this.declaredAt.get(member.getKey()).isBefore(cutoff));
    return before - this.rows.size() - this.households.size();
  }

  @Override
  public synchronized boolean declareHousehold(final UUID first, final UUID second,
      final String declaredBy, final @Nullable String reason, final Instant at) {
    final String ofFirst = this.households.get(first);
    final String ofSecond = this.households.get(second);
    if (ofFirst != null && ofSecond != null) {
      return ofFirst.equals(ofSecond);
    }
    final String id = ofFirst != null ? ofFirst
        : ofSecond != null ? ofSecond : UUID.randomUUID().toString();
    this.households.put(first, id);
    this.households.put(second, id);
    if (ofFirst == null) {
      this.declaredAt.put(first, at);
    }
    if (ofSecond == null) {
      this.declaredAt.put(second, at);
    }
    return true;
  }

  @Override
  public synchronized boolean leaveHousehold(final UUID account) {
    return this.households.remove(account) != null;
  }

  @Override
  public synchronized Map<UUID, Instant> sharingAddressWith(final UUID account,
      final Instant since) {
    final Map<UUID, Instant> others = new HashMap<>();
    for (Row own : this.rows) {
      if (!own.account().equals(account)) {
        continue;
      }
      for (Row other : this.rows) {
        if (!other.account().equals(account) && Arrays.equals(other.ip(), own.ip())
            && !other.last().isBefore(since)) {
          others.merge(other.account(), other.last(), (a, b) -> a.isAfter(b) ? a : b);
        }
      }
    }
    return others;
  }
}
