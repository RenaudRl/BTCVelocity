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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LinkKeyTest {

  /** An obvious test key, 32 bytes: never a real one. */
  static final String TEST_KEY = base64("test-only-key-test-only-key-0001");
  private static final String OTHER_TEST_KEY = base64("test-only-key-test-only-key-0002");
  private static final UUID ACCOUNT = UUID.fromString("33333333-3333-3333-3333-333333333333");
  private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");

  static String base64(final String text) {
    return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.US_ASCII));
  }

  static LinkKey key(final String value) {
    return LinkKey.fromEnvironment("TEST_KEY", value, error -> { }).orElseThrow();
  }

  private static Sighting seen(final LinkKey key, final String address) {
    return key.sighting(ACCOUNT, InetAddress.ofLiteral(address), NOW);
  }

  @Test
  void sameAddressSameDigest() {
    final LinkKey key = key(TEST_KEY);

    assertArrayEquals(seen(key, "198.51.100.7").ipDigest(), seen(key, "198.51.100.7").ipDigest());
    assertFalse(Arrays.equals(seen(key, "198.51.100.7").ipDigest(),
        seen(key, "198.51.100.8").ipDigest()));
    assertArrayEquals(seen(key, "198.51.100.7").netDigest(), seen(key, "198.51.100.8").netDigest());
  }

  @Test
  void otherKeyOtherDigest() {
    assertFalse(Arrays.equals(seen(key(TEST_KEY), "198.51.100.7").ipDigest(),
        seen(key(OTHER_TEST_KEY), "198.51.100.7").ipDigest()));
  }

  @Test
  void mappedIpv4EqualsIpv4() {
    final LinkKey key = key(TEST_KEY);

    assertArrayEquals(seen(key, "198.51.100.7").ipDigest(),
        seen(key, "::ffff:198.51.100.7").ipDigest());
    assertArrayEquals(seen(key, "198.51.100.7").ipDigest(),
        seen(key, "2002:c633:6407::1").ipDigest(), "6to4 carries 198.51.100.7");
  }

  @Test
  void ipv6AddressIsThe64AndNetworkThe48() {
    final LinkKey key = key(TEST_KEY);

    assertArrayEquals(seen(key, "2001:db8:1:2::1").ipDigest(),
        seen(key, "2001:db8:1:2:ffff::9").ipDigest(), "a new address in the same /64");
    assertFalse(Arrays.equals(seen(key, "2001:db8:1:2::1").ipDigest(),
        seen(key, "2001:db8:1:3::1").ipDigest()));
    assertArrayEquals(seen(key, "2001:db8:1:2::1").netDigest(),
        seen(key, "2001:db8:1:ffff::1").netDigest(), "the same /48");
    assertFalse(Arrays.equals(seen(key, "2001:db8:1::1").netDigest(),
        seen(key, "2001:db8:2::1").netDigest()));
  }

  @Test
  void shortKeyDisablesLinks() {
    final List<String> errors = new ArrayList<>();

    assertTrue(LinkKey.fromEnvironment("K", null, errors::add).isEmpty());
    assertTrue(LinkKey.fromEnvironment("K", " ", errors::add).isEmpty());
    assertTrue(LinkKey.fromEnvironment("K", "not base64 !", errors::add).isEmpty());
    assertTrue(LinkKey.fromEnvironment("K", base64("thirty-one-bytes-only-0123456789")
        .substring(0, 40), errors::add).isEmpty(), "30 bytes");
    assertEquals(4, errors.size());
    assertTrue(errors.stream().allMatch(error -> error.contains("DISABLED")), errors.toString());
  }

  @Test
  void toStringHidesKey() {
    final List<String> errors = new ArrayList<>();
    final String shortKey = base64("short-secret-value");
    LinkKey.fromEnvironment("K", shortKey, errors::add);

    assertEquals("LinkKey[32 bytes]", key(TEST_KEY).toString());
    assertFalse(errors.getFirst().contains(shortKey), errors.getFirst());
    assertFalse(seen(key(TEST_KEY), "198.51.100.7").toString().contains("198.51"));
  }
}
