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

package com.btcvelocity.proxy.observability;

import com.google.common.net.HostAndPort;
import com.google.common.net.InetAddresses;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A read-only HTTP listener for {@code /metrics} (Prometheus text format 0.0.4) and
 * {@code /healthz}.
 *
 * <p>It has no authentication, so it only ever binds to a loopback address: both
 * {@link #parseLoopbackBind(String)} (used by the configuration check) and the constructor refuse
 * anything else. A scraper on another host has to go through something that authenticates.</p>
 */
public final class MetricsEndpoint implements AutoCloseable {

  private static final Logger LOGGER = LogManager.getLogger(MetricsEndpoint.class);

  private static final String METRICS_CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

  private final HttpServer server;
  private final Set<String> allowedHosts;

  /**
   * Binds and starts the listener.
   *
   * @param bind loopback address and port to listen on; port 0 picks a free one
   * @param metrics produces the {@code /metrics} body, called on the HTTP thread
   * @param healthy tells whether {@code /healthz} answers 200
   * @throws IOException if the port cannot be bound
   * @throws IllegalArgumentException if the address is not a loopback address
   */
  public MetricsEndpoint(final InetSocketAddress bind, final Supplier<String> metrics,
                         final BooleanSupplier healthy) throws IOException {
    final InetAddress address = bind.getAddress();
    if (address == null || !address.isLoopbackAddress()) {
      throw new IllegalArgumentException("The metrics endpoint only binds to loopback, not " + bind);
    }
    // Without this, a web page could reach the listener through DNS rebinding.
    this.allowedHosts = new HashSet<>(List.of("127.0.0.1", "localhost", "::1", address.getHostAddress()));
    this.server = HttpServer.create(bind, 0);
    server.createContext("/", exchange -> {
      try {
        final String path = exchange.getRequestURI().getPath();
        if (!hostAllowed(exchange.getRequestHeaders().getFirst("Host"))) {
          reply(exchange, 403, "text/plain; charset=utf-8", "forbidden\n");
        } else if (!"GET".equals(exchange.getRequestMethod())) {
          reply(exchange, 405, "text/plain; charset=utf-8", "method not allowed\n");
        } else if ("/metrics".equals(path)) {
          reply(exchange, 200, METRICS_CONTENT_TYPE, metrics.get());
        } else if ("/healthz".equals(path)) {
          final boolean ok = healthy.getAsBoolean();
          reply(exchange, ok ? 200 : 503, "text/plain; charset=utf-8", ok ? "ok\n" : "stuck\n");
        } else {
          reply(exchange, 404, "text/plain; charset=utf-8", "not found\n");
        }
      } catch (final RuntimeException e) {
        LOGGER.warn("Metrics request failed", e);
        reply(exchange, 500, "text/plain; charset=utf-8", "error\n");
      }
    });
    server.start();
  }

  /**
   * Parses {@code host:port} and requires the host to be a loopback address.
   *
   * @param bind an IP literal or {@code localhost}, then a port; IPv6 in brackets
   * @return the address to bind
   * @throws IllegalArgumentException if it is malformed, names a host rather than an IP literal,
   *     or is not loopback
   */
  public static InetSocketAddress parseLoopbackBind(final String bind) {
    final HostAndPort hostAndPort = HostAndPort.fromString(bind.trim());
    if (!hostAndPort.hasPort()) {
      throw new IllegalArgumentException("missing port");
    }
    final String host = hostAndPort.getHost();
    // No DNS lookup at configuration time, and no name that could resolve elsewhere later.
    final InetAddress address;
    if ("localhost".equalsIgnoreCase(host)) {
      address = InetAddress.getLoopbackAddress();
    } else if (InetAddresses.isInetAddress(host)) {
      address = InetAddresses.forString(host);
    } else {
      throw new IllegalArgumentException("'" + host + "' is not an IP address");
    }
    if (!address.isLoopbackAddress()) {
      throw new IllegalArgumentException("'" + host + "' is not a loopback address");
    }
    return new InetSocketAddress(address, hostAndPort.getPort());
  }

  // A missing Host is accepted: a local HTTP/1.0 scraper does not have to send one.
  private boolean hostAllowed(final String hostHeader) {
    if (hostHeader == null) {
      return true;
    }
    try {
      return allowedHosts.contains(HostAndPort.fromString(hostHeader.trim()).getHost()
          .toLowerCase(Locale.ROOT));
    } catch (final IllegalArgumentException e) {
      return false;
    }
  }

  /**
   * Gets the port actually bound.
   *
   * @return the listening port
   */
  public int port() {
    return server.getAddress().getPort();
  }

  @Override
  public void close() {
    server.stop(0);
  }

  private static void reply(final HttpExchange exchange, final int status, final String type,
                            final String body) throws IOException {
    final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", type);
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }
}
