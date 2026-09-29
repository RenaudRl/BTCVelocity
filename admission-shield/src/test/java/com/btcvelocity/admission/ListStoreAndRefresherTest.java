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
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ListStoreAndRefresherTest {

  private static final ListSource DATACENTER = new ListSource("dc", IpClass.DATACENTER,
      URI.create("https://lists.invalid/dc.txt"), "dc.txt");
  private static final ListSource TOR = new ListSource("tor", IpClass.TOR,
      URI.create("https://lists.invalid/tor.ipset"), "tor.ipset");

  @TempDir
  Path directory;

  private final RecordingLogger log = new RecordingLogger();
  private ListStore store;

  private static InetAddress ip(final String literal) {
    return InetAddress.ofLiteral(literal);
  }

  @BeforeEach
  void createStore() {
    this.store = new ListStore(this.directory, this.log.logger(), Clock.systemUTC());
  }

  private ListRefresher refresherServing(final String body, final List<URI> requested) {
    return new ListRefresher(this.store, (url, destination) -> {
      requested.add(url);
      Files.writeString(destination, body);
    }, this.log.logger());
  }

  @Test
  void withoutAnyFileTheStoreIsOpenAndSaysSoInAWarning() {
    // Act
    this.store.loadAll(List.of(DATACENTER, TOR));

    // Assert - open: nothing is classified, and the operator is told which classes are unguarded
    assertFalse(this.store.hasAnyList());
    assertTrue(this.store.classify(ip("1.2.3.4")).isEmpty());
    assertTrue(this.log.has("WARN", "List 'dc' (datacenter) has no file yet"));
    assertTrue(this.log.has("WARN", "List 'tor' (tor) has no file yet"));
  }

  @Test
  void loadsFilesAndClassifiesByListInConfigurationOrder() throws IOException {
    // Arrange - the same address is in both lists; the first configured names it
    Files.writeString(this.directory.resolve("dc.txt"), "203.0.113.0/24\n2001:db8::/32\n");
    Files.writeString(this.directory.resolve("tor.ipset"), "# header\n203.0.113.9\n");

    // Act
    this.store.loadAll(List.of(DATACENTER, TOR));

    // Assert
    assertEquals(new IpClassifier.Match(IpClass.DATACENTER, "dc"),
        this.store.classify(ip("203.0.113.9")).orElseThrow());
    assertEquals(new IpClassifier.Match(IpClass.DATACENTER, "dc"),
        this.store.classify(ip("2001:db8::7")).orElseThrow());
    assertTrue(this.store.classify(ip("198.51.100.1")).isEmpty());
  }

  @Test
  void invalidLinesInALoadedFileAreLoggedAndCountedNotFatal() throws IOException {
    Files.writeString(this.directory.resolve("dc.txt"), "203.0.113.0/24\ngarbage\n");

    this.store.loadAll(List.of(DATACENTER));

    assertTrue(this.store.hasAnyList());
    assertEquals(1, this.store.snapshot().getFirst().invalidLines());
    assertTrue(this.log.has("WARN", "1 invalid lines ignored"));
    assertTrue(this.store.classify(ip("203.0.113.5")).isPresent());
  }

  @Test
  void aSuccessfulRefreshReplacesTheFileAndTheClassification() throws IOException {
    // Arrange - an old list is in place
    Files.writeString(this.directory.resolve("dc.txt"), "203.0.113.0/24\n");
    this.store.loadAll(List.of(DATACENTER));
    final List<URI> requested = new ArrayList<>();

    // Act
    final boolean replaced =
        refresherServing("198.51.100.0/24\n", requested).refresh(DATACENTER);

    // Assert
    assertTrue(replaced);
    assertEquals(List.of(DATACENTER.url()), requested, "only the public list URL is requested");
    assertTrue(this.store.classify(ip("198.51.100.20")).isPresent());
    assertTrue(this.store.classify(ip("203.0.113.5")).isEmpty(), "the new list replaced the old");
    assertEquals("198.51.100.0/24\n", Files.readString(this.directory.resolve("dc.txt")));
    assertFalse(Files.exists(this.directory.resolve("dc.txt.part")), "no temporary file is left");
  }

  @Test
  void aFailedDownloadKeepsTheLastValidListInFileAndMemoryAndLogsIt() throws IOException {
    // Arrange
    Files.writeString(this.directory.resolve("dc.txt"), "203.0.113.0/24\n");
    this.store.loadAll(List.of(DATACENTER));
    final ListRefresher refresher = new ListRefresher(this.store, (url, destination) -> {
      Files.writeString(destination, "partial");
      throw new IOException("connection reset");
    }, this.log.logger());

    // Act
    final boolean replaced = refresher.refresh(DATACENTER);

    // Assert
    assertFalse(replaced);
    assertEquals("203.0.113.0/24\n", Files.readString(this.directory.resolve("dc.txt")));
    assertTrue(this.store.classify(ip("203.0.113.5")).isPresent());
    assertTrue(this.log.has("ERROR", "connection reset"));
    assertFalse(Files.exists(this.directory.resolve("dc.txt.part")));
  }

  @Test
  void anHtmlErrorPageIsNotAListAndDoesNotReplaceOne() throws IOException {
    Files.writeString(this.directory.resolve("dc.txt"), "203.0.113.0/24\n");
    this.store.loadAll(List.of(DATACENTER));

    final boolean replaced = refresherServing(
        "<!DOCTYPE html>\n<html>\n<body>429 Too Many Requests</body>\n</html>\n", new ArrayList<>())
        .refresh(DATACENTER);

    assertFalse(replaced);
    assertEquals("203.0.113.0/24\n", Files.readString(this.directory.resolve("dc.txt")));
    assertTrue(this.log.has("ERROR", "was rejected"));
  }

  @Test
  void anEmptyDownloadDoesNotEraseTheList() throws IOException {
    Files.writeString(this.directory.resolve("dc.txt"), "203.0.113.0/24\n");
    this.store.loadAll(List.of(DATACENTER));

    assertFalse(refresherServing("# nothing here\n", new ArrayList<>()).refresh(DATACENTER));

    assertTrue(this.store.classify(ip("203.0.113.5")).isPresent());
  }

  @Test
  void aFirstDownloadOnAnEmptyDirectoryOpensTheClass() {
    // Arrange - startup without a file: open, then the first download arrives
    this.store.loadAll(List.of(DATACENTER));
    assertTrue(this.store.classify(ip("203.0.113.5")).isEmpty());

    // Act
    final boolean replaced = refresherServing("203.0.113.0/24\n", new ArrayList<>())
        .refresh(DATACENTER);

    // Assert
    assertTrue(replaced);
    assertTrue(this.store.classify(ip("203.0.113.5")).isPresent());
  }

  @Test
  void oneListFailingDoesNotStopTheOthers() {
    final ListRefresher refresher = new ListRefresher(this.store, (url, destination) -> {
      if (url.equals(DATACENTER.url())) {
        throw new IOException("host down");
      }
      Files.writeString(destination, "185.220.101.1\n");
    }, this.log.logger());

    final int replaced = refresher.refreshAll(List.of(DATACENTER, TOR));

    assertEquals(1, replaced);
    assertTrue(this.store.classify(ip("185.220.101.1")).isPresent());
  }

  @Test
  void aStaleOrMissingFileIsDueNowAndAFreshOneWaitsForTheRestOfTheInterval() throws IOException {
    // Arrange
    final Instant now = Instant.parse("2026-09-29T12:00:00Z");
    final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    final Duration interval = Duration.ofHours(24);
    final Path file = this.directory.resolve("dc.txt");

    // Act & Assert - missing
    assertEquals(Duration.ZERO,
        ListRefresher.delayBeforeFirstRefresh(this.directory, DATACENTER, interval, clock));

    // fresh: 6 h old, 18 h to wait
    Files.writeString(file, "203.0.113.0/24\n");
    Files.setLastModifiedTime(file,
        java.nio.file.attribute.FileTime.from(now.minus(Duration.ofHours(6))));
    assertEquals(Duration.ofHours(18),
        ListRefresher.delayBeforeFirstRefresh(this.directory, DATACENTER, interval, clock));

    // stale: 30 h old
    Files.setLastModifiedTime(file,
        java.nio.file.attribute.FileTime.from(now.minus(Duration.ofHours(30))));
    assertEquals(Duration.ZERO,
        ListRefresher.delayBeforeFirstRefresh(this.directory, DATACENTER, interval, clock));
  }
}
