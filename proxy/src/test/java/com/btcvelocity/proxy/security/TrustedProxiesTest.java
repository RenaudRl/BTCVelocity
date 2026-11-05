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

package com.btcvelocity.proxy.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Only a listed relay may speak PROXY protocol; everyone else is refused, the default included.
 */
class TrustedProxiesTest {

  private static InetAddress ip(final String literal) {
    return InetAddress.ofLiteral(literal);
  }

  @Test
  void defaultTrustsLocalRelaysButNotThePublicInternet() {
    final TrustedProxies trusted = TrustedProxies.parse(TrustedProxies.DEFAULT_ENTRIES);
    assertTrue(trusted.invalidEntries().isEmpty());
    assertTrue(trusted.contains(ip("127.0.0.1")));
    assertTrue(trusted.contains(ip("172.22.0.3")));
    assertTrue(trusted.contains(ip("192.168.1.20")));
    assertTrue(trusted.contains(ip("::1")));
    assertTrue(trusted.contains(ip("fd12::5")));
    assertFalse(trusted.contains(ip("8.8.8.8")));
    assertFalse(trusted.contains(ip("172.32.0.1")));
    assertFalse(trusted.contains(ip("2001:db8::1")));
  }

  @Test
  void ipv4MappedPeerIsMatchedAsIpv4() {
    final TrustedProxies trusted = TrustedProxies.parse(List.of("10.0.0.0/8"));
    assertTrue(trusted.contains(ip("::ffff:10.1.2.3")));
    assertFalse(trusted.contains(ip("::ffff:11.1.2.3")));
  }

  @Test
  void partialBytePrefixIsHonoured() {
    final TrustedProxies trusted = TrustedProxies.parse(List.of("203.0.113.64/27"));
    assertTrue(trusted.contains(ip("203.0.113.64")));
    assertTrue(trusted.contains(ip("203.0.113.95")));
    assertFalse(trusted.contains(ip("203.0.113.96")));
    assertFalse(trusted.contains(ip("203.0.113.63")));
  }

  @Test
  void bareAddressTrustsOnlyItself() {
    final TrustedProxies trusted = TrustedProxies.parse(List.of("198.51.100.7"));
    assertTrue(trusted.contains(ip("198.51.100.7")));
    assertFalse(trusted.contains(ip("198.51.100.8")));
  }

  @Test
  void emptyListTrustsNobody() {
    final TrustedProxies trusted = TrustedProxies.parse(List.of());
    assertFalse(trusted.contains(ip("127.0.0.1")));
  }

  @Test
  void malformedHostnameAndTooWideEntriesAreReportedAndTrustNothing() {
    final TrustedProxies trusted = TrustedProxies.parse(
        List.of("0.0.0.0/0", "::/0", "localhost", "10.0.0.0/33", "10.0.0.0/", "1.2.3.4/-1"));
    assertEquals(6, trusted.invalidEntries().size());
    assertFalse(trusted.contains(ip("1.2.3.4")));
    assertFalse(trusted.contains(ip("127.0.0.1")));
  }
}
