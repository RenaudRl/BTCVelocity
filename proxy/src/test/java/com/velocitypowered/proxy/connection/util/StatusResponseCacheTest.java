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

package com.velocitypowered.proxy.connection.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.btcvelocity.proxy.cluster.VelocityClusterPlayerService;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.config.VelocityConfiguration;
import com.velocitypowered.proxy.event.VelocityEventManager;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The status cache seen from the path a client ping takes: initial ping, ProxyPingEvent, JSON.
 */
class StatusResponseCacheTest {

  @TempDir
  Path dir;

  private final AtomicInteger events = new AtomicInteger();
  private final AtomicLong nanos = new AtomicLong();
  private final VelocityServer server = mock(VelocityServer.class);
  private ServerListPingHandler handler;
  private CompletableFuture<Void> eventGate = CompletableFuture.completedFuture(null);

  @BeforeEach
  void setUp() {
    final VelocityEventManager eventManager = mock(VelocityEventManager.class);
    // The listener "decorates" the ping so that every build yields a distinct response.
    doAnswer(invocation -> {
      final ProxyPingEvent event = invocation.getArgument(0);
      final int n = events.incrementAndGet();
      event.setPing(event.getPing().asBuilder().description(Component.text("build-" + n)).build());
      return eventGate.thenApply(ignored -> event);
    }).when(eventManager).fire(any(ProxyPingEvent.class));
    when(server.getEventManager()).thenReturn(eventManager);
    when(server.getVersion()).thenReturn(
        new com.velocitypowered.api.util.ProxyVersion("Velocity", "Test", "1"));
    final VelocityClusterPlayerService players = mock(VelocityClusterPlayerService.class);
    when(server.getClusterPlayerService()).thenReturn(players);
    handler = new ServerListPingHandler(server, new StatusPingCache(nanos::get));
  }

  private void ttl(final String value) throws IOException {
    final Path secret = dir.resolve("forwarding.secret");
    Files.writeString(secret, "secret");
    final Path file = dir.resolve("velocity.toml");
    try (InputStream in = VelocityConfiguration.class.getClassLoader()
        .getResourceAsStream("default-velocity.toml")) {
      final String defaults = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      Files.writeString(file, defaults
          .replace("forwarding-secret-file = \"forwarding.secret\"",
              "forwarding-secret-file = '" + secret + "'")
          .replace("status-cache-ttl = 0", "status-cache-ttl = " + value), StandardCharsets.UTF_8);
    }
    when(server.getConfiguration()).thenReturn(VelocityConfiguration.read(file));
  }

  private static VelocityInboundConnection client(final ProtocolVersion version, final String host) {
    final VelocityInboundConnection connection = mock(VelocityInboundConnection.class);
    when(connection.getProtocolVersion()).thenReturn(version);
    when(connection.getVirtualHost()).thenReturn(
        host == null ? Optional.empty() : Optional.of(InetSocketAddress.createUnresolved(host, 25565)));
    return connection;
  }

  private String ping(final VelocityInboundConnection connection) throws Exception {
    return handler.getStatusResponse(connection).get().orElseThrow();
  }

  @Test
  void twoPingsInsideTheTtlFireASingleEvent() throws Exception {
    ttl("5");
    final VelocityInboundConnection client = client(ProtocolVersion.MINECRAFT_1_21, "play.example.com");

    final String first = ping(client);
    nanos.addAndGet(4_000_000_000L);
    final String second = ping(client);

    assertEquals(1, events.get());
    assertEquals(first, second);
    assertTrue(first.contains("build-1"), first);
    assertEquals(1, handler.getStatusCache().hits());
    assertEquals(1, handler.getStatusCache().misses());
  }

  @Test
  void afterTheTtlThePingIsBuiltAgain() throws Exception {
    ttl("5");
    final VelocityInboundConnection client = client(ProtocolVersion.MINECRAFT_1_21, "play.example.com");

    ping(client);
    nanos.addAndGet(5_000_000_000L);
    final String again = ping(client);

    assertEquals(2, events.get());
    assertTrue(again.contains("build-2"), again);
  }

  @Test
  void eachProtocolVersionAndEachHostHasItsOwnEntry() throws Exception {
    ttl("5");
    ping(client(ProtocolVersion.MINECRAFT_1_21, "play.example.com"));
    ping(client(ProtocolVersion.MINECRAFT_1_20_2, "play.example.com"));
    ping(client(ProtocolVersion.MINECRAFT_1_21, "forced.example.com"));
    ping(client(ProtocolVersion.MINECRAFT_1_21, null));
    assertEquals(4, events.get());

    // The host is compared in lower case, like forced hosts are.
    ping(client(ProtocolVersion.MINECRAFT_1_21, "PLAY.example.com"));
    assertEquals(4, events.get());
  }

  @Test
  void withTheCacheOffEveryPingFiresTheEvent() throws Exception {
    for (final String value : new String[] {"0", "-1"}) {
      events.set(0);
      ttl(value);
      final VelocityInboundConnection client = client(ProtocolVersion.MINECRAFT_1_21, "play.example.com");

      final String first = ping(client);
      final String second = ping(client);

      assertEquals(2, events.get(), value);
      assertFalse(first.equals(second), value);
    }
  }

  @Test
  void simultaneousPingsOnOneKeyShareASingleBuild() throws Exception {
    ttl("5");
    eventGate = new CompletableFuture<>();
    final VelocityInboundConnection client = client(ProtocolVersion.MINECRAFT_1_21, "play.example.com");

    final CompletableFuture<Optional<String>> a = handler.getStatusResponse(client);
    final CompletableFuture<Optional<String>> b = handler.getStatusResponse(client);
    final CompletableFuture<Optional<String>> c = handler.getStatusResponse(client);
    assertFalse(a.isDone());
    eventGate.complete(null);

    assertEquals(1, events.get());
    assertEquals(a.get(), b.get());
    assertEquals(a.get(), c.get());
  }

  @Test
  void aPingRefusedByAPluginIsNotKept() throws Exception {
    ttl("5");
    final VelocityEventManager eventManager = server.getEventManager();
    doAnswer(invocation -> {
      final ProxyPingEvent event = invocation.getArgument(0);
      events.incrementAndGet();
      event.setResult(ResultedEvent.GenericResult.denied());
      return CompletableFuture.completedFuture(event);
    }).when(eventManager).fire(any(ProxyPingEvent.class));
    final VelocityInboundConnection client = client(ProtocolVersion.MINECRAFT_1_21, "play.example.com");

    assertTrue(handler.getStatusResponse(client).get().isEmpty());
    assertTrue(handler.getStatusResponse(client).get().isEmpty());
    assertEquals(2, events.get());
  }
}
