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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** The three judgements made of a whole IP-to-ASN file. */
class AsnGuardsTest {

  private static final int CEILING = 1000;

  /** {@code count} routed rows of 256 addresses, the first {@code denied} of them from AS 666. */
  private static String file(final int count, final int denied) {
    final StringBuilder text = new StringBuilder();
    for (int i = 0; i < count; i++) {
      final String first = "10." + (i / 256) + "." + (i % 256) + ".0";
      final String last = "10." + (i / 256) + "." + (i % 256) + ".255";
      final long system = i < denied ? 666 : 1000L + i;
      text.append(first).append('\t').append(last).append('\t').append(system)
          .append("\tFR\tName ").append(system).append('\n');
    }
    return text.toString();
  }

  private static AsnParser.Result parsed(final String text) {
    return AsnParser.parse(text, 1_000_000);
  }

  @Test
  void aSoundFilePassesTheAbsoluteChecks() {
    assertNull(AsnGuards.absoluteRejection(parsed(file(50, 0)), CEILING, Set.of()));
  }

  @Test
  void aFileWithNoValidRowIsRefusedWithItsFirstSamples() {
    final String why = AsnGuards.absoluteRejection(
        parsed("<html>404 not found</html>\n"), CEILING, Set.of());

    assertNotNull(why);
    assertTrue(why.contains("no valid row"), why);
    assertTrue(why.contains("404"), why);
  }

  @Test
  void moreUnreadableLinesThanRowsIsRefused() {
    final String why = AsnGuards.absoluteRejection(
        parsed(file(2, 0) + "junk\njunk\njunk\n"), CEILING, Set.of());

    assertNotNull(why);
    assertTrue(why.contains("unreadable"), why);
  }

  @Test
  void aFileReadPastTheRowCeilingIsRefused() {
    final AsnParser.Result truncated = AsnParser.parse(file(50, 0), 20);

    final String why = AsnGuards.absoluteRejection(truncated, 20, Set.of());

    assertNotNull(why);
    assertTrue(why.contains("asn.max-rows"), why);
  }

  @Test
  void aFileWhereNothingIsRoutedIsRefused() {
    final String why = AsnGuards.absoluteRejection(
        parsed("1.0.0.0\t1.0.0.255\t0\tNone\tNot routed\n"), CEILING, Set.of());

    assertNotNull(why);
    assertTrue(why.contains("AS 0"), why);
  }

  @Test
  void aFileThatGivesTheDeniedSystemsAFifthOfTheInternetIsRefused() {
    // 10.0.0.0/8 is 16.7 M addresses; the denied system "owns" 11 % of 2^32 in one range here,
    // twice that in two: a poisoned file attributing the world to a hosting provider.
    final String twelvePercent = "16.0.0.0\t47.255.255.255\t666\tUS\tBig\n";
    final String fortyPercent = "16.0.0.0\t127.255.255.255\t666\tUS\tHuge\n";

    assertNull(AsnGuards.absoluteRejection(parsed(twelvePercent), CEILING, Set.of(666L)));
    final String why =
        AsnGuards.absoluteRejection(parsed(fortyPercent), CEILING, Set.of(666L));
    assertNotNull(why);
    assertTrue(why.contains("IPv4"), why);
    assertNull(AsnGuards.absoluteRejection(parsed(fortyPercent), CEILING, Set.of()),
        "with nothing denied the share does not matter");
  }

  @Test
  void aRowCountThatMovedByMoreThanHalfIsRefused() {
    final AsnDatabase before = parsed(file(100, 0)).database();

    assertNull(AsnGuards.relativeRejection(parsed(file(140, 0)).database(), before, Set.of()));
    assertNull(AsnGuards.relativeRejection(parsed(file(60, 0)).database(), before, Set.of()));
    final String grew = AsnGuards.relativeRejection(parsed(file(160, 0)).database(), before,
        Set.of());
    final String shrank = AsnGuards.relativeRejection(parsed(file(40, 0)).database(), before,
        Set.of());
    assertNotNull(grew);
    assertNotNull(shrank);
    assertTrue(grew.contains("row count"), grew);
  }

  @Test
  void aDeniedCoverageThatDoubledIsRefusedEvenWithTheRowCountSteady() {
    final AsnDatabase before = parsed(file(100, 10)).database();

    assertNull(AsnGuards.relativeRejection(parsed(file(100, 15)).database(), before,
        Set.of(666L)));
    final String why = AsnGuards.relativeRejection(parsed(file(100, 20)).database(), before,
        Set.of(666L));
    assertNotNull(why);
    assertTrue(why.contains("doubled"), why);
    assertNull(AsnGuards.relativeRejection(parsed(file(100, 20)).database(), before, Set.of()),
        "with nothing denied there is no coverage to watch");
  }

  @Test
  void theEmbeddedBaselineAcceptsTheFigureOfTheRealFileAndRefusesAnythingFarFromIt() {
    final AsnGuards.Baseline baseline = AsnGuards.EMBEDDED;
    assertTrue(baseline.minRows() < 722_982 && 722_982 < baseline.maxRows());
    assertTrue(baseline.minIpv4Ranges() < 405_248 && 405_248 < baseline.maxIpv4Ranges());
    assertTrue(baseline.minIpv6Ranges() < 97_508 && 97_508 < baseline.maxIpv6Ranges());
    assertTrue(baseline.minSystems() < 87_182 && 87_182 < baseline.maxSystems());

    final String tiny = baseline.rejection(parsed(file(50, 0)).database());
    assertNotNull(tiny);
    assertTrue(tiny.contains("embedded baseline"), tiny);
  }

  @Test
  void aBaselineNamesTheFirstBoundCrossed() {
    final AsnGuards.Baseline wide = new AsnGuards.Baseline(10, 100, 10, 100, 0, 0, 10, 100);

    assertNull(wide.rejection(parsed(file(50, 0)).database()));
    final String rows = wide.rejection(parsed(file(500, 0)).database());
    assertNotNull(rows);
    assertTrue(rows.contains("rows"), rows);
    final String ipv6 = wide.rejection(parsed(
        file(50, 0) + "2001:db8::\t2001:db8::ffff\t64496\tNL\tV6\n").database());
    assertNotNull(ipv6);
    assertTrue(ipv6.contains("IPv6"), ipv6);
  }
}
