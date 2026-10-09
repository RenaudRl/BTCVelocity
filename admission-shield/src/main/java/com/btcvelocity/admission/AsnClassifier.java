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

/**
 * Puts the addresses of the denied autonomous systems ({@code asn.deny-asns}) in the class
 * {@link IpClass#ASN}, so that they follow the path of a datacenter list: exemptions, observe or
 * enforce, journal, "would refuse in addition" during a quarantine.
 *
 * <p>The source id it answers with is {@code iptoasn:AS<number>}, which is what the journal's
 * {@code source} field and {@code /admission why} show. With nothing denied it answers empty
 * without looking anything up.
 */
public final class AsnClassifier implements IpClassifier {

  private final AsnLookup lookup;
  private final AsnSettings settings;

  /**
   * Creates the classifier.
   *
   * @param lookup where the system of an address is found
   * @param settings the {@code asn} section, naming the denied systems
   */
  public AsnClassifier(final AsnLookup lookup, final AsnSettings settings) {
    this.lookup = lookup;
    this.settings = settings;
  }

  @Override
  public Optional<Match> classify(final InetAddress address) {
    if (this.settings.denyAsns().isEmpty()) {
      return Optional.empty();
    }
    return this.lookup.lookup(address)
        .filter(info -> this.settings.denies(info.number()))
        .map(info -> new Match(IpClass.ASN, AsnSettings.SOURCE_ID + ":AS" + info.number()));
  }
}
