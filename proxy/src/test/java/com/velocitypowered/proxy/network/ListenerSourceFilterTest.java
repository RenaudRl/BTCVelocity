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

package com.velocitypowered.proxy.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.btcvelocity.proxy.security.GeyserListenerAddress;
import com.btcvelocity.proxy.security.TrustedProxies;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.config.VelocityConfiguration;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.config.Configurator;
import org.junit.jupiter.api.Test;

/**
 * The source filter of a listener's server channel: who may send a PROXY header is decided before
 * the child channel is registered, so that no initializer (nor any plugin wrapping it) is called.
 */
class ListenerSourceFilterTest {

  /** In the main list's default (RFC 1918), not in the Geyser listener's (loopback only). */
  private static final String PRIVATE = "10.1.2.3";
  private static final String LOOPBACK = "127.0.0.1";
  private static final String STRANGER = "203.0.113.9";

  private static VelocityServer server(final boolean mainFlag) {
    final VelocityServer server = mock(VelocityServer.class);
    final VelocityConfiguration configuration = mock(VelocityConfiguration.class);
    when(server.getConfiguration()).thenReturn(configuration);
    when(configuration.isProxyProtocol()).thenReturn(mainFlag);
    when(configuration.getProxyProtocolTrustedProxies())
        .thenReturn(TrustedProxies.parse(TrustedProxies.DEFAULT_ENTRIES));
    when(configuration.getGeyserListenerTrustedProxies())
        .thenReturn(TrustedProxies.parse(GeyserListenerAddress.DEFAULT_TRUSTED_PROXIES));
    return server;
  }

  @Test
  void theGeyserListenerAdmitsOnlyItsOwnTrustedSourcesEvenWithTheMainFlagOff() {
    final ListenerSourceFilter filter = new ListenerSourceFilter(server(false), "geyser");

    assertTrue(filter.admits(new PeerChannel(LOOPBACK)));
    assertFalse(filter.admits(new PeerChannel(PRIVATE)),
        "a backend in 10/8 must not be able to claim the Geyser origin");
    assertFalse(filter.admits(new PeerChannel(STRANGER)));
  }

  @Test
  void aPrivateSourceIsRefusedOnGeyserButAdmittedOnTheMainListenerWithTheDefaultLists() {
    final VelocityServer server = server(true);

    assertFalse(new ListenerSourceFilter(server, "geyser").admits(new PeerChannel(PRIVATE)));
    assertTrue(new ListenerSourceFilter(server, null).admits(new PeerChannel(PRIVATE)));
    assertFalse(new ListenerSourceFilter(server, null).admits(new PeerChannel(STRANGER)));
  }

  @Test
  void theMainListenerWithTheFlagOffAdmitsAnyPeer() {
    final ListenerSourceFilter filter = new ListenerSourceFilter(server(false), null);

    assertTrue(filter.admits(new PeerChannel(STRANGER)));
    assertTrue(filter.admits(new PeerChannel(PRIVATE)));
  }

  @Test
  void aRefusedChildIsClosedAndNeverReachesTheAcceptor() {
    final EmbeddedChannel serverChannel =
        new EmbeddedChannel(new ListenerSourceFilter(server(false), "geyser"));
    final PeerChannel stranger = new PeerChannel(STRANGER);
    final PeerChannel relay = new PeerChannel(LOOPBACK);

    serverChannel.writeInbound(stranger);
    serverChannel.writeInbound(relay);

    assertFalse(stranger.isOpen());
    assertTrue(relay.isOpen());
    assertSame(relay, serverChannel.readInbound(), "only the admitted child goes on to the acceptor");
    assertNull(serverChannel.readInbound());
  }

  @Test
  void theDecisionTakenAtTheFilterIsTheOneTheInitializerAppliesWhateverTheConfigBecomes() {
    final VelocityServer server = server(false);
    final VelocityConfiguration configuration = server.getConfiguration();
    when(configuration.getPacketLimiterConfig())
        .thenReturn(VelocityConfiguration.PacketLimiterConfig.DEFAULT);
    final ListenerSourceFilter filter = new ListenerSourceFilter(server, null);
    final PeerChannel child = new PeerChannel(STRANGER);

    assertTrue(filter.admits(child), "flag off: admitted without a PROXY header");
    when(configuration.isProxyProtocol()).thenReturn(true); // reload between filter and worker
    new ServerChannelInitializer(server).initChannel(child);

    assertNull(child.pipeline().get(HAProxyMessageDecoder.class),
        "a connection admitted under the old value must not get a PROXY decoder");
  }

  /** The first refusal of each listener is a warn naming it, the following ones are debug. */
  @Test
  void theFirstRefusalOfEachListenerWarnsAndTheNextOnesAreDebug() throws InterruptedException {
    final List<LogEvent> events = new CopyOnWriteArrayList<>();
    final AbstractAppender appender = new AbstractAppender("refusals", null, null, true,
        Property.EMPTY_ARRAY) {
      @Override
      public void append(final LogEvent event) {
        events.add(event.toImmutable());
      }
    };
    appender.start();
    Configurator.setLevel(ListenerSourceFilter.class.getName(), Level.DEBUG);
    final org.apache.logging.log4j.core.Logger logger =
        (org.apache.logging.log4j.core.Logger) LogManager.getLogger(ListenerSourceFilter.class);
    logger.addAppender(appender);
    try {
      final VelocityServer server = server(true);
      final ListenerSourceFilter geyser = new ListenerSourceFilter(server, "geyser");
      final ListenerSourceFilter main = new ListenerSourceFilter(server, null);

      for (int i = 0; i < 3; i++) {
        new EmbeddedChannel(geyser).writeInbound(new PeerChannel(STRANGER));
      }
      for (int i = 0; i < 2; i++) {
        new EmbeddedChannel(main).writeInbound(new PeerChannel(STRANGER));
      }

      // the proxy logs through async loggers: wait for the events to be delivered
      final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (events.size() < 5 && System.nanoTime() < deadline) {
        Thread.sleep(20);
      }

      assertEquals(List.of(Level.WARN, Level.DEBUG, Level.DEBUG, Level.WARN, Level.DEBUG),
          events.stream().map(LogEvent::getLevel).toList());
      assertTrue(events.get(0).getMessage().getFormattedMessage().contains("geyser listener"));
      assertTrue(events.get(0).getMessage().getFormattedMessage()
          .contains("geyser-listener-trusted-proxies"));
      assertTrue(events.get(3).getMessage().getFormattedMessage().contains("main listener"));
      assertTrue(events.get(3).getMessage().getFormattedMessage()
          .contains("haproxy-protocol-trusted-proxies"));
    } finally {
      logger.removeAppender(appender);
      appender.stop();
    }
  }
}
