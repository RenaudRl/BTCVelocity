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

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.Optional;

/**
 * Remembers, for a short time, the connections the module has refused, by their socket address
 * (address and port: one per live connection).
 *
 * <p>A refusal is a result set on an event, and another listener running after it can set it
 * back. The module applies its refusal again as late as it can at the login, and this ledger is
 * the last net: at the post-login the connection is closed if a refusal was noted for it and
 * nevertheless got through.
 */
public final class RefusalLedger {

  private static final long MAX_CONNECTIONS = 10_000;
  private static final Duration REMEMBERED_FOR = Duration.ofMinutes(2);

  private final Cache<SocketAddress, Verdict> refused = Caffeine.newBuilder()
      .maximumSize(MAX_CONNECTIONS).expireAfterWrite(REMEMBERED_FOR).build();

  /**
   * Notes that a connection was refused.
   *
   * @param connection the connection's remote socket address
   * @param verdict the refusal
   */
  public void note(final SocketAddress connection, final Verdict verdict) {
    this.refused.put(connection, verdict);
  }

  /**
   * Takes the refusal noted for a connection, if any.
   *
   * @param connection the connection's remote socket address
   * @return the refusal; empty when none was noted
   */
  public Optional<Verdict> take(final SocketAddress connection) {
    return Optional.ofNullable(this.refused.asMap().remove(connection));
  }
}
