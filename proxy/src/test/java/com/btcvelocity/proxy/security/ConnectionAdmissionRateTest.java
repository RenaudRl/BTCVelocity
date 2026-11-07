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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.proxy.util.ratelimit.Ratelimiters;
import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConnectionAdmissionRateTest {

  private static InetAddress ip(final String literal) {
    return InetAddress.ofLiteral(literal);
  }

  private static ConnectionAdmission admission(final int limit, final List<String> exempt,
      final AdmissionStats stats) {
    return new ConnectionAdmission(HandshakeHosts.of(HandshakeHosts.Mode.OFF, List.of(), List.of()),
        Ratelimiters.createWindowed(limit, 60_000, 1000), TrustedProxies.parse(exempt), 0, stats);
  }

  @Test
  void aSourceOverTheLimitIsRefusedAndCounted() {
    final AdmissionStats stats = new AdmissionStats();
    final ConnectionAdmission admission = admission(2, List.of(), stats);

    assertTrue(admission.admitConnection(ip("203.0.113.9")));
    assertTrue(admission.admitConnection(ip("203.0.113.9")));
    assertFalse(admission.admitConnection(ip("203.0.113.9")));
    assertFalse(admission.admitConnection(ip("203.0.113.9")));

    assertEquals(2, stats.count(AdmissionStats.Result.RATE_LIMITED));
  }

  @Test
  void anotherSourceIsNotAffected() {
    final ConnectionAdmission admission = admission(1, List.of(), new AdmissionStats());

    assertTrue(admission.admitConnection(ip("203.0.113.9")));
    assertFalse(admission.admitConnection(ip("203.0.113.9")));
    assertTrue(admission.admitConnection(ip("203.0.113.10")));
  }

  @Test
  void anExemptSourceIsNeverLimitedNorCounted() {
    final AdmissionStats stats = new AdmissionStats();
    final ConnectionAdmission admission = admission(1, List.of("198.51.100.0/24"), stats);

    for (int i = 0; i < 50; i++) {
      assertTrue(admission.admitConnection(ip("198.51.100.7")));
    }
    assertEquals(0, stats.count(AdmissionStats.Result.RATE_LIMITED));
  }

  @Test
  void aLimitOfZeroIsOff() {
    final ConnectionAdmission admission = admission(0, List.of(), new AdmissionStats());

    for (int i = 0; i < 1000; i++) {
      assertTrue(admission.admitConnection(ip("203.0.113.9")));
    }
  }

  @Test
  void anIpv6SourceIsCountedAsItsSlash64() {
    final ConnectionAdmission admission = admission(1, List.of(), new AdmissionStats());

    assertTrue(admission.admitConnection(ip("2001:db8:1:2::1")));
    assertFalse(admission.admitConnection(ip("2001:db8:1:2:ffff:eeee:dddd:cccc")),
        "same /64: same subscriber");
    assertTrue(admission.admitConnection(ip("2001:db8:1:3::1")), "next /64");
  }

  @Test
  void anIpv4MappedAddressIsCountedAsTheIpv4Address() {
    final ConnectionAdmission admission = admission(1, List.of(), new AdmissionStats());

    assertTrue(admission.admitConnection(ip("203.0.113.9")));
    assertFalse(admission.admitConnection(ip("::ffff:203.0.113.9")));
  }

  @Test
  void theHostsOnlyConstructorLimitsNothingAndHasNoDeadline() {
    final ConnectionAdmission admission = new ConnectionAdmission(
        HandshakeHosts.of(HandshakeHosts.Mode.OFF, List.of(), List.of()), new AdmissionStats());

    assertTrue(admission.admitConnection(ip("203.0.113.9")));
    assertEquals(0, admission.handshakeTimeoutMillis());
  }
}
