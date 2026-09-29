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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class CidrSetTest {

  private static InetAddress ip(final String literal) {
    return InetAddress.ofLiteral(literal);
  }

  private static CidrSet setOf(final String... entries) {
    final CidrSet.Builder builder = CidrSet.builder();
    for (String entry : entries) {
      assertTrue(builder.add(entry), "should be accepted: " + entry);
    }
    return builder.build();
  }

  @Test
  void aNetworkContainsItsFirstAndLastAddressAndNothingBeyond() {
    // Arrange
    final CidrSet set = setOf("10.0.0.0/24");

    // Act & Assert - the edges are where an off-by-one lives
    assertTrue(set.contains(ip("10.0.0.0")));
    assertTrue(set.contains(ip("10.0.0.255")));
    assertFalse(set.contains(ip("9.255.255.255")));
    assertFalse(set.contains(ip("10.0.1.0")));
  }

  @Test
  void aBareAddressIsAHostRoute() {
    final CidrSet set = setOf("192.0.2.7");

    assertTrue(set.contains(ip("192.0.2.7")));
    assertFalse(set.contains(ip("192.0.2.6")));
    assertFalse(set.contains(ip("192.0.2.8")));
  }

  @Test
  void hostBitsBelowThePrefixAreIgnored() {
    final CidrSet set = setOf("10.1.2.3/8");

    assertTrue(set.contains(ip("10.255.255.255")));
    assertTrue(set.contains(ip("10.0.0.0")));
    assertFalse(set.contains(ip("11.0.0.0")));
  }

  @Test
  void aNetworkWiderThanSlash8OrSlash16IsRejectedWhateverTheListSays() {
    // Arrange - one such line in a poisoned list would classify a large share of the Internet
    final CidrSet.Builder builder = CidrSet.builder();

    // Act & Assert
    assertFalse(builder.add("0.0.0.0/0"));
    assertFalse(builder.add("128.0.0.0/1"));
    assertFalse(builder.add("10.0.0.0/7"));
    assertFalse(builder.add("::/0"));
    assertFalse(builder.add("2000::/3"));
    assertFalse(builder.add("2001::/15"));
    assertFalse(builder.add("::ffff:0.0.0.0/96"), "a mapped /96 is an IPv4 /0");
    assertFalse(builder.add("::ffff:10.0.0.0/103"), "a mapped /103 is an IPv4 /7");
    final CidrSet rejected = builder.build();
    assertTrue(rejected.isEmpty());
    assertFalse(rejected.contains(ip("203.0.113.9")));
    assertFalse(rejected.contains(ip("2001:db8::1")));
  }

  @Test
  void theWidestAcceptedNetworksAreSlash8AndSlash16AndStayInTheirFamily() {
    final CidrSet v4 = setOf("10.0.0.0/8", "::ffff:11.0.0.0/104");
    final CidrSet v6 = setOf("2001::/16");

    assertTrue(v4.contains(ip("10.255.0.1")));
    assertTrue(v4.contains(ip("11.1.2.3")));
    assertFalse(v4.contains(ip("12.0.0.0")));
    assertFalse(v4.contains(ip("2001:db8::1")));
    assertTrue(v6.contains(ip("2001:db8::1")));
    assertFalse(v6.contains(ip("2002::1")));
    assertFalse(v6.contains(ip("10.0.0.1")));
  }

  @Test
  void coverageCountsEachAddressOnceAndApproximatesIpv6InSlash64Networks() {
    final CidrSet set = setOf("192.0.2.0/24", "192.0.2.128/25", "198.51.100.7",
        "2001:db8::/48", "2001:db8::/64", "2001:db8:1::1/128");

    assertEquals(257, set.ipv4AddressCount(), "the /25 lies inside the /24");
    assertEquals(65_536 + 1, set.ipv6Slash64Count(), 0.0, "a /48 is 65 536 /64s, a host is one");
  }

  @Test
  void ipv6NetworksRespectTheirBoundariesOnBothHalves() {
    // Arrange - /32 stays in the high half, /64 ends exactly on it, /96 and /128 use the low half
    final CidrSet set = setOf("2001:db8::/32", "2001:418:1401:4::/64", "2a01:e0::/96",
        "2606:4700::1111/128");

    // Act & Assert
    assertTrue(set.contains(ip("2001:db8:ffff:ffff:ffff:ffff:ffff:ffff")));
    assertTrue(set.contains(ip("2001:db8::")));
    assertFalse(set.contains(ip("2001:db9::")));
    assertTrue(set.contains(ip("2001:418:1401:4:ffff:ffff:ffff:ffff")));
    assertFalse(set.contains(ip("2001:418:1401:5::")));
    assertTrue(set.contains(ip("2a01:e0::ffff:ffff")));
    assertFalse(set.contains(ip("2a01:e0::1:0:0")));
    assertTrue(set.contains(ip("2606:4700::1111")));
    assertFalse(set.contains(ip("2606:4700::1112")));
  }

  @Test
  void overlappingAndAdjacentNetworksMergeWithoutHidingAnything() {
    // Arrange - a /16 swallowing a /24, plus two /25 halves that only make a /24 together
    final CidrSet set = setOf("172.16.0.0/16", "172.16.5.0/24", "192.168.0.0/25",
        "192.168.0.128/25");

    // Act & Assert
    assertEquals(4, set.entryCount());
    assertEquals(2, set.rangeCount());
    assertTrue(set.contains(ip("172.16.5.9")));
    assertTrue(set.contains(ip("192.168.0.127")));
    assertTrue(set.contains(ip("192.168.0.128")));
    assertFalse(set.contains(ip("192.168.1.0")));
  }

  @Test
  void aWideRangeAfterANarrowOneIsNotShadowedByIt() {
    // The narrow one sorts first and ends early: the wide one must still be found.
    final CidrSet set = setOf("10.0.0.5", "10.0.0.0/8");

    assertTrue(set.contains(ip("10.200.0.1")));
  }

  @Test
  void anIpv4MappedIpv6AddressIsLookedUpAsIpv4() {
    final CidrSet set = setOf("198.51.100.0/24");

    assertTrue(set.contains(ip("::ffff:198.51.100.20")));
    assertFalse(set.contains(ip("::ffff:198.51.101.20")));
  }

  @Test
  void anEmptySetContainsNothing() {
    assertTrue(CidrSet.empty().isEmpty());
    assertFalse(CidrSet.empty().contains(ip("1.1.1.1")));
    assertFalse(CidrSet.empty().contains(ip("::1")));
  }

  @Test
  void malformedEntriesAreRejectedAndNeverResolved() {
    final CidrSet.Builder builder = CidrSet.builder();

    assertFalse(builder.add("not-an-ip"));
    assertFalse(builder.add("example.com"), "a host name must not trigger a DNS lookup");
    assertFalse(builder.add("10.0.0.0/33"));
    assertFalse(builder.add("2001:db8::/129"));
    assertFalse(builder.add("10.0.0.0/"));
    assertFalse(builder.add("10.0.0.0/-1"));
    assertFalse(builder.add("10.0.0.0/abc"));
    assertFalse(builder.add("/24"));
    assertFalse(builder.add(""));
    assertTrue(builder.build().isEmpty());
  }
}
