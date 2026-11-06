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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.btcvelocity.api.connection.ListenerOrigin;
import com.btcvelocity.proxy.security.GeyserListenerAddress;
import com.btcvelocity.proxy.security.TrustedProxies;
import com.velocitypowered.api.event.proxy.ListenerBoundEvent;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.config.VelocityConfiguration;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.event.VelocityEventManager;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The named listener on a real server socket (loopback, port 0), through the real
 * {@link ConnectionManager#bind}: what the listener name, the trusted sources and the PROXY header
 * give on an accepted connection. The assertions the embedded-channel tests cannot make.
 */
@SuppressWarnings("deprecation") // the initializer holder's setter is how a plugin wraps it
class ListenerBootstrapTest {

  private static final InetSocketAddress ANY_PORT =
      new InetSocketAddress(InetAddress.ofLiteral("127.0.0.1"), 0);
  private static final byte[] HEADER =
      "PROXY TCP4 203.0.113.7 127.0.0.1 4242 25566\r\n".getBytes(StandardCharsets.US_ASCII);
  private static final InetSocketAddress PLAYER =
      new InetSocketAddress(InetAddress.ofLiteral("203.0.113.7"), 4242);

  private final BlockingQueue<Channel> initialized = new LinkedBlockingQueue<>();
  private VelocityConfiguration configuration;
  private VelocityEventManager events;
  private ConnectionManager manager;

  @BeforeEach
  void startManager() {
    final VelocityServer server = mock(VelocityServer.class);
    this.configuration = mock(VelocityConfiguration.class);
    this.events = mock(VelocityEventManager.class);
    when(server.getConfiguration()).thenReturn(this.configuration);
    when(server.getEventManager()).thenReturn(this.events);
    doReturn(CompletableFuture.completedFuture(null)).when(this.events).fire(any());
    when(this.configuration.getPacketLimiterConfig())
        .thenReturn(VelocityConfiguration.PacketLimiterConfig.DEFAULT);
    when(this.configuration.getProxyProtocolTrustedProxies())
        .thenReturn(TrustedProxies.parse(TrustedProxies.DEFAULT_ENTRIES));
    trustOnGeyser(GeyserListenerAddress.DEFAULT_TRUSTED_PROXIES);

    this.manager = new ConnectionManager(server);
    // As a plugin wrapping the initializer would: the real one runs, and every call is recorded
    final ServerChannelInitializer real = new ServerChannelInitializer(server);
    this.manager.serverChannelInitializer.set(new ChannelInitializer<Channel>() {
      @Override
      protected void initChannel(final Channel ch) {
        real.initChannel(ch);
        initialized.add(ch);
      }
    });
  }

  @AfterEach
  void stopManager() {
    this.manager.shutdown();
    this.manager.getBossGroup().shutdownGracefully(0, 1, TimeUnit.SECONDS);
  }

  private void trustOnGeyser(final List<String> entries) {
    when(this.configuration.getGeyserListenerTrustedProxies())
        .thenReturn(TrustedProxies.parse(entries));
  }

  private Socket connectTo(final InetSocketAddress requested)
      throws IOException, InterruptedException {
    // bind() returns when the socket is open; the endpoint is registered by a listener right after
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (this.manager.localAddressOf(requested) == null && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    final InetSocketAddress bound = this.manager.localAddressOf(requested);
    assertNotNull(bound, "the listener must be bound");
    final Socket socket = new Socket(bound.getAddress(), bound.getPort());
    socket.setSoTimeout(5000);
    return socket;
  }

  private static SocketAddress remoteOf(final MinecraftConnection connection,
      final SocketAddress expected) throws InterruptedException {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!expected.equals(connection.getRemoteAddress()) && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    return connection.getRemoteAddress();
  }

  @Test
  void aHeaderFromLoopbackOnTheGeyserListenerGivesTheListenerNameAndTheHeaderAddress()
      throws Exception {
    this.manager.bind(ANY_PORT, ListenerOrigin.GEYSER);

    try (Socket client = connectTo(ANY_PORT)) {
      client.getOutputStream().write(HEADER);
      client.getOutputStream().flush();

      final Channel child = this.initialized.poll(10, TimeUnit.SECONDS);
      assertNotNull(child, "a trusted source gets a pipeline");
      final MinecraftConnection connection = child.pipeline().get(MinecraftConnection.class);
      assertEquals(Optional.of("geyser"), connection.getListenerName());
      assertEquals(PLAYER, remoteOf(connection, PLAYER),
          "the address of the connection is the one of the PROXY header");
    }
  }

  @Test
  void aListThatExcludesLoopbackClosesTheConnectionAndNothingIsInitialized() throws Exception {
    trustOnGeyser(List.of("10.0.0.0/8"));
    this.manager.bind(ANY_PORT, ListenerOrigin.GEYSER);

    try (Socket client = connectTo(ANY_PORT)) {
      client.getOutputStream().write(HEADER);
      client.getOutputStream().flush();
      final InputStream in = client.getInputStream();

      int read = 0;
      try {
        read = in.read();
      } catch (IOException reset) {
        read = -1; // closed with unread data: the peer may report a reset instead of an end
      }

      assertEquals(-1, read, "the server closes the connection without answering");
      assertNull(this.initialized.poll(300, TimeUnit.MILLISECONDS),
          "no initializer (nor any plugin wrapping it) is called for a refused source");
    }
  }

  @Test
  void theMainListenerHasNoNameAndOnlyItAnnouncesItselfToPlugins() throws Exception {
    final InetSocketAddress main = new InetSocketAddress(InetAddress.ofLiteral("127.0.0.2"), 0);
    this.manager.bind(ANY_PORT, ListenerOrigin.GEYSER);
    this.manager.bind(main, null);

    try (Socket client = connectTo(main)) {
      final Channel child = this.initialized.poll(10, TimeUnit.SECONDS);
      assertNotNull(child);
      assertTrue(child.pipeline().get(MinecraftConnection.class).getListenerName().isEmpty());
    }

    verify(this.events, timeout(5000).atLeastOnce()).fireAndForget(isA(ListenerBoundEvent.class));
    Thread.sleep(200);
    verify(this.events, times(1)).fireAndForget(any());
  }
}
