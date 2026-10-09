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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ListQuarantineTest {

  private static final ListSource SOURCE = new ListSource("dc", IpClass.DATACENTER,
      URI.create("https://lists.invalid/dc.txt"), "dc.txt");
  private static final Duration HOLD = Duration.ofHours(6);
  private static final String OLD = "198.18.0.0/24\n198.18.1.0/24\n198.18.2.0/24\n198.18.3.0/24\n";
  private static final String NEW = OLD + "192.0.2.0/24\n";
  private static final UUID PLAYER = new UUID(0, 1);

  @TempDir
  Path directory;

  private final RecordingLogger log = new RecordingLogger();
  private final ManualClock clock = new ManualClock();
  private ListStore store;

  private static InetAddress ip(final String literal) {
    return InetAddress.ofLiteral(literal);
  }

  @BeforeEach
  void inUse() throws IOException {
    this.store = new ListStore(this.directory, this.log.logger(), this.clock,
        AdmissionConfig.DEFAULT_MAX_ENTRIES);
    Files.writeString(this.directory.resolve("dc.txt"), OLD);
    this.store.loadAll(List.of(SOURCE));
  }

  private ListRefresher refresherServing(final String body, final List<URI> requested) {
    return new ListRefresher(this.store, (url, destination) -> {
      requested.add(url);
      Files.writeString(destination, body);
    }, this.log.logger(), HOLD);
  }

  private Path candidate() {
    return this.directory.resolve("dc.txt.candidate");
  }

  @Test
  void aChangedVersionWaitsAndTheVersionInUseKeepsDeciding() throws IOException {
    // Act
    final boolean replaced = refresherServing(NEW, new ArrayList<>()).refresh(SOURCE);

    // Assert - nothing changed for the players, the new text waits next to the old
    assertFalse(replaced);
    assertTrue(this.store.quarantine().holds("dc"));
    assertTrue(this.store.classify(ip("192.0.2.5")).isEmpty(), "not refused while it waits");
    assertTrue(this.store.classify(ip("198.18.1.1")).isPresent());
    assertEquals(OLD, Files.readString(this.directory.resolve("dc.txt")));
    assertEquals(NEW, Files.readString(candidate()));
    assertTrue(this.store.quarantine().shadow().classify(ip("192.0.2.5")).isPresent(),
        "but the waiting version would refuse it");
  }

  @Test
  void aVersionNobodyWasRefusedByIsPromotedWhenTheTimeIsUp() throws IOException {
    refresherServing(NEW, new ArrayList<>()).refresh(SOURCE);

    this.clock.advance(HOLD.minusMinutes(1));
    assertEquals(List.of(), this.store.quarantine().settleDue(0), "too early");
    assertTrue(this.store.classify(ip("192.0.2.5")).isEmpty());
    this.clock.advance(Duration.ofMinutes(1));
    final List<ListQuarantine.Outcome> outcomes = this.store.quarantine().settleDue(0);

    assertEquals(1, outcomes.size());
    assertTrue(outcomes.getFirst().promoted());
    assertTrue(this.store.classify(ip("192.0.2.5")).isPresent());
    assertEquals(NEW, Files.readString(this.directory.resolve("dc.txt")));
    assertFalse(Files.exists(candidate()));
    assertFalse(this.store.quarantine().holds("dc"));
  }

  @Test
  void aVersionThatWouldHaveRefusedAPlayerInAdditionIsRejectedAndTheStaffIsTold()
      throws IOException {
    refresherServing(NEW, new ArrayList<>()).refresh(SOURCE);
    assertTrue(this.store.quarantine().noteWouldRefuse("dc", PLAYER));
    assertFalse(this.store.quarantine().noteWouldRefuse("dc", PLAYER), "counted once a player");

    this.clock.advance(HOLD);
    final List<ListQuarantine.Outcome> outcomes = this.store.quarantine().settleDue(0);

    assertEquals(1, outcomes.size());
    assertFalse(outcomes.getFirst().promoted());
    assertEquals(1, outcomes.getFirst().extraPlayers());
    assertTrue(outcomes.getFirst().staffMessage().contains("REJETÉE"));
    assertTrue(outcomes.getFirst().staffMessage().contains("dc"));
    assertTrue(this.log.has("ERROR", "REJECTED after quarantine"));
    assertTrue(this.store.classify(ip("192.0.2.5")).isEmpty(), "the old version stays");
    assertEquals(OLD, Files.readString(this.directory.resolve("dc.txt")));
    assertFalse(Files.exists(candidate()));
  }

  @Test
  void theLimitIsInclusive() {
    refresherServing(NEW, new ArrayList<>()).refresh(SOURCE);
    this.store.quarantine().noteWouldRefuse("dc", PLAYER);
    this.store.quarantine().noteWouldRefuse("dc", new UUID(0, 2));

    this.clock.advance(HOLD);

    assertTrue(this.store.quarantine().settleDue(2).getFirst().promoted());
  }

  @Test
  void aVersionInQuarantineIsNotDownloadedAgain() {
    final List<URI> requested = new ArrayList<>();
    final ListRefresher refresher = refresherServing(NEW, requested);

    refresher.refresh(SOURCE);
    final boolean again = refresher.refresh(SOURCE);

    assertFalse(again);
    assertEquals(1, requested.size());
  }

  @Test
  void anUnchangedDownloadIsNotQuarantined() {
    final boolean replaced = refresherServing(OLD, new ArrayList<>()).refresh(SOURCE);

    assertTrue(replaced);
    assertFalse(this.store.quarantine().holds("dc"));
  }

  @Test
  void aFirstVersionIsNeverHeldBecauseNothingIsInUseToKeepDeciding() throws IOException {
    final ListStore empty = new ListStore(this.directory.resolve("other"), this.log.logger(),
        this.clock, AdmissionConfig.DEFAULT_MAX_ENTRIES);
    final ListRefresher refresher = new ListRefresher(empty,
        (url, destination) -> Files.writeString(destination, NEW), this.log.logger(), HOLD);

    assertTrue(refresher.refresh(SOURCE));

    assertTrue(empty.classify(ip("192.0.2.5")).isPresent());
    assertFalse(empty.quarantine().holds("dc"));
  }

  @Test
  void aRestartForgetsTheQuarantineAndDeletesTheCandidateFile() throws IOException {
    final ListRefresher refresher = refresherServing(NEW, new ArrayList<>());
    refresher.refresh(SOURCE);

    final int purged = refresher.purgeLeftovers();

    assertEquals(1, purged);
    assertFalse(Files.exists(candidate()));
    assertTrue(Files.exists(this.directory.resolve("dc.txt")));
  }

  // --- the engine: who would be refused in addition -------------------------------------------

  private static final IpClassifier DC = address -> Optional.of(
      new IpClassifier.Match(IpClass.DATACENTER, "dc"));
  private static final IpClassifier NOTHING = address -> Optional.empty();

  private static AdmissionConfig config(final String yaml) {
    try {
      return AdmissionConfig.parseYaml(new StringReader(yaml), warning -> { });
    } catch (IOException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static Attempt player() {
    return Attempt.claimed("Steve", ip("203.0.113.9")).authenticated("Steve", PLAYER, false, null);
  }

  @Test
  void onlyWhatTheWaitingVersionAddsCountsAsRefusedInAddition() {
    final AdmissionConfig defaults = AdmissionConfig.defaults();

    assertTrue(new AdmissionEngine(defaults, NOTHING, DC).wouldRefuseInAddition(player())
        .isPresent());
    assertTrue(new AdmissionEngine(defaults, DC, DC).wouldRefuseInAddition(player()).isEmpty(),
        "already refused by the version in use");
    assertTrue(new AdmissionEngine(defaults, NOTHING, NOTHING).wouldRefuseInAddition(player())
        .isEmpty());
  }

  @Test
  void anExemptNetworkIsNotCountedEither() {
    final AdmissionConfig exempt = config(
        "exemptions:\n  networks:\n    - {network: 203.0.113.0/24, expires: 2099-12-31}\n");

    assertTrue(new AdmissionEngine(exempt, NOTHING, DC).wouldRefuseInAddition(player())
        .isEmpty());
  }

  @Test
  void wouldRefuseIsJournaledAsNothingMoreThanALine() {
    final AdmissionJournal journal = new AdmissionJournal(this.log.logger(), this.clock);
    final Verdict verdict = new AdmissionEngine(AdmissionConfig.defaults(), NOTHING, DC)
        .wouldRefuseInAddition(player()).orElseThrow();

    journal.recordWouldRefuse(player(), verdict, AdmissionConfig.Mode.ENFORCE);

    assertTrue(this.log.lines().getLast().contains("decision=would_refuse"));
    assertTrue(this.log.lines().getLast().contains("code=none"));
    assertTrue(journal.lastForName("Steve").isEmpty(), "it is not a decision");
  }

  @Test
  void theQuarantineSettingsDefaultToSixHoursAndZeroPlayersAndZeroHoursSwitchesItOff()
      throws IOException {
    final AdmissionConfig defaults = AdmissionConfig.defaults();
    final AdmissionConfig off = AdmissionConfig.parseYaml(new StringReader(
        "lists:\n  quarantine-hours: 0\n  quarantine-max-extra-players: 3\n"), warning -> { });

    assertEquals(Duration.ofHours(6), defaults.quarantine());
    assertEquals(0, defaults.quarantineMaxExtraPlayers());
    assertTrue(off.quarantine().isZero());
    assertEquals(3, off.quarantineMaxExtraPlayers());
  }
}
