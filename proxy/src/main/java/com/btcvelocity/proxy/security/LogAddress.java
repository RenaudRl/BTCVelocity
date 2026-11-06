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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

/** A player's address as the logs may write it: the network, not the address itself. */
final class LogAddress {

  /** IPv4 prefix kept in logs. */
  private static final int V4_PREFIX = 24;

  /** IPv6 prefix kept in logs. */
  private static final int V6_PREFIX = 48;

  private static final int V6_LENGTH = 16;
  private static final int V4_LENGTH = 4;
  private static final int V4_MAPPED_OFFSET = 12;
  private static final int V4_MAPPED_ZEROS = 10;

  private LogAddress() {
  }

  /**
   * Masks an address for a log line.
   *
   * @param address the remote address, possibly {@code null} or unresolved
   * @return {@code network/prefix} (IPv4 /24, IPv6 /48, the port dropped), or {@code unknown}
   */
  static String of(final SocketAddress address) {
    if (!(address instanceof InetSocketAddress socket) || socket.getAddress() == null) {
      return "unknown";
    }
    return mask(socket.getAddress());
  }

  static String mask(final InetAddress address) {
    byte[] bytes = address.getAddress();
    if (bytes.length == V6_LENGTH && isV4Mapped(bytes)) {
      bytes = Arrays.copyOfRange(bytes, V4_MAPPED_OFFSET, V6_LENGTH);
    }
    final int prefix = bytes.length == V4_LENGTH ? V4_PREFIX : V6_PREFIX;
    final byte[] masked = new byte[bytes.length];
    for (int i = 0; i < bytes.length; i++) {
      final int kept = Math.max(0, Math.min(Byte.SIZE, prefix - Byte.SIZE * i));
      masked[i] = (byte) (bytes[i] & (0xff << (Byte.SIZE - kept)));
    }
    try {
      return InetAddress.getByAddress(masked).getHostAddress() + "/" + prefix;
    } catch (UnknownHostException impossible) {
      throw new IllegalStateException("an address of " + masked.length + " bytes", impossible);
    }
  }

  private static boolean isV4Mapped(final byte[] bytes) {
    for (int i = 0; i < V4_MAPPED_ZEROS; i++) {
      if (bytes[i] != 0) {
        return false;
      }
    }
    return bytes[V4_MAPPED_ZEROS] == (byte) 0xff && bytes[V4_MAPPED_ZEROS + 1] == (byte) 0xff;
  }
}
