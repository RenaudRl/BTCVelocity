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

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * An immutable IP-to-ASN table: sorted, disjoint address ranges, each owned by an autonomous
 * system, searched by bisection.
 *
 * <p>Built from the iptoasn.com {@code ip2asn-combined} file (public domain, PDDL v1.0). The file
 * lists every range of both families, the unrouted ones included as AS 0; an unrouted range tells
 * a lookup nothing a missing range does not, so it is not stored, and neighbouring ranges of the
 * same system are merged. A lookup is {@code O(log n)} and allocates nothing but its answer.
 *
 * <p>Memory is a handful of primitive arrays, about 12 bytes per IPv4 range and 36 per IPv6 range,
 * plus one entry per distinct system (number, country, name as UTF-8 bytes in one shared array):
 * no object per range. The measured footprint of the real file is in the module README.
 *
 * <p>Both families are searched unsigned: IPv4 values are kept as {@code int} with the sign bit
 * flipped, which makes the signed order of the array the unsigned order of the addresses.
 */
public final class AsnDatabase {

  /** The longest autonomous system name kept, in characters; a longer one is cut. */
  public static final int MAX_NAME_LENGTH = 120;

  private static final int SIGN = 0x8000_0000;
  private static final long UNSIGNED_INT = 0xFFFF_FFFFL;
  private static final int INITIAL_CAPACITY = 1 << 12;
  private static final AsnDatabase EMPTY = new Builder().build();

  /**
   * What is known about an autonomous system.
   *
   * @param number the AS number, an unsigned 32-bit value (never 0: unrouted space has no system)
   * @param name the name the registry gives it, at most {@value #MAX_NAME_LENGTH} characters
   * @param country the two-letter country code, or an empty string when none is given
   */
  public record Info(long number, String name, String country) {
  }

  private final int[] v4Start;
  private final int[] v4End;
  private final int[] v4As;
  private final long[] v6StartHi;
  private final long[] v6StartLo;
  private final long[] v6EndHi;
  private final long[] v6EndLo;
  private final int[] v6As;
  private final int[] asNumber;
  private final int[] nameOffset;
  private final byte[] names;
  private final byte[] countries;
  private final int rows;

  private AsnDatabase(final Builder builder) {
    this.v4Start = Arrays.copyOf(builder.v4Start, builder.v4Size);
    this.v4End = Arrays.copyOf(builder.v4End, builder.v4Size);
    this.v4As = Arrays.copyOf(builder.v4As, builder.v4Size);
    this.v6StartHi = Arrays.copyOf(builder.v6StartHi, builder.v6Size);
    this.v6StartLo = Arrays.copyOf(builder.v6StartLo, builder.v6Size);
    this.v6EndHi = Arrays.copyOf(builder.v6EndHi, builder.v6Size);
    this.v6EndLo = Arrays.copyOf(builder.v6EndLo, builder.v6Size);
    this.v6As = Arrays.copyOf(builder.v6As, builder.v6Size);
    this.asNumber = Arrays.copyOf(builder.asNumber, builder.asSize);
    this.nameOffset = Arrays.copyOf(builder.nameOffset, builder.asSize + 1);
    this.names = Arrays.copyOf(builder.names, builder.nameOffset[builder.asSize]);
    this.countries = Arrays.copyOf(builder.countries, 2 * builder.asSize);
    this.rows = builder.rows;
  }

  /**
   * The database that knows no address.
   *
   * @return the empty database
   */
  public static AsnDatabase empty() {
    return EMPTY;
  }

  /**
   * Starts a database.
   *
   * @return a builder; its ranges must be added in ascending order, per family
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * The autonomous system that announces an address.
   *
   * @param address the address; an IPv4-mapped IPv6 address is looked up as the IPv4 it wraps
   * @return the system, or empty when no stored range holds the address
   */
  public Optional<Info> lookup(final InetAddress address) {
    final byte[] bytes = address.getAddress();
    final int system;
    if (bytes.length == 4) {
      system = findV4(readInt(bytes, 0));
    } else if (isV4Mapped(bytes)) {
      system = findV4(readInt(bytes, 12));
    } else {
      system = findV6(readLong(bytes, 0), readLong(bytes, 8));
    }
    return system < 0 ? Optional.empty() : Optional.of(infoOf(system));
  }

