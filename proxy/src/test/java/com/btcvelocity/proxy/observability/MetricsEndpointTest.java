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

package com.btcvelocity.proxy.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.btcvelocity.proxy.bridge.BridgeMetrics;
import com.btcvelocity.proxy.security.AdmissionStats;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class MetricsEndpointTest {

  private static final HttpClient CLIENT = HttpClient.newHttpClient();

  @Test
  void metricsAreServedInPrometheusTextFormat() throws Exception {
    final BridgeMetrics bridge = new BridgeMetrics();
    bridge.record(BridgeMetrics.Event.RECEIVED);
    final String body = ProxyMetrics.render(7, Map.of("lobby", 3L, "sur\"vival", 4L),
        Map.of("survival", 2),
        bridge.snapshot(), 9, 2, 3, 1,
        Map.of(AdmissionStats.Result.HOST_OBSERVED, 5L),
        List.of(new LoopWatchdog.Reading("worker-0", 1_500_000, 4)));

    try (MetricsEndpoint endpoint = new MetricsEndpoint(
        new InetSocketAddress("127.0.0.1", 0), () -> body, () -> true)) {
      final HttpResponse<String> response = get(endpoint, "/metrics");

      assertEquals(200, response.statusCode());
      assertEquals("text/plain; version=0.0.4; charset=utf-8",
          response.headers().firstValue("Content-Type").orElseThrow());
      final String text = response.body();
      assertTrue(text.contains("btc_proxy_players_connected 7\n"), text);
      assertTrue(text.contains("btc_proxy_backend_players{server=\"lobby\"} 3\n"), text);
      assertTrue(text.contains("btc_proxy_backend_players{server=\"sur\\\"vival\"} 4\n"), text);
      assertTrue(text.contains("btc_proxy_queue_length{server=\"survival\"} 2\n"), text);
      assertTrue(text.contains("btc_proxy_bridge_events_total{event=\"received\"} 1\n"), text);
      assertTrue(text.contains("btc_proxy_status_cache_total{result=\"hit\"} 9\n"), text);
      assertTrue(text.contains("btc_proxy_status_cache_total{result=\"miss\"} 2\n"), text);
      assertTrue(text.contains("btc_proxy_admission_total{result=\"host_observed\"} 5\n"), text);
      assertTrue(text.contains("# TYPE btc_proxy_admission_total counter\n"), text);
      assertTrue(text.contains("btc_proxy_cluster_duplicate_sessions_total 3\n"), text);
      assertTrue(text.contains("# TYPE btc_proxy_cluster_duplicate_sessions_total counter\n"), text);
      assertTrue(text.contains("btc_proxy_cluster_duplicate_forced_closes_total 1\n"), text);
      assertTrue(text.contains("# TYPE btc_proxy_cluster_duplicate_forced_closes_total counter\n"),
          text);
      assertTrue(text.contains("btc_proxy_eventloop_lag_seconds{loop=\"worker-0\"} 0.001500000\n"),
          text);
      assertTrue(text.contains("btc_proxy_eventloop_pending_tasks{loop=\"worker-0\"} 4\n"), text);
      assertTrue(text.contains("jvm_memory_used_bytes{area=\"heap\"} "), text);
      assertTrue(text.contains("# TYPE btc_proxy_bridge_events_total counter\n"), text);
    }
  }

  @Test
  void healthzFollowsTheLoopsAndOtherPathsAreRefused() throws Exception {
    final AtomicBoolean healthy = new AtomicBoolean(true);
    try (MetricsEndpoint endpoint = new MetricsEndpoint(
        new InetSocketAddress("127.0.0.1", 0), () -> "", healthy::get)) {
      assertEquals(200, get(endpoint, "/healthz").statusCode());
      healthy.set(false);
      assertEquals(503, get(endpoint, "/healthz").statusCode());
      assertEquals(404, get(endpoint, "/other").statusCode());

      final HttpResponse<String> post = CLIENT.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + endpoint.port() + "/metrics"))
              .POST(HttpRequest.BodyPublishers.noBody()).build(),
          HttpResponse.BodyHandlers.ofString());
      assertEquals(405, post.statusCode());
    }
  }

  @Test
  void aForeignHostHeaderIsRefusedAgainstDnsRebinding() throws Exception {
    try (MetricsEndpoint endpoint = new MetricsEndpoint(
        new InetSocketAddress("127.0.0.1", 0), () -> "ok", () -> true)) {
      assertEquals(403, statusWithHost(endpoint, "evil.example"));
      assertEquals(403, statusWithHost(endpoint, "evil.example:" + endpoint.port()));
      assertEquals(200, statusWithHost(endpoint, "127.0.0.1:" + endpoint.port()));
      assertEquals(200, statusWithHost(endpoint, "localhost:" + endpoint.port()));
      assertEquals(200, statusWithHost(endpoint, "[::1]:" + endpoint.port()));
      assertEquals(200, statusWithHost(endpoint, null));
    }
  }

  @Test
  void theListenerRefusesToBindOutsideLoopback() {
    assertThrows(IllegalArgumentException.class,
        () -> new MetricsEndpoint(new InetSocketAddress("0.0.0.0", 0), () -> "", () -> true));
  }

  @Test
  void onlyLoopbackBindsParse() {
    assertEquals(9225, MetricsEndpoint.parseLoopbackBind("127.0.0.1:9225").getPort());
    assertTrue(MetricsEndpoint.parseLoopbackBind("[::1]:9225").getAddress().isLoopbackAddress());
    assertTrue(MetricsEndpoint.parseLoopbackBind("localhost:9225").getAddress().isLoopbackAddress());

    for (final String bad : List.of("0.0.0.0:9225", "8.8.8.8:9225", "192.168.1.10:9225",
        "[::]:9225", "example.com:9225", "127.0.0.1", "127.0.0.1:99999", "", ":9225")) {
      assertThrows(IllegalArgumentException.class, () -> MetricsEndpoint.parseLoopbackBind(bad),
          bad);
    }
  }

  /** Raw HTTP/1.0 request: the JDK client does not let a test choose the Host header. */
  private static int statusWithHost(final MetricsEndpoint endpoint, final String host)
      throws IOException {
    try (Socket socket = new Socket("127.0.0.1", endpoint.port())) {
      socket.getOutputStream().write(("GET /metrics HTTP/1.0\r\n"
          + (host == null ? "" : "Host: " + host + "\r\n") + "\r\n")
          .getBytes(StandardCharsets.US_ASCII));
      final String statusLine = new BufferedReader(new InputStreamReader(socket.getInputStream(),
          StandardCharsets.US_ASCII)).readLine();
      return Integer.parseInt(statusLine.split(" ")[1]);
    }
  }

  private static HttpResponse<String> get(final MetricsEndpoint endpoint, final String path)
      throws IOException, InterruptedException {
    return CLIENT.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + endpoint.port() + path)).build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
