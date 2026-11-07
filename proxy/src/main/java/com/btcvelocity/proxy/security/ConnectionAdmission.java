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

import com.velocitypowered.proxy.util.ratelimit.Ratelimiter;
import com.velocitypowered.proxy.util.ratelimit.Ratelimiters;
import java.net.InetAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

/**
 * The checks made on a connection before the proxy spends an event on it.
 *
 * <p>One instance per configuration (rebuilt on reload); the counters live in {@link AdmissionStats}
 * and survive it. Thread-safe: called from every Netty worker.
 */
public final class ConnectionAdmission {

  private static final Logger LOGGER = LogManager.getLogger(ConnectionAdmission.class);

  // Bounds the log, not the behaviour: a scanner can send a new host every time.
  private static final int MAX_LOGGED_HOSTS = 50;
  private static final int MAX_LOGGED_HOST_LENGTH = 64;

  /** IPv6 bits that identify one subscriber: a /64 is what an ISP hands out to a single line. */
  private static final int IPV6_LIMIT_PREFIX_BYTES = 8;

  private final HandshakeHosts hosts;
  private final Ratelimiter<InetAddress> connectionLimiter;
  private final TrustedProxies connectionExempt;
  private final long handshakeTimeoutMillis;
  private final AdmissionStats stats;
  private final Set<String> loggedHosts = ConcurrentHashMap.newKeySet();
  private final AtomicBoolean refusalWarned = new AtomicBoolean();
  private final AtomicBoolean observeCapWarned = new AtomicBoolean();
  private final AtomicBoolean rateWarned = new AtomicBoolean();
  private final AtomicBoolean timeoutWarned = new AtomicBoolean();
  private final AtomicBoolean packetLimitWarned = new AtomicBoolean();

  /**
   * Creates the checks of one configuration.
   *
   * @param hosts the handshake host policy
   * @param connectionLimiter how many connections a source may open per window (keyed by
   *                          {@link #limitKey}); a no-op limiter turns the limit off
   * @param connectionExempt sources never limited
   * @param handshakeTimeoutMillis how long a connection may take to send its handshake; 0 = no
   *                               deadline
   * @param stats the counters to feed
   */
  public ConnectionAdmission(final HandshakeHosts hosts,
      final Ratelimiter<InetAddress> connectionLimiter, final TrustedProxies connectionExempt,
      final long handshakeTimeoutMillis, final AdmissionStats stats) {
    this.hosts = hosts;
    this.connectionLimiter = connectionLimiter;
    this.connectionExempt = connectionExempt;
    this.handshakeTimeoutMillis = handshakeTimeoutMillis;
    this.stats = stats;
  }

  /**
   * Host policy only: no connection limit, no handshake deadline.
   *
   * @param hosts the handshake host policy
   * @param stats the counters to feed
   */
  public ConnectionAdmission(final HandshakeHosts hosts, final AdmissionStats stats) {
    this(hosts, Ratelimiters.createWindowed(0, 0, 0), TrustedProxies.parse(List.of()), 0, stats);
  }

  public AdmissionStats stats() {
    return this.stats;
  }

  /**
   * How long a connection may take to send its handshake.
   *
   * @return milliseconds, 0 when there is no deadline
   */
  public long handshakeTimeoutMillis() {
    return this.handshakeTimeoutMillis;
  }

  /**
   * Counts a new connection of a source against its window. Every connection counts, a status ping
   * as much as a login: the point is the volume, not what is asked.
   *
   * @param source the client address, after PROXY protocol resolution when it is in use
   * @return {@code false} if the source opened too many connections: close this one
   */
  public boolean admitConnection(final InetAddress source) {
    if (this.connectionExempt.contains(source) || this.connectionLimiter.attempt(limitKey(source))) {
      return true;
    }
    this.stats.record(AdmissionStats.Result.RATE_LIMITED);
    if (this.rateWarned.compareAndSet(false, true)) {
      LOGGER.warn("Connection rate limit reached by {}: closing its connections until the window "
          + "ends (connection-rate-limit; further ones are logged at debug)", LogAddress.mask(source));
    } else if (LOGGER.isDebugEnabled()) {
      LOGGER.debug("Connection rate limit reached by {}", LogAddress.mask(source));
    }
    return false;
  }

  /** Counts a connection closed for being late with its handshake. */
  public void recordHandshakeTimeout() {
    this.stats.record(AdmissionStats.Result.HANDSHAKE_TIMEOUT);
    if (this.timeoutWarned.compareAndSet(false, true)) {
      LOGGER.warn("Closing a connection that did not send its handshake within {} ms "
          + "(handshake-timeout-ms; further ones are not logged, see btc_proxy_admission_total)",
          this.handshakeTimeoutMillis);
    }
  }

