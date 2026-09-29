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
import org.jspecify.annotations.Nullable;

/**
 * Sanity checks on a whole list, before it is trusted.
 *
 * <p>A list is a file fetched from someone else's repository. If it is poisoned, truncated or
 * simply broken, it must not be able to refuse the players, so a list that looks wrong is thrown
 * away and the previous valid one stays. Line-level limits (no network wider than /8 or /16) are
 * in {@link CidrSet.Builder}; these judge the list as a whole:
 * <ul>
 *   <li>no valid entry, or more unreadable lines than valid ones (an error page);</li>
 *   <li>more entries than the configured ceiling;</li>
 *   <li>more than {@value #MAX_IPV4_SHARE_PERCENT} % of the IPv4 space;</li>
 *   <li>compared with the previous valid version: an entry count that moved by more than
 *       {@value #MAX_ENTRY_VARIATION_PERCENT} %, or a coverage (IPv4 or IPv6) that doubled.</li>
 * </ul>
 * Each returns a sentence for the log, or {@code null} when the list passes.
 */
public final class ListGuards {

  /** The whole IPv4 space: 2<sup>32</sup> addresses. */
  public static final long IPV4_ADDRESSES = 1L << 32;

  /** The most of the IPv4 space one list may cover, in percent. */
  public static final int MAX_IPV4_SHARE_PERCENT = 20;

  /** The most the entry count may move between two versions of a list, in percent. */
  public static final int MAX_ENTRY_VARIATION_PERCENT = 50;

  private static final double MAX_COVERAGE_GROWTH = 2.0;

  private ListGuards() {
  }

  /**
   * The checks that need nothing but the list itself.
   *
   * @param candidate the parsed list
   * @param maxEntries the ceiling on the number of entries
   * @return why the list is refused, or {@code null} if it passes
   */
  public static @Nullable String absoluteRejection(
      final ListParser.Result candidate, final int maxEntries) {
    if (candidate.validLines() == 0) {
      return "no valid entry, " + candidate.invalidLines() + " unreadable lines, first "
          + candidate.invalidSamples();
    }
    if (candidate.invalidLines() > candidate.validLines()) {
      return "more unreadable lines (" + candidate.invalidLines() + ") than entries ("
          + candidate.validLines() + "), first " + candidate.invalidSamples();
    }
    if (candidate.validLines() > maxEntries) {
      return candidate.validLines() + " entries, above the ceiling of " + maxEntries
          + " (lists.max-entries)";
    }
    final double share = 100.0 * candidate.set().ipv4AddressCount() / IPV4_ADDRESSES;
    if (share > MAX_IPV4_SHARE_PERCENT) {
      return String.format(Locale.ROOT, "covers %.1f %% of the IPv4 space (limit %d %%)",
          share, MAX_IPV4_SHARE_PERCENT);
    }
    return null;
  }

  /**
   * The checks against the version being replaced.
   *
   * @param candidate the parsed list
   * @param previous the valid list currently in use, or {@code null} for the first one
   * @return why the list is refused, or {@code null} if it passes
   */
  public static @Nullable String relativeRejection(
      final ListParser.Result candidate, final ListStore.@Nullable Loaded previous) {
    if (previous == null) {
      return null;
    }
    final double before = previous.validLines();
    final double variation = 100.0 * Math.abs(candidate.validLines() - before) / before;
    if (variation > MAX_ENTRY_VARIATION_PERCENT) {
      return String.format(Locale.ROOT,
          "entry count moved from %d to %d (%.0f %%, limit %d %%); delete the list file and"
              + " restart to accept it", previous.validLines(), candidate.validLines(), variation,
          MAX_ENTRY_VARIATION_PERCENT);
    }
    final CidrSet old = previous.set();
    if (old.ipv4AddressCount() > 0
        && candidate.set().ipv4AddressCount() >= MAX_COVERAGE_GROWTH * old.ipv4AddressCount()) {
      return "IPv4 coverage doubled (" + old.ipv4AddressCount() + " to "
          + candidate.set().ipv4AddressCount() + " addresses); delete the list file and restart"
          + " to accept it";
    }
    if (old.ipv6Slash64Count() > 0
        && candidate.set().ipv6Slash64Count() >= MAX_COVERAGE_GROWTH * old.ipv6Slash64Count()) {
      return "IPv6 coverage doubled; delete the list file and restart to accept it";
    }
    return null;
  }
}
