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

import java.util.Locale;
import java.util.Optional;

/**
 * The classes of address a list can put an address in.
 *
 * <p>All three are membership of a public list or of a short list of systems the staff chose, not
 * a score, and all three are refused: they barely overlap with a home or a mobile subscription.
 * Any other class a future list might carry belongs in observation only, until its false positives
 * have been measured.
 */
public enum IpClass {

  /** A hosting or VPN network (X4BNet {@code lists_vpn}, datacenter list). */
  DATACENTER("datacenter", "ADM-VPN-DC"),

  /** A Tor exit node (FireHOL {@code tor_exits}). */
  TOR("tor", "ADM-VPN-TOR"),

  /**
   * An autonomous system the staff denies ({@code asn.deny-asns}, from the iptoasn.com database).
   * Not a list: it has its own mode and no address list may be configured with this class.
   */
  ASN("asn", "ADM-VPN-ASN");

  private final String key;
  private final String causeCode;

  IpClass(final String key, final String causeCode) {
    this.key = key;
    this.causeCode = causeCode;
  }

  /**
   * The name used in the configuration file and in the journal.
   *
   * @return the lowercase key
   */
  public String key() {
    return this.key;
  }

  /**
   * The code shown to a refused player and written in the journal, so support can match the two.
   *
   * @return the cause code
   */
  public String causeCode() {
    return this.causeCode;
  }

  /**
   * Finds a class by its configuration key.
   *
   * @param key the key, any case
   * @return the class, or empty if the key names none
   */
  public static Optional<IpClass> fromKey(final String key) {
    final String wanted = key.trim().toLowerCase(Locale.ROOT);
    for (IpClass candidate : values()) {
      if (candidate.key.equals(wanted)) {
        return Optional.of(candidate);
      }
    }
    return Optional.empty();
  }
}
