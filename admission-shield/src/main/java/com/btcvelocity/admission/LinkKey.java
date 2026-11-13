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
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;

/**
 * The secret that turns an address into what is stored: HMAC-SHA256, never the address itself.
 *
 * <p>A stolen table alone tells nothing; the table AND the key give back every IPv4 address in
 * minutes (2^32 tries), so the key must never be stored or backed up with the database. It lives
 * in an environment variable of the proxy host, never in the database, never in a file of this
 * module, never in a log line ({@link #toString()} hides it).
 * Changing or losing the key makes every stored digest meaningless: the link history is lost, and
 * nothing else.
 */
public final class LinkKey {

  /** The shortest key accepted, in bytes. */
  public static final int MIN_BYTES = 32;

  private static final String ALGORITHM = "HmacSHA256";
  private static final int ADDRESS_V4 = 32;
  // An IPv6 subscriber holds a whole /64 and picks a new address in it at will.
  private static final int ADDRESS_V6 = 64;
  private static final int NETWORK_V4 = 24;
  private static final int NETWORK_V6 = 48;

  private final SecretKeySpec key;
  private final int length;

  private LinkKey(final byte[] secret) {
    this.key = new SecretKeySpec(secret, ALGORITHM);
    this.length = secret.length;
  }

  /**
   * Reads the key from the value of its environment variable.
   *
   * @param variable the name of the variable, for the messages
   * @param value its value (base64), or {@code null} when it is not set
   * @param errors receives one sentence when the key is unusable; it never contains the value
   * @return the key, or empty when it is absent, not base64 or shorter than {@link #MIN_BYTES}
   */
  public static Optional<LinkKey> fromEnvironment(final String variable,
      final @Nullable String value, final Consumer<String> errors) {
    if (value == null || value.isBlank()) {
      errors.accept("links: the environment variable " + variable + " is not set; account links"
          + " are DISABLED (the proxy runs without them)");
      return Optional.empty();
    }
    final byte[] secret;
    try {
      secret = Base64.getDecoder().decode(value.trim());
    } catch (IllegalArgumentException notBase64) {
      errors.accept("links: " + variable + " is not base64; account links are DISABLED");
      return Optional.empty();
    }
    if (secret.length < MIN_BYTES) {
      errors.accept("links: " + variable + " holds " + secret.length + " bytes, fewer than "
          + MIN_BYTES + "; account links are DISABLED");
      return Optional.empty();
    }
    if (trivial(secret)) {
      errors.accept("links: " + variable + " holds the same byte repeated; generate a random key."
          + " Account links are DISABLED");
      return Optional.empty();
    }
    return Optional.of(new LinkKey(secret));
  }

  private static boolean trivial(final byte[] secret) {
    for (byte each : secret) {
      if (each != secret[0]) {
        return false;
      }
    }
    return true;
  }

  /**
   * The stored form of an account seen on an address.
   *
   * @param account the account
   * @param address the judged address (a tunnel or an IPv4-mapped form is read as the IPv4 address)
   * @param at when
   * @return the sighting: the digests of its address (an IPv4 /32, an IPv6 /64: one subscriber)
   *     and of its network (an IPv4 /24, an IPv6 /48)
   */
  public Sighting sighting(final UUID account, final InetAddress address, final Instant at) {
    final InetAddress normal = AddressPolicy.unwrap(address);
    return new Sighting(account, digest("ip|" + Networks.of(normal, ADDRESS_V4, ADDRESS_V6)),
        digest("net|" + Networks.of(normal, NETWORK_V4, NETWORK_V6)), at);
  }

  private byte[] digest(final String text) {
    try {
      // A Mac is not thread-safe; one per digest costs microseconds, at most twice per login.
      final Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(this.key);
      return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException impossible) {
      throw new IllegalStateException("HmacSHA256 is required of every Java runtime", impossible);
    }
  }

  @Override
  public String toString() {
    return "LinkKey[" + this.length + " bytes]";
  }
}
