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

import java.net.InetAddress;
import java.util.Optional;

/** Says which autonomous system an address belongs to, if the module knows. */
@FunctionalInterface
public interface AsnLookup {

  /** A lookup that knows nothing: the module before its first ASN download. */
  AsnLookup NONE = address -> Optional.empty();

  /**
   * Looks an address up.
   *
   * @param address the address; a non-public address has no autonomous system
   * @return the autonomous system, or empty when the address is not public, not routed, or the
   *     database is not loaded
   */
  Optional<AsnDatabase.Info> lookup(InetAddress address);
}
