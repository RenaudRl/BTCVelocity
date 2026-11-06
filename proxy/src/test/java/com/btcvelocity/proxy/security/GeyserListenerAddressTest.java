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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;

/** The value of {@code advanced.geyser-listener}: a literal address and a port, nothing else. */
class GeyserListenerAddressTest {

  @Test
  void readsAnIpv4AndABracketedIpv6Literal() {
    assertEquals(new InetSocketAddress(InetAddress.ofLiteral("127.0.0.1"), 25566),
        GeyserListenerAddress.parse("127.0.0.1:25566"));
    assertEquals(new InetSocketAddress(InetAddress.ofLiteral("::1"), 25566),
        GeyserListenerAddress.parse(" [::1]:25566 "));
  }

  @Test
  void refusesAHostName() {
    assertThrows(IllegalArgumentException.class, () -> GeyserListenerAddress.parse("localhost:25566"));
    assertThrows(IllegalArgumentException.class,
        () -> GeyserListenerAddress.parse("geyser.example.org:25566"));
  }

  @Test
  void refusesAMissingOrInvalidPort() {
    for (String bad : new String[] {"127.0.0.1", "127.0.0.1:", "127.0.0.1:0", "127.0.0.1:65536",
        "127.0.0.1:-1", "127.0.0.1:12ab", "127.0.0.1:999999", ":25566", ""}) {
      assertThrows(IllegalArgumentException.class, () -> GeyserListenerAddress.parse(bad), bad);
    }
  }

  @Test
  void refusesAnIpv6AddressWithoutBrackets() {
    assertThrows(IllegalArgumentException.class, () -> GeyserListenerAddress.parse("::1:25566"));
  }

  @Test
  void readsTheIpv6Wildcard() {
    assertEquals(25565, GeyserListenerAddress.parse("[::]:25565").getPort());
    assertTrue(GeyserListenerAddress.parse("[::]:25565").getAddress().isAnyLocalAddress());
  }
}
