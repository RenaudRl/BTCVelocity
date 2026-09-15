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

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import eu.cloudnetservice.driver.event.EventManager;
import eu.cloudnetservice.driver.inject.InjectionLayer;
import eu.cloudnetservice.driver.provider.CloudServiceProvider;
import eu.cloudnetservice.wrapper.holder.ServiceInfoHolder;
import java.net.InetSocketAddress;
import org.slf4j.Logger;

/**
 * Keeps the proxy's server list in step with the services CloudNet runs, and reports this proxy's
 * occupancy back to the node.
 *
 * <p>This plugin replaces the CloudNet bridge plugin <em>on the proxy</em> (AD-047). The bridge
 * is kept on the backends, where it only observes; on the proxy it also picked the initial
 * server, redirected on kick, served {@code /hub} and blocked every login on a query to the
 * node. None of that is wanted: destinations are a BTC decision, carried by {@code btc:bridge}.
 *
 * <p>Outside a CloudNet service the plugin loads and does nothing.
 */
@Plugin(
    id = "btc-cloudnet-registry",
    name = "BTC CloudNet Registry",
    version = "0.2",
    description = "Registers CloudNet services with the proxy. Never routes a player.",
    authors = {"BTC Studio"})
public final class BtcCloudNetRegistryPlugin {

  private final Logger logger;
  private final ProxyServer proxy;

  private volatile ServiceInfoHolder serviceInfoHolder;

  @Inject
  public BtcCloudNetRegistryPlugin(ProxyServer proxy, Logger logger) {
    this.proxy = proxy;
    this.logger = logger;
  }

  @Subscribe
  public void onProxyInitialize(ProxyInitializeEvent event) {
    if (!runningAsCloudNetService()) {
      this.logger.info("Not running as a CloudNet service, the registry stays idle.");
      return;
    }

    var registry = new ServiceRegistry(new ProxyRegistrar(this.proxy));
    var listener = new CloudNetServiceListener(this.logger, this.proxy, registry);

    var injectionLayer = InjectionLayer.ext();
    this.serviceInfoHolder = injectionLayer.instance(ServiceInfoHolder.class);
    injectionLayer.instance(EventManager.class).registerListener(listener);

    // Services that were already running when this proxy started emit no event: ask once.
    injectionLayer.instance(CloudServiceProvider.class).servicesAsync().thenAccept(services -> {
      for (var service : services) {
        listener.accept(service);
      }
      this.logger.info("CloudNet registry ready, {} service(s) known.", registry.registeredNames().size());
    }).exceptionally(throwable -> {
      this.logger.error("Unable to read the initial CloudNet service list.", throwable);
      return null;
    });
  }

  @Subscribe
  public void onPostLogin(PostLoginEvent event) {
    this.publishOccupancy();
  }

  @Subscribe
  public void onDisconnect(DisconnectEvent event) {
    this.publishOccupancy();
  }

  @Subscribe
  public void onProxyShutdown(ProxyShutdownEvent event) {
    this.publishOccupancy();
  }

  /**
   * Pushes this proxy's player count to the node. Pushed, never polled: the node asks the proxy
   * nothing, so an idle proxy costs nothing.
   */
  private void publishOccupancy() {
    var holder = this.serviceInfoHolder;
    if (holder != null) {
      holder.publishServiceInfoUpdate();
    }
  }

  private static boolean runningAsCloudNetService() {
    try {
      Class.forName("eu.cloudnetservice.wrapper.holder.ServiceInfoHolder");
      return true;
    } catch (ClassNotFoundException exception) {
      return false;
    }
  }

  /**
   * Applies registry decisions to the proxy.
   */
  private record ProxyRegistrar(ProxyServer proxy) implements ServiceRegistry.Registrar {

    @Override
    public void register(String name, InetSocketAddress address) {
      this.proxy.registerServer(new ServerInfo(name, address));
    }

    @Override
    public void unregister(String name) {
      this.proxy.getServer(name)
          .map(RegisteredServer::getServerInfo)
          .ifPresent(this.proxy::unregisterServer);
    }
  }
}
