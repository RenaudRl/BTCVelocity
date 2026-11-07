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

import static com.btcvelocity.proxy.security.HandshakeHosts.Verdict.ADMIT;
import static com.btcvelocity.proxy.security.HandshakeHosts.Verdict.OBSERVED;
import static com.btcvelocity.proxy.security.HandshakeHosts.Verdict.REFUSE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class HandshakeHostsTest {

  private static HandshakeHosts of(final HandshakeHosts.Mode mode) {
    return HandshakeHosts.of(mode, List.of("play.borntocraft.net", "51.38.1.2"),
        List.of("lobby.borntocraft.net"));
  }

  @Test
  void offAdmitsEveryHostWithoutLookingAtTheList() {
    final HandshakeHosts hosts = of(HandshakeHosts.Mode.OFF);

    assertEquals(ADMIT, hosts.check("203.0.113.9"));
    assertEquals(ADMIT, hosts.check("play.borntocraft.net"));
  }

  @Test
  void observeLetsAnUnlistedHostThroughButSaysSo() {
    final HandshakeHosts hosts = of(HandshakeHosts.Mode.OBSERVE);

    assertEquals(OBSERVED, hosts.check("203.0.113.9"));
    assertEquals(ADMIT, hosts.check("play.borntocraft.net"));
  }

  @Test
  void enforceRefusesAnUnlistedHostAndAdmitsAListedOne() {
    final HandshakeHosts hosts = of(HandshakeHosts.Mode.ENFORCE);

    assertEquals(REFUSE, hosts.check("203.0.113.9"));
    assertEquals(REFUSE, hosts.check(""));
    assertEquals(ADMIT, hosts.check("play.borntocraft.net"));
    assertEquals(ADMIT, hosts.check("51.38.1.2"), "an address listed on purpose (staff, health check)");
  }

  @Test
  void aForcedHostIsAllowedWithoutBeingListed() {
    assertEquals(ADMIT, of(HandshakeHosts.Mode.ENFORCE).check("lobby.borntocraft.net"));
  }

  @Test
  void theForgeMarkerAndTheBungeeTailAreIgnored() {
    final HandshakeHosts hosts = of(HandshakeHosts.Mode.ENFORCE);

    assertEquals(ADMIT, hosts.check("play.borntocraft.net\0FML\0"), "legacy Forge");
    assertEquals(ADMIT, hosts.check("play.borntocraft.net\0FML2\0"), "Forge 1.13+");
    assertEquals(ADMIT, hosts.check("play.borntocraft.net\0FORGE"), "modern Forge");
    assertEquals(ADMIT, hosts.check("play.borntocraft.net\0" + "203.0.113.9\0uuid"), "BungeeCord tail");
    assertEquals(REFUSE, hosts.check("203.0.113.9\0FML\0"), "the marker does not make a host valid");
    assertEquals(REFUSE, hosts.check("\0play.borntocraft.net"), "the host is what comes before the NUL");
  }

  @Test
  void theDnsRootDotAndTheCaseAreIgnored() {
    final HandshakeHosts hosts = of(HandshakeHosts.Mode.ENFORCE);

    assertEquals(ADMIT, hosts.check("play.borntocraft.net."));
    assertEquals(ADMIT, hosts.check("Play.BornToCraft.NET"));
    assertEquals(ADMIT, hosts.check("PLAY.borntocraft.net.\0FML\0"));
    assertEquals(REFUSE, hosts.check("."));
    assertEquals(REFUSE, hosts.check("xplay.borntocraft.net"), "no suffix match");
    assertEquals(REFUSE, hosts.check("play.borntocraft.net.evil.example"));
  }

  @Test
  void aConfiguredNameIsNormalizedLikeAnIncomingOne() {
    final HandshakeHosts hosts = HandshakeHosts.of(HandshakeHosts.Mode.ENFORCE,
        List.of(" Play.BornToCraft.net. "), List.of());

    assertEquals(ADMIT, hosts.check("play.borntocraft.net"));
  }

  @Test
  void anEmptyListIsReportedSoThatEnforceCanBeRefusedAtStartup() {
    assertTrue(HandshakeHosts.of(HandshakeHosts.Mode.ENFORCE, List.of(), List.of()).isEmpty());
    assertFalse(of(HandshakeHosts.Mode.ENFORCE).isEmpty());
    assertFalse(HandshakeHosts.of(HandshakeHosts.Mode.ENFORCE, List.of(), List.of("a.b")).isEmpty());
  }

  @Test
  void theModeIsReadInAnyCaseAndAnUnknownValueIsNull() {
    assertEquals(HandshakeHosts.Mode.OBSERVE, HandshakeHosts.Mode.parse("Observe"));
    assertEquals(HandshakeHosts.Mode.ENFORCE, HandshakeHosts.Mode.parse(" ENFORCE "));
    assertEquals(HandshakeHosts.Mode.OFF, HandshakeHosts.Mode.parse("off"));
    assertNull(HandshakeHosts.Mode.parse("block"));
    assertNull(HandshakeHosts.Mode.parse(""));
    assertNull(HandshakeHosts.Mode.parse(null));
  }
}
