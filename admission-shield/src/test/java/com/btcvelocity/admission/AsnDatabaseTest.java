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

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The IP-to-ASN table: bisection over sorted ranges, bounds, unsigned order, both families. */
class AsnDatabaseTest {

  private static final long FIRST_A = ip(10, 0, 0, 0);
  private static final long LAST_A = ip(10, 0, 0, 255);

  private static long ip(final int a, final int b, final int c, final int d) {
    return (long) a << 24 | (long) b << 16 | (long) c << 8 | d;
  }

  private static InetAddress address(final String literal) {
    try {
      return InetAddress.getByName(literal);
    } catch (UnknownHostException impossible) {
      throw new IllegalArgumentException(literal, impossible);
    }
  }

  private static Optional<AsnDatabase.Info> find(final AsnDatabase database, final String literal) {
    return database.lookup(address(literal));
  }

  @Test
  void anEmptyDatabaseKnowsNothing() {
    assertTrue(find(AsnDatabase.empty(), "8.8.8.8").isEmpty());
    assertTrue(find(AsnDatabase.empty(), "2001:db8::1").isEmpty());
    assertEquals(0, AsnDatabase.empty().rowCount());
  }

  @Test
  void aLookupFindsTheRangeHoldingTheAddressAndNoOther() {
    final AsnDatabase.Builder builder = AsnDatabase.builder();
    assertTrue(builder.addIpv4(FIRST_A, LAST_A, 64496, "FR", "Alpha Hosting"));
    assertTrue(builder.addIpv4(ip(10, 0, 2, 0), ip(10, 0, 2, 255), 64497, "DE", "Beta Cloud"));
    final AsnDatabase database = builder.build();

    assertEquals(new AsnDatabase.Info(64496, "Alpha Hosting", "FR"),
        find(database, "10.0.0.0").orElseThrow());
    assertEquals(64496, find(database, "10.0.0.255").orElseThrow().number());
    assertEquals(64497, find(database, "10.0.2.77").orElseThrow().number());
    assertTrue(find(database, "9.255.255.255").isEmpty(), "just before the first range");
    assertTrue(find(database, "10.0.1.0").isEmpty(), "in the gap between two ranges");
    assertTrue(find(database, "10.0.3.0").isEmpty(), "just after the last range");
  }

  @Test
  void addressesAboveTwoToTheThirtyFirstAreOrderedAsUnsigned() {
    final AsnDatabase.Builder builder = AsnDatabase.builder();
    builder.addIpv4(ip(1, 0, 0, 0), ip(1, 0, 0, 255), 100, "AU", "Low");
    builder.addIpv4(ip(200, 1, 0, 0), ip(200, 1, 255, 255), 200, "BR", "High");
    builder.addIpv4(ip(255, 255, 255, 0), ip(255, 255, 255, 255), 300, "ZZ", "Top");
    final AsnDatabase database = builder.build();

    assertEquals(100, find(database, "1.0.0.9").orElseThrow().number());
    assertEquals(200, find(database, "200.1.200.1").orElseThrow().number());
    assertEquals(300, find(database, "255.255.255.255").orElseThrow().number());
    assertTrue(find(database, "128.0.0.0").isEmpty());
  }

  @Test
  void unroutedRangesAreCountedButNeverAnswered() {
    final AsnDatabase.Builder builder = AsnDatabase.builder();
    builder.addIpv4(FIRST_A, LAST_A, 0, "", "Not routed");
    builder.addIpv4(ip(10, 0, 1, 0), ip(10, 0, 1, 255), 64496, "FR", "Alpha");
    final AsnDatabase database = builder.build();

    assertEquals(2, database.rowCount());
    assertEquals(1, database.ipv4RangeCount());
    assertTrue(find(database, "10.0.0.5").isEmpty());
    assertTrue(find(database, "10.0.1.5").isPresent());
  }

  @Test
  void neighbouringRangesOfOneSystemAreMergedAndOnesOfAnotherAreNot() {
    final AsnDatabase.Builder builder = AsnDatabase.builder();
    builder.addIpv4(ip(10, 0, 0, 0), ip(10, 0, 0, 255), 64496, "FR", "Alpha");
    builder.addIpv4(ip(10, 0, 1, 0), ip(10, 0, 1, 255), 64496, "FR", "Alpha");
    builder.addIpv4(ip(10, 0, 2, 0), ip(10, 0, 2, 255), 64497, "FR", "Beta");
    builder.addIpv4(ip(10, 0, 4, 0), ip(10, 0, 4, 255), 64496, "FR", "Alpha");
    final AsnDatabase database = builder.build();

    assertEquals(4, database.rowCount());
    assertEquals(3, database.ipv4RangeCount(), "the first two fold into one");
    assertEquals(2, database.systemCount());
    assertEquals(64496, find(database, "10.0.1.200").orElseThrow().number());
  }

  @Test
  void aRangeThatDoesNotComeAfterThePreviousOneIsRefused() {
    final AsnDatabase.Builder builder = AsnDatabase.builder();
    assertTrue(builder.addIpv4(ip(10, 0, 1, 0), ip(10, 0, 1, 255), 64496, "FR", "Alpha"));
    assertFalse(builder.addIpv4(ip(10, 0, 0, 0), ip(10, 0, 0, 255), 64497, "FR", "Beta"),
        "before the previous one");
    assertFalse(builder.addIpv4(ip(10, 0, 1, 128), ip(10, 0, 2, 0), 64497, "FR", "Beta"),
        "overlapping the previous one");
    assertFalse(builder.addIpv4(ip(10, 0, 5, 9), ip(10, 0, 5, 8), 64497, "FR", "Beta"),
        "its end before its start");
    assertEquals(1, builder.build().rowCount(), "refused ranges are not counted");
  }

