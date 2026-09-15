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

package com.btcvelocity.cloudnet.registry;

import com.velocitypowered.api.proxy.ProxyServer;
import eu.cloudnetservice.driver.event.EventListener;
import eu.cloudnetservice.driver.event.events.network.NetworkChannelCloseEvent;
import eu.cloudnetservice.driver.event.events.service.CloudServiceLifecycleChangeEvent;
import eu.cloudnetservice.driver.event.events.service.CloudServiceUpdateEvent;
import eu.cloudnetservice.driver.service.ServiceEnvironmentType;
import eu.cloudnetservice.driver.service.ServiceInfoSnapshot;
import eu.cloudnetservice.driver.service.ServiceLifeCycle;
import eu.cloudnetservice.wrapper.event.ServiceInfoPropertiesConfigureEvent;
import java.net.InetSocketAddress;
import java.util.List;
import org.slf4j.Logger;

/**
 * Translates CloudNet service events into registry decisions, and publishes this proxy's own
 * occupancy so the node can observe it.
 *
 * <p>It listens, it registers, it reports. It never moves a player: no fallback, no kick
 * redirect, no hub command, no pre-login query. Those belong to the proxy and to
 * {@code btc:bridge}.
 */
public final class CloudNetServiceListener {

  /**
   * Published by the CloudNet bridge plugin on each backend once the server accepts connections.
   * Registering before that would hand players a server that is still starting.
   */
  private static final String ONLINE_PROPERTY = "Online";

  private final Logger logger;
  private final ProxyServer proxy;
  private final ServiceRegistry registry;

  public CloudNetServiceListener(Logger logger, ProxyServer proxy, ServiceRegistry registry) {
    this.logger = logger;
    this.proxy = proxy;
    this.registry = registry;
  }

  /**
   * Turns a snapshot into the registry's view of it.
   */
  public static ServiceView toView(ServiceInfoSnapshot snapshot) {
    var environment = snapshot.serviceId().environment();
    var javaServer = environment != null
        && Boolean.TRUE.equals(environment.readProperty(ServiceEnvironmentType.JAVA_SERVER));
    var eligible = javaServer
        && snapshot.connected()
        && snapshot.lifeCycle() == ServiceLifeCycle.RUNNING
        && snapshot.propertyHolder().getBoolean(ONLINE_PROPERTY, false);

    return new ServiceView(
        snapshot.serviceId().uniqueId(),
        snapshot.name(),
        new InetSocketAddress(snapshot.address().host(), snapshot.address().port()),
        eligible);
  }

  /**
   * Applies a snapshot and logs only the transitions, never the steady state: a service updates
   * itself every few seconds and an unfiltered log would bury everything else.
   */
  public void accept(ServiceInfoSnapshot snapshot) {
    var view = toView(snapshot);
    if (this.registry.apply(view)) {
      if (view.eligible()) {
        this.logger.info("Registered CloudNet service {} at {}", view.name(), view.address());
      } else {
        this.logger.info("Unregistered CloudNet service {}", view.name());
      }
    }
  }

  @EventListener
  public void handleServiceUpdate(CloudServiceUpdateEvent event) {
    this.accept(event.serviceInfo());
  }

  @EventListener
  public void handleLifecycleChange(CloudServiceLifecycleChangeEvent event) {
    this.accept(event.serviceInfo());
  }

  /**
   * The node channel closed: every service we knew about is now stale. Stale is unavailable,
   * never empty — a service whose occupancy we can no longer observe must not stay a
   * destination.
   *
   * <p>This one runs on CloudNet's own network event loop. It is deliberately kept to in-memory
   * map removals: no outbound call, no blocking, no destination choice. Handing it to a
   * scheduler would open a window in which a reconnecting node re-registers a service just
   * before the queued drop removes it again.
   */
  @EventListener
  public void handleChannelClose(NetworkChannelCloseEvent event) {
    List<String> dropped = this.registry.dropAll();
    if (!dropped.isEmpty()) {
      this.logger.warn(
          "CloudNet channel closed ({}), dropped {} service(s) as stale: {}",
          event.channelType(),
          dropped.size(),
          String.join(", ", dropped));
    }
  }

  /**
   * Reports this proxy's occupancy to the node. Observation only — the numbers feed the
   * {@code smart} module and the panel, never a routing decision.
   */
  @EventListener
  public void handleOwnInfoConfigure(ServiceInfoPropertiesConfigureEvent event) {
    var configuration = this.proxy.getConfiguration();
    event.propertyHolder()
        .append(ONLINE_PROPERTY, Boolean.TRUE)
        .append("Online-Count", this.proxy.getPlayerCount())
        .append("Max-Players", configuration.getShowMaxPlayers());
  }
}
