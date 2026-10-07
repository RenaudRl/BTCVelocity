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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * An immutable set of IPv4 and IPv6 networks, answering "is this address inside?" by binary search.
 *
 * <p>Networks are merged into sorted disjoint ranges when the set is built, so a lookup is
 * {@code O(log n)} whatever the number of entries and whether or not the source list overlaps
 * itself. Both families are stored as 128-bit unsigned values (an IPv4 address is a value below
 * 2<sup>32</sup> with a zero high half), which lets one code path serve both.
 *
 * <p>An IPv4-mapped IPv6 address ({@code ::ffff:a.b.c.d}) is looked up as the IPv4 address it
 * wraps: a dual-stack socket reports every IPv4 client that way.
 */
public final class CidrSet {

  /** The widest IPv4 network a set accepts, in prefix bits: {@code /8}. */
  public static final int MIN_PREFIX_V4 = 8;

  /**
   * The widest IPv6 network a set accepts, in prefix bits: {@code /20}. The widest line of the
   * X4BNet datacenter list is a {@code /24} (October 2026), so a {@code /20} leaves four bits of
   * room; a {@code /16} would already take in several large residential ISPs.
   */
  public static final int MIN_PREFIX_V6 = 20;

  private static final CidrSet EMPTY = new Builder().build();

  private final Ranges v4;
  private final Ranges v6;
  private final int entries;

  private CidrSet(final Ranges v4, final Ranges v6, final int entries) {
    this.v4 = v4;
    this.v6 = v6;
    this.entries = entries;
  }

  /**
   * The set that contains nothing.
   *
   * @return the empty set
   */
  public static CidrSet empty() {
    return EMPTY;
  }

  /**
   * Starts a new set.
   *
   * @return a builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Whether the address falls inside one of the networks.
   *
   * @param address the address to look up
   * @return {@code true} if some network contains it
   */
  public boolean contains(final InetAddress address) {
    final byte[] bytes = address.getAddress();
    if (bytes.length == 4) {
      return this.v4.contains(0L, unsignedInt(bytes, 0));
    }
    if (isV4Mapped(bytes)) {
      return this.v4.contains(0L, unsignedInt(bytes, 12));
    }
    return this.v6.contains(readLong(bytes, 0), readLong(bytes, 8));
  }

  /**
   * Whether nothing was loaded into this set.
   *
   * @return {@code true} if it holds no network
   */
  public boolean isEmpty() {
    return this.entries == 0;
  }

  /**
   * How many networks were added, before overlapping ones were merged.
   *
   * @return the number of accepted entries
   */
  public int entryCount() {
    return this.entries;
  }

  /**
   * How many disjoint ranges the entries merged into.
   *
   * @return the number of ranges, both families together
   */
  public int rangeCount() {
    return this.v4.size + this.v6.size;
  }

  /**
   * How many IPv4 addresses the set covers, overlaps counted once.
   *
   * @return the number of addresses
   */
  public long ipv4AddressCount() {
    long total = 0;
    for (int i = 0; i < this.v4.size; i++) {
      total += this.v4.endLo[i] - this.v4.startLo[i] + 1;
    }
    return total;
  }

  /**
   * How much IPv6 space the set covers, in /64 networks (a finer range counts as one). A measure
   * for comparing two versions of a list, not an exact count.
   *
   * @return the approximate number of /64 networks
   */
  public double ipv6Slash64Count() {
    double total = 0;
    for (int i = 0; i < this.v6.size; i++) {
      final long span = this.v6.endHi[i] - this.v6.startHi[i];
      total += unsignedToDouble(span) + 1.0;
    }
    return total;
  }

  private static double unsignedToDouble(final long value) {
    return (value >>> 1) * 2.0 + (value & 1L);
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
    return bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
  }

  private static long unsignedInt(final byte[] bytes, final int offset) {
    return ((bytes[offset] & 0xffL) << 24)
        | ((bytes[offset + 1] & 0xffL) << 16)
        | ((bytes[offset + 2] & 0xffL) << 8)
        | (bytes[offset + 3] & 0xffL);
  }

  private static long readLong(final byte[] bytes, final int offset) {
    long value = 0;
    for (int i = 0; i < 8; i++) {
      value = (value << 8) | (bytes[offset + i] & 0xffL);
    }
    return value;
  }

  private static int compare(final long hiA, final long loA, final long hiB, final long loB) {
    final int high = Long.compareUnsigned(hiA, hiB);
    return high != 0 ? high : Long.compareUnsigned(loA, loB);
  }

  /** Sorted, disjoint, inclusive ranges over 128-bit unsigned values. */
  private static final class Ranges {

    private static final Ranges NONE = new Ranges(new long[0][]);

    private final long[] startHi;
    private final long[] startLo;
    private final long[] endHi;
    private final long[] endLo;
    private final int size;

    private Ranges(final long[][] merged) {
      this.size = merged.length;
      this.startHi = new long[this.size];
      this.startLo = new long[this.size];
      this.endHi = new long[this.size];
      this.endLo = new long[this.size];
      for (int i = 0; i < this.size; i++) {
        this.startHi[i] = merged[i][0];
        this.startLo[i] = merged[i][1];
        this.endHi[i] = merged[i][2];
        this.endLo[i] = merged[i][3];
      }
    }

    static Ranges of(final List<long[]> raw) {
      if (raw.isEmpty()) {
        return NONE;
      }
      final long[][] sorted = raw.toArray(new long[0][]);
      Arrays.sort(sorted, (a, b) -> compare(a[0], a[1], b[0], b[1]));
      return new Ranges(merge(sorted));
    }

