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

/** Says which class of address an IP belongs to, if any. */
@FunctionalInterface
public interface IpClassifier {

  /**
   * A list hit.
   *
   * @param ipClass the class of the list that matched
   * @param sourceId the id of the list that matched
   */
  record Match(IpClass ipClass, String sourceId) {
  }

  /**
   * Looks an address up.
   *
   * @param address the address to classify
   * @return the class and list that matched, or empty when no list knows the address
   */
  Optional<Match> classify(InetAddress address);
}