  private Info infoOf(final int system) {
    final String name = new String(this.names, this.nameOffset[system],
        this.nameOffset[system + 1] - this.nameOffset[system], StandardCharsets.UTF_8);
    final String country = this.countries[2 * system] == 0 ? ""
        : new String(this.countries, 2 * system, 2, StandardCharsets.US_ASCII);
    return new Info(Integer.toUnsignedLong(this.asNumber[system]), name, country);
  }

  /** The index of the system announcing the address, or -1. */
  private int findV4(final int address) {
    final int key = address ^ SIGN;
    int low = 0;
    int high = this.v4Start.length - 1;
    while (low <= high) {
      final int mid = (low + high) >>> 1;
      if (this.v4Start[mid] <= key) {
        if (key <= this.v4End[mid]) {
          return this.v4As[mid];
        }
        low = mid + 1;
      } else {
        high = mid - 1;
      }
    }
    return -1;
  }

  private int findV6(final long hi, final long lo) {
    int low = 0;
    int high = this.v6StartHi.length - 1;
    while (low <= high) {
      final int mid = (low + high) >>> 1;
      if (compare(this.v6StartHi[mid], this.v6StartLo[mid], hi, lo) <= 0) {
        if (compare(hi, lo, this.v6EndHi[mid], this.v6EndLo[mid]) <= 0) {
          return this.v6As[mid];
        }
        low = mid + 1;
      } else {
        high = mid - 1;
      }
    }
    return -1;
  }

  /**
   * How many ranges the file held, unrouted ones included. This is the figure the guards compare
   * between two versions.
   *
   * @return the number of valid lines read
   */
  public int rowCount() {
    return this.rows;
  }

  /**
   * How many IPv4 ranges are stored, after unrouted ones were dropped and neighbours merged.
   *
   * @return the number of ranges
   */
  public int ipv4RangeCount() {
    return this.v4Start.length;
  }

  /**
   * How many IPv6 ranges are stored.
   *
   * @return the number of ranges
   */
  public int ipv6RangeCount() {
    return this.v6StartHi.length;
  }

  /**
   * How many distinct autonomous systems announce at least one stored range.
   *
   * @return the number of systems
   */
  public int systemCount() {
    return this.asNumber.length;
  }

  /**
   * How many IPv4 addresses the given systems announce.
   *
   * @param systems AS numbers
   * @return the number of addresses, overlaps counted once (ranges are disjoint)
   */
  public long ipv4AddressesOf(final Set<Long> systems) {
    if (systems.isEmpty()) {
      return 0;
    }
    final boolean[] wanted = wantedFlags(systems);
    long total = 0;
    for (int i = 0; i < this.v4Start.length; i++) {
      if (wanted[this.v4As[i]]) {
        total += unflip(this.v4End[i]) - unflip(this.v4Start[i]) + 1;
      }
    }
    return total;
  }

  /**
   * How many IPv6 /64 networks the given systems announce. A /64 is the unit of
   * {@link ListGuards}; the count is a {@code double} because the whole address space is 2<sup>64</sup>
   * of them, more than a {@code long} holds.
   *
   * @param systems AS numbers
   * @return the number of /64 networks, overlaps counted once (ranges are disjoint)
   */
  public double ipv6Slash64Of(final Set<Long> systems) {
    if (systems.isEmpty()) {
      return 0;
    }
    final boolean[] wanted = wantedFlags(systems);
    double total = 0;
    for (int i = 0; i < this.v6StartHi.length; i++) {
      if (wanted[this.v6As[i]]) {
        total += unsignedToDouble(this.v6EndHi[i] - this.v6StartHi[i]) + 1.0;
      }
    }
    return total;
  }

  private boolean[] wantedFlags(final Set<Long> systems) {
    final boolean[] wanted = new boolean[this.asNumber.length];
    for (int i = 0; i < wanted.length; i++) {
      wanted[i] = systems.contains(Integer.toUnsignedLong(this.asNumber[i]));
    }
    return wanted;
  }

  private static double unsignedToDouble(final long value) {
    return value >= 0 ? (double) value : (double) (value >>> 1) * 2.0 + (value & 1);
  }

