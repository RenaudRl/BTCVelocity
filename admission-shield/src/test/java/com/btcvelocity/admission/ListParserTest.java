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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ListParserTest {

  private static InetAddress ip(final String literal) {
    return InetAddress.ofLiteral(literal);
  }

  @Test
  void readsTheX4bnetFormatOneCidrPerLine() {
    // Arrange - shape of output/datacenter/ipv4.txt and ipv6.txt: no header, no comment
    final String text = "1.12.0.0/14\n1.44.96.0/24\n2001:310::/32\n2001:418:1401:4::/64\n";

    // Act
    final ListParser.Result result = ListParser.parse(text);

    // Assert
    assertEquals(4, result.validLines());
    assertEquals(0, result.invalidLines());
    assertTrue(result.set().contains(ip("1.15.255.255")));
    assertFalse(result.set().contains(ip("1.16.0.0")));
    assertTrue(result.set().contains(ip("2001:310:1::1")));
  }

  @Test
  void readingStopsAsSoonAsTheLineCeilingIsCrossed(@TempDir final Path directory)
      throws IOException {
    // Arrange - a list that goes on far past the ceiling, valid and invalid lines mixed
    final StringBuilder text = new StringBuilder();
    for (int i = 0; i < 5_000; i++) {
      text.append(i % 2 == 0 ? "203.0.113.0/24" : "garbage").append('\n');
    }
    final Path file = directory.resolve("long.txt");
    Files.writeString(file, text);

    // Act
    final ListParser.Result fromFile = ListParser.parse(file, 10);
    final ListParser.Result fromText = ListParser.parse(text.toString(), 10);

    // Assert - nothing past the ceiling was read or kept
    for (ListParser.Result result : List.of(fromFile, fromText)) {
      assertTrue(result.truncated());
      assertEquals(10, result.validLines() + result.invalidLines());
    }
    final ListParser.Result exact = ListParser.parse("192.0.2.1\n192.0.2.2\n192.0.2.3\n", 3);
    assertFalse(exact.truncated(), "exactly at the ceiling is still within it");
    assertEquals(3, exact.validLines());
  }

  @Test
  void readsTheFireholIpsetFormatWithItsCommentedHeader() {
    // Arrange - shape of tor_exits.ipset: '#' header, then bare addresses
    final String text = """
        #
        # tor_exits
        #
        # ipv4 hash:ip ipset
        # Entries         : 1355 unique IPs
        #
        2.26.97.42
        171.25.193.25

        185.220.101.0/24
        """;

    final ListParser.Result result = ListParser.parse(text);

    assertEquals(3, result.validLines());
    assertEquals(0, result.invalidLines());
    assertTrue(result.set().contains(ip("2.26.97.42")));
    assertTrue(result.set().contains(ip("185.220.101.77")));
    assertFalse(result.set().contains(ip("2.26.97.43")));
  }

  @Test
  void commentsBlankLinesAndTrailingCommentsAreNotEntries() {
    final String text = "# only a comment\n\n   \n10.0.0.0/8 # trailing comment\n\t192.0.2.1\t\n";

    final ListParser.Result result = ListParser.parse(text);

    assertEquals(2, result.validLines());
    assertEquals(0, result.invalidLines());
    assertTrue(result.set().contains(ip("10.9.9.9")));
    assertTrue(result.set().contains(ip("192.0.2.1")));
  }

  @Test
  void windowsLineEndingsAreAccepted() {
    final ListParser.Result result = ListParser.parse("10.0.0.0/8\r\n192.0.2.1\r\n");

    assertEquals(2, result.validLines());
    assertEquals(0, result.invalidLines());
  }

  @Test
  void invalidLinesAreCountedAndSampledNeverFatal() {
    // Arrange - a good list with junk in it, and an HTML error page's worth of garbage
    final String text = "10.0.0.0/8\nhello\n999.1.1.1\n10.0.0.0/40\n192.0.2.1\nfifth-junk\n";

    // Act
    final ListParser.Result result = ListParser.parse(text);

    // Assert
    assertEquals(2, result.validLines());
    assertEquals(4, result.invalidLines());
    assertEquals(List.of("hello", "999.1.1.1", "10.0.0.0/40"), result.invalidSamples(),
        "only the first few are kept for the log");
    assertTrue(result.set().contains(ip("10.1.1.1")), "the good lines still count");
  }

  @Test
  void aVeryLongInvalidLineIsShortenedInTheSample() {
    final ListParser.Result result = ListParser.parse("x".repeat(500));

    assertEquals(1, result.invalidLines());
    assertEquals(80, result.invalidSamples().getFirst().length());
  }

  @Test
  void anEmptyTextYieldsAnEmptyResult() {
    final ListParser.Result result = ListParser.parse("");

    assertEquals(0, result.validLines());
    assertEquals(0, result.invalidLines());
    assertTrue(result.set().isEmpty());
  }

  @Test
  void readsAFileFromDisk(@TempDir final Path directory) throws IOException {
    final Path file = directory.resolve("list.txt");
    Files.writeString(file, "# header\n203.0.113.0/24\n");

    final ListParser.Result result = ListParser.parse(file);

    assertEquals(1, result.validLines());
    assertTrue(result.set().contains(ip("203.0.113.200")));
  }
}
