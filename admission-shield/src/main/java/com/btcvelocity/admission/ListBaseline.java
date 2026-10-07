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
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * What a default list is expected to look like, shipped with the module.
 *
 * <p>The comparison with the previous version (see {@link ListGuards#relativeRejection}) needs a
 * previous version. The first list a proxy loads has none, and neither has any list at all when
 * the {@code lists/} directory is not kept between two starts (a template that is recreated). The
 * baseline is what such a first load is judged against: bounds on the number of entries and on the
 * coverage of each family, taken from the real lists on 2026-10-07 with a wide margin (about 0.4
 * to 2.5 times today's size), so that growth over a year or two fits and a poisoned or truncated
 * file does not.
 *
 * <p>A family whose maximum is {@code 0} cannot appear at all: the X4BNet IPv4 file holds no IPv6
 * network, the FireHOL Tor ipset is IPv4 only.
 *
 * <p>The numbers age. When a default list outgrows its baseline the list is no longer loaded and
 * an ERROR names the bound that was crossed: update the baseline with the module, or switch it off
 * with {@code lists.embedded-baseline: false}, which leaves the first load to the absolute guards.
 *
 * @param minEntries the fewest entries a first load may hold
 * @param maxEntries the most entries a first load may hold
 * @param minIpv4Addresses the fewest IPv4 addresses it may cover
 * @param maxIpv4Addresses the most IPv4 addresses it may cover, {@code 0} for none
 * @param minIpv6Slash64 the fewest /64 networks it may cover
 * @param maxIpv6Slash64 the most /64 networks it may cover, {@code 0} for none
 */
public record ListBaseline(
    int minEntries,
    int maxEntries,
    long minIpv4Addresses,
    long maxIpv4Addresses,
    double minIpv6Slash64,
    double maxIpv6Slash64) {

  /** X4BNet lists_vpn datacenter, 2026-10-07: 44 414 lines, 395 276 592 addresses (9.2 %). */
  private static final ListBaseline X4BNET_IPV4 =
      new ListBaseline(18_000, 110_000, 150_000_000L, 700_000_000L, 0, 0);

  /** X4BNet lists_vpn datacenter, 2026-10-07: 8 752 lines, 2.89e13 /64 (0.00125 % of 2000::/3). */
  private static final ListBaseline X4BNET_IPV6 =
      new ListBaseline(3_500, 22_000, 0, 0, 1.1e13, 7.5e13);

  /** FireHOL tor_exits, 2026-10-07: 1 270 single addresses. */
  private static final ListBaseline TOR_EXITS = new ListBaseline(300, 5_000, 300, 5_000, 0, 0);

  private static final Map<String, ListBaseline> BY_SOURCE = Map.of(
      "x4bnet-datacenter-ipv4", X4BNET_IPV4,
      "x4bnet-datacenter-ipv6", X4BNET_IPV6,
      "firehol-tor-exits", TOR_EXITS);

  /**
   * The baseline of a default source.
   *
   * @param sourceId the id of the source
   * @return its baseline, or empty for a source the module does not ship
   */
  public static Optional<ListBaseline> forSource(final String sourceId) {
    return Optional.ofNullable(BY_SOURCE.get(sourceId));
  }

  /**
   * Judges a list that has nothing to be compared with.
   *
   * @param candidate the parsed list
   * @return why the list is outside the baseline, or {@code null} if it is within
   */
  public @Nullable String rejection(final ListParser.Result candidate) {
    final int entries = candidate.validLines();
    if (entries < this.minEntries || entries > this.maxEntries) {
      return outside("entries", entries, this.minEntries, this.maxEntries);
    }
    final long v4 = candidate.set().ipv4AddressCount();
    if (v4 < this.minIpv4Addresses || v4 > this.maxIpv4Addresses) {
      return outside("IPv4 addresses", v4, this.minIpv4Addresses, this.maxIpv4Addresses);
    }
    final double v6 = candidate.set().ipv6Slash64Count();
    if (v6 < this.minIpv6Slash64 || v6 > this.maxIpv6Slash64) {
      return outside("IPv6 /64 networks", v6, this.minIpv6Slash64, this.maxIpv6Slash64);
    }
    return null;
  }

  private static String outside(
      final String what, final Number value, final Number min, final Number max) {
    return String.format(Locale.ROOT,
        "%s %s, outside the embedded baseline of %s to %s (first load, no previous version)",
        value, what, min, max);
  }
}
