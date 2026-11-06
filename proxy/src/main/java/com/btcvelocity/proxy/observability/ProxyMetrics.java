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

import com.btcvelocity.proxy.bridge.BridgeMetrics;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writes the proxy's existing numbers in the Prometheus text exposition format (0.0.4).
 *
 * <p>Nothing is counted here: the caller hands over what the proxy already tracks, this class only
 * names it. Label values are escaped, so a server name can never break the format.</p>
 */
public final class ProxyMetrics {

  private ProxyMetrics() {
  }

  /**
   * Renders one scrape.
   *
   * @param players players connected to this proxy
   * @param playersByBackend players of this proxy, by backend name
   * @param queueLengths queue length, by backend name (empty when queues are off)
   * @param bridge bridge counters, by event
   * @param statusCacheHits status pings answered by the status cache
   * @param statusCacheMisses status pings the status cache had to build
   * @param loops event loop readings (empty when the watchdog is off)
   * @return the exposition text
   */
  public static String render(final long players, final Map<String, Long> playersByBackend,
                              final Map<String, Integer> queueLengths,
                              final Map<BridgeMetrics.Event, Long> bridge,
                              final long statusCacheHits, final long statusCacheMisses,
                              final List<LoopWatchdog.Reading> loops) {
    final StringBuilder out = new StringBuilder(2048);

    header(out, "btc_proxy_players_connected", "gauge", "Players connected to this proxy.");
    out.append("btc_proxy_players_connected ").append(players).append('\n');

    header(out, "btc_proxy_backend_players", "gauge",
        "Players of this proxy on each backend server.");
    playersByBackend.forEach((name, count) ->
        sample(out, "btc_proxy_backend_players", "server", name, String.valueOf(count)));

    header(out, "btc_proxy_queue_length", "gauge", "Players waiting in each server queue.");
    queueLengths.forEach((name, size) ->
        sample(out, "btc_proxy_queue_length", "server", name, String.valueOf(size)));

    header(out, "btc_proxy_bridge_events_total", "counter",
        "Messages handled by the btc:bridge channel, by outcome.");
    bridge.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(entry -> sample(out, "btc_proxy_bridge_events_total", "event",
            entry.getKey().name().toLowerCase(Locale.ROOT), String.valueOf(entry.getValue())));

    header(out, "btc_proxy_status_cache_total", "counter",
        "Server list pings answered from the status cache (hit) or built (miss); 0 while it is off.");
    sample(out, "btc_proxy_status_cache_total", "result", "hit", String.valueOf(statusCacheHits));
    sample(out, "btc_proxy_status_cache_total", "result", "miss", String.valueOf(statusCacheMisses));

    header(out, "btc_proxy_eventloop_lag_seconds", "gauge",
        "How late the last task marker ran on each Netty event loop.");
    for (final LoopWatchdog.Reading loop : loops) {
      sample(out, "btc_proxy_eventloop_lag_seconds", "loop", loop.loop(),
          String.format(Locale.ROOT, "%.9f", loop.lagNanos() / 1e9));
    }

    header(out, "btc_proxy_eventloop_pending_tasks", "gauge",
        "Tasks queued on each Netty event loop.");
    for (final LoopWatchdog.Reading loop : loops) {
      sample(out, "btc_proxy_eventloop_pending_tasks", "loop", loop.loop(),
          String.valueOf(loop.pendingTasks()));
    }

    final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    header(out, "jvm_memory_used_bytes", "gauge", "Used JVM memory.");
    sample(out, "jvm_memory_used_bytes", "area", "heap",
        String.valueOf(memory.getHeapMemoryUsage().getUsed()));
    sample(out, "jvm_memory_used_bytes", "area", "nonheap",
        String.valueOf(memory.getNonHeapMemoryUsage().getUsed()));

    return out.toString();
  }

  private static void header(final StringBuilder out, final String name, final String type,
                             final String help) {
    out.append("# HELP ").append(name).append(' ').append(help).append('\n');
    out.append("# TYPE ").append(name).append(' ').append(type).append('\n');
  }

  private static void sample(final StringBuilder out, final String name, final String label,
                             final String labelValue, final String value) {
    out.append(name).append('{').append(label).append("=\"")
        .append(labelValue.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"))
        .append("\"} ").append(value).append('\n');
  }
}
