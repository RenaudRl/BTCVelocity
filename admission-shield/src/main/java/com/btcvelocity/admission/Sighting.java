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

import java.time.Instant;
import java.util.UUID;

/**
 * One account seen on one address, as stored: never the address itself, only its keyed digests
 * (see {@link LinkKey}).
 *
 * @param account the account
 * @param ipDigest HMAC-SHA256 of the exact address
 * @param netDigest HMAC-SHA256 of its network (an IPv4 /24 or an IPv6 /64)
 * @param at when it was seen
 */
public record Sighting(UUID account, byte[] ipDigest, byte[] netDigest, Instant at) {

  @Override
  public String toString() {
    // The digests are pseudonymous data: they have no business in a log line.
    return "Sighting[" + this.account + " at " + this.at + "]";
  }
}
