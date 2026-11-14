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
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Where the sightings (account, address digest) and the declared households are kept.
 *
 * <p>Every method may block on a database: none is ever called on a network thread.
 */
public interface LinkStore {

  /**
   * The most accounts one lookup returns per address or network. Far above
   * {@code shared-address-accounts}: an address that reaches it is shared anyway.
   */
  int MAX_ACCOUNTS_PER_LOOKUP = 256;

  /**
   * What is known around an entering session.
   *
   * @param entering the session being judged (it is NOT recorded by this call)
   * @param addressSince the oldest sighting on the same address that still counts
   * @param networkSince the oldest sighting on the same network that still counts
   * @return the facts
   * @throws SQLException if the store cannot be read
   */
  LinkFacts factsFor(Sighting entering, Instant addressSince, Instant networkSince)
      throws SQLException;

  /**
   * Records that an account was accepted by a server from an address; a second sighting of the
   * same pair only moves its last-seen time. The account is also recorded as known, as by
   * {@link #recordKnown}.
   *
   * @param sighting the accepted session
   * @throws SQLException if the store cannot be written
   */
  void record(Sighting sighting) throws SQLException;

  /**
   * Records that an account was accepted by a server, with no address at all: its first and last
   * time are all that is kept. This is what tells an account that was already playing before a ban
   * from one made after it ({@link LinkFacts#firstSeen()}); it outlives {@link #forget} and the
   * purge of the sightings, and is deleted by {@link #purgeKnownOlderThan} alone.
   *
   * @param account the account
   * @param at when
   * @throws SQLException if the store cannot be written
   */
  void recordKnown(UUID account, Instant at) throws SQLException;

  /**
   * Erases what is kept about an account: its sightings and its household membership. The
   * account's first and last time ({@link #recordKnown}) stay until their own retention ends.
   *
   * @param account the account
   * @return the rows removed
   * @throws SQLException if the store cannot be written
   */
  int forget(UUID account) throws SQLException;

  /**
   * Deletes the sightings last seen before a time.
   *
   * @param cutoff the oldest last-seen time kept
   * @return the rows removed
   * @throws SQLException if the store cannot be written
   */
  int purgeOlderThan(Instant cutoff) throws SQLException;

  /**
   * Deletes the accounts last seen before a time, from the list of known accounts.
   *
   * @param cutoff the oldest last-seen time kept
   * @return the rows removed
   * @throws SQLException if the store cannot be written
   */
  int purgeKnownOlderThan(Instant cutoff) throws SQLException;

  /**
   * Declares two accounts one household: the one that has a household brings the other in, or a
   * new household is made for both.
   *
   * @param first an account
   * @param second another account
   * @param declaredBy who declares it (a staff UUID, or {@code console})
   * @param reason why, for the staff, or {@code null}
   * @param at when
   * @return {@code false} when the two already belong to two different households (nothing is
   *     changed: one has to be removed first)
   * @throws SQLException if the store cannot be written
   */
  boolean declareHousehold(UUID first, UUID second, String declaredBy, @Nullable String reason,
      Instant at) throws SQLException;

  /**
   * Takes an account out of its household.
   *
   * @param account the account
   * @return whether it had one
   * @throws SQLException if the store cannot be written
   */
  boolean leaveHousehold(UUID account) throws SQLException;

  /**
   * The accounts seen on any address this account was seen on, for the staff.
   *
   * @param account the account
   * @param since the oldest sighting that still counts
   * @return each other account with when it was last seen on one of those addresses
   * @throws SQLException if the store cannot be read
   */
  Map<UUID, Instant> sharingAddressWith(UUID account, Instant since) throws SQLException;
}
