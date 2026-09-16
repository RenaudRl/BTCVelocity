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

package com.btcvelocity.platform;

import com.btcvelocity.api.bridge.BridgePlatformSources;
import com.btcvelocity.api.bridge.PlatformSource;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

/**
 * Tells the bridge how to resolve a player's client platform.
 *
 * <p>The bridge lives in the proxy core, and the core's classloader is the parent of every
 * plugin's: it cannot see Floodgate's classes, whatever it is compiled against. Measured on the
 * bench, 17/09 — the proxy reported "no platform source" while running with Floodgate loaded. The
 * dependency has to run the other way round, and that is all this plugin is: something that can
 * see both sides and hands the answer to the core.
 *
 * <p>Floodgate is an <em>optional</em> dependency. A proxy without it (production today) keeps
 * working: nothing is installed, and the bridge says so rather than assuming everyone is Java.
 */
@Plugin(
    id = "btc-platform-resolver",
    name = "BTC Platform Resolver",
    version = "1.0.0",
    description = "Resolves a session's client platform for the btc:bridge control plane",
    dependencies = {@Dependency(id = "floodgate", optional = true)}
)
public final class BtcPlatformResolverPlugin {

  private final ProxyServer proxy;
  private final Logger logger;

  @Inject
  public BtcPlatformResolverPlugin(final ProxyServer proxy, final Logger logger) {
    this.proxy = proxy;
    this.logger = logger;
  }

  /**
   * Installs the source once every plugin is up.
   *
   * <p>The declared dependency makes Velocity start Floodgate before this plugin, so by the time
   * this runs the question has a stable answer — which is exactly what the core could not get.
   */
  @Subscribe
  public void onProxyInitialize(final ProxyInitializeEvent event) {
    final PlatformSource source = FloodgatePlatformSource.createIfPresent(proxy);
    BridgePlatformSources.install(source);
    if (source.canResolve()) {
      logger.info("Platform resolution installed for btc:bridge via {}", source.describe());
    } else {
      // Not a warning: a proxy without Floodgate is the production case, and the bridge already
      // announces what it will and will not do about it.
      logger.info("No platform source available ({}); btc:bridge will state so itself",
          source.describe());
    }
  }

  /** Leaves nothing behind: a stale source would outlive the plugin that knew how to use it. */
  @Subscribe
  public void onProxyShutdown(final ProxyShutdownEvent event) {
    BridgePlatformSources.reset();
  }
}
