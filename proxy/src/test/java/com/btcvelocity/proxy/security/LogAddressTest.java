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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;

class LogAddressTest {

  private static InetSocketAddress socket(final String literal) {
    return new InetSocketAddress(InetAddress.ofLiteral(literal), 25565);
  }

  @Test
  void anIpv4AddressKeepsItsSlash24AndLosesItsPort() {
    assertEquals("203.0.113.0/24", LogAddress.of(socket("203.0.113.77")));
  }

  @Test
  void anIpv6AddressKeepsItsSlash48() {
    assertEquals("2001:db8:1:0:0:0:0:0/48", LogAddress.of(socket("2001:db8:1:2:3::5")));
  }

  @Test
  void anIpv4MappedAddressIsReadAsIpv4() {
    assertEquals("203.0.113.0/24", LogAddress.of(socket("::ffff:203.0.113.77")));
  }

  @Test
  void anUnresolvedOrMissingAddressIsUnknown() {
    assertEquals("unknown", LogAddress.of(InetSocketAddress.createUnresolved("example.test", 1)));
    assertEquals("unknown", LogAddress.of(null));
  }
}