  @Test
  void anAsNumberAboveTwoToTheThirtyFirstSurvivesIntact() {
    final AsnDatabase.Builder builder = AsnDatabase.builder();
    builder.addIpv4(FIRST_A, LAST_A, 4_294_967_295L, "US", "Last 32-bit AS");
    builder.addIpv4(ip(10, 0, 1, 0), ip(10, 0, 1, 255), 3_000_000_000L, "US", "Big AS");
    final AsnDatabase database = builder.build();

    assertEquals(4_294_967_295L, find(database, "10.0.0.1").orElseThrow().number());
    assertEquals(3_000_000_000L, find(database, "10.0.1.1").orElseThrow().number());
    assertEquals(Set.of(4_294_967_295L), Set.of(find(database, "10.0.0.1").orElseThrow().number()));
  }

  @Test
  void aNumberBeyondThirtyTwoBitsIsRefused() {
    final AsnDatabase.Builder builder = AsnDatabase.builder();
    assertFalse(builder.addIpv4(FIRST_A, LAST_A, 4_294_967_296L, "US", "Too big"));
    assertFalse(builder.addIpv4(FIRST_A, LAST_A, -1, "US", "Negative"));
  }

  @Test
  void ipv6RangesAreSearchedOnTheirFullSixteenBytes() {
    final AsnDatabase.Builder builder = AsnDatabase.builder();
    // 2001:db8::/32 -> 2001:0db8:0000:0000:0000:0000:0000:0000 to 2001:0db8:ffff:...
    builder.addIpv6(0x2001_0db8_0000_0000L, 0L, 0x2001_0db8_ffff_ffffL, -1L, 64496, "NL", "V6 Net");
    // a range whose high half is above 2^63, to catch signed comparisons
    builder.addIpv6(0xfe00_0000_0000_0000L, 0L, 0xfe00_0000_0000_0000L, 0xffffL, 64497, "NL",
        "High V6");
    final AsnDatabase database = builder.build();

    assertEquals(64496, find(database, "2001:db8:1234::5678").orElseThrow().number());
    assertEquals(64496, find(database, "2001:db8::").orElseThrow().number());
    assertEquals(64497, find(database, "fe00::ff").orElseThrow().number());
    assertTrue(find(database, "fe00::1:0").isEmpty());
    assertTrue(find(database, "2001:db9::1").isEmpty());
    assertEquals(2, database.ipv6RangeCount());
  }

  @Test
  void anIpv4MappedIpv6AddressIsLookedUpAsTheIpv4ItWraps() {
    final AsnDatabase.Builder builder = AsnDatabase.builder();
    builder.addIpv4(FIRST_A, LAST_A, 64496, "FR", "Alpha");
    final AsnDatabase database = builder.build();

    final byte[] mapped = new byte[16];
    mapped[10] = (byte) 0xff;
    mapped[11] = (byte) 0xff;
    mapped[12] = 10;
    mapped[15] = 7;
    try {
      assertEquals(64496, database.lookup(InetAddress.getByAddress(mapped)).orElseThrow().number());
    } catch (UnknownHostException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  @Test
  void theIpv4AddressesOfSomeSystemsAreSummed() {
    final AsnDatabase.Builder builder = AsnDatabase.builder();
    builder.addIpv4(ip(10, 0, 0, 0), ip(10, 0, 0, 255), 64496, "FR", "Alpha");
    builder.addIpv4(ip(10, 0, 1, 0), ip(10, 0, 3, 255), 64497, "FR", "Beta");
    builder.addIpv4(ip(200, 0, 0, 0), ip(200, 0, 0, 15), 64496, "BR", "Alpha");
    final AsnDatabase database = builder.build();

    assertEquals(256 + 16, database.ipv4AddressesOf(Set.of(64496L)));
    assertEquals(768, database.ipv4AddressesOf(Set.of(64497L)));
    assertEquals(0, database.ipv4AddressesOf(Set.of()));
    assertEquals(0, database.ipv4AddressesOf(Set.of(1L)));
  }

  @Test
  void aSystemWithoutACountryReportsAnEmptyOne() {
    final AsnDatabase.Builder builder = AsnDatabase.builder();
    builder.addIpv4(FIRST_A, LAST_A, 64496, "", "Nowhere");
    assertEquals("", find(builder.build(), "10.0.0.1").orElseThrow().country());
  }

  @Test
  void aNonAsciiNameComesBackIntact() {
    final AsnDatabase.Builder builder = AsnDatabase.builder();
    builder.addIpv4(FIRST_A, LAST_A, 64496, "FR", "Société Générale d'Hébergement - 東京");
    assertEquals("Société Générale d'Hébergement - 東京",
        find(builder.build(), "10.0.0.1").orElseThrow().name());
  }

  @Test
  void theFootprintGrowsWithTheRangesAndNotWithObjects() {
    final AsnDatabase.Builder builder = AsnDatabase.builder();
    final int ranges = 10_000;
    for (int i = 0; i < ranges; i++) {
      builder.addIpv4(i * 512L, i * 512L + 255, 1000L + i % 100, "FR", "AS " + i % 100);
    }
    final long bytes = builder.build().approximateBytes();
    assertTrue(bytes >= 12L * ranges, "at least 12 bytes per range: " + bytes);
    assertTrue(bytes < 20L * ranges, "and not much more: " + bytes);
  }
}
