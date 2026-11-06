/*
 * Copyright (C) 2018-2026 Velocity Contributors
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

/**
 * The address of {@code advanced.geyser-listener}: a literal {@code host:port}, never a host name
 * (same rule as {@link TrustedProxies}: nothing here touches DNS), with an explicit port.
 */
public final class GeyserListenerAddress {

  private static final int MAX_PORT = 65535;

  private GeyserListenerAddress() {
    throw new AssertionError();
  }

  /**
   * Reads {@code 1.2.3.4:25566} or {@code [::1]:25566}.
   *
   * @param text the value as written in the configuration
   * @return the address
   * @throws IllegalArgumentException if it is not a literal address with a port from 1 to 65535
   */
  public static InetSocketAddress parse(final String text) {
    final String value = text.trim();
    final int colon = value.lastIndexOf(':');
    if (colon <= 0) {
      throw new IllegalArgumentException("expected host:port");
    }
    String host = value.substring(0, colon);
    final boolean bracketed = host.startsWith("[") && host.endsWith("]");
    if (bracketed) {
      host = host.substring(1, host.length() - 1);
    } else if (host.indexOf(':') >= 0) {
      throw new IllegalArgumentException("an IPv6 address must be written [address]:port");
    }
    final InetAddress address = InetAddress.ofLiteral(host);
    final String portText = value.substring(colon + 1);
    if (portText.isEmpty() || portText.length() > 5 || !portText.chars().allMatch(Character::isDigit)) {
      throw new IllegalArgumentException("invalid port '" + portText + "'");
    }
    final int port = Integer.parseInt(portText);
    if (port < 1 || port > MAX_PORT) {
      throw new IllegalArgumentException("port out of range 1-65535: " + port);
    }
    return new InetSocketAddress(address, port);
  }

  /**
   * Whether two listeners would fight for the same socket: same port, and the same address or one
   * of them the wildcard.
   *
   * @param main the main listener ({@code bind}), possibly an unresolved host name
   * @param geyser the Geyser listener, a literal address
   * @return {@code true} if both cannot be bound together
   */
  public static boolean overlaps(final InetSocketAddress main, final InetSocketAddress geyser) {
    if (main.getPort() != geyser.getPort()) {
      return false;
    }
    final InetAddress mainAddress = main.getAddress();
    if (mainAddress == null) {
      // An unresolved name in bind: it may resolve to anything, only the wildcard surely clashes.
      return geyser.getAddress().isAnyLocalAddress()
          || main.getHostString().equalsIgnoreCase(geyser.getHostString());
    }
    return mainAddress.equals(geyser.getAddress())
        || mainAddress.isAnyLocalAddress() || geyser.getAddress().isAnyLocalAddress();
  }
}
