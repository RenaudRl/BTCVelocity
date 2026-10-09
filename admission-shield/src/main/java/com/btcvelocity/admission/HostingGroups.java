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

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.slf4j.Logger;

/**
 * Which autonomous systems the per-network bound puts into one bucket.
 *
 * <p>A system is grouped when the settings take it for hosting ({@link AsnSettings#isHosting}). In
 * {@code enforce} one more condition holds: the system must not announce more than
 * {@code network-rate.max-asn-share-percent} of the address space. A file that attributes the
 * internet to one "hosting" system, poisoned or mistaken, would otherwise make a single bucket of
 * most players, and the bound would refuse them all once it ran out. Such a system is left to the
 * prefix grouping, and the warning says so, once per system.
 */
final class HostingGroups implements Predicate<AsnDatabase.Info> {

  private final AsnSettings settings;
  private final AsnLookup asn;
  private final boolean enforcing;
  private final double maxSharePercent;
  private final Logger logger;
  private final Set<Long> warned = ConcurrentHashMap.newKeySet();

  /**
   * Creates the policy.
   *
   * @param settings says which systems are hosting
   * @param asn the database in use, which knows how much of the space a system announces
   * @param enforcing whether the bound refuses (the share limit is held in this mode only)
   * @param maxSharePercent the most of the address space a grouped system may announce
   * @param logger where the neutralised systems are reported
   */
  HostingGroups(final AsnSettings settings, final AsnLookup asn, final boolean enforcing,
      final double maxSharePercent, final Logger logger) {
    this.settings = settings;
    this.asn = asn;
    this.enforcing = enforcing;
    this.maxSharePercent = maxSharePercent;
    this.logger = logger;
  }

  @Override
  public boolean test(final AsnDatabase.Info info) {
    if (!this.settings.isHosting(info)) {
      return false;
    }
    if (this.enforcing && this.asn.coversMoreThan(info.number(), this.maxSharePercent)) {
      if (this.warned.add(info.number())) {
        this.logger.warn("AS{} ({}) announces more than {} % of the address space: it is NOT"
            + " grouped into one bucket by the per-network bound, its addresses keep their"
            + " network prefix. The IP-to-ASN file or asn.observe-keywords is probably wrong"
            + " (network-rate.max-asn-share-percent).", info.number(), info.name(),
            this.maxSharePercent);
      }
      return false;
    }
    return true;
  }
}
