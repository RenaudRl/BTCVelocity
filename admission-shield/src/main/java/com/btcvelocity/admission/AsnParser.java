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

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.jspecify.annotations.Nullable;

/**
 * Reads the iptoasn.com {@code ip2asn-combined.tsv.gz} file: one range per line, five tab-separated
 * columns, {@code range_start range_end AS_number country_code AS_description}, both families in
 * the same file, each family in ascending order. AS 0 stands for space that is not routed
 * (country {@code None}, description {@code Not routed}).
 *
 * <p>The file is someone else's, so reading is bounded: a line longer than
 * {@value #MAX_LINE_CHARS} characters is a bad line (it is never held whole), at most
 * {@code maxRows} lines are read, at most {@value #MAX_SYSTEMS} distinct systems and
 * {@value #MAX_NAME_BYTES} bytes of names are kept (reading stops at the first one past, before
 * the memory is spent), and the compressed stream may not inflate beyond
 * {@value #MAX_DECOMPRESSED_BYTES} bytes. A line that is not five well-formed columns, or whose
 * range does not come after the previous one of its family, is counted and remembered as a sample,
 * never fatal: the guards of {@link AsnGuards} decide whether the file as a whole is worth keeping.
 */
public final class AsnParser {

  /** The longest line read; the real file's longest is under 300 characters. */
  static final int MAX_LINE_CHARS = 1024;

  /**
   * The most a gzip stream may inflate to. The real file is 46 MB; this stops a compression bomb,
   * not growth.
   */
  static final long MAX_DECOMPRESSED_BYTES = 256L * 1024 * 1024;

  /** Real file: 87 182 systems (October 2026); the baseline of a first load stops at 245 000. */
  static final int MAX_SYSTEMS = 300_000;

  /** Real file: about 2 MB of names; names are cut at 120 characters, up to 4 bytes each. */
  static final long MAX_NAME_BYTES = 16L * 1024 * 1024;

  private static final int COLUMNS = 5;
  private static final int MAX_SAMPLES = 3;
  private static final int MAX_SAMPLE_LENGTH = 80;
  private static final int MAX_ASN_DIGITS = 10;
  private static final long MAX_ASN = 0xFFFF_FFFFL;

  private AsnParser() {
  }

  /**
   * What a parse found.
   *
   * @param database the ranges that were understood
   * @param validLines how many lines were accepted, unrouted ranges included
   * @param invalidLines how many lines were rejected
   * @param invalidSamples the first few rejected lines, shortened, for the log
   * @param truncated whether reading stopped because the file went past the line ceiling: the
   *     counts then stop at that point, and the file is not to be trusted
   * @param exceeded why reading stopped because the file named too many distinct systems or too
   *     many bytes of names, or {@code null}; the file is not to be trusted either
   */
  public record Result(AsnDatabase database, int validLines, int invalidLines,
      List<String> invalidSamples, boolean truncated, @Nullable String exceeded) {

    /**
     * A result that stayed within the ceilings on systems and names.
     *
     * @param database the ranges that were understood
     * @param validLines how many lines were accepted
     * @param invalidLines how many lines were rejected
     * @param invalidSamples the first few rejected lines, shortened
     * @param truncated whether reading stopped at the line ceiling
     */
    public Result(final AsnDatabase database, final int validLines, final int invalidLines,
        final List<String> invalidSamples, final boolean truncated) {
      this(database, validLines, invalidLines, invalidSamples, truncated, null);
    }

    /**
     * The rejected lines as a log may carry them: control and formatting characters are escaped,
     * as in the journal, so nothing in someone else's file can forge a log line.
     *
     * @return the samples, each safe to write
     */
    public List<String> printableSamples() {
      return this.invalidSamples.stream().map(AdmissionJournal::value).toList();
    }
  }

  /**
   * Parses a file held in memory, uncompressed.
   *
   * @param text the whole file
   * @param maxRows stop reading once more lines than this were seen
   * @return what was found
   */
  public static Result parse(final String text, final int maxRows) {
    return parse(text, maxRows, MAX_SYSTEMS, MAX_NAME_BYTES);
  }

  static Result parse(final String text, final int maxRows, final int maxSystems,
      final long maxNameBytes) {
    try (Reader reader = new StringReader(text)) {
      return parseLines(reader, maxRows, maxSystems, maxNameBytes);
    } catch (IOException impossible) {
      throw new IllegalStateException("a string reader does not fail", impossible);
    }
  }