  /**
   * The size of the arrays that make up this database, in bytes. Object headers and the few
   * scalar fields are left out; it is the figure to compare with a heap measurement.
   *
   * @return the approximate footprint
   */
  public long approximateBytes() {
    return 4L * (this.v4Start.length * 3L + this.v6As.length + this.asNumber.length
        + this.nameOffset.length) + 8L * this.v6StartHi.length * 4 + this.names.length
        + this.countries.length;
  }

  private static int compare(final long hiA, final long loA, final long hiB, final long loB) {
    final int high = Long.compareUnsigned(hiA, hiB);
    return high != 0 ? high : Long.compareUnsigned(loA, loB);
  }

  private static int readInt(final byte[] bytes, final int at) {
    return (bytes[at] & 0xff) << 24 | (bytes[at + 1] & 0xff) << 16 | (bytes[at + 2] & 0xff) << 8
        | (bytes[at + 3] & 0xff);
  }

  private static long readLong(final byte[] bytes, final int at) {
    return Integer.toUnsignedLong(readInt(bytes, at)) << 32
        | Integer.toUnsignedLong(readInt(bytes, at + 4));
  }

  private static boolean isV4Mapped(final byte[] bytes) {
    if (bytes.length != 16) {
      return false;
    }
    for (int i = 0; i < 10; i++) {
      if (bytes[i] != 0) {
        return false;
      }
    }
    return (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff;
  }

  /**
   * Accumulates ranges, then freezes them. Not thread-safe; one builder per parse.
   *
   * <p>Ranges of one family must arrive in ascending order and must not overlap: the table is
   * searched by bisection, and a file that breaks this is not the file the module expects.
   */
  public static final class Builder {

    private int[] v4Start = new int[INITIAL_CAPACITY];
    private int[] v4End = new int[INITIAL_CAPACITY];
    private int[] v4As = new int[INITIAL_CAPACITY];
    private int v4Size;
    private long lastV4End = -1;

    private long[] v6StartHi = new long[INITIAL_CAPACITY];
    private long[] v6StartLo = new long[INITIAL_CAPACITY];
    private long[] v6EndHi = new long[INITIAL_CAPACITY];
    private long[] v6EndLo = new long[INITIAL_CAPACITY];
    private int[] v6As = new int[INITIAL_CAPACITY];
    private int v6Size;
    private boolean hasLastV6End;
    private long lastV6EndHi;
    private long lastV6EndLo;

    private int[] asNumber = new int[INITIAL_CAPACITY];
    private int[] nameOffset = new int[INITIAL_CAPACITY + 1];
    private byte[] names = new byte[INITIAL_CAPACITY * 16];
    private byte[] countries = new byte[INITIAL_CAPACITY * 2];
    private int asSize;
    private final Map<Long, Integer> indexOfSystem = new HashMap<>();
    private int rows;

    private Builder() {
    }

    /**
     * Adds an IPv4 range.
     *
     * @param start the first address, 0 to 2<sup>32</sup>-1
     * @param end the last address, not below {@code start}
     * @param system the AS number, 0 for unrouted space; 0 to 2<sup>32</sup>-1
     * @param country the country code, or an empty string
     * @param name the system's name
     * @return {@code false} if the range is malformed, or does not start after the previous range
     */
    public boolean addIpv4(final long start, final long end, final long system,
        final String country, final String name) {
      if (start < 0 || end > UNSIGNED_INT || start > end || start <= this.lastV4End
          || system < 0 || system > UNSIGNED_INT) {
        return false;
      }
      this.lastV4End = end;
      this.rows++;
      if (system == 0) {
        return true;
      }
      final int index = systemIndex(system, country, name);
      final int last = this.v4Size - 1;
      if (last >= 0 && this.v4As[last] == index && unflip(this.v4End[last]) + 1 == start) {
        this.v4End[last] = flip(end);
        return true;
      }
      if (this.v4Size == this.v4Start.length) {
        final int grown = this.v4Size * 2;
        this.v4Start = Arrays.copyOf(this.v4Start, grown);
        this.v4End = Arrays.copyOf(this.v4End, grown);
        this.v4As = Arrays.copyOf(this.v4As, grown);
      }
      this.v4Start[this.v4Size] = flip(start);
      this.v4End[this.v4Size] = flip(end);
      this.v4As[this.v4Size] = index;
      this.v4Size++;
      return true;
    }

    /**
     * Adds an IPv6 range, each bound as two unsigned 64-bit halves.
     *
     * @param startHi the high half of the first address
     * @param startLo the low half of the first address
     * @param endHi the high half of the last address
     * @param endLo the low half of the last address
     * @param system the AS number, 0 for unrouted space; 0 to 2<sup>32</sup>-1
     * @param country the country code, or an empty string
     * @param name the system's name
     * @return {@code false} if the range is malformed, or does not start after the previous range
     */
    public boolean addIpv6(final long startHi, final long startLo, final long endHi,
        final long endLo, final long system, final String country, final String name) {
      if (compare(startHi, startLo, endHi, endLo) > 0 || system < 0 || system > UNSIGNED_INT
          || this.hasLastV6End && compare(startHi, startLo, this.lastV6EndHi, this.lastV6EndLo) <= 0) {
        return false;
      }
      this.hasLastV6End = true;
      this.lastV6EndHi = endHi;
      this.lastV6EndLo = endLo;
      this.rows++;
      if (system == 0) {
        return true;
      }
      final int index = systemIndex(system, country, name);
      final int last = this.v6Size - 1;
      if (last >= 0 && this.v6As[last] == index && isNext(this.v6EndHi[last], this.v6EndLo[last],
          startHi, startLo)) {
        this.v6EndHi[last] = endHi;
        this.v6EndLo[last] = endLo;
        return true;
      }
      if (this.v6Size == this.v6StartHi.length) {
        final int grown = this.v6Size * 2;
        this.v6StartHi = Arrays.copyOf(this.v6StartHi, grown);
        this.v6StartLo = Arrays.copyOf(this.v6StartLo, grown);
        this.v6EndHi = Arrays.copyOf(this.v6EndHi, grown);
        this.v6EndLo = Arrays.copyOf(this.v6EndLo, grown);
        this.v6As = Arrays.copyOf(this.v6As, grown);
      }
      this.v6StartHi[this.v6Size] = startHi;
      this.v6StartLo[this.v6Size] = startLo;
      this.v6EndHi[this.v6Size] = endHi;
      this.v6EndLo[this.v6Size] = endLo;
      this.v6As[this.v6Size] = index;
      this.v6Size++;
      return true;
    }

    /**
     * Freezes what was added.
     *
     * @return the database
     */
    public AsnDatabase build() {
      return new AsnDatabase(this);
    }

    private int systemIndex(final long system, final String country, final String name) {
      final Integer known = this.indexOfSystem.get(system);
      if (known != null) {
        return known;
      }
      if (this.asSize == this.asNumber.length) {
        final int grown = this.asSize * 2;
        this.asNumber = Arrays.copyOf(this.asNumber, grown);
        this.nameOffset = Arrays.copyOf(this.nameOffset, grown + 1);
        this.countries = Arrays.copyOf(this.countries, grown * 2);
      }
      final byte[] encoded = name.getBytes(StandardCharsets.UTF_8);
      final int used = this.nameOffset[this.asSize];
      if (used + encoded.length > this.names.length) {
        this.names = Arrays.copyOf(this.names, Math.max(this.names.length * 2, used + encoded.length));
      }
      System.arraycopy(encoded, 0, this.names, used, encoded.length);
      final int index = this.asSize++;
      this.asNumber[index] = (int) system;
      this.nameOffset[index + 1] = used + encoded.length;
      if (country.length() == 2) {
        this.countries[2 * index] = (byte) country.charAt(0);
        this.countries[2 * index + 1] = (byte) country.charAt(1);
      }
      this.indexOfSystem.put(system, index);
      return index;
    }

    private static boolean isNext(final long endHi, final long endLo, final long startHi,
        final long startLo) {
      if (endLo == -1L) {
        return endHi != -1L && startHi == endHi + 1 && startLo == 0;
      }
      return startHi == endHi && startLo == endLo + 1;
    }

    private static int flip(final long unsigned) {
      return (int) unsigned ^ SIGN;
    }
  }

  private static long unflip(final int flipped) {
    return (flipped ^ SIGN) & UNSIGNED_INT;
  }
}
