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
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Sanity checks on a whole IP-to-ASN file, before it is trusted: the same three judgements as
 * {@link ListGuards} and {@link ListBaseline} make of an address list, in the terms of this file.
 *
 * <ul>
 *   <li><b>absolute</b>: read past the row ceiling, no valid line, more unreadable lines than valid
 *       ones (an error page), no autonomous system at all, or the denied systems covering more than
 *       {@value ListGuards#MAX_IPV4_SHARE_PERCENT} % of the IPv4 space, or more than
 *       {@value ListGuards#MAX_IPV6_SHARE_PERCENT} % of the IPv6 space {@code 2000::/3};</li>
 *   <li><b>relative</b>, against the version in use: a row count, or an IPv6 range count, that
 *       moved by more than {@value ListGuards#MAX_ENTRY_VARIATION_PERCENT} %, or a denied IPv4 or
 *       IPv6 coverage that doubled;</li>
 *   <li><b>baseline</b>, for a first load with nothing to compare with: counts within a wide margin
 *       of the real file of 2026-10-09.</li>
 * </ul>
 * Each returns a sentence for the log, or {@code null} when the file passes.
 */
public final class AsnGuards {

  /**
   * What the real file is expected to look like, from 0.4 to 2.5 times the figures of 2026-10-09:
   * 722 982 rows, 405 248 IPv4 and 97 508 IPv6 ranges once unrouted ones are dropped and neighbours
   * merged, 87 182 autonomous systems.
   */
  public static final Baseline EMBEDDED =
      new Baseline(300_000, 1_800_000, 160_000, 1_020_000, 39_000, 245_000, 35_000, 220_000);

  /**
   * The counts a first load must fall within.
   *
   * @param minRows the fewest rows (valid lines)
   * @param maxRows the most rows
   * @param minIpv4Ranges the fewest stored IPv4 ranges
   * @param maxIpv4Ranges the most stored IPv4 ranges
   * @param minIpv6Ranges the fewest stored IPv6 ranges
   * @param maxIpv6Ranges the most stored IPv6 ranges
   * @param minSystems the fewest distinct autonomous systems
   * @param maxSystems the most distinct autonomous systems
   */
  public record Baseline(int minRows, int maxRows, int minIpv4Ranges, int maxIpv4Ranges,
      int minIpv6Ranges, int maxIpv6Ranges, int minSystems, int maxSystems) {

    /**
     * Judges a file that has nothing to be compared with.
     *
     * @param database what the file was parsed into
     * @return why it is outside the baseline, or {@code null} if it is within
     */
    public @Nullable String rejection(final AsnDatabase database) {
      if (database.rowCount() < this.minRows || database.rowCount() > this.maxRows) {
        return outside("rows", database.rowCount(), this.minRows, this.maxRows);
      }
      if (database.ipv4RangeCount() < this.minIpv4Ranges
          || database.ipv4RangeCount() > this.maxIpv4Ranges) {
        return outside("IPv4 ranges", database.ipv4RangeCount(), this.minIpv4Ranges,
            this.maxIpv4Ranges);
      }
      if (database.ipv6RangeCount() < this.minIpv6Ranges
          || database.ipv6RangeCount() > this.maxIpv6Ranges) {
        return outside("IPv6 ranges", database.ipv6RangeCount(), this.minIpv6Ranges,
            this.maxIpv6Ranges);
      }
      if (database.systemCount() < this.minSystems || database.systemCount() > this.maxSystems) {
        return outside("autonomous systems", database.systemCount(), this.minSystems,
            this.maxSystems);
      }
      return null;
    }

    private static String outside(final String what, final int value, final int min,
        final int max) {
      return value + " " + what + ", outside the embedded baseline of " + min + " to " + max
          + " (first load, no previous version)";
    }
  }

  private static final double MAX_COVERAGE_GROWTH = 2.0;

  /**
   * A /29, in /64 networks: the largest block a registry hands one operator in one go. The IPv6
   * coverage of the denied systems may reach twice this from nothing, so that a system that starts
   * announcing IPv6 does not get every later file refused; beyond it, twice what it had is the
   * limit, as for IPv4.
   */
  private static final double IPV6_GROWTH_FLOOR_SLASH64 = Math.scalb(1.0, 35);

  private AsnGuards() {
  }

  /**
   * The checks that need nothing but the file itself.
   *
   * @param candidate the parsed file
   * @param maxRows the ceiling on the rows of the file
   * @param denied the AS numbers the module refuses, whose IPv4 share is bounded
   * @return why the file is refused, or {@code null} if it passes
   */
  public static @Nullable String absoluteRejection(final AsnParser.Result candidate,
      final int maxRows, final Set<Long> denied) {
    if (candidate.truncated()) {
      return "more than " + maxRows + " rows, above the ceiling of " + maxRows
          + " (asn.max-rows); not read further";
    }
    if (candidate.exceeded() != null) {
      return candidate.exceeded();
    }
    if (candidate.validLines() == 0) {
      return "no valid row, " + candidate.invalidLines() + " unreadable lines, first "
          + candidate.printableSamples();
    }
    if (candidate.invalidLines() > candidate.validLines()) {
      return "more unreadable lines (" + candidate.invalidLines() + ") than rows ("
          + candidate.validLines() + "), first " + candidate.printableSamples();
    }
    if (candidate.database().systemCount() == 0) {
      return "no routed range: every row is AS 0 (not routed)";
    }
    final double share = 100.0 * candidate.database().ipv4AddressesOf(denied)
        / ListGuards.IPV4_ADDRESSES;
    if (share > ListGuards.MAX_IPV4_SHARE_PERCENT) {
      return String.format(Locale.ROOT,
          "the denied systems cover %.1f %% of the IPv4 space (limit %d %%)", share,
          ListGuards.MAX_IPV4_SHARE_PERCENT);
    }
    final double share6 = 100.0 * candidate.database().ipv6Slash64Of(denied)
        / ListGuards.GLOBAL_UNICAST_SLASH64;
    if (share6 > ListGuards.MAX_IPV6_SHARE_PERCENT) {
      return String.format(Locale.ROOT,
          "the denied systems cover %.5f %% of the IPv6 space 2000::/3 (limit %s %%)", share6,
          ListGuards.MAX_IPV6_SHARE_PERCENT);
    }
    return null;
  }

  /**
   * The checks against the version being replaced.
   *
   * @param candidate the parsed file
   * @param previous the version in use
   * @param denied the AS numbers the module refuses
   * @return why the file is refused, or {@code null} if it passes
   */
  public static @Nullable String relativeRejection(final AsnDatabase candidate,
      final AsnDatabase previous, final Set<Long> denied) {
    final double before = previous.rowCount();
    if (before > 0) {
      final double variation = 100.0 * Math.abs(candidate.rowCount() - before) / before;
      if (variation > ListGuards.MAX_ENTRY_VARIATION_PERCENT) {
        return String.format(Locale.ROOT, "row count moved from %d to %d (%.0f %%, limit %d %%)",
            previous.rowCount(), candidate.rowCount(), variation,
            ListGuards.MAX_ENTRY_VARIATION_PERCENT);
      }
    }
    final String ipv6Ranges = ipv6RangesRejection(candidate, previous);
    if (ipv6Ranges != null) {
      return ipv6Ranges;
    }
    final long old = previous.ipv4AddressesOf(denied);
    final long now = candidate.ipv4AddressesOf(denied);
    if (old > 0 && now >= MAX_COVERAGE_GROWTH * old) {
      return "the IPv4 coverage of the denied systems doubled (" + old + " to " + now
          + " addresses)";
    }
    final double old6 = previous.ipv6Slash64Of(denied);
    final double now6 = candidate.ipv6Slash64Of(denied);
    if (now6 >= MAX_COVERAGE_GROWTH * Math.max(old6, IPV6_GROWTH_FLOOR_SLASH64)) {
      return String.format(Locale.ROOT, "the IPv6 coverage of the denied systems doubled"
          + " (%.0f to %.0f /64 networks)", old6, now6);
    }
    return null;
  }

  /**
   * The row count of the whole file hides the IPv6 half: it is a quarter of the rows, so it can
   * vanish while the total moves by a third. Its range count is judged by itself.
   */
  private static @Nullable String ipv6RangesRejection(final AsnDatabase candidate,
      final AsnDatabase previous) {
    final double before = previous.ipv6RangeCount();
    if (before <= 0) {
      return null;
    }
    final double variation = 100.0 * Math.abs(candidate.ipv6RangeCount() - before) / before;
    if (variation > ListGuards.MAX_ENTRY_VARIATION_PERCENT) {
      return String.format(Locale.ROOT, "IPv6 ranges moved from %d to %d (%.0f %%, limit %d %%)",
          previous.ipv6RangeCount(), candidate.ipv6RangeCount(), variation,
          ListGuards.MAX_ENTRY_VARIATION_PERCENT);
    }
    return null;
  }
}
