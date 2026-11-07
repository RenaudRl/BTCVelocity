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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;

/** What the proxy counts and logs when the packet limiter closes a connection. */
class ConnectionAdmissionPacketLimitTest {

  private static final int EVENTS = 3;

  private static InetSocketAddress client(final String literal) {
    return new InetSocketAddress(InetAddress.ofLiteral(literal), 40000);
  }

  @Test
  void eachClosedConnectionIsCountedUnderPacketLimit() {
    final AdmissionStats stats = new AdmissionStats();
    final ConnectionAdmission admission = new ConnectionAdmission(
        HandshakeHosts.of(HandshakeHosts.Mode.OFF, List.of(), List.of()), stats);

    // another range than the log test: async log events of this test may reach its appender late
    admission.recordPacketLimit(client("198.51.100.9"), "Steve");
    admission.recordPacketLimit(client("198.51.100.9"), null);

    assertEquals(2, stats.count(AdmissionStats.Result.PACKET_LIMIT));
    assertEquals(0, stats.count(AdmissionStats.Result.RATE_LIMITED));
  }

  @Test
  void theFirstClosureWarnsWithAMaskedAddressAndThePlayerThenItIsDebug() throws Exception {
    final List<LogEvent> events = new CopyOnWriteArrayList<>();
    final AbstractAppender appender = new AbstractAppender("packet-limit", null, null, true,
        Property.EMPTY_ARRAY) {
      @Override
      public void append(final LogEvent event) {
        if (event.getMessage().getFormattedMessage().contains("203.0.113.0/24")) {
          events.add(event.toImmutable());
        }
      }
    };
    appender.start();
    Configurator.setLevel(ConnectionAdmission.class.getName(), Level.DEBUG);
    final org.apache.logging.log4j.core.Logger logger =
        (org.apache.logging.log4j.core.Logger) LogManager.getLogger(ConnectionAdmission.class);
    logger.addAppender(appender);
    try {
      final ConnectionAdmission admission = new ConnectionAdmission(
          HandshakeHosts.of(HandshakeHosts.Mode.OFF, List.of(), List.of()), new AdmissionStats());

      admission.recordPacketLimit(client("203.0.113.9"), "Steve");
      admission.recordPacketLimit(client("203.0.113.77"), "Alex\nforged line");
      admission.recordPacketLimit(client("203.0.113.9"), null);

      // the proxy logs through async loggers: wait for the events to be delivered
      final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (events.size() < EVENTS && System.nanoTime() < deadline) {
        Thread.sleep(20);
      }

      assertEquals(List.of(Level.WARN, Level.DEBUG, Level.DEBUG),
          events.stream().map(LogEvent::getLevel).toList());
      final String warning = events.get(0).getMessage().getFormattedMessage();
      assertTrue(warning.contains("player Steve"), warning);
      assertTrue(warning.contains("203.0.113.0/24"), warning);
      assertFalse(warning.contains("203.0.113.9"), "the address is masked: " + warning);
      assertTrue(warning.contains("packets-per-second"), warning);
      final String forged = events.get(1).getMessage().getFormattedMessage();
      assertFalse(forged.contains("\n"), "a name cannot forge a log line: " + forged);
      assertTrue(events.get(2).getMessage().getFormattedMessage()
          .contains("an unidentified connection"));
    } finally {
      logger.removeAppender(appender);
      appender.stop();
    }
  }
}
