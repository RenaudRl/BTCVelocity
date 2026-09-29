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

import java.net.URI;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ListGuardsTest {

  private static final ListSource SOURCE = new ListSource("dc", IpClass.DATACENTER,
      URI.create("https://lists.invalid/dc.txt"), "dc.txt");

  private static ListStore.Loaded loaded(final String text) {
    final ListParser.Result parsed = ListParser.parse(text);
    return new ListStore.Loaded(SOURCE, parsed.set(), parsed.validLines(), parsed.invalidLines(),
        Instant.EPOCH);
  }

  @Test
  void aSensibleListPassesAndAFirstListHasNothingToBeComparedWith() {
    final ListParser.Result list = ListParser.parse("203.0.113.0/24\n2001:db8::/32\n");

    assertNull(ListGuards.absoluteRejection(list, 10));
    assertNull(ListGuards.relativeRejection(list, null));
  }

  @Test
  void justUnderAFifthOfIpv4PassesAndOneMoreSlash8DoesNot() {
    // 51 /8 networks are 19.9 %, 52 are 20.3 %
    final StringBuilder fiftyOne = new StringBuilder();
    for (int first = 1; first <= 51; first++) {
      fiftyOne.append(first).append(".0.0.0/8\n");
    }

    assertNull(ListGuards.absoluteRejection(ListParser.parse(fiftyOne.toString()), 1000));
    final String rejection = ListGuards.absoluteRejection(
        ListParser.parse(fiftyOne + "52.0.0.0/8\n"), 1000);
    assertNotNull(rejection);
    assertTrue(rejection.contains("20.3 %"), rejection);
  }

  @Test
  void theCeilingOnEntriesIsInclusive() {
    final ListParser.Result three = ListParser.parse("192.0.2.1\n192.0.2.2\n192.0.2.3\n");

    assertNull(ListGuards.absoluteRejection(three, 3));
    assertNotNull(ListGuards.absoluteRejection(three, 2));
  }

  @Test
  void anIpv6CoverageThatDoublesIsRejected() {
    final ListStore.Loaded previous = loaded("2001:db8::/48\n2001:db9::/48\n");

    assertNull(ListGuards.relativeRejection(
        ListParser.parse("2001:db8::/48\n2001:dba::/48\n"), previous));
    final String rejection = ListGuards.relativeRejection(
        ListParser.parse("2001:db8::/47\n2001:dba::/47\n"), previous);
    assertNotNull(rejection);
    assertTrue(rejection.contains("IPv6 coverage doubled"), rejection);
  }

  @Test
  void anEntryCountMovingByExactlyHalfIsStillAccepted() {
    final ListStore.Loaded previous = loaded("192.0.2.1\n192.0.2.2\n192.0.2.3\n192.0.2.4\n");

    assertNull(ListGuards.relativeRejection(
        ListParser.parse("192.0.2.1\n192.0.2.2\n"), previous));
    assertNotNull(ListGuards.relativeRejection(ListParser.parse("192.0.2.1\n"), previous));
  }
}
