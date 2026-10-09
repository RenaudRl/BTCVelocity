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

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

/** The network an address sits in, written at a coarser grain than the address itself. */
final class Networks {

  /** The grain of the journal's aggregation: an IPv4 /24 or an IPv6 /64. */
  static final int AGGREGATE_V4 = 24;

  /** See {@link #AGGREGATE_V4}. */
  static final int AGGREGATE_V6 = 64;

  /** The grain shown to the staff: an IPv4 /24 or an IPv6 /48, not the player's own address. */
  static final int STAFF_V4 = 24;

  /** See {@link #STAFF_V4}. */
  static final int STAFF_V6 = 48;

  private Networks() {
  }

  /**
   * The network of an address, as {@code network/prefix}. An IPv4-mapped IPv6 address is read as
   * the IPv4 address it wraps.
   *
   * @param address the address
   * @param v4Prefix the prefix kept for an IPv4 address
   * @param v6Prefix the prefix kept for an IPv6 address
   * @return the network, for example {@code 203.0.113.0/24}
   */
  static String of(final InetAddress address, final int v4Prefix, final int v6Prefix) {
    byte[] bytes = address.getAddress();
    if (bytes.length == 16 && isV4Mapped(bytes)) {
      bytes = Arrays.copyOfRange(bytes, 12, 16);
    }
    final int prefix = bytes.length == 4 ? v4Prefix : v6Prefix;
    final byte[] masked = new byte[bytes.length];
    for (int i = 0; i < bytes.length; i++) {
      final int kept = Math.max(0, Math.min(8, prefix - 8 * i));
      masked[i] = (byte) (bytes[i] & (0xff << (8 - kept)));
    }
    try {
      return InetAddress.getByAddress(masked).getHostAddress() + "/" + prefix;
    } catch (UnknownHostException impossible) {
      throw new IllegalStateException("an address of " + masked.length + " bytes", impossible);
    }
  }

  private static boolean isV4Mapped(final byte[] bytes) {
    for (int i = 0; i < 10; i++) {
      if (bytes[i] != 0) {
        return false;
      }
    }
    return bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
  }
}