    private static long[][] merge(final long[][] sorted) {
      final List<long[]> merged = new ArrayList<>(sorted.length);
      long[] current = sorted[0].clone();
      for (int i = 1; i < sorted.length; i++) {
        final long[] next = sorted[i];
        if (touches(current, next)) {
          if (compare(next[2], next[3], current[2], current[3]) > 0) {
            current[2] = next[2];
            current[3] = next[3];
          }
        } else {
          merged.add(current);
          current = next.clone();
        }
      }
      merged.add(current);
      return merged.toArray(new long[0][]);
    }

    /** Overlapping, or adjacent (a range ending at x and one starting at x + 1 are one range). */
    private static boolean touches(final long[] current, final long[] next) {
      if (compare(next[0], next[1], current[2], current[3]) <= 0) {
        return true;
      }
      if (current[2] == -1L && current[3] == -1L) {
        return true;
      }
      final long incrementedLo = current[3] + 1;
      final long incrementedHi = incrementedLo == 0 ? current[2] + 1 : current[2];
      return compare(next[0], next[1], incrementedHi, incrementedLo) == 0;
    }

    boolean contains(final long hi, final long lo) {
      int low = 0;
      int high = this.size - 1;
      int found = -1;
      while (low <= high) {
        final int mid = (low + high) >>> 1;
        if (compare(this.startHi[mid], this.startLo[mid], hi, lo) <= 0) {
          found = mid;
          low = mid + 1;
        } else {
          high = mid - 1;
        }
      }
      return found >= 0 && compare(hi, lo, this.endHi[found], this.endLo[found]) <= 0;
    }
  }

  /** Collects networks, then freezes them into a {@link CidrSet}. */
  public static final class Builder {

    private final List<long[]> v4 = new ArrayList<>();
    private final List<long[]> v6 = new ArrayList<>();
    private int entries;

    private Builder() {
    }

    /**
     * Adds one entry: a bare address, or {@code address/prefix}.
     *
     * <p>Host bits below the prefix are ignored ({@code 10.1.2.3/8} is {@code 10.0.0.0/8}). The
     * token is never resolved through DNS: anything that is not an address literal is rejected.
     * So is a prefix shorter than {@value #MIN_PREFIX_V4} (IPv4) or {@value #MIN_PREFIX_V6}
     * (IPv6) bits.
     *
     * @param token the entry as written in a list
     * @return {@code true} if it was understood and added, {@code false} if it was rejected
     */
    public boolean add(final String token) {
      final String text = token.trim();
      final int slash = text.indexOf('/');
      final String addressPart = slash < 0 ? text : text.substring(0, slash);
      final InetAddress address = parseLiteral(addressPart);
      if (address == null) {
        return false;
      }
      final byte[] bytes = address.getAddress();
      final boolean asV4 = bytes.length == 4 || isV4Mapped(bytes);
      final int width = asV4 ? 32 : 128;
      int prefix = width;
      if (slash >= 0) {
        final String prefixPart = text.substring(slash + 1);
        if (prefixPart.isEmpty() || prefixPart.length() > 3 || !prefixPart.chars().allMatch(
            Character::isDigit)) {
          return false;
        }
        prefix = Integer.parseInt(prefixPart);
        // A prefix written against a mapped IPv6 address counts in IPv6 bits. The parser may
        // already have turned "::ffff:a.b.c.d" into a plain IPv4 address, so the text decides.
        if (asV4 && addressPart.indexOf(':') >= 0) {
          prefix -= 96;
        }
        if (prefix < 0 || prefix > width) {
          return false;
        }
      }
      // A network this wide would classify a large share of the Internet: a poisoned or broken
      // list must not be able to say it, whatever the file claims.
      if (prefix < (asV4 ? MIN_PREFIX_V4 : MIN_PREFIX_V6)) {
        return false;
      }
      if (asV4) {
        addV4(unsignedInt(bytes, bytes.length - 4), prefix);
      } else {
        addV6(readLong(bytes, 0), readLong(bytes, 8), prefix);
      }
      this.entries++;
      return true;
    }

    private static InetAddress parseLiteral(final String text) {
      if (text.isEmpty()) {
        return null;
      }
      try {
        return InetAddress.ofLiteral(text);
      } catch (IllegalArgumentException malformed) {
        return null;
      }
    }

    private void addV4(final long address, final int prefix) {
      final long mask = prefix == 0 ? 0L : (0xffffffffL << (32 - prefix)) & 0xffffffffL;
      final long start = address & mask;
      final long end = start | (~mask & 0xffffffffL);
      this.v4.add(new long[] {0L, start, 0L, end});
    }

    private void addV6(final long hi, final long lo, final int prefix) {
      final long maskHi = prefix == 0 ? 0L : -1L << (64 - Math.min(prefix, 64));
      final long maskLo = prefix <= 64 ? 0L : -1L << (128 - prefix);
      final long startHi = hi & maskHi;
      final long startLo = lo & maskLo;
      this.v6.add(new long[] {startHi, startLo, startHi | ~maskHi, startLo | ~maskLo});
    }

    /**
     * Freezes the collected networks.
     *
     * @return the immutable set
     */
    public CidrSet build() {
      return new CidrSet(Ranges.of(this.v4), Ranges.of(this.v6), this.entries);
    }
  }
}
