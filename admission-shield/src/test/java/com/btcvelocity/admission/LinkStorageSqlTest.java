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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The statements the two JDBC classes send. No PostgreSQL is run here: what is checked is the SQL,
 * the values bound and their order.
 */
class LinkStorageSqlTest {

  private static final UUID BANNED = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
  private static final UUID SISTER = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");
  private static final UUID ENTRANT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000003");
  private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");
  private static final Sighting SEEN = LinkKeyTest.key(LinkKeyTest.TEST_KEY)
      .sighting(ENTRANT, InetAddress.ofLiteral("198.51.100.7"), NOW);

  /** A store whose tables exist already, its set-up statements forgotten. */
  private static JdbcLinkStore ready(final FakeJdbc db) throws SQLException {
    final JdbcLinkStore store = new JdbcLinkStore(db.dataSource(), "btc_");
    store.createTables();
    db.calls.clear();
    return store;
  }

  @Test
  void aDatabaseAbsentAtStartGetsItsTablesOnItsFirstAnswer() throws SQLException {
    final FakeJdbc db = new FakeJdbc();
    db.failingExecutes = 1;
    final JdbcLinkStore store = new JdbcLinkStore(db.dataSource(), "btc_");

    assertThrows(SQLException.class, () -> store.record(SEEN), "down: nothing written");
    store.record(SEEN);
    store.record(SEEN);

    assertEquals(6 + 2, db.calls.size(), "the schema once, then the two sightings");
    assertTrue(db.calls.get(0).sql().startsWith("CREATE TABLE IF NOT EXISTS"),
        db.calls.get(0).sql());
    assertTrue(db.calls.get(6).sql().startsWith("INSERT INTO"), db.calls.get(6).sql());
  }

  @Test
  void aBanInForceIsANetworkBanOfThisNetworkNeitherRevokedNorExpired() throws SQLException {
    final Instant first = NOW.minusSeconds(86_400);
    final Instant pronounced = NOW.minusSeconds(3600);
    final FakeJdbc db = new FakeJdbc().answer(
        new Object[] {BANNED.toString(), 42L, first.toEpochMilli(), pronounced.toEpochMilli()});

    final Map<UUID, BanLookup.ActiveBan> bans =
        new JdbcBanLookup(db.dataSource(), "btc_", "borntocraft")
            .activeNetworkBans(Set.of(BANNED), NOW);

    assertEquals(Map.of(BANNED, new BanLookup.ActiveBan(42L, first, pronounced)), bans);
    final FakeJdbc.Call call = db.calls.getFirst();
    for (String predicate : List.of("MIN(created_at), MAX(created_at)", "FROM btc_sanctions",
        "player_uuid = ANY (?)", "type = 'BAN'",
        "scope = 'NETWORK'", "network_id = ?", "revoked_at IS NULL",
        "(expires_at IS NULL OR expires_at > ?)")) {
      assertTrue(call.sql().contains(predicate), predicate + " in " + call.sql());
    }
    assertArrayEquals(new Object[] {BANNED.toString()},
        (Object[]) ((java.sql.Array) call.params().get(1)).getArray());
    assertEquals("borntocraft", call.params().get(2));
    assertEquals(NOW.toEpochMilli(), call.params().get(3), "expiry compared in epoch millis");
  }

  @Test
  void noLinkedAccountNoQuery() throws SQLException {
    final FakeJdbc db = new FakeJdbc();

    assertTrue(new JdbcBanLookup(db.dataSource(), "btc_", "borntocraft")
        .activeNetworkBans(Set.of(), NOW).isEmpty());
    assertTrue(db.calls.isEmpty());
  }

  @Test
  void aPrefixThatIsNotAnIdentifierNeverReachesTheSql() {
    final FakeJdbc db = new FakeJdbc();

    assertThrows(IllegalArgumentException.class,
        () -> new JdbcLinkStore(db.dataSource(), "btc_; DROP TABLE x; --"));
    assertThrows(IllegalArgumentException.class,
        () -> new JdbcBanLookup(db.dataSource(), "Btc", "borntocraft"));
  }

  @Test
  void theSchemaIsTwoTablesOfItsOwnAndCanRunAgain() throws SQLException {
    final FakeJdbc db = new FakeJdbc();
    new JdbcLinkStore(db.dataSource(), "sent_").createTables();

    assertEquals(6, db.calls.size());
    for (FakeJdbc.Call call : db.calls) {
      assertTrue(call.sql().contains("IF NOT EXISTS sent_admission_"), call.sql());
      assertFalse(call.sql().contains("sanctions"), call.sql());
    }
  }

  @Test
  void aSightingIsOneRowPerAccountAndAddressDigest() throws SQLException {
    final FakeJdbc db = new FakeJdbc();
    ready(db).record(SEEN);

    final FakeJdbc.Call call = db.calls.getFirst();
    assertTrue(call.sql().contains("ON CONFLICT (uuid, ip_digest) DO UPDATE"), call.sql());
    assertEquals(ENTRANT.toString(), call.params().get(1));
    assertArrayEquals(SEEN.ipDigest(), (byte[]) call.params().get(2));
    assertArrayEquals(SEEN.netDigest(), (byte[]) call.params().get(3));
    assertEquals(NOW.toEpochMilli(), call.params().get(5));
  }

