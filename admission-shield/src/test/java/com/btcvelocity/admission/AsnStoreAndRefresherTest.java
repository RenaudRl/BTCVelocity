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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The IP-to-ASN file on disk: loading, the guards, the last good version, the quarantine. */
class AsnStoreAndRefresherTest {

  private static final InetAddress ALPHA = InetAddress.ofLiteral("192.0.2.10");
  private static final InetAddress HOME = InetAddress.ofLiteral("198.51.100.77");
  private static final InetAddress PRIVATE = InetAddress.ofLiteral("10.0.0.1");

  private final RecordingLogger log = new RecordingLogger();
  private final ManualClock clock = new ManualClock();

  @TempDir
  Path directory;

  private static AsnSettings settings(final String asnSection) {
    try {
      return AdmissionConfig.parseYaml(new StringReader(asnSection), warning -> { }).asn();
    } catch (IOException malformed) {
      throw new IllegalStateException(malformed);
    }
  }

  /** {@code rows} rows of 256 addresses; the first belongs to AS 64496, the others to AS 64497. */
  private static String file(final int rows) {
    final StringBuilder text = new StringBuilder(
        "192.0.2.0\t192.0.2.255\t64496\tNL\tAlpha Hosting BV\n");
    for (int i = 1; i < rows; i++) {
      text.append("198.51.").append(99 + i).append(".0\t198.51.").append(99 + i)
          .append(".255\t64497\tFR\tHome Fibre SA\n");
    }
    return text.toString();
  }