  /**
   * Parses a gzip file.
   *
   * @param file the {@code .tsv.gz} file
   * @param maxRows stop reading once more lines than this were seen
   * @return what was found
   * @throws IOException if the file cannot be read, is not gzip, or inflates past the byte ceiling
   */
  public static Result parseGzip(final Path file, final int maxRows) throws IOException {
    try (InputStream stream = Files.newInputStream(file)) {
      return parseGzip(stream, maxRows, MAX_DECOMPRESSED_BYTES);
    }
  }

  static Result parseGzip(final InputStream compressed, final int maxRows,
      final long maxInflatedBytes) throws IOException {
    final InputStream inflated = new Capped(new GZIPInputStream(compressed), maxInflatedBytes);
    try (Reader reader = new InputStreamReader(inflated, StandardCharsets.UTF_8)) {
      return parseLines(reader, maxRows, MAX_SYSTEMS, MAX_NAME_BYTES);
    }
  }

  private static Result parseLines(final Reader source, final int maxRows, final int maxSystems,
      final long maxNameBytes) throws IOException {
    final AsnDatabase.Builder builder = AsnDatabase.builder();
    final List<String> samples = new ArrayList<>(MAX_SAMPLES);
    final Lines lines = new Lines(source);
    int valid = 0;
    int invalid = 0;
    String line;
    while ((line = lines.next()) != null) {
      final String entry = line.trim();
      if (entry.isEmpty() || entry.charAt(0) == '#') {
        continue;
      }
      if (valid + invalid >= maxRows) {
        return new Result(builder.build(), valid, invalid, List.copyOf(samples), true);
      }
      if (!lines.lastWasCut() && addRow(builder, line)) {
        valid++;
        final String exceeded = exceeded(builder, maxSystems, maxNameBytes);
        if (exceeded != null) {
          return new Result(builder.build(), valid, invalid, List.copyOf(samples), false, exceeded);
        }
      } else {
        invalid++;
        if (samples.size() < MAX_SAMPLES) {
          samples.add(shorten(entry));
        }
      }
    }
    return new Result(builder.build(), valid, invalid, List.copyOf(samples), false);
  }

  /**
   * Memory before judgement: a hostile file names a new system on every line, and each one costs a
   * map entry and its name. The ceilings are far above the real file (87 182 systems, about 2 MB of
   * names in October 2026), so they stop a flood without ever meeting growth.
   */
  private static @Nullable String exceeded(final AsnDatabase.Builder builder,
      final int maxSystems, final long maxNameBytes) {
    if (builder.systemCount() > maxSystems) {
      return "more than " + maxSystems + " distinct autonomous systems; not read further";
    }
    if (builder.nameBytes() > maxNameBytes) {
      return "more than " + maxNameBytes + " bytes of system names; not read further";
    }
    return null;
  }

  private static boolean addRow(final AsnDatabase.Builder builder, final String line) {
    final String[] columns = line.split("\t", COLUMNS);
    if (columns.length < COLUMNS) {
      return false;
    }
    final long system = parseSystem(columns[2].trim());
    if (system < 0) {
      return false;
    }
    final String country = parseCountry(columns[3].trim());
    final String name = cleaned(columns[4].trim());
    final String start = columns[0].trim();
    final String end = columns[1].trim();
    if (start.indexOf(':') >= 0) {
      return addIpv6(builder, start, end, system, country, name);
    }
    final long first = parseIpv4(start);
    final long last = parseIpv4(end);
    return first >= 0 && last >= 0 && builder.addIpv4(first, last, system, country, name);
  }

  private static boolean addIpv6(final AsnDatabase.Builder builder, final String start,
      final String end, final long system, final String country, final String name) {
    final byte[] first = ipv6Bytes(start);
    final byte[] last = ipv6Bytes(end);
    return first != null && last != null
        && builder.addIpv6(hi(first), lo(first), hi(last), lo(last), system, country, name);
  }

  private static byte[] ipv6Bytes(final String text) {
    try {
      final InetAddress address = InetAddress.ofLiteral(text);
      // ofLiteral hands a mapped address back as the IPv4 it wraps; a range of the IPv6 half of
      // the file that is not sixteen bytes is not one the table can place.
      return address instanceof Inet4Address ? null : address.getAddress();
    } catch (IllegalArgumentException malformed) {
      return null;
    }
  }

  private static long hi(final byte[] bytes) {
    return readLong(bytes, 0);
  }

  private static long lo(final byte[] bytes) {
    return readLong(bytes, 8);
  }

