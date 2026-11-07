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

import java.util.List;
import org.junit.jupiter.api.Test;

class ConnectionAdmissionHostTest {

  private static ConnectionAdmission admission(final HandshakeHosts.Mode mode,
      final AdmissionStats stats) {
    return new ConnectionAdmission(
        HandshakeHosts.of(mode, List.of("play.borntocraft.net"), List.of()), stats);
  }

  @Test
  void observeCountsTheUnlistedHostsAndNeverRefuses() {
    final AdmissionStats stats = new AdmissionStats();
    final ConnectionAdmission admission = admission(HandshakeHosts.Mode.OBSERVE, stats);

    assertEquals(HandshakeHosts.Verdict.OBSERVED, admission.checkHost("203.0.113.9", false));
    assertEquals(HandshakeHosts.Verdict.OBSERVED, admission.checkHost("203.0.113.9", false));
    assertEquals(HandshakeHosts.Verdict.ADMIT, admission.checkHost("play.borntocraft.net", false));

    assertEquals(2, stats.count(AdmissionStats.Result.HOST_OBSERVED));
    assertEquals(0, stats.count(AdmissionStats.Result.HOST_REFUSED));
  }

  @Test
  void enforceRefusesAndCounts() {
    final AdmissionStats stats = new AdmissionStats();
    final ConnectionAdmission admission = admission(HandshakeHosts.Mode.ENFORCE, stats);

    assertEquals(HandshakeHosts.Verdict.REFUSE, admission.checkHost("203.0.113.9", false));
    assertEquals(HandshakeHosts.Verdict.ADMIT, admission.checkHost("play.borntocraft.net", false));

    assertEquals(1, stats.count(AdmissionStats.Result.HOST_REFUSED));
    assertEquals(0, stats.count(AdmissionStats.Result.HOST_OBSERVED));
  }

  @Test
  void offCountsNothing() {
    final AdmissionStats stats = new AdmissionStats();

    assertEquals(HandshakeHosts.Verdict.ADMIT,
        admission(HandshakeHosts.Mode.OFF, stats).checkHost("203.0.113.9", false));

    assertEquals(0, stats.count(AdmissionStats.Result.HOST_OBSERVED));
    assertEquals(0, stats.count(AdmissionStats.Result.HOST_REFUSED));
  }

  @Test
  void anExemptConnectionIsNeitherJudgedNorCounted() {
    final AdmissionStats stats = new AdmissionStats();

    assertEquals(HandshakeHosts.Verdict.ADMIT,
        admission(HandshakeHosts.Mode.ENFORCE, stats).checkHost("127.0.0.1", true));

    assertEquals(0, stats.count(AdmissionStats.Result.HOST_REFUSED));
  }

  @Test
  void countersSurviveANewConfigurationThatSharesTheStats() {
    final AdmissionStats stats = new AdmissionStats();
    admission(HandshakeHosts.Mode.ENFORCE, stats).checkHost("203.0.113.9", false);
    admission(HandshakeHosts.Mode.ENFORCE, stats).checkHost("203.0.113.9", false);

    assertEquals(2, stats.count(AdmissionStats.Result.HOST_REFUSED));
  }

  @Test
  void aHostFromTheNetworkIsMadeSafeForTheLog() {
    assertEquals("evil?host", ConnectionAdmission.printable("evil\nhost\0FML\0"));
    assertEquals("a?b", ConnectionAdmission.printable("aéb"));
    assertEquals(64, ConnectionAdmission.printable("x".repeat(500)).length());
  }
}
