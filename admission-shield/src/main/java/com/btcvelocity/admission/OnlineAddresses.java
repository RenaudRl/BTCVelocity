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

import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Which accounts are online on which address, in memory only and keyed by the address DIGEST
 * ({@link LinkKey}): the address itself is never held, and nothing here outlives the proxy.
 *
 * <p>One entry per account. A session is told apart by a token (the player object): the old session
 * of an account that logs in again may end after the new one began, and must not take the new one
 * away.
 */
final class OnlineAddresses {

  private record Session(String address, Object token) {
  }

  private final Map<UUID, Session> sessions = new HashMap<>();

  /**
   * An account came online on an address.
   *
   * @param account the account
   * @param addressDigest the keyed digest of its address
   * @param token tells this session from another of the same account
   */
  synchronized void enter(final UUID account, final byte[] addressDigest, final Object token) {
    this.sessions.put(account, new Session(HexFormat.of().formatHex(addressDigest), token));
  }

  /**
   * An account went offline; ignored when the account has since come back under another token.
   *
   * @param account the account
   * @param token the token it entered with
   */
  synchronized void leave(final UUID account, final Object token) {
    final Session session = this.sessions.get(account);
    if (session != null && session.token() == token) {
      this.sessions.remove(account);
    }
  }

  /**
   * The other accounts online on an address.
   *
   * @param self the entering account, never counted against itself
   * @param addressDigest the keyed digest of the address
   * @return those accounts
   */
  synchronized Set<UUID> othersOn(final UUID self, final byte[] addressDigest) {
    final String address = HexFormat.of().formatHex(addressDigest);
    final Set<UUID> others = new HashSet<>();
    this.sessions.forEach((account, session) -> {
      if (!account.equals(self) && session.address().equals(address)) {
        others.add(account);
      }
    });
    return others;
  }
}
