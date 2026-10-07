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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StaffAlertsTest {

  /** A clock the test moves by hand. */
  private static final class ManualClock extends Clock {
    private Instant now = Instant.parse("2026-09-29T20:00:00Z");

    void advance(final Duration by) {
      this.now = this.now.plus(by);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(final ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return this.now;
    }
  }

  private static AdmissionJournal.Entry refusal(final String name, final Instant at) {
    final Verdict verdict = new Verdict(Verdict.Action.REFUSE, Verdict.Basis.LISTED,
        Verdict.Origin.DIRECT, InetAddress.ofLiteral("203.0.113.50"), IpClass.DATACENTER, "dc");
    return new AdmissionJournal.Entry(at, name, null, verdict, "refused", "ADM-VPN-DC",
        Phase.PRE_LOGIN, AdmissionConfig.Mode.ENFORCE);
  }

  @Test
  void theFirstRefusalIsToldAtOnceAndAWaveBecomesOneMessagePerWindow() {
    // Arrange
    final ManualClock clock = new ManualClock();
    final List<String> told = new ArrayList<>();
    final StaffAlerts alerts = new StaffAlerts(clock, told::add);

    // Act - one refusal, then 50 more within the window
    alerts.onRefusal(refusal("First", clock.instant()));
    for (int i = 0; i < 50; i++) {
      clock.advance(Duration.ofMillis(100));
      alerts.onRefusal(refusal("Bot" + i, clock.instant()));
      alerts.flushIfDue();
    }
    final int duringTheWave = told.size();
    clock.advance(StaffAlerts.WINDOW);
    alerts.flushIfDue();
    alerts.flushIfDue();

    // Assert
    assertEquals(1, duringTheWave, "nothing more than the first message within the window");
    assertEquals(2, told.size());
    assertTrue(told.get(0).contains("First"), told.get(0));
    assertTrue(told.get(0).contains("ADM-VPN-DC"), told.get(0));
    assertTrue(told.get(1).contains("50 refus"), told.get(1));
    assertTrue(told.get(1).contains("Bot49"), "the latest one is the example: " + told.get(1));
  }

  @Test
  void nothingIsToldWithoutARefusal() {
    final ManualClock clock = new ManualClock();
    final List<String> told = new ArrayList<>();
    final StaffAlerts alerts = new StaffAlerts(clock, told::add);

    clock.advance(Duration.ofMinutes(5));
    alerts.flushIfDue();

    assertEquals(List.of(), told);
  }

  @Test
  void theStaffSeesTheNetworkNotThePlayersOwnAddress() {
    final ManualClock clock = new ManualClock();
    final List<String> told = new ArrayList<>();

    new StaffAlerts(clock, told::add).onRefusal(refusal("Bot", clock.instant()));

    assertTrue(told.getFirst().contains("203.0.113.0/24"), told.getFirst());
    assertTrue(!told.getFirst().contains("203.0.113.50"), told.getFirst());
  }

  @Test
  void aSectionSignInANameIsWrittenOutNotLeftForTheClientToInterpret() {
    final ManualClock clock = new ManualClock();
    final List<String> told = new ArrayList<>();

    new StaffAlerts(clock, told::add).onRefusal(refusal("§cStaff§kX", clock.instant()));

    assertTrue(!told.getFirst().contains("§"), told.getFirst());
    assertTrue(told.getFirst().contains("\\u00A7cStaff"), told.getFirst());
  }

  @Test
  void aHostileNameReachesTheStaffEscaped() {
    final ManualClock clock = new ManualClock();
    final List<String> told = new ArrayList<>();

    new StaffAlerts(clock, told::add).onRefusal(refusal("x‮evil", clock.instant())); // RLO

    assertTrue(told.getFirst().codePoints().noneMatch(c -> c == 0x202E), told.getFirst());
    assertTrue(told.getFirst().contains("declared"), "a pre-login name is only declared");
  }
}