  @Test
  void theFactsAreTheAddressTheNetworkAndTheHouseholdOfTheEntrant() throws SQLException {
    final FakeJdbc db = new FakeJdbc()
        .answer(new Object[] {SISTER.toString(), NOW.minusSeconds(60).toEpochMilli()})
        .answer(new Object[] {BANNED.toString(), NOW.minusSeconds(120).toEpochMilli()})
        .answer(new Object[] {SISTER.toString()})
        .answer(new Object[] {NOW.minusSeconds(86_400).toEpochMilli()});
    final Instant addressSince = NOW.minus(LinkSettings.ADDRESS_HISTORY);
    final Instant networkSince = NOW.minusSeconds(3600);

    final LinkFacts facts = ready(db)
        .factsFor(SEEN, addressSince, networkSince);
    final LinkFacts unknown = new JdbcLinkStore(new FakeJdbc().answer().answer().answer()
        .answer(new Object[] {null}).dataSource(), "btc_").factsFor(SEEN, addressSince,
        networkSince);

    assertEquals(Map.of(SISTER, NOW.minusSeconds(60)), facts.onAddress());
    assertEquals(Map.of(BANNED, NOW.minusSeconds(120)), facts.onNetwork());
    assertEquals(Set.of(SISTER), facts.household());
    assertEquals(NOW.minusSeconds(86_400), facts.firstSeen());
    assertTrue(db.calls.get(3).sql().contains("MIN(first_seen) FROM btc_admission_sightings"
        + " WHERE uuid = ?"), db.calls.get(3).sql());
    assertEquals(ENTRANT.toString(), db.calls.get(3).params().get(1));
    assertNull(unknown.firstSeen(),"MIN over no row is NULL: never seen");
    final List<FakeJdbc.Call> calls = db.calls;
    assertTrue(calls.get(0).sql().contains("WHERE ip_digest = ?"), calls.get(0).sql());
    assertArrayEquals(SEEN.ipDigest(), (byte[]) calls.get(0).params().get(1));
    assertEquals(addressSince.toEpochMilli(), calls.get(0).params().get(2));
    assertTrue(calls.get(1).sql().contains("WHERE net_digest = ?"), calls.get(1).sql());
    assertArrayEquals(SEEN.netDigest(), (byte[]) calls.get(1).params().get(1));
    assertEquals(networkSince.toEpochMilli(), calls.get(1).params().get(2));
    assertEquals(LinkStore.MAX_ACCOUNTS_PER_LOOKUP, calls.get(1).params().get(3));
    assertEquals(ENTRANT.toString(), calls.get(2).params().get(1));
  }

  @Test
  void purgeDeletesTheSightingsLastSeenBeforeTheCutoff() throws SQLException {
    final FakeJdbc db = new FakeJdbc();
    final Instant cutoff = NOW.minus(java.time.Duration.ofDays(180));

    ready(db).purgeOlderThan(cutoff);

    assertTrue(db.calls.getFirst().sql().endsWith("btc_admission_sightings WHERE last_seen < ?"),
        db.calls.getFirst().sql());
    assertEquals(cutoff.toEpochMilli(), db.calls.getFirst().params().get(1));
  }

  @Test
  void forgetEmptiesBothTablesForTheAccountInOneTransaction() throws SQLException {
    final FakeJdbc db = new FakeJdbc();
    db.updateCount = 2;

    assertEquals(4, ready(db).forget(ENTRANT));

    assertEquals(List.of("DELETE FROM btc_admission_sightings WHERE uuid = ?",
        "DELETE FROM btc_admission_households WHERE uuid = ?"),
        db.calls.stream().map(FakeJdbc.Call::sql).toList());
    assertTrue(db.calls.stream().allMatch(call -> ENTRANT.toString().equals(call.params().get(1))));
    assertEquals(List.of("autoCommit=false", "commit", "autoCommit=true"), db.transaction);
  }

  @Test
  void aHouseholdIsJoinedNeverMerged() throws SQLException {
    final FakeJdbc joining = new FakeJdbc().answer(new Object[] {SISTER.toString(), "house-1"});
    final FakeJdbc twoHomes = new FakeJdbc().answer(new Object[] {SISTER.toString(), "house-1"},
        new Object[] {ENTRANT.toString(), "house-2"});

    assertTrue(ready(joining).declareHousehold(SISTER, ENTRANT, "console", "fratrie", NOW));
    assertFalse(ready(twoHomes).declareHousehold(SISTER, ENTRANT, "console", null, NOW));

    final FakeJdbc.Call insert = joining.calls.getLast();
    assertTrue(insert.sql().startsWith("INSERT INTO btc_admission_households"), insert.sql());
    assertEquals(2, joining.calls.size(), "only the account without a household is inserted");
    assertEquals(ENTRANT.toString(), insert.params().get(1));
    assertEquals("house-1", insert.params().get(2));
    assertEquals(1, twoHomes.calls.size(), "two households: nothing written");
    assertThrows(IllegalArgumentException.class, () -> new JdbcLinkStore(joining.dataSource(),
        "btc_").declareHousehold(SISTER, SISTER, "console", null, NOW));
  }
}
