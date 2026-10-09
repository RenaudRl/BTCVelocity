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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JournalAggregationTest {

  private final RecordingLogger log = new RecordingLogger();
  private final ManualClock clock = new ManualClock();
  private AdmissionJournal journal;

  private static InetAddress ip(final String literal) {
    return InetAddress.ofLiteral(literal);
  }

  @BeforeEach
  void journal() {
    this.journal = new AdmissionJournal(this.log.logger(), this.clock);
  }

  private void refuse(final String address) {
    this.journal.record(Attempt.claimed("Bot", ip(address)),
        new Verdict(Verdict.Action.REFUSE, Verdict.Basis.LISTED, Verdict.Origin.DIRECT,
            ip(address), IpClass.DATACENTER, "dc"),
        Phase.PRE_LOGIN, AdmissionConfig.Mode.ENFORCE);
  }

  @Test
  void aWaveFromOneSlash24IsOneLineAndOneCounter() {
    // Act - 5 refusals in a minute, from 5 addresses of one /24
    for (int host = 1; host <= 5; host++) {
      refuse("203.0.113." + host);
    }
    final int duringTheWindow = this.log.lines().size();
    this.clock.advance(Duration.ofSeconds(61));
    this.journal.flushExpired();

    // Assert
    assertEquals(1, duringTheWindow, "only the first one is written in full");
    assertEquals(2, this.log.lines().size());
    final String summary = this.log.lines().getLast();
    assertTrue(summary.contains("event=aggregate"), summary);
    assertTrue(summary.contains("net=203.0.113.0/24"), summary);
    assertTrue(summary.contains("suppressed=4"), summary);
    assertTrue(summary.contains("decision=refused"), summary);
    assertTrue(summary.contains("window_s=60"), summary);
  }

  @Test
  void anotherNetworkAndAnotherFamilyGrainAreCountedApart() {
    refuse("203.0.113.1");
    refuse("203.0.114.1");
    refuse("2001:db8:1:2::1");
    refuse("2001:db8:1:2::2");
    refuse("2001:db8:1:3::1");

    // /24 differs, then the same /64, then another /64: 4 full lines, one suppressed
    assertEquals(4, this.log.lines().size());
    this.clock.advance(Duration.ofSeconds(61));
    this.journal.flushExpired();
    assertTrue(this.log.lines().getLast().contains("net=2001:db8:1:2:0:0:0:0/64"));
  }

  @Test
  void aNewWindowWritesInFullAgainAndClosesTheOldOne() {
    refuse("203.0.113.1");
    refuse("203.0.113.2");
    this.clock.advance(Duration.ofSeconds(61));

    refuse("203.0.113.3");

    assertEquals(3, this.log.lines().size());
    assertTrue(this.log.lines().get(1).contains("suppressed=1"), this.log.lines().get(1));
    assertTrue(this.log.lines().get(2).contains("ip=203.0.113.3"));
  }

  @Test
  void aSuppressedLineIsStillRememberedForWhyAndAnExemptionIsNeverAggregated() {
    refuse("203.0.113.1");
    refuse("203.0.113.2");

    assertTrue(this.journal.lastForAddress(ip("203.0.113.2")).isPresent());
    for (int i = 0; i < 3; i++) {
      this.journal.record(Attempt.claimed("Staff", ip("203.0.113.9")),
          new Verdict(Verdict.Action.ADMIT, Verdict.Basis.EXEMPT_NETWORK, Verdict.Origin.DIRECT,
              ip("203.0.113.9"), IpClass.DATACENTER, "dc"),
          Phase.PRE_LOGIN, AdmissionConfig.Mode.ENFORCE);
    }
    assertEquals(4, this.log.lines().size(), "1 refusal in full + 3 exemptions");
  }

  @Test
  void aNetworkIsWrittenAtTheCoarserGrainAndIpv4MappedIsReadAsIpv4() {
    assertEquals("203.0.113.0/24", Networks.of(ip("203.0.113.77"), 24, 64));
    assertEquals("2001:db8:1:2:0:0:0:0/64", Networks.of(ip("2001:db8:1:2:3::5"), 24, 64));
    assertEquals("2001:db8:1:0:0:0:0:0/48", Networks.of(ip("2001:db8:1:2:3::5"), 24, 48));
    assertEquals("203.0.113.0/24", Networks.of(ip("::ffff:203.0.113.77"), 24, 48));
  }
}