  private static byte[] gzip(final String text) throws IOException {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
      gz.write(text.getBytes(StandardCharsets.UTF_8));
    }
    return out.toByteArray();
  }

  private AsnStore store(final AsnSettings settings) {
    return new AsnStore(this.directory, this.log.logger(), this.clock, settings, false);
  }

  private void writeFile(final String text) throws IOException {
    Files.write(this.directory.resolve(AsnSettings.FILE_NAME), gzip(text));
  }

  // --- the store -------------------------------------------------------------------------------

  @Test
  void withNoFileTheStoreKnowsNothingAndSaysSo() {
    final AsnStore store = store(AsnSettings.defaults());

    store.loadAtStart();

    assertFalse(store.isLoaded());
    assertEquals(Optional.empty(), store.lookup(ALPHA));
    assertTrue(this.log.has("WARN", "no file yet"), this.log.lines().toString());
  }

  @Test
  void aGoodFileIsLoadedAndAnswersLookups() throws IOException {
    writeFile(file(5));
    final AsnStore store = store(AsnSettings.defaults());

    store.loadAtStart();

    assertTrue(store.isLoaded());
    assertEquals(64496, store.lookup(ALPHA).orElseThrow().number());
    assertEquals("Alpha Hosting BV", store.lookup(ALPHA).orElseThrow().name());
    assertEquals(64497, store.lookup(HOME).orElseThrow().number());
    assertEquals(5, store.current().orElseThrow().validLines());
  }

  @Test
  void theStoreSaysWhichSystemsAnnounceMoreThanAShareAndFollowsTheDatabaseSwap()
      throws IOException {
    final String wide = "16.0.0.0\t19.255.255.255\t64500\tUS\tBig Hosting Inc\n";
    writeFile(wide + file(10));
    final AsnStore store = store(AsnSettings.defaults());
    assertFalse(store.coversMoreThan(64500, 1.0), "nothing loaded, nothing known");

    store.loadAtStart();

    assertTrue(store.coversMoreThan(64500, 1.0));
    assertFalse(store.coversMoreThan(64496, 1.0));
    assertFalse(store.coversMoreThan(64500, 2.0));

    writeFile(file(10));
    assertTrue(store.reload());
    assertFalse(store.coversMoreThan(64500, 1.0), "the new database does not know the system");
  }

  private static AsnStore.Loaded version(final long number, final String name) {
    final AsnDatabase.Builder builder = AsnDatabase.builder();
    builder.addIpv4(0xC0000200L, 0xC00002FFL, number, "NL", name);
    return new AsnStore.Loaded(builder.build(), 1, 0, java.time.Instant.EPOCH);
  }

  @Test
  void readsDuringADatabaseSwapAlwaysSeeOneWholeVersion() throws Exception {
    final AsnStore.Loaded first = version(64496, "Alpha Hosting BV");
    final AsnStore.Loaded second = version(64999, "Beta Version SA");
    final AsnStore store = store(AsnSettings.defaults());
    store.install(first);
    final int readers = 4;
    final int swaps = 20_000;
    final java.util.concurrent.atomic.AtomicBoolean swapping =
        new java.util.concurrent.atomic.AtomicBoolean(true);
    final java.util.concurrent.atomic.AtomicLong lookups =
        new java.util.concurrent.atomic.AtomicLong();
    final java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(readers);
    // Swaps wait for every reader: a slow pool start would otherwise leave nothing to test.
    final java.util.concurrent.CountDownLatch started =
        new java.util.concurrent.CountDownLatch(readers);
    final List<java.util.concurrent.Future<String>> verdicts = new ArrayList<>();
    try {
      for (int i = 0; i < readers; i++) {
        verdicts.add(pool.submit(() -> {
          boolean counted = false;
          while (swapping.get()) {
            final Optional<AsnDatabase.Info> info = store.lookup(ALPHA);
            lookups.incrementAndGet();
            if (!counted) {
              counted = true;
              started.countDown();
            }
            if (info.isEmpty()) {
              return "a lookup found nothing in the middle of a swap";
            }
            final boolean whole = info.get().number() == 64496
                && "Alpha Hosting BV".equals(info.get().name())
                || info.get().number() == 64999 && "Beta Version SA".equals(info.get().name());
            if (!whole) {
              return "a lookup mixed two versions: " + info.get();
            }
            if (store.coversMoreThan(64496, 1.0) && store.coversMoreThan(64999, 1.0)) {
              return "the share answers came from two versions at once";
            }
          }
          return "";
        }));
      }
      assertTrue(started.await(30, java.util.concurrent.TimeUnit.SECONDS),
          "every reader ran a first lookup");
      for (int i = 0; i < swaps; i++) {
        store.install(i % 2 == 0 ? second : first);
      }
    } finally {
      swapping.set(false);
      pool.shutdown();
    }
    for (java.util.concurrent.Future<String> verdict : verdicts) {
      assertEquals("", verdict.get(30, java.util.concurrent.TimeUnit.SECONDS));
    }
    assertTrue(pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS));
    assertTrue(lookups.get() > 0, "the readers did run while the swaps happened");
  }

  @Test
  void aNonPublicAddressIsNeverGivenASystem() throws IOException {
    writeFile("10.0.0.0\t10.0.0.255\t64496\tNL\tPrivately announced\n");
    final AsnStore store = store(AsnSettings.defaults());

    store.loadAtStart();

    assertTrue(store.isLoaded());
    assertEquals(Optional.empty(), store.lookup(PRIVATE));
  }

  @Test
  void aFileThatIsNotGzipLeavesTheStoreOpen() throws IOException {
    Files.writeString(this.directory.resolve(AsnSettings.FILE_NAME), "<html>oops</html>");
    final AsnStore store = store(AsnSettings.defaults());

    store.loadAtStart();

    assertFalse(store.isLoaded());
    assertTrue(this.log.has("ERROR", "could not be read"), this.log.lines().toString());
  }

  @Test
  void aFirstLoadFarFromTheRealFileIsRejectedByTheEmbeddedBaseline() throws IOException {
    writeFile(file(5));
    final AsnStore withBaseline =
        new AsnStore(this.directory, this.log.logger(), this.clock, AsnSettings.defaults(), true);

    withBaseline.loadAtStart();

    assertFalse(withBaseline.isLoaded());
    assertTrue(this.log.has("ERROR", "embedded baseline"), this.log.lines().toString());
  }

  @Test
  void aReloadThatMovesTheRowCountByMoreThanHalfKeepsThePreviousVersion() throws IOException {
    writeFile(file(10));
    final AsnStore store = store(AsnSettings.defaults());
    store.loadAtStart();

    writeFile(file(30));
    final boolean replaced = store.reload();

    assertFalse(replaced);
    assertEquals(10, store.current().orElseThrow().validLines());
    assertTrue(this.log.has("ERROR", "row count moved"), this.log.lines().toString());
  }

  @Test
  void aReloadWithinTheBoundsReplacesTheVersion() throws IOException {
    writeFile(file(10));
    final AsnStore store = store(AsnSettings.defaults());
    store.loadAtStart();

    writeFile(file(12));

    assertTrue(store.reload());
    assertEquals(12, store.current().orElseThrow().validLines());
  }

  // --- the refresher ----------------------------------------------------------------------------

  private AsnRefresher refresher(final AsnStore store, final ListFetcher fetcher,
      final Duration quarantine) {
    return new AsnRefresher(store, fetcher, this.log.logger(), quarantine);
  }

  private static ListFetcher serving(final String text) {
    return (url, destination) -> Files.write(destination, gzip(text));
  }

  @Test
  void aFirstDownloadIsPutInUseAtOnceEvenWithAQuarantine() throws IOException {
    final AsnStore store = store(settings("asn:\n  deny-asns: [64496]\n"));

    final boolean replaced =
        refresher(store, serving(file(10)), Duration.ofHours(6)).refresh();

    assertTrue(replaced);
    assertTrue(store.isLoaded());
    assertFalse(store.holds(), "there is nothing in use to keep deciding meanwhile");
    assertTrue(Files.exists(store.file()));
  }

  @Test
  void aFailedDownloadKeepsTheLastGoodVersionInMemoryAndOnDisk() throws IOException {
    writeFile(file(10));
    final AsnStore store = store(AsnSettings.defaults());
    store.loadAtStart();
    final byte[] before = Files.readAllBytes(store.file());

    final boolean replaced = refresher(store, (url, destination) -> {
      throw new IOException("HTTP 503");
    }, Duration.ZERO).refresh();

    assertFalse(replaced);
    assertEquals(10, store.current().orElseThrow().validLines());
    assertEquals(before.length, Files.readAllBytes(store.file()).length);
    assertTrue(this.log.has("ERROR", "could not be refreshed"), this.log.lines().toString());
  }

  @Test
  void anOutOfMemoryDuringARefreshKeepsThePreviousVersionAndTheNextRefreshStillRuns()
      throws IOException {
    writeFile(file(10));
    final AsnStore store = store(AsnSettings.defaults());
    store.loadAtStart();
    final boolean[] exhausted = {true};
    final AsnRefresher refresher = refresher(store, (url, destination) -> {
      if (exhausted[0]) {
        throw new OutOfMemoryError("Java heap space");
      }
      Files.write(destination, gzip(file(12)));
    }, Duration.ZERO);

    final boolean replaced = refresher.refresh();

    assertFalse(replaced);
    assertEquals(10, store.current().orElseThrow().validLines());
    assertTrue(this.log.has("ERROR", "could not be refreshed"), this.log.lines().toString());
    try (var leftovers = Files.newDirectoryStream(this.directory, "*.part")) {
      assertFalse(leftovers.iterator().hasNext());
    }
    exhausted[0] = false;
    assertTrue(refresher.refresh(), "the refresh is not left marked as running");
    assertEquals(12, store.current().orElseThrow().validLines());
  }

  @Test
  void anErrorPageIsThrownAwayAndNoTemporaryFileStaysBehind() throws IOException {
    writeFile(file(10));
    final AsnStore store = store(AsnSettings.defaults());
    store.loadAtStart();

    final boolean replaced = refresher(store,
        (url, destination) -> Files.writeString(destination, "<html>502</html>"),
        Duration.ZERO).refresh();

    assertFalse(replaced);
    assertEquals(10, store.current().orElseThrow().validLines());
    try (var leftovers = Files.newDirectoryStream(this.directory, "*.part")) {
      assertFalse(leftovers.iterator().hasNext());
    }
  }

  @Test
  void aFileThatMovedTooMuchIsRejectedAndTheOldOneStays() throws IOException {
    writeFile(file(10));
    final AsnStore store = store(AsnSettings.defaults());
    store.loadAtStart();

    final boolean replaced =
        refresher(store, serving(file(40)), Duration.ZERO).refresh();

    assertFalse(replaced);
    assertEquals(10, store.current().orElseThrow().validLines());
    assertTrue(this.log.has("ERROR", "rejected"), this.log.lines().toString());
  }

  @Test
  void withNothingDeniedANewVersionIsPutInUseAtOnceWhateverTheQuarantine() throws IOException {
    writeFile(file(10));
    final AsnStore store = store(AsnSettings.defaults());
    store.loadAtStart();

    final boolean replaced =
        refresher(store, serving(file(12)), Duration.ofHours(6)).refresh();

    assertTrue(replaced);
    assertEquals(12, store.current().orElseThrow().validLines());
    assertFalse(store.holds());
  }

  @Test
  void withASystemDeniedAChangedVersionWaitsInQuarantineNextToTheOneInUse() throws IOException {
    writeFile(file(10));
    final AsnStore store = store(settings("asn:\n  deny-asns: [64496]\n"));
    store.loadAtStart();

    final boolean replaced =
        refresher(store, serving(file(12)), Duration.ofHours(6)).refresh();

    assertFalse(replaced);
    assertTrue(store.holds());
    assertEquals(10, store.current().orElseThrow().validLines(), "the version in use decides");
    assertTrue(Files.exists(this.directory.resolve(
        AsnSettings.FILE_NAME + AsnStore.CANDIDATE_SUFFIX)));
    assertEquals(12, store.waitingStatus().orElseThrow().pending().loaded().validLines());
  }

  @Test
  void whileAVersionWaitsNothingIsDownloadedAgain() throws IOException {
    writeFile(file(10));
    final AsnStore store = store(settings("asn:\n  deny-asns: [64496]\n"));
    store.loadAtStart();
    final List<String> downloads = new ArrayList<>();
    final AsnRefresher refresher = refresher(store, (url, destination) -> {
      downloads.add(url.toString());
      Files.write(destination, gzip(file(12)));
    }, Duration.ofHours(6));

    refresher.refresh();
    refresher.refresh();

    assertEquals(1, downloads.size());
  }

  // --- systems named by a keyword ---------------------------------------------------------------

  private AsnStore storeGuardingKeywords(final boolean guard) {
    return new AsnStore(this.directory, this.log.logger(), this.clock,
        settings("asn:\n  observe-keywords: [hosting]\n"), false, guard);
  }

  @Test
  void aKeywordSystemStretchedOverTheInternetIsRefusedOnlyWhenTheBoundGroupsBySystem() {
    final AsnParser.Result world = AsnParser.parse(
        "16.0.0.0\t127.255.255.255\t64500\tUS\tBig Hosting Inc\n" + file(10), 1_000_000);

    final String guarded = storeGuardingKeywords(true).rejectionOf(world);
    final String notGuarded = storeGuardingKeywords(false).rejectionOf(world);

    assertNotNull(guarded);
    assertTrue(guarded.contains("IPv4"), guarded);
    assertNull(notGuarded, "nothing groups by system, so no bucket can swallow the internet");
  }

  @Test
  void aKeywordSystemWhoseCoverageDoubledIsRefusedAgainstTheVersionInUse() throws IOException {
    final String doubled = "192.0.2.0\t192.0.3.255\t64496\tNL\tAlpha Hosting BV\n"
        + file(10).substring(file(10).indexOf("198.51."));
    for (boolean guard : new boolean[] {true, false}) {
      writeFile(file(10));
      final AsnStore store = storeGuardingKeywords(guard);
      store.loadAtStart();

      final String why = store.rejectionOf(AsnParser.parse(doubled, 1_000_000));

      if (guard) {
        assertNotNull(why);
        assertTrue(why.contains("doubled"), why);
      } else {
        assertNull(why);
      }
    }
  }

  @Test
  void aChangedVersionWaitsInQuarantineWhenKeywordSystemsAreGuardedEvenWithNothingDenied()
      throws IOException {
    writeFile(file(10));
    final AsnStore guarded = storeGuardingKeywords(true);
    guarded.loadAtStart();

    refresher(guarded, serving(file(12)), Duration.ofHours(6)).refresh();

    assertTrue(guarded.holds());
    assertEquals(10, guarded.current().orElseThrow().validLines());
  }

  @Test
  void aChangedVersionIsPutInUseAtOnceWhenKeywordsOnlyObserve() throws IOException {
    writeFile(file(10));
    final AsnStore observing = storeGuardingKeywords(false);
    observing.loadAtStart();

    refresher(observing, serving(file(12)), Duration.ofHours(6)).refresh();

    assertFalse(observing.holds());
    assertEquals(12, observing.current().orElseThrow().validLines());
  }

  @Test
  void aFileIdenticalToTheOneInUseIsNotQuarantined() throws IOException {
    writeFile(file(10));
    final AsnStore store = store(settings("asn:\n  deny-asns: [64496]\n"));
    store.loadAtStart();

    refresher(store, serving(file(10)), Duration.ofHours(6)).refresh();

    assertFalse(store.holds());
  }

  // --- the quarantine -------------------------------------------------------------------------

  private AsnStore storeWithAVersionWaiting() throws IOException {
    writeFile(file(10));
    final AsnStore store = store(settings("asn:\n  deny-asns: [64497]\n"));
    store.loadAtStart();
    refresher(store, serving(file(12)), Duration.ofHours(6)).refresh();
    return store;
  }

  @Test
  void theShadowAnswersFromTheWaitingVersionOnly() throws IOException {
    final AsnStore store = storeWithAVersionWaiting();

    final InetAddress onlyInTheNewVersion = InetAddress.ofLiteral("198.51.110.5");
    assertTrue(store.shadow().lookup(onlyInTheNewVersion).isPresent());
    assertEquals(Optional.empty(), store.lookup(onlyInTheNewVersion));
    assertTrue(store.lookup(HOME).isPresent());
    final AsnStore alone = store(AsnSettings.defaults());
    assertEquals(Optional.empty(), alone.shadow().lookup(HOME), "empty while nothing waits");
  }

  @Test
  void aPlayerIsCountedOncePerWaitingVersion() throws IOException {
    final AsnStore store = storeWithAVersionWaiting();
    final UUID player = UUID.randomUUID();

    assertTrue(store.noteWouldRefuse(player));
    assertFalse(store.noteWouldRefuse(player));
    assertEquals(1, store.waitingStatus().orElseThrow().extraPlayers());
  }

  @Test
  void aVersionIsNotSettledBeforeItsTime() throws IOException {
    final AsnStore store = storeWithAVersionWaiting();

    assertTrue(store.settleDue(0).isEmpty());
    assertTrue(store.holds());
  }

  @Test
  void aVersionThatWouldRefuseNobodyInAdditionIsPromotedWhenItsTimeIsUp() throws IOException {
    final AsnStore store = storeWithAVersionWaiting();
    this.clock.advance(Duration.ofHours(7));

    final ListQuarantine.Outcome outcome = store.settleDue(0).orElseThrow();

    assertTrue(outcome.promoted());
    assertEquals(12, store.current().orElseThrow().validLines());
    assertFalse(store.holds());
    assertFalse(Files.exists(this.directory.resolve(
        AsnSettings.FILE_NAME + AsnStore.CANDIDATE_SUFFIX)), "moved into place");
    store.reload();
    assertEquals(12, store.current().orElseThrow().validLines(), "and it is the file in use");
  }

  @Test
  void aVersionThatWouldRefuseTooManyPlayersIsRejectedAndTheStaffIsTold() throws IOException {
    final AsnStore store = storeWithAVersionWaiting();
    store.noteWouldRefuse(UUID.randomUUID());
    this.clock.advance(Duration.ofHours(7));

    final ListQuarantine.Outcome outcome = store.settleDue(0).orElseThrow();

    assertFalse(outcome.promoted());
    assertEquals(1, outcome.extraPlayers());
    assertTrue(outcome.staffMessage().contains("iptoasn"), outcome.staffMessage());
    assertTrue(outcome.staffMessage().contains("REJETÉE"), outcome.staffMessage());
    assertEquals(10, store.current().orElseThrow().validLines(), "the old version stays");
    assertFalse(Files.exists(this.directory.resolve(
        AsnSettings.FILE_NAME + AsnStore.CANDIDATE_SUFFIX)), "the candidate is deleted");
  }

  @Test
  void theLeftoversOfAKilledRunAreSweptWithTheSuffixesOfTheLists() throws IOException {
    Files.writeString(this.directory.resolve(AsnSettings.FILE_NAME + ".abc.part"), "x");
    Files.writeString(this.directory.resolve(
        AsnSettings.FILE_NAME + AsnStore.CANDIDATE_SUFFIX), "x");
    final ListStore lists = new ListStore(this.directory, this.log.logger(), this.clock, 1000);

    final int deleted = new ListRefresher(lists, (url, destination) -> { }, this.log.logger())
        .purgeLeftovers();

    assertEquals(2, deleted);
  }
}
