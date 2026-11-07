/*
 * Copyright (C) 2018-2026 Velocity Contributors
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

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The host names a client may put in its handshake ({@code serverAddress}), and what to do with
 * the others.
 *
 * <p>Scanners reach the proxy by IP address and send that address as the host; real players come
 * through DNS. The allowed set is the names written in {@code handshake-allowed-hosts} plus every
 * forced host. A host is compared the way Velocity reads it: everything after a NUL byte is dropped
 * (the Forge/FML marker {@code \0FML\0}, the BungeeCord forwarding tail), the DNS root dot is
 * dropped, and case is ignored.
 *
 * <p>Immutable and thread-safe.
 */
public final class HandshakeHosts {

  /** What to do with a host that is not in the list. */
  public enum Mode {
    /** Nothing is checked. */
    OFF,
    /** Unlisted hosts are counted and logged, never refused. */
    OBSERVE,
    /** Unlisted hosts are refused: the connection is closed. */
    ENFORCE;

    /**
     * Reads a configuration value.
     *
     * @param value the value as written, any case
     * @return the mode, or {@code null} if it is none of off, observe, enforce
     */
    public static @Nullable Mode parse(final @Nullable String value) {
      if (value == null) {
        return null;
      }
      try {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
      } catch (final IllegalArgumentException unknown) {
        return null;
      }
    }
  }

  /** The decision for one handshake. */
  public enum Verdict {
    /** Let it through: mode is off, or the host is listed. */
    ADMIT,
    /** Not listed, but the mode only observes: let it through, after counting it. */
    OBSERVED,
    /** Not listed and the mode enforces: close the connection. */
    REFUSE
  }

  private final Mode mode;
  private final Set<String> allowed;

  private HandshakeHosts(final Mode mode, final Set<String> allowed) {
    this.mode = mode;
    this.allowed = allowed;
  }

  /**
   * Builds the policy.
   *
   * @param mode what to do with an unlisted host
   * @param listed the hosts written in the configuration
   * @param forcedHosts the forced-host keys, always allowed
   * @return the policy
   */
  public static HandshakeHosts of(final Mode mode, final Collection<String> listed,
      final Collection<String> forcedHosts) {
    final Set<String> allowed = new HashSet<>();
    for (final String host : listed) {
      allowed.add(normalize(host));
    }
    for (final String host : forcedHosts) {
      allowed.add(normalize(host));
    }
    return new HandshakeHosts(mode, Set.copyOf(allowed));
  }

  /**
   * The form a host is compared in.
   *
   * @param serverAddress the host as sent by the client, or as written in the configuration
   * @return the part before any NUL byte, without the DNS root dot, in lower case
   */
  public static String normalize(final String serverAddress) {
    String host = serverAddress;
    final int nul = host.indexOf('\0');
    if (nul > -1) {
      host = host.substring(0, nul);
    }
    host = host.strip();
    int end = host.length();
    while (end > 0 && host.charAt(end - 1) == '.') {
      end--;
    }
    return host.substring(0, end).toLowerCase(Locale.ROOT);
  }

  public Mode mode() {
    return this.mode;
  }

  /**
   * Whether the list holds a name. With {@link Mode#ENFORCE} an empty list would refuse everyone:
   * the configuration is refused instead.
   *
   * @return {@code true} if no host is allowed at all
   */
  public boolean isEmpty() {
    return this.allowed.isEmpty();
  }

  /**
   * Judges a handshake host.
   *
   * @param serverAddress the host as it came in the handshake packet
   * @return what to do with the connection
   */
  public Verdict check(final String serverAddress) {
    if (this.mode == Mode.OFF || this.allowed.contains(normalize(serverAddress))) {
      return Verdict.ADMIT;
    }
    return this.mode == Mode.ENFORCE ? Verdict.REFUSE : Verdict.OBSERVED;
  }
}
