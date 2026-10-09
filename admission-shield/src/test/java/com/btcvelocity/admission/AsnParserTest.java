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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reading the iptoasn.com file: the good lines, the bad ones, and the bounds. */
class AsnParserTest {

  private static final String SAMPLE = String.join("\n",
      "0.0.0.0\t0.255.255.255\t0\tNone\tNot routed",
      "1.0.0.0\t1.0.0.255\t13335\tUS\tCLOUDFLARENET",
      "1.0.1.0\t1.0.3.255\t0\tNone\tNot routed",
      "200.0.0.0\t200.0.0.255\t4200000000\tBR\tBIG-AS-NAME",
      "2001:db8::\t2001:db8:ffff:ffff:ffff:ffff:ffff:ffff\t64496\tNL\tV6-NET",
      "") + "\n";

  private static InetAddress address(final String literal) {
    return InetAddress.ofLiteral(literal);
  }

  private static byte[] gzip(final String text) throws IOException {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
      gz.write(text.getBytes(StandardCharsets.UTF_8));
    }
    return out.toByteArray();
  }

  @Test
  void aWellFormedFileFillsBothFamilies() {
    final AsnParser.Result result = AsnParser.parse(SAMPLE, 1000);

    assertEquals(5, result.validLines(), "unrouted lines are valid lines");
    assertEquals(0, result.invalidLines());
    assertFalse(result.truncated());
    final AsnDatabase database = result.database();
    assertEquals(13335, database.lookup(address("1.0.0.9")).orElseThrow().number());
    assertEquals("CLOUDFLARENET", database.lookup(address("1.0.0.9")).orElseThrow().name());
    assertEquals("US", database.lookup(address("1.0.0.9")).orElseThrow().country());
    assertEquals(4_200_000_000L, database.lookup(address("200.0.0.1")).orElseThrow().number());
    assertEquals(64496, database.lookup(address("2001:db8::77")).orElseThrow().number());
    assertTrue(database.lookup(address("0.1.2.3")).isEmpty(), "unrouted");
    assertTrue(database.lookup(address("1.0.2.0")).isEmpty(), "unrouted");
  }

  @Test
  void theNoneCountryBecomesEmptyAndCarriageReturnsAreHarmless() {
    final AsnParser.Result result = AsnParser.parse(
        "5.0.0.0\t5.0.0.255\t64496\tNone\tNo country\r\n", 10);

    assertEquals(1, result.validLines());
    assertEquals("", result.database().lookup(address("5.0.0.1")).orElseThrow().country());
    assertEquals("No country", result.database().lookup(address("5.0.0.1")).orElseThrow().name());
  }

  @Test
  void aBadLineIsCountedAndSampledAndTheRestStillLoads() {
    final String text = "1.0.0.0\t1.0.0.255\t13335\tUS\tGood\n"
        + "garbage without tabs\n"
        + "1.0.1.0\t1.0.1.255\tnotanumber\tUS\tBad AS\n"
        + "1.0.2.0\t1.0.2.999\t64496\tUS\tBad address\n"
        + "1.0.4.0\t1.0.4.255\t99999999999\tUS\tAS too big\n"
        + "1.0.5.0\t1.0.5.255\t64497\tUS\tFine\n";
    final AsnParser.Result result = AsnParser.parse(text, 100);

    assertEquals(2, result.validLines());
    assertEquals(4, result.invalidLines());
    assertEquals(3, result.invalidSamples().size(), "only the first few are remembered");
    assertEquals("garbage without tabs", result.invalidSamples().get(0));
    assertEquals(64497, result.database().lookup(address("1.0.5.5")).orElseThrow().number());
  }

  @Test
  void aRangeOutOfOrderIsInvalidNotFatal() {
    final String text = "1.0.1.0\t1.0.1.255\t13335\tUS\tFirst\n"
        + "1.0.0.0\t1.0.0.255\t64496\tUS\tBefore it\n"
        + "1.0.2.0\t1.0.2.255\t64497\tUS\tAfter it\n";
    final AsnParser.Result result = AsnParser.parse(text, 100);

    assertEquals(2, result.validLines());
    assertEquals(1, result.invalidLines());
  }

  @Test
  void commentsAndBlankLinesAreIgnoredEntirely() {
    final AsnParser.Result result = AsnParser.parse(
        "# a header\n\n   \n1.0.0.0\t1.0.0.255\t13335\tUS\tGood\n", 100);

    assertEquals(1, result.validLines());
    assertEquals(0, result.invalidLines());
  }

  @Test
  void aNameIsStrippedOfControlCharactersAndCutToLength() {
    final String longName = "N".repeat(500);
    final String escape = String.valueOf((char) 0x1b);
    final String override = String.valueOf((char) 0x202e);
    final AsnParser.Result result = AsnParser.parse(
        "1.0.0.0\t1.0.0.255\t13335\tUS\tEvil" + escape + "[31m" + override + "Name\n"
            + "1.0.1.0\t1.0.1.255\t64496\tUS\t" + longName + "\n", 100);

    final String cleaned = result.database().lookup(address("1.0.0.1")).orElseThrow().name();
    assertFalse(cleaned.contains(escape), "no escape character");
    assertFalse(cleaned.contains(override), "no bidirectional override");
    assertEquals(AsnDatabase.MAX_NAME_LENGTH,
        result.database().lookup(address("1.0.1.1")).orElseThrow().name().length());
  }

  @Test
  void aCountryThatIsNotTwoLettersIsDropped() {
    final AsnParser.Result result = AsnParser.parse(
        "1.0.0.0\t1.0.0.255\t13335\tUSA\tThree letters\n"
            + "1.0.1.0\t1.0.1.255\t64496\t1x\tDigit\n"
            + "1.0.2.0\t1.0.2.255\t64497\tfr\tLower\n", 100);

    assertEquals("", result.database().lookup(address("1.0.0.1")).orElseThrow().country());
    assertEquals("", result.database().lookup(address("1.0.1.1")).orElseThrow().country());
    assertEquals("FR", result.database().lookup(address("1.0.2.1")).orElseThrow().country());
  }

  @Test
  void aLineLongerThanTheCeilingIsOneBadLineAndTheNextOneIsRead() {
    final String monster = "1.0.0.0\t1.0.0.255\t13335\tUS\t" + "x".repeat(50_000) + "\n";
    final AsnParser.Result result = AsnParser.parse(
        monster + "1.0.1.0\t1.0.1.255\t64496\tUS\tAfter\n", 100);

    assertEquals(1, result.validLines());
    assertEquals(1, result.invalidLines());
    assertTrue(result.invalidSamples().get(0).length() <= 80, "a sample is short");
    assertEquals(64496, result.database().lookup(address("1.0.1.9")).orElseThrow().number());
  }

  @Test
  void readingStopsAtTheRowCeilingAndSaysSo() {
    final StringBuilder text = new StringBuilder();
    for (int i = 0; i < 50; i++) {
      text.append("10.0.").append(i).append(".0\t10.0.").append(i).append(".255\t64496\tFR\tA\n");
    }
    final AsnParser.Result result = AsnParser.parse(text.toString(), 20);

    assertTrue(result.truncated());
    assertEquals(20, result.validLines());
  }

  @Test
  void printableSamplesCannotForgeALogLine() {
    final AsnParser.Result result = AsnParser.parse("bad\u001b[2J\nline two\twith\ttabs\n", 10);

    for (String sample : result.printableSamples()) {
      assertFalse(sample.contains("\u001b"));
      assertFalse(sample.contains("\n"));
    }
  }

  @Test
  void aGzipFileIsReadLikeItsContent(@TempDir final Path directory) throws IOException {
    final Path file = directory.resolve("ip2asn-combined.tsv.gz");
    Files.write(file, gzip(SAMPLE));

    final AsnParser.Result result = AsnParser.parseGzip(file, 1000);

    assertEquals(5, result.validLines());
    assertEquals(13335, result.database().lookup(address("1.0.0.1")).orElseThrow().number());
  }

  @Test
  void aFileThatIsNotGzipIsAnIoError(@TempDir final Path directory) throws IOException {
    final Path file = directory.resolve("not-gzip.tsv.gz");
    Files.writeString(file, SAMPLE);

    assertThrows(IOException.class, () -> AsnParser.parseGzip(file, 1000));
  }

  @Test
  void aStreamThatInflatesPastTheByteCeilingIsAbandoned() throws IOException {
    final byte[] bomb = gzip("1.0.0.0\t1.0.0.255\t13335\tUS\tGood\n".repeat(5_000));

    assertThrows(IOException.class,
        () -> AsnParser.parseGzip(new ByteArrayInputStream(bomb), 1_000_000, 10_000));
  }
}
