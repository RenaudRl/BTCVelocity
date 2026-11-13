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
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What the store knows around an entering account: who was seen on its address and on its
 * network, and who shares its declared household. The rule turns this into {@link Link}s.
 *
 * @param onAddress the accounts seen on the same exact address, with when they were last seen
 *     there (bounded to the address history; the entering account may be among them)
 * @param onNetwork the accounts seen on the same /24 or /64, with when they were last seen there
 * @param household the accounts declared in the same household as the entering one (itself
 *     included when it has one)
 */
public record LinkFacts(Map<UUID, Instant> onAddress, Map<UUID, Instant> onNetwork,
    Set<UUID> household) {

  /** Nothing known: the address and the network were never seen, no household. */
  public static final LinkFacts NONE = new LinkFacts(Map.of(), Map.of(), Set.of());

  /**
   * Creates the facts, copied.
   *
   * @param onAddress the accounts seen on the same exact address
   * @param onNetwork the accounts seen on the same network
   * @param household the accounts of the same declared household
   */
  public LinkFacts {
    onAddress = Map.copyOf(onAddress);
    onNetwork = Map.copyOf(onNetwork);
    household = Set.copyOf(household);
  }
}
