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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a plain-text network list: one address or CIDR per line.
 *
 * <p>Covers both formats in use here: the X4BNet {@code lists_vpn} files (bare CIDR, one per
 * line) and the FireHOL ipsets (a commented header, then bare addresses or CIDR). {@code #} starts
 * a comment, on its own line or after an entry; blank lines are skipped. A line that is neither is
 * counted and remembered as a sample, never fatal: a list with a few bad lines is still a list.
 */
public final class ListParser {

  private static final int MAX_SAMPLES = 3;
  private static final int MAX_SAMPLE_LENGTH = 80;

  private ListParser() {
  }

  /**
   * What a parse found.
   *
   * @param set the networks that were understood
   * @param validLines how many entry lines were accepted
   * @param invalidLines how many entry lines were rejected
   * @param invalidSamples the first few rejected lines, shortened, for the log
   * @param truncated whether reading stopped because the list went past the line ceiling: the
   *     counts then stop at that point, and the list is not to be trusted
   */
  public record Result(CidrSet set, int validLines, int invalidLines, List<String> invalidSamples,
      boolean truncated) {
  }

  /**
   * Parses a list held in memory, with no ceiling on its length.
   *
   * @param text the whole list
   * @return what was found
   */
  public static Result parse(final String text) {
    return parse(text, Integer.MAX_VALUE);
  }

  /**
   * Parses a list held in memory.
   *
   * @param text the whole list
   * @param maxLines stop reading once more entry lines (valid and invalid) than this were seen
   * @return what was found
   */
  public static Result parse(final String text, final int maxLines) {
    try (BufferedReader reader = new BufferedReader(new StringReader(text))) {
      return parse(reader, maxLines);
    } catch (IOException impossible) {
      throw new IllegalStateException("a string reader does not fail", impossible);
    }
  }

  /**
   * Parses a list held in a file, with no ceiling on its length.
   *
   * @param file the list, UTF-8
   * @return what was found
   * @throws IOException if the file cannot be read
   */
  public static Result parse(final Path file) throws IOException {
    return parse(file, Integer.MAX_VALUE);
  }

  /**
   * Parses a list held in a file.
   *
   * <p>Reading stops as soon as more than {@code maxLines} entry lines were seen: every accepted
   * entry costs memory, and a list that long is thrown away anyway, so it must not be read to the
   * end first.
   *
   * @param file the list, UTF-8
   * @param maxLines the ceiling on entry lines (valid and invalid together)
   * @return what was found; {@link Result#truncated()} says whether reading was cut short
   * @throws IOException if the file cannot be read
   */
  public static Result parse(final Path file, final int maxLines) throws IOException {
    try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      return parse(reader, maxLines);
    }
  }

  private static Result parse(final Reader source, final int maxLines) throws IOException {
    final BufferedReader reader =
        source instanceof BufferedReader buffered ? buffered : new BufferedReader(source);
    final CidrSet.Builder builder = CidrSet.builder();
    final List<String> samples = new ArrayList<>(MAX_SAMPLES);
    int valid = 0;
    int invalid = 0;
    String line;
    while ((line = reader.readLine()) != null) {
      final String entry = entryOf(line);
      if (entry.isEmpty()) {
        continue;
      }
      if (valid + invalid >= maxLines) {
        return new Result(builder.build(), valid, invalid, List.copyOf(samples), true);
      }
      if (builder.add(entry)) {
        valid++;
      } else {
        invalid++;
        if (samples.size() < MAX_SAMPLES) {
          samples.add(shorten(entry));
        }
      }
    }
    return new Result(builder.build(), valid, invalid, List.copyOf(samples), false);
  }

  /** The first token of the line once any comment is cut off, or empty. */
  private static String entryOf(final String line) {
    final int hash = line.indexOf('#');
    final String content = (hash < 0 ? line : line.substring(0, hash)).trim();
    for (int i = 0; i < content.length(); i++) {
      if (Character.isWhitespace(content.charAt(i))) {
        return content.substring(0, i);
      }
    }
    return content;
  }

  private static String shorten(final String entry) {
    return entry.length() <= MAX_SAMPLE_LENGTH ? entry : entry.substring(0, MAX_SAMPLE_LENGTH);
  }
}