  /**
   * Counts and logs a connection closed by the packet limiter: a warning the first time (so a limit
   * set too low is noticed), debug after that, since a flood would fill the log.
   *
   * @param remote the client address (masked in the log)
   * @param player the player's name once the connection has one, otherwise {@code null}
   */
  public void recordPacketLimit(final SocketAddress remote, final @Nullable String player) {
    this.stats.record(AdmissionStats.Result.PACKET_LIMIT);
    final String who = player == null ? "an unidentified connection" : "player " + printable(player);
    if (this.packetLimitWarned.compareAndSet(false, true)) {
      LOGGER.warn("Packet limit reached by {} from {}: closing the connection ([packet-limiter] "
          + "packets-per-second, bytes-per-second; further ones are logged at debug)",
          who, LogAddress.of(remote));
    } else if (LOGGER.isDebugEnabled()) {
      LOGGER.debug("Packet limit reached by {} from {}", who, LogAddress.of(remote));
    }
  }

  /**
   * What a source is counted as: its IPv4 address, or the /64 of an IPv6 one (a single line owns
   * 2^64 addresses, counting them one by one would limit nothing).
   *
   * @param source the client address
   * @return the address to count
   */
  static InetAddress limitKey(final InetAddress source) {
    final byte[] bytes = source.getAddress();
    if (bytes.length != 16) {
      return source;
    }
    final byte[] masked = new byte[16];
    System.arraycopy(bytes, 0, masked, 0, IPV6_LIMIT_PREFIX_BYTES);
    try {
      return InetAddress.getByAddress(masked);
    } catch (final UnknownHostException impossible) {
      throw new IllegalStateException("an address of 16 bytes", impossible);
    }
  }

  /**
   * Judges the host of a handshake, counts and logs what is outside the list.
   *
   * @param serverAddress the host as it came in the handshake packet
   * @param exempt whether this connection is not judged (the Geyser listener: Geyser sends the
   *               address it was configured with, not a public name)
   * @return {@link HandshakeHosts.Verdict#REFUSE} if the connection must be closed
   */
  public HandshakeHosts.Verdict checkHost(final String serverAddress, final boolean exempt) {
    if (exempt) {
      return HandshakeHosts.Verdict.ADMIT;
    }
    final HandshakeHosts.Verdict verdict = this.hosts.check(serverAddress);
    switch (verdict) {
      case OBSERVED -> {
        this.stats.record(AdmissionStats.Result.HOST_OBSERVED);
        logObserved(serverAddress);
      }
      case REFUSE -> {
        this.stats.record(AdmissionStats.Result.HOST_REFUSED);
        logRefused(serverAddress);
      }
      default -> { }
    }
    return verdict;
  }

  private void logObserved(final String serverAddress) {
    // Compared form, so "Play.Example.com" and "play.example.com." are one line.
    final String host = printable(HandshakeHosts.normalize(serverAddress));
    if (this.loggedHosts.size() < MAX_LOGGED_HOSTS) {
      if (this.loggedHosts.add(host)) {
        LOGGER.info("Handshake host '{}' is not in handshake-allowed-hosts or forced-hosts "
            + "(observe mode, connection admitted; each host is logged once)", host);
      }
    } else if (this.observeCapWarned.compareAndSet(false, true)) {
      LOGGER.info("More than {} different unlisted handshake hosts seen: further ones are only "
          + "counted (btc_proxy_admission_total)", MAX_LOGGED_HOSTS);
    }
  }

  private void logRefused(final String serverAddress) {
    final String host = printable(serverAddress);
    // First refusal at warn so a wrong list is visible; later ones at debug, since anyone can
    // open connections and fill the log.
    if (this.refusalWarned.compareAndSet(false, true)) {
      LOGGER.warn("Refusing handshake host '{}': not in handshake-allowed-hosts or forced-hosts "
          + "(further refusals are logged at debug)", host);
    } else {
      LOGGER.debug("Refusing handshake host '{}'", host);
    }
  }

  /** A host from the network, safe for the log: cut at NUL, short, printable ASCII only. */
  static String printable(final String serverAddress) {
    final int nul = serverAddress.indexOf('\0');
    final String host = nul > -1 ? serverAddress.substring(0, nul) : serverAddress;
    final StringBuilder out = new StringBuilder(Math.min(host.length(), MAX_LOGGED_HOST_LENGTH));
    for (int i = 0; i < host.length() && i < MAX_LOGGED_HOST_LENGTH; i++) {
      final char c = host.charAt(i);
      out.append(c >= 0x20 && c < 0x7f ? c : '?');
    }
    return out.toString();
  }
}
