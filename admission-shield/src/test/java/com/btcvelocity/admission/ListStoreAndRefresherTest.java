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
    this.store = new ListStore(this.directory, this.log.logger(), Clock.systemUTC(),
        AdmissionConfig.DEFAULT_MAX_ENTRIES);
  }

  private List<Path> leftovers() throws IOException {
    try (var files = Files.list(this.directory)) {
      return files.filter(file -> file.getFileName().toString().endsWith(".part")).toList();
    }
  }

  private static String slash24s(final int count) {
    final StringBuilder list = new StringBuilder();
    for (int i = 0; i < count; i++) {
      list.append("198.").append(i / 256).append('.').append(i % 256).append(".0/24\n");
    }
    return list.toString();
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
    assertEquals(List.of(), leftovers(), "no temporary file is left");
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
    assertEquals(List.of(), leftovers());
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

  // --- H4: a poisoned list cannot refuse the players ----------------------------------------

  @Test
  void aSlash0LineInADownloadedListClassifiesNothingBeyondTheRealEntries() {
    // Arrange - the review's case: one line upstream refused 100 % of the players
    this.store.loadAll(List.of(DATACENTER));

    // Act
    final boolean replaced = refresherServing("0.0.0.0/0\n::/0\n203.0.113.0/24\n2001:db8::/32\n",
        new ArrayList<>()).refresh(DATACENTER);

    // Assert
    assertTrue(replaced, "two bad lines among four do not sink the list");
    assertTrue(this.store.classify(ip("203.0.113.5")).isPresent());
    assertTrue(this.store.classify(ip("8.8.8.8")).isEmpty());
    assertTrue(this.store.classify(ip("2606:4700::1111")).isEmpty());
  }

  @Test
  void aListCoveringMoreThanAFifthOfIpv4IsRejectedAndThePreviousOneKept() throws IOException {
    // Arrange - 52 /8 networks are 20.3 % of the IPv4 space
    Files.writeString(this.directory.resolve("dc.txt"), "203.0.113.0/24\n");
    this.store.loadAll(List.of(DATACENTER));
    final StringBuilder wide = new StringBuilder();
    for (int first = 1; first <= 52; first++) {
      wide.append(first).append(".0.0.0/8\n");
    }

    // Act
    final boolean replaced = refresherServing(wide.toString(), new ArrayList<>())
        .refresh(DATACENTER);

    // Assert
    assertFalse(replaced);
    assertTrue(this.log.has("ERROR", "of the IPv4 space"));
    assertTrue(this.store.classify(ip("203.0.113.5")).isPresent(), "the previous list stays");
    assertTrue(this.store.classify(ip("20.0.0.1")).isEmpty());
    assertEquals("203.0.113.0/24\n", Files.readString(this.directory.resolve("dc.txt")));
  }

  @Test
  void aListAboveTheEntryCeilingIsNotLoadedNorDownloaded() throws IOException {
    // Arrange
    final ListStore small = new ListStore(this.directory, this.log.logger(), Clock.systemUTC(), 3);
    Files.writeString(this.directory.resolve("dc.txt"), slash24s(4));

    // Act
    small.loadAll(List.of(DATACENTER));
    final boolean replaced = new ListRefresher(small,
        (url, destination) -> Files.writeString(destination, slash24s(5)), this.log.logger())
        .refresh(DATACENTER);

    // Assert
    assertFalse(small.hasAnyList());
    assertFalse(replaced);
    assertTrue(this.log.has("ERROR", "above the ceiling of 3"));
  }

  @Test
  void anEntryCountThatMovesByMoreThanHalfIsRejectedAgainstThePreviousList() throws IOException {
    // Arrange - 100 entries in use
    Files.writeString(this.directory.resolve("dc.txt"), slash24s(100));
    this.store.loadAll(List.of(DATACENTER));

    // Act - 40 entries (-60 %), then 151 (+51 %), then 149 (+49 %: within the limit, but
    // coverage grows by 1.49, not 2)
    final boolean shrunk = refresherServing(slash24s(40), new ArrayList<>()).refresh(DATACENTER);
    final boolean grown = refresherServing(slash24s(151), new ArrayList<>()).refresh(DATACENTER);
    final boolean fine = refresherServing(slash24s(149), new ArrayList<>()).refresh(DATACENTER);

    // Assert
    assertFalse(shrunk);
    assertFalse(grown);
    assertTrue(this.log.has("ERROR", "entry count moved from 100 to 40"));
    assertTrue(fine);
  }

  @Test
  void aCoverageThatDoublesIsRejectedEvenWithTheSameEntryCount() throws IOException {
    // Arrange - 10 /24 networks in use
    Files.writeString(this.directory.resolve("dc.txt"), slash24s(10));
    this.store.loadAll(List.of(DATACENTER));
    final String wider = slash24s(9) + "203.0.0.0/16\n"; // same count, 265 /24s worth

    // Act
    final boolean replaced = refresherServing(wider, new ArrayList<>()).refresh(DATACENTER);

    // Assert
    assertFalse(replaced);
    assertTrue(this.log.has("ERROR", "IPv4 coverage doubled"));
    assertTrue(this.store.classify(ip("203.0.7.7")).isEmpty());
  }

  @Test
  void anIpv6LineAddedToAnIpv4OnlyListIsRejectedAndThePreviousOneKept() throws IOException {
    // Arrange - the review's case: 2a01::/16-sized poison in the Tor list, which has no IPv6
    Files.writeString(this.directory.resolve("tor.ipset"), slash24s(10));
    this.store.loadAll(List.of(TOR));

    // Act
    final boolean replaced = refresherServing(slash24s(10) + "2a01:e00::/26\n", new ArrayList<>())
        .refresh(TOR);

    // Assert
    assertFalse(replaced);
    assertTrue(this.log.has("ERROR", "IPv6 networks appear in a list that had none"));
    assertTrue(this.store.classify(ip("2a01:e00::1")).isEmpty());
  }

  @Test
  void privateAndSharedAddressesAreNeverClassifiedEvenWhenAListNamesThem() throws IOException {
    // Arrange - a list that (wrongly) holds the proxy's neighbours
    Files.writeString(this.directory.resolve("dc.txt"), "10.0.0.0/8\n172.16.0.0/12\n"
        + "192.168.0.0/16\n100.64.0.0/10\n127.0.0.0/8\n169.254.0.0/16\nfc00::/20\nfd00::/20\n"
        + "fe80::/20\n203.0.113.0/24\n");

    // Act
    this.store.loadAll(List.of(DATACENTER));

    // Assert
    for (String neighbour : List.of("10.1.2.3", "172.20.0.1", "192.168.1.10", "100.100.0.1",
        "127.0.0.1", "169.254.3.4", "fc00::1", "fd0a::1", "fe80::1", "::1", "::ffff:10.0.0.1")) {
      assertTrue(this.store.classify(ip(neighbour)).isEmpty(), neighbour + " was classified");
    }
    assertTrue(this.store.classify(ip("203.0.113.1")).isPresent());
  }

  @Test
  void tunnelledIpv6AddressesAreJudgedOnTheIpv4TheyCarry() throws IOException {
    // Arrange - 203.0.113.1 is cb00:7101
    Files.writeString(this.directory.resolve("dc.txt"), "203.0.113.0/24\n");
    this.store.loadAll(List.of(DATACENTER));

    // Act & Assert - 6to4, Teredo (client address XOR-ed), NAT64
    assertTrue(this.store.classify(ip("2002:cb00:7101::1")).isPresent(), "6to4");
    assertTrue(this.store.classify(ip("2001:0:4136:e378:8000:63bf:34ff:8efe")).isPresent(),
        "Teredo carrying ~34ff:8efe = 203.0.113.1");
    assertTrue(this.store.classify(ip("64:ff9b::cb00:7101")).isPresent(), "NAT64");
    assertTrue(this.store.classify(ip("2002:c633:6401::1")).isEmpty(), "6to4 of 198.51.100.1");
  }

  // --- M9: one refresh at a time, one temporary file per attempt ------------------------------

  @Test
  void aSecondRefreshWhileOneIsRunningIsSkipped() throws Exception {
    // Arrange - the first download blocks until released
    final java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
    final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    final List<Path> destinations = new java.util.concurrent.CopyOnWriteArrayList<>();
    final ListRefresher refresher = new ListRefresher(this.store, (url, destination) -> {
      destinations.add(destination);
      started.countDown();
      try {
        release.await();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
      Files.writeString(destination, "203.0.113.0/24\n");
    }, this.log.logger());
    final Thread first = new Thread(() -> refresher.refresh(DATACENTER));
    first.start();
    assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));

    // Act
    final boolean second = refresher.refresh(DATACENTER);
    final int all = refresher.refreshAll(List.of(DATACENTER, TOR));
    release.countDown();
    first.join(5000);

    // Assert
    assertFalse(second);
    assertEquals(0, all);
    assertEquals(1, destinations.size(), "only the first refresh downloaded anything");
    assertTrue(this.log.has("WARN", "still running"));
    assertTrue(this.store.classify(ip("203.0.113.5")).isPresent());
    assertTrue(refresher.refresh(DATACENTER), "once it is over, the next one runs");
  }

  @Test
  void eachAttemptDownloadsToItsOwnTemporaryFile() throws IOException {
    final List<Path> destinations = new ArrayList<>();
    final ListRefresher refresher = new ListRefresher(this.store, (url, destination) -> {
      destinations.add(destination);
      Files.writeString(destination, "203.0.113.0/24\n");
    }, this.log.logger());

    refresher.refresh(DATACENTER);
    refresher.refresh(DATACENTER);

    assertEquals(2, destinations.size());
    assertFalse(destinations.get(0).equals(destinations.get(1)));
    assertFalse(destinations.contains(this.directory.resolve("dc.txt")));
    assertEquals(List.of(), leftovers());
  }

  @Test
  void leftoverDownloadsOfAKilledRunArePurged() throws IOException {
    Files.writeString(this.directory.resolve("dc.txt.123.part"), "half");
    Files.writeString(this.directory.resolve("dc.txt"), "203.0.113.0/24\n");

    final int purged = refresherServing("", new ArrayList<>()).purgeLeftovers();

    assertEquals(1, purged);
    assertEquals(List.of(), leftovers());
    assertTrue(Files.exists(this.directory.resolve("dc.txt")));
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
