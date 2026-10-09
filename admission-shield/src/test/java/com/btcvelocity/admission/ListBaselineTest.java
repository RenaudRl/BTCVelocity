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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ListBaselineTest {

  /** A list the size of the X4BNet IPv4 one: ten /8 networks and 18 000 /24 networks. */
  static String x4bnetIpv4Sized() {
    final StringBuilder list = new StringBuilder();
    for (int first = 11; first <= 20; first++) {
      list.append(first).append(".0.0.0/8\n");
    }
    for (int i = 0; i < 18_000; i++) {
      list.append("198.").append(i / 256).append('.').append(i % 256).append(".0/24\n");
    }
    return list.toString();
  }

  @Test
  void everyDefaultSourceHasABaselineAndItsFamilyBoundsAreCoherent() {
    for (ListSource source : AdmissionConfig.defaultSources()) {
      final ListBaseline baseline = ListBaseline.forSource(source.id()).orElse(null);

      assertNotNull(baseline, source.id() + " has no embedded baseline");
      assertTrue(baseline.minEntries() > 0 && baseline.minEntries() < baseline.maxEntries());
      assertTrue(baseline.minIpv4Addresses() <= baseline.maxIpv4Addresses());
      assertTrue(baseline.minIpv6Slash64() <= baseline.maxIpv6Slash64());
      assertTrue(baseline.maxEntries() <= AdmissionConfig.DEFAULT_MAX_ENTRIES);
    }
  }

  @Test
  void aListTheSizeOfTheRealOneIsWithinItsBaselineAndAShortOneIsNot() {
    final ListBaseline v4 = ListBaseline.forSource("x4bnet-datacenter-ipv4").orElseThrow();

    assertNull(v4.rejection(ListParser.parse(x4bnetIpv4Sized())));
    final String tiny = v4.rejection(ListParser.parse("203.0.113.0/24\n198.51.100.0/24\n"));
    assertNotNull(tiny);
    assertTrue(tiny.contains("outside the embedded baseline"), tiny);
  }

  @Test
  void aFamilyTheDefaultListDoesNotCarryCannotAppearOnAFirstLoad() {
    final ListBaseline v4 = ListBaseline.forSource("x4bnet-datacenter-ipv4").orElseThrow();
    final ListBaseline tor = ListBaseline.forSource("firehol-tor-exits").orElseThrow();

    final String v6InTheV4List =
        v4.rejection(ListParser.parse(x4bnetIpv4Sized() + "2a01:e00::/26\n"));
    final StringBuilder torSized = new StringBuilder();
    for (int i = 0; i < 1000; i++) {
      torSized.append("185.220.").append(i / 256).append('.').append(i % 256).append('\n');
    }

    assertNotNull(v6InTheV4List);
    assertTrue(v6InTheV4List.contains("IPv6"), v6InTheV4List);
    assertNull(tor.rejection(ListParser.parse(torSized.toString())));
    assertNotNull(tor.rejection(ListParser.parse(torSized + "2a01:e00::/26\n")));
    assertEquals(1000, ListParser.parse(torSized.toString()).validLines());
  }
}
