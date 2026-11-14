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

import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.IntFunction;

/**
 * The sessions that hold a place on an address, in memory only and keyed by the address DIGEST
 * ({@link LinkKey}): the address itself is never held, and nothing here outlives the proxy.
 *
 * <p>A place is taken when a login is judged, in the same step as the count it is judged on
 * ({@link #judgeAndReserve}): ten logins that arrive before any of them reaches a server cannot
 * all see an empty address. It belongs to a SESSION, told by a token (the player object), not to
 * an account: a second connection of an account already online, refused later as a duplicate, comes
 * and goes without taking the live session's place away. What is counted is the DISTINCT accounts
 * other than the entering one.
 *
 * <p>A session whose end is heard before its place was taken (the events are not ordered) never
 * takes it afterwards: it would hold the address for ever.
 */
final class OnlineAddresses {

  private record Session(UUID account, String address) {
  }

  private final Map<Object, Session> sessions = new IdentityHashMap<>();
  /** Sessions that ended; weakly held, so the set never outlives the players. */
  private final Set<Object> ended = Collections.newSetFromMap(new WeakHashMap<>());

  /**
   * Counts the other accounts on an address, lets the caller judge on that count, and takes the
   * place of the session unless the verdict refuses it, all in one step.
   *
   * @param account the entering account
   * @param addressDigest the keyed digest of its address
   * @param token tells this session from another, including another of the same account
   * @param household the entrant's declared household, never counted against it
   * @param judging the verdict from the number of other accounts online
   * @return the verdict
   */
  synchronized Verdict judgeAndReserve(final UUID account, final byte[] addressDigest,
      final Object token, final Set<UUID> household, final IntFunction<Verdict> judging) {
    final Set<UUID> others = othersOn(account, addressDigest);
    others.removeAll(household);
    final Verdict verdict = judging.apply(others.size());
    if (verdict.action() != Verdict.Action.REFUSE) {
      reserve(account, addressDigest, token);
    }
    return verdict;
  }

  /**
   * Takes the place of a session without judging it (the lookup failed or timed out: the login is
   * admitted, and it counts against the next ones all the same). Does nothing for a session that
   * has ended already.
   *
   * @param account the account
   * @param addressDigest the keyed digest of its address
   * @param token the session
   */
  synchronized void reserve(final UUID account, final byte[] addressDigest, final Object token) {
    if (!this.ended.contains(token)) {
      this.sessions.put(token, new Session(account, HexFormat.of().formatHex(addressDigest)));
    }
  }

  /**
   * A session ended: its place is freed, and it can no longer take one.
   *
   * @param token the session
   */
  synchronized void leave(final Object token) {
    this.sessions.remove(token);
    this.ended.add(token);
  }

  /**
   * The distinct accounts, other than one, with a place on an address.
   *
   * @param self the entering account, never counted against itself
   * @param addressDigest the keyed digest of the address
   * @return those accounts, a copy
   */
  synchronized Set<UUID> othersOn(final UUID self, final byte[] addressDigest) {
    final String address = HexFormat.of().formatHex(addressDigest);
    final Set<UUID> others = new HashSet<>();
    this.sessions.values().forEach(session -> {
      if (!session.account().equals(self) && session.address().equals(address)) {
        others.add(session.account());
      }
    });
    return others;
  }
}
