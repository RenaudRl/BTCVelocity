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

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.junit.jupiter.api.Test;

class AddressPolicyTest {

  private static InetAddress ip(final String literal) {
    return InetAddress.ofLiteral(literal);
  }

  @Test
  void addressesThatAreNotPublicAreNeverClassifiable() {
    for (String address : List.of("127.0.0.1", "10.0.0.1", "172.16.0.1", "172.31.255.255",
        "192.168.0.1", "100.64.0.1", "100.127.255.255", "169.254.1.1", "0.0.0.0", "0.1.2.3",
        "224.0.0.1", "::1", "::", "fe80::1", "fc00::1", "fdff:ffff::1", "ff02::1")) {
      assertTrue(AddressPolicy.classifiable(ip(address)).isEmpty(), address);
    }
  }

  @Test
  void publicAddressesAtTheEdgesOfThoseRangesStayClassifiable() {
    for (String address : List.of("100.63.255.255", "100.128.0.0", "172.15.255.255",
        "172.32.0.0", "11.0.0.0", "1.1.1.1", "2001:db8::1", "fbff::1", "fe00::1")) {
      assertEquals(ip(address), AddressPolicy.classifiable(ip(address)).orElseThrow(), address);
    }
  }

  @Test
  void sixToFourTeredoAndNat64AreUnwrappedToTheirIpv4() {
    assertEquals(ip("203.0.113.1"), AddressPolicy.unwrap(ip("2002:cb00:7101:1::5")));
    assertEquals(ip("203.0.113.1"),
        AddressPolicy.unwrap(ip("2001:0:4136:e378:8000:63bf:34ff:8efe")));
    assertEquals(ip("203.0.113.1"), AddressPolicy.unwrap(ip("64:ff9b::cb00:7101")));
    assertEquals(ip("2001:db8::1"), AddressPolicy.unwrap(ip("2001:db8::1")),
        "2001:db8::/32 is not Teredo (2001:0::/32)");
    assertEquals(ip("64:ff9b:1::cb00:7101"), AddressPolicy.unwrap(ip("64:ff9b:1::cb00:7101")),
        "the local-use NAT64 prefix is not the well-known one");
  }

  @Test
  void aTunnelToAPrivateAddressIsNotClassifiable() {
    // 6to4 of 10.0.0.1 and of 192.168.1.1
    assertTrue(AddressPolicy.classifiable(ip("2002:a00:1::1")).isEmpty());
    assertTrue(AddressPolicy.classifiable(ip("2002:c0a8:101::1")).isEmpty());
  }

  @Test
  void anIpv4MappedAddressHeldAsIpv6IsUnwrapped() throws UnknownHostException {
    final byte[] mapped = new byte[16];
    mapped[10] = (byte) 0xff;
    mapped[11] = (byte) 0xff;
    mapped[12] = 10;
    mapped[15] = 1;
    final InetAddress asIpv6 = Inet6Address.getByAddress(null, mapped, -1);

    assertFalse(AddressPolicy.classifiable(asIpv6).isPresent(), "10.0.0.1 in disguise");
  }
}
