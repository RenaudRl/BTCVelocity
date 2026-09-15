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

package com.btcvelocity.arrival;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;

/**
 * Sends an arriving — or a bounced — player to a backend the proxy knows about right now.
 *
 * <p>Velocity resolves {@code try} against the servers written in {@code velocity.toml}, and
 * refuses to start when a name in it is not there. A CloudNet service is registered long after
 * that check, so it can never appear in {@code try}: without this plugin a player reaches the
 * proxy and has nowhere to go, however many backends are running. This closes that gap the only
 * way that keeps the chantier's rule intact — <em>the orchestrator never decides a player's
 * destination</em>. The node declares what exists; the decision made here is the proxy's own,
 * from a policy declared next to the proxy, with no call to the node.
 *
 * <p>It is not the CloudNet bridge coming back through the window. The bridge queried the node on
 * every login, owned {@code /hub}, and could not be turned off. This reads a list of already
 * registered servers, and stays inert unless {@value #POLICY_VARIABLE} declares a destination.
 *
 * <p>Deliberately absent: any transfer a player or a backend asks for. Those are business
 * intents and travel on {@code btc:bridge} v2. This only answers the two moments where nobody
 * else can speak — a player who has just logged in, and a player whose server just dropped them.
 */
@Plugin(
    id = "btc-arrival-router",
    name = "BTC Arrival Router",
    version = "0.1",
    description = "Chooses an arrival and fallback backend among the servers the proxy knows. Never asks the node.",
    authors = {"BTC Studio"})
public final class BtcArrivalRouterPlugin {

  /**
   * Where the policy is declared. An environment variable rather than a file on purpose: a
   * CloudNet service directory is recreated from its template at every start, so a file written
   * there is as volatile as memory, while the task's environment survives.
   */
  public static final String POLICY_VARIABLE = "BTC_ARRIVAL_SERVERS";

  private final Logger logger;
  private final ProxyServer proxy;
  private final ArrivalPolicy policy;
  private final ArrivalAttempts attempts = new ArrivalAttempts();

  @Inject
  public BtcArrivalRouterPlugin(ProxyServer proxy, Logger logger) {
    this(proxy, logger, ArrivalPolicy.parse(System.getenv(POLICY_VARIABLE)));
  }

  BtcArrivalRouterPlugin(ProxyServer proxy, Logger logger, ArrivalPolicy policy) {
    this.proxy = proxy;
    this.logger = logger;
    this.policy = policy;
  }

  @Subscribe
  public void onProxyInitialize(ProxyInitializeEvent event) {
    if (this.policy.isEmpty()) {
      this.logger.warn(
          "{} is not set: no arrival destination is declared, players will only reach what "
              + "'try' already names in velocity.toml.",
          POLICY_VARIABLE);
      return;
    }
    this.logger.info("Arrival destinations: {}", String.join(", ", this.policy.patterns()));
  }

  /**
   * Chooses where a player who has just logged in goes, unless the configuration already said.
   *
   * <p>A destination named in {@code try} wins: it is an explicit operator decision, and this
   * plugin exists for the case where there is nothing to name.
   *
   * @param event the initial-server choice
   */
  @Subscribe
  public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
    if (this.policy.isEmpty() || event.getInitialServer().isPresent()) {
      return;
    }

    var player = event.getPlayer();
    Optional<RegisteredServer> chosen = this.choose(Set.of());
    if (chosen.isEmpty()) {
      this.logger.warn("No arrival destination available for {}; the proxy will disconnect them.",
          player.getUsername());
      return;
    }

    var target = chosen.get();
    this.attempts.record(player.getUniqueId(), target.getServerInfo().getName());
    event.setInitialServer(target);
    this.logger.info("{} arrives on {}", player.getUsername(), target.getServerInfo().getName());
  }

  /**
   * Sends a player whose backend dropped them to another one, if there is another one.
   *
   * <p>Leaves an existing redirect alone: another plugin — or {@code btc:bridge} — may already
   * have decided, and a business decision outranks this fallback. When nothing is left, the
   * result is untouched too, so the player gets Velocity's own reason rather than a silent
   * disconnect.
   *
   * @param event the kick being handled
   */
  @Subscribe
  public void onKickedFromServer(KickedFromServerEvent event) {
    if (this.policy.isEmpty()
        || event.getResult() instanceof KickedFromServerEvent.RedirectPlayer) {
      return;
    }

    var player = event.getPlayer();
    var from = event.getServer().getServerInfo().getName();
    this.attempts.record(player.getUniqueId(), from);

    Optional<RegisteredServer> chosen = this.choose(this.attempts.of(player.getUniqueId()));
    if (chosen.isEmpty()) {
      this.logger.info(
          "{} was kicked from {} and no other destination is left; leaving the disconnect as is.",
          player.getUsername(), from);
      return;
    }

    var target = chosen.get();
    this.attempts.record(player.getUniqueId(), target.getServerInfo().getName());
    event.setResult(KickedFromServerEvent.RedirectPlayer.create(target));
    this.logger.info("{} kicked from {}, redirected to {}",
        player.getUsername(), from, target.getServerInfo().getName());
  }

  @Subscribe
  public void onDisconnect(DisconnectEvent event) {
    this.attempts.forget(event.getPlayer().getUniqueId());
  }

  private Optional<RegisteredServer> choose(Set<String> excluded) {
    List<ArrivalCandidate> candidates = new ArrayList<>();
    for (RegisteredServer server : this.proxy.getAllServers()) {
      candidates.add(new ArrivalCandidate(
          server.getServerInfo().getName(),
          server.getPlayersConnected().size()));
    }

    return this.policy.select(candidates, excluded)
        .flatMap(candidate -> this.proxy.getServer(candidate.name()));
  }
}
