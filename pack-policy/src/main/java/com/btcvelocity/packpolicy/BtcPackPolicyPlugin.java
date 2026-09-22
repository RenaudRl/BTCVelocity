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

package com.btcvelocity.packpolicy;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import org.slf4j.Logger;

/**
 * Takes every resource pack off a player who enters a backend declared pack-less.
 *
 * <p>A client keeps the packs of the server it leaves. The CraftEngine pack reassigns vanilla
 * block states to custom blocks (unwaxed cut copper becomes custom wood, persistent oak leaves
 * become palm leaves...), and only a CraftEngine backend remaps the real blocks away from those
 * states. Carried onto a backend without CraftEngine, the pack paints its real blocks as the
 * custom ones. Measured on 22/09: the Créatif's cut copper showed as mistwood and echowood.
 *
 * <p>The removal is a clear-all written to the client like any other packet, so craft-engine-proxy
 * sees it and forgets the pack too: back on a CraftEngine backend, the pack is offered again
 * instead of being deduplicated against a pack the client no longer has.
 */
@Plugin(
    id = "btc-pack-policy",
    name = "BTC Pack Policy",
    version = "0.1",
    description = "Clears resource packs on entering a backend declared pack-less.",
    authors = {"BTC Studio"})
public final class BtcPackPolicyPlugin {

  /**
   * Where the pack-less backends are declared. An environment variable for the same reason as the
   * arrival policy: the proxy's service directory is recreated at every start, its task's
   * environment is not.
   */
  public static final String DECLARATION_VARIABLE = "BTC_PACKLESS_SERVERS";

  private final Logger logger;
  private final PacklessServers packless;

  @Inject
  public BtcPackPolicyPlugin(Logger logger) {
    this(logger, PacklessServers.parse(System.getenv(DECLARATION_VARIABLE)));
  }

  BtcPackPolicyPlugin(Logger logger, PacklessServers packless) {
    this.logger = logger;
    this.packless = packless;
  }

  @Subscribe
  public void onProxyInitialize(ProxyInitializeEvent event) {
    if (this.packless.isEmpty()) {
      this.logger.warn("{} is not set: packs follow players onto every backend.",
          DECLARATION_VARIABLE);
      return;
    }
    this.logger.info("Pack-less backends: {}", String.join(", ", this.packless.patterns()));
  }

  /**
   * Clears the packs once the player is on the new backend.
   *
   * <p>Unconditional on purpose: a clear-all on a client holding nothing is harmless, while
   * trusting the proxy's record of applied packs would miss a pack still downloading.
   *
   * @param event the completed server switch
   */
  @Subscribe
  public void onServerPostConnect(ServerPostConnectEvent event) {
    var player = event.getPlayer();
    var server = player.getCurrentServer();
    if (server.isEmpty()) {
      return;
    }
    String name = server.get().getServerInfo().getName();
    if (!this.packless.covers(name)) {
      return;
    }
    player.clearResourcePacks();
    this.logger.info("{} entered pack-less {}: resource packs cleared", player.getUsername(), name);
  }
}
