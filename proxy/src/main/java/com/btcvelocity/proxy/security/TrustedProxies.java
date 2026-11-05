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

package com.btcvelocity.proxy.security;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * The sources allowed to send a PROXY protocol header, as a short list of networks.
 *
 * <p>Without this list anyone who reaches a PROXY-protocol listener can claim any client address
 * and walk past every per-address check (Sentinel, rate limits, bans). A connection from outside
 * the list is refused before its pipeline is built. An empty list trusts nobody.
 *
 * <p>An IPv4-mapped IPv6 address ({@code ::ffff:a.b.c.d}) is matched as the IPv4 address it wraps,
 * since a dual-stack socket reports IPv4 peers that way. Entries are address literals, never
 * host names: nothing here touches DNS.
 */
public final class TrustedProxies {

  /** Loopback, private and link-local networks: the usual place for a local relay. */
  public static final List<String> DEFAULT_ENTRIES = List.of(
      "127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16",
      "::1/128", "fc00::/7", "fe80::/10");

  private final List<Network> networks;
  private final List<String> acceptedEntries;
  private final List<String> invalidEntries;

  private TrustedProxies(final List<Network> networks, final List<String> acceptedEntries,
      final List<String> invalidEntries) {
    this.networks = networks;
    this.acceptedEntries = acceptedEntries;
    this.invalidEntries = invalidEntries;
  }

  /**
   * Reads a list of {@code address} or {@code address/prefix} entries. Entries that cannot be
   * read are kept aside in {@link #invalidEntries()} and trust nothing.
   *
   * @param entries the entries as written in the configuration
   * @return the parsed list
   */
  public static TrustedProxies parse(final List<String> entries) {
    final List<Network> networks = new ArrayList<>(entries.size());
    final List<String> accepted = new ArrayList<>(entries.size());
    final List<String> invalid = new ArrayList<>();
    for (final String entry : entries) {
      final Network network = Network.parse(entry);
      if (network == null) {
        invalid.add(String.valueOf(entry));
      } else {
        networks.add(network);
        accepted.add(entry.trim());
      }
    }
    return new TrustedProxies(List.copyOf(networks), List.copyOf(accepted), List.copyOf(invalid));
  }

  /**
   * The accepted entries for a log line, or {@code "nobody"} when none.
   *
   * @return a human-readable summary
   */
  public String describe() {
    return this.acceptedEntries.isEmpty() ? "nobody" : String.join(", ", this.acceptedEntries);
  }

  /**
   * Whether a PROXY header coming from this address may be believed.
   *
   * @param address the peer address of the TCP connection
   * @return {@code true} if a listed network contains it
   */
  public boolean contains(final InetAddress address) {
    final byte[] bytes = unmapped(address.getAddress());
    for (final Network network : this.networks) {
      if (network.contains(bytes)) {
        return true;
      }
    }
    return false;
  }

  /**
   * The entries that were not understood, for the configuration check to report.
   *
   * @return the rejected entries, as written
   */
  public List<String> invalidEntries() {
    return this.invalidEntries;
  }

  private static byte[] unmapped(final byte[] bytes) {
    if (bytes.length != 16) {
      return bytes;
    }
    for (int i = 0; i < 10; i++) {
      if (bytes[i] != 0) {
        return bytes;
      }
    }
    if (bytes[10] != (byte) 0xff || bytes[11] != (byte) 0xff) {
      return bytes;
    }
    return new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]};
  }

  private record Network(byte[] base, int prefix) {

    static Network parse(final String entry) {
      if (entry == null) {
        return null;
      }
      final String text = entry.trim();
      final int slash = text.indexOf('/');
      final byte[] address;
      try {
        address = unmapped(InetAddress.ofLiteral(slash < 0 ? text : text.substring(0, slash))
            .getAddress());
      } catch (IllegalArgumentException malformed) {
        return null;
      }
      final int width = address.length * 8;
      if (slash < 0) {
        return new Network(address, width);
      }
      final String prefixText = text.substring(slash + 1);
      if (prefixText.isEmpty() || prefixText.length() > 3
          || !prefixText.chars().allMatch(Character::isDigit)) {
        return null;
      }
      final int prefix = Integer.parseInt(prefixText);
      // Wider than /8 (IPv4) or /7 (IPv6) is a typo or "trust everyone": refuse it outright.
      if (prefix > width || prefix < (width == 32 ? 8 : 7)) {
        return null;
      }
      return new Network(address, prefix);
    }

    boolean contains(final byte[] candidate) {
      if (candidate.length != this.base.length) {
        return false;
      }
      final int fullBytes = this.prefix / 8;
      for (int i = 0; i < fullBytes; i++) {
        if (candidate[i] != this.base[i]) {
          return false;
        }
      }
      final int remainingBits = this.prefix % 8;
      if (remainingBits == 0) {
        return true;
      }
      final int mask = (0xff << (8 - remainingBits)) & 0xff;
      return (candidate[fullBytes] & mask) == (this.base[fullBytes] & mask);
    }
  }
}