  private static long readLong(final byte[] bytes, final int at) {
    long value = 0;
    for (int i = 0; i < Long.BYTES; i++) {
      value = value << 8 | (bytes[at + i] & 0xff);
    }
    return value;
  }

  /** A dotted quad as an unsigned 32-bit value, or -1. */
  private static long parseIpv4(final String text) {
    long value = 0;
    int parts = 0;
    int octet = -1;
    for (int i = 0; i <= text.length(); i++) {
      final char c = i < text.length() ? text.charAt(i) : '.';
      if (c >= '0' && c <= '9') {
        octet = (octet < 0 ? 0 : octet * 10) + (c - '0');
        if (octet > 255) {
          return -1;
        }
      } else if (c == '.' && octet >= 0 && parts < 4) {
        value = value << 8 | octet;
        octet = -1;
        parts++;
      } else {
        return -1;
      }
    }
    return parts == 4 ? value : -1;
  }

  /** An AS number, 0 to 2<sup>32</sup>-1, or -1. */
  private static long parseSystem(final String text) {
    if (text.isEmpty() || text.length() > MAX_ASN_DIGITS) {
      return -1;
    }
    long value = 0;
    for (int i = 0; i < text.length(); i++) {
      final char c = text.charAt(i);
      if (c < '0' || c > '9') {
        return -1;
      }
      value = value * 10 + (c - '0');
    }
    return value > MAX_ASN ? -1 : value;
  }

  /** Two letters, upper-cased; the file writes {@code None} for no country. */
  private static String parseCountry(final String text) {
    if (text.length() == 2 && Character.isLetter(text.charAt(0))
        && Character.isLetter(text.charAt(1)) && text.chars().allMatch(c -> c < 128)) {
      return text.toUpperCase(java.util.Locale.ROOT);
    }
    return "";
  }

  /** A name as the module keeps it: no control or formatting character, cut to length. */
  private static String cleaned(final String name) {
    final String cut = name.length() <= AsnDatabase.MAX_NAME_LENGTH ? name
        : name.substring(0, AsnDatabase.MAX_NAME_LENGTH);
    final StringBuilder safe = new StringBuilder(cut.length());
    cut.codePoints().forEach(c -> safe.appendCodePoint(
        Character.isISOControl(c) || Character.getType(c) == Character.FORMAT ? '?' : c));
    return safe.toString();
  }

  private static String shorten(final String entry) {
    return entry.length() <= MAX_SAMPLE_LENGTH ? entry : entry.substring(0, MAX_SAMPLE_LENGTH);
  }

  /** Splits a reader into lines without ever holding more than {@value #MAX_LINE_CHARS}. */
  private static final class Lines {

    private final Reader in;
    private final char[] buffer = new char[8192];
    private final StringBuilder current = new StringBuilder();
    private int position;
    private int limit;
    private boolean cut;

    private Lines(final Reader in) {
      this.in = in;
    }

    /** The next line without its terminator, or {@code null} at the end. */
    private String next() throws IOException {
      this.current.setLength(0);
      this.cut = false;
      boolean any = false;
      while (true) {
        if (this.position == this.limit) {
          this.limit = this.in.read(this.buffer);
          this.position = 0;
          if (this.limit < 0) {
            this.limit = 0;
            return any ? this.current.toString() : null;
          }
        }
        any = true;
        final char c = this.buffer[this.position++];
        if (c == '\n') {
          return this.current.toString();
        }
        if (this.current.length() < MAX_LINE_CHARS) {
          this.current.append(c);
        } else {
          this.cut = true;
        }
      }
    }

    /** Whether the line just returned was longer than the ceiling and is incomplete. */
    private boolean lastWasCut() {
      return this.cut;
    }
  }

  /** Fails once more than a given number of bytes have been read. */
  private static final class Capped extends FilterInputStream {

    private final long maxBytes;
    private long total;

    private Capped(final InputStream in, final long maxBytes) {
      super(in);
      this.maxBytes = maxBytes;
    }

    @Override
    public int read() throws IOException {
      final int value = super.read();
      if (value >= 0) {
        count(1);
      }
      return value;
    }

    @Override
    public int read(final byte[] bytes, final int offset, final int length) throws IOException {
      final int read = super.read(bytes, offset, length);
      if (read > 0) {
        count(read);
      }
      return read;
    }

    private void count(final int read) throws IOException {
      this.total += read;
      if (this.total > this.maxBytes) {
        throw new IOException("the file inflates past " + this.maxBytes
            + " bytes; reading abandoned (compression bomb?)");
      }
    }
  }
}
