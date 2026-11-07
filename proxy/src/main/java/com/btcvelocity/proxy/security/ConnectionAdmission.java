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

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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

  private final HandshakeHosts hosts;
  private final AdmissionStats stats;
  private final Set<String> loggedHosts = ConcurrentHashMap.newKeySet();
  private final AtomicBoolean refusalWarned = new AtomicBoolean();
  private final AtomicBoolean observeCapWarned = new AtomicBoolean();

  /**
   * Creates the checks of one configuration.
   *
   * @param hosts the handshake host policy
   * @param stats the counters to feed
   */
  public ConnectionAdmission(final HandshakeHosts hosts, final AdmissionStats stats) {
    this.hosts = hosts;
    this.stats = stats;
  }

  public AdmissionStats stats() {
    return this.stats;
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
