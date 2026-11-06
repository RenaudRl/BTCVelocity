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

package com.velocitypowered.proxy.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.netty.handler.codec.haproxy.HAProxyCommand;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.codec.haproxy.HAProxyProtocolVersion;
import io.netty.handler.codec.haproxy.HAProxyProxiedProtocol;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;

/** The client address a PROXY header may announce: TCP over IPv4 or IPv6, as a literal. */
class ProxiedSourceTest {

  private static HAProxyMessage header(final HAProxyProxiedProtocol protocol, final String source,
      final int port) {
    return new HAProxyMessage(HAProxyProtocolVersion.V2, HAProxyCommand.PROXY, protocol, source,
        source, port, port == 0 ? 0 : 25565);
  }

  @Test
  void tcpOverIpv4AndIpv6AreReadAsLiterals() {
    final InetSocketAddress v4 =
        MinecraftConnection.proxiedSource(header(HAProxyProxiedProtocol.TCP4, "203.0.113.7", 4242));
    final InetSocketAddress v6 =
        MinecraftConnection.proxiedSource(header(HAProxyProxiedProtocol.TCP6, "2001:db8::7", 4243));

    assertEquals(new InetSocketAddress(InetAddress.ofLiteral("203.0.113.7"), 4242), v4);
    assertEquals(new InetSocketAddress(InetAddress.ofLiteral("2001:db8::7"), 4243), v6);
    assertFalse(v4.isUnresolved());
  }

  @Test
  void anyOtherProtocolIsRefused() {
    // a UNIX socket header carries free text as its "address": it must never reach a resolver
    assertNull(MinecraftConnection.proxiedSource(
        header(HAProxyProxiedProtocol.UNIX_STREAM, "evil.example.com", 0)));
    assertNull(MinecraftConnection.proxiedSource(
        header(HAProxyProxiedProtocol.UNIX_DGRAM, "evil.example.com", 0)));
    assertNull(MinecraftConnection.proxiedSource(
        header(HAProxyProxiedProtocol.UDP4, "203.0.113.7", 4242)));
    assertNull(MinecraftConnection.proxiedSource(
        header(HAProxyProxiedProtocol.UDP6, "2001:db8::7", 4242)));
    assertNull(MinecraftConnection.proxiedSource(new HAProxyMessage(HAProxyProtocolVersion.V2,
        HAProxyCommand.LOCAL, HAProxyProxiedProtocol.UNKNOWN, null, null, 0, 0)));
  }
}
