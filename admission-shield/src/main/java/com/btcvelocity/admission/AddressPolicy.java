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

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;

/**
 * Which addresses may be classified at all, and as what.
 *
 * <p>Two rules stand between a list and an address:
 * <ul>
 *   <li>An address that is not public is never classified, whatever a list says: loopback,
 *       RFC 1918, carrier-grade NAT ({@code 100.64.0.0/10}), link-local, IPv6 unique-local,
 *       multicast, "this network". A list that named them (by mistake or by poisoning) would
 *       refuse the proxy's own neighbours, or every player behind a shared carrier address.</li>
 *   <li>An IPv6 address that only carries an IPv4 address is classified as that IPv4 address:
 *       6to4 ({@code 2002::/16}), Teredo ({@code 2001::/32}, client address XOR-ed) and NAT64
 *       ({@code 64:ff9b::/96}). Otherwise a datacenter host would slip past an IPv4 list by
 *       connecting through a tunnel it controls.</li>
 * </ul>
 */
public final class AddressPolicy {

  private AddressPolicy() {
  }

  /**
   * The address to look up in the lists, or nothing if this address must never be classified.
   *
   * @param address the address a connection came from
   * @return the address to classify (unwrapped if it was a tunnel), or empty if it is not public
   */
  public static Optional<InetAddress> classifiable(final InetAddress address) {
    final InetAddress unwrapped = unwrap(address);
    return isPublic(unwrapped) ? Optional.of(unwrapped) : Optional.empty();
  }

  /**
   * Returns the IPv4 address a 6to4, Teredo, NAT64 or IPv4-mapped address carries, or the address
   * itself.
   *
   * @param address any address
   * @return the embedded IPv4 address, or {@code address} if it embeds none
   */
  public static InetAddress unwrap(final InetAddress address) {
    if (!(address instanceof Inet6Address)) {
      return address;
    }
    final byte[] b = address.getAddress();
    final byte[] embedded = new byte[4];
    if ((b[0] & 0xff) == 0x20 && (b[1] & 0xff) == 0x02) {
      System.arraycopy(b, 2, embedded, 0, 4);
    } else if ((b[0] & 0xff) == 0x20 && b[1] == 0x01 && b[2] == 0 && b[3] == 0) {
      for (int i = 0; i < 4; i++) {
        embedded[i] = (byte) ~b[12 + i];
      }
    } else if (isNat64(b) || isV4Mapped(b)) {
      System.arraycopy(b, 12, embedded, 0, 4);
    } else {
      return address;
    }
    try {
      return InetAddress.getByAddress(embedded);
    } catch (UnknownHostException impossible) {
      throw new IllegalStateException("four bytes are always an address", impossible);
    }
  }

  /** {@code ::ffff:a.b.c.d}: how a dual-stack socket may report an IPv4 client. */
  private static boolean isV4Mapped(final byte[] b) {
    for (int i = 0; i < 10; i++) {
      if (b[i] != 0) {
        return false;
      }
    }
    return (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff;
  }

  private static boolean isNat64(final byte[] b) {
    if (b[0] != 0 || b[1] != 0x64 || (b[2] & 0xff) != 0xff || (b[3] & 0xff) != 0x9b) {
      return false;
    }
    for (int i = 4; i < 12; i++) {
      if (b[i] != 0) {
        return false;
      }
    }
    return true;
  }

  /**
   * Whether an address is one a list may legitimately speak about.
   *
   * @param address the address
   * @return {@code false} for loopback, private, CGNAT, link-local, unique-local, multicast and
   *     unspecified addresses
   */
  public static boolean isPublic(final InetAddress address) {
    if (address.isAnyLocalAddress() || address.isLoopbackAddress()
        || address.isLinkLocalAddress() || address.isSiteLocalAddress()
        || address.isMulticastAddress()) {
      return false;
    }
    final byte[] b = address.getAddress();
    if (b.length == 4) {
      // 0.0.0.0/8, "this network"; 100.64.0.0/10, carrier-grade NAT: many subscribers behind
      // one address.
      return b[0] != 0 && !((b[0] & 0xff) == 100 && (b[1] & 0xc0) == 0x40);
    }
    // fc00::/7, unique-local (isSiteLocalAddress only knows the deprecated fec0::/10).
    return (b[0] & 0xfe) != 0xfc;
  }
}
