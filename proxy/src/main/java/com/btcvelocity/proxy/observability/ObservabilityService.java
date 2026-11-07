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

import com.btcvelocity.proxy.queue.VelocityQueue;
import com.btcvelocity.proxy.queue.VelocityQueueManager;
import com.btcvelocity.proxy.redis.VelocityRedis;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.config.VelocityConfiguration;
import com.velocitypowered.proxy.server.VelocityRegisteredServer;
import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.EventExecutor;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Starts and stops the event loop watchdog and the metrics listener, as configured in
 * {@code [observability]}.
 */
public final class ObservabilityService implements AutoCloseable {

  private static final Logger LOGGER = LogManager.getLogger(ObservabilityService.class);

  // Two checks per threshold, never faster than every 10 ms: a stall is sampled within ~1.5x the
  // threshold while the cost stays one tiny task per loop per check.
  private static final long MIN_INTERVAL_MILLIS = 10;

  // One stack per loop per minute is enough to find the culprit and cannot flood the log.
  private static final long REPORT_WINDOW_MILLIS = 60_000;

  private final LoopWatchdog watchdog;
  private final MetricsEndpoint endpoint;

  private ObservabilityService(final LoopWatchdog watchdog, final MetricsEndpoint endpoint) {
    this.watchdog = watchdog;
    this.endpoint = endpoint;
  }

  /**
   * Starts what the configuration asks for. A failure here (port already taken) is logged and
   * leaves the proxy running: observability must never keep players out.
   *
   * @param server the proxy
   * @param workerGroup the Netty worker loops
   * @param config the {@code [observability]} settings
   * @return the running service, to close at shutdown
   */
  public static ObservabilityService start(final VelocityServer server,
                                           final EventLoopGroup workerGroup,
                                           final VelocityConfiguration.Observability config) {
    LoopWatchdog watchdog = null;
    if (config.isWatchdogEnabled()) {
      final Map<String, EventExecutor> loops = new TreeMap<>();
      // Boss loops are left alone: accepting is trivial, and the marker would start every one of
      // them (2 x cores) although one serves. Connection work runs on the workers.
      int index = 0;
      for (final EventExecutor loop : workerGroup) {
        loops.put("worker-" + index++, loop);
      }
      final long threshold = config.getWatchdogThresholdMs();
      watchdog = new LoopWatchdog(loops, threshold,
          Math.max(MIN_INTERVAL_MILLIS, threshold / 2), REPORT_WINDOW_MILLIS,
          LoopWatchdog::logStall);
    }

    MetricsEndpoint endpoint = null;
    if (config.isPrometheusEnabled()) {
      final LoopWatchdog loopWatchdog = watchdog;
      try {
        endpoint = new MetricsEndpoint(
            MetricsEndpoint.parseLoopbackBind(config.getPrometheusBind()),
            () -> scrape(server, loopWatchdog),
            () -> loopWatchdog == null || loopWatchdog.isResponsive());
        LOGGER.info("Metrics listening on {} (/metrics, /healthz)", config.getPrometheusBind());
      } catch (final IOException | IllegalArgumentException e) {
        LOGGER.error("Unable to start the metrics listener on {}", config.getPrometheusBind(), e);
      }
    }
    return new ObservabilityService(watchdog, endpoint);
  }

  private static String scrape(final VelocityServer server, final LoopWatchdog watchdog) {
    final Map<String, Long> byBackend = new TreeMap<>();
    for (final VelocityRegisteredServer backend : server.getAllServers()) {
      byBackend.put(backend.getServerInfo().getName(), backend.getPlayerCount());
    }
    final Map<String, Integer> queues = new TreeMap<>();
    // The manager only exists if the queue was enabled at startup: a reload cannot create it.
    final VelocityQueueManager queueManager = server.getQueueManagerOrNull();
    if (queueManager != null) {
      for (final VelocityQueue queue : queueManager.getQueues()) {
        queues.put(queue.getName(), queue.size());
      }
    }
    final VelocityRedis redis = server.getRedis();
    return ProxyMetrics.render(server.getLocalPlayerCount(), byBackend, queues,
        server.getBridgeChannel().metrics().snapshot(),
        server.getServerListPingHandler().getStatusCache().hits(),
        server.getServerListPingHandler().getStatusCache().misses(),
        redis == null ? 0 : redis.getPlayerService().getDuplicateSessionsClosed(),
        redis == null ? 0 : redis.getPlayerService().getDuplicateForcedCloses(),
        server.getAdmissionStats().snapshot(),
        watchdog == null ? List.of() : watchdog.readings());
  }

  @Override
  public void close() {
    if (endpoint != null) {
      endpoint.close();
    }
    if (watchdog != null) {
      watchdog.close();
    }
  }
}
