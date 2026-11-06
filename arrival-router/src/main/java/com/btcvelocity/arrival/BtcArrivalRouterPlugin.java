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

import com.btcvelocity.api.event.player.LimboHoldEvent;
import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.server.ServerRegisteredEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import java.nio.file.Path;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
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

  /**
   * The server that holds a player when no destination is available, declared in
   * {@code velocity.toml} (it runs outside CloudNet). Unset: no limbo, behaviour unchanged.
   */
  public static final String LIMBO_VARIABLE = "BTC_ARRIVAL_LIMBO";

  // One sweep for every way a destination comes back (registration, restart, failed attempt).
  private static final long LIMBO_SWEEP_SECONDS = 5;

  /**
   * Maintenance state at startup ({@code true} to start in maintenance). Changed at runtime with
   * {@code /btcmaintenance}; a restart goes back to this value.
   */
  public static final String MAINTENANCE_VARIABLE = "BTC_ARRIVAL_MAINTENANCE";

  static final String MAINTENANCE_PERMISSION = "btc.arrival.maintenance";
  static final String MAINTENANCE_BYPASS_PERMISSION = "btc.arrival.maintenance.bypass";

  private final Logger logger;
  private final ProxyServer proxy;
  private final ArrivalPolicy policy;
  private final String limbo;
  private final Path dataDirectory;
  private final ArrivalAttempts attempts = new ArrivalAttempts();
  private final PlayerCounts counts;
  private ArrivalMessages messages = ArrivalMessages.defaults();
  private volatile boolean maintenance;

  @Inject
  public BtcArrivalRouterPlugin(ProxyServer proxy, Logger logger,
                                @DataDirectory Path dataDirectory) {
    this(proxy, logger, ArrivalPolicy.parse(System.getenv(POLICY_VARIABLE)),
        System.getenv(LIMBO_VARIABLE), dataDirectory);
    this.maintenance = Boolean.parseBoolean(System.getenv(MAINTENANCE_VARIABLE));
  }

  BtcArrivalRouterPlugin(ProxyServer proxy, Logger logger, ArrivalPolicy policy, String limbo,
                         Path dataDirectory) {
    this.proxy = proxy;
    this.logger = logger;
    this.policy = policy;
    this.limbo = limbo == null || limbo.isBlank() ? null : limbo.trim();
    this.dataDirectory = dataDirectory;
    this.counts = new PlayerCounts(this::clusterCount, this::localCount,
        System::currentTimeMillis, logger);
  }

  // The cluster-wide count (every proxy, through Redis); without a cluster service, ours alone.
  private int clusterCount(String serverName) {
    var cluster = this.proxy.getClusterPlayerService();
    return cluster == null ? this.localCount(serverName) : cluster.getPlayersOnServerCount(serverName);
  }

  private int localCount(String serverName) {
    return this.proxy.getServer(serverName).map(server -> server.getPlayersConnected().size()).orElse(0);
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
    if (this.dataDirectory != null) {
      this.messages = ArrivalMessages.load(this.dataDirectory);
    }
    var commands = this.proxy.getCommandManager();
    commands.register(commands.metaBuilder("btcmaintenance").plugin(this).build(),
        new MaintenanceCommand());
    this.logger.info("Arrival destinations: {}", String.join(", ", this.policy.patterns()));
    if (this.maintenance) {
      this.logger.warn("Starting in maintenance ({}): players without {} are held in the limbo.",
          MAINTENANCE_VARIABLE, MAINTENANCE_BYPASS_PERMISSION);
    }
    if (this.limbo != null) {
      this.logger.info("Limbo: {} (players there are released every {} s)",
          this.limbo, LIMBO_SWEEP_SECONDS);
      this.proxy.getScheduler().buildTask(this, this::releaseFromLimbo)
          .repeat(LIMBO_SWEEP_SECONDS, TimeUnit.SECONDS)
          .schedule();
    }
  }

  /**
   * A server registered again is a new service: players it refused may try it once more.
   *
   * @param event the registration
   */
  @Subscribe
  public void onServerRegistered(ServerRegisteredEvent event) {
    this.attempts.forgetServer(event.registeredServer().getServerInfo().getName());
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
    Optional<RegisteredServer> chosen = this.destinationFor(player, Set.of());
    if (chosen.isEmpty()) {
      chosen = this.limboServer();
    }
    if (chosen.isEmpty()) {
      chosen = this.lastResort(player, Set.of());
    }
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

    Optional<RegisteredServer> chosen =
        this.destinationFor(player, this.attempts.of(player.getUniqueId()));
    // To the limbo, unless the limbo is what dropped them or where they still are (failed switch).
    boolean stillInLimbo = player.getCurrentServer()
        .map(current -> this.isLimbo(current.getServerInfo().getName()))
        .orElse(false);
    if (chosen.isEmpty() && !this.isLimbo(from) && !stillInLimbo) {
      chosen = this.limboServer();
    }
    if (chosen.isEmpty() && !stillInLimbo) {
      chosen = this.lastResort(player, this.attempts.of(player.getUniqueId()));
    }
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

  /**
   * Sends every player waiting in the limbo to a destination, as soon as one is available. A
   * failed attempt leaves them in the limbo, recorded, until the next sweep or re-registration.
   */
  // shortcut: a server declared in velocity.toml is never re-registered, so once it failed for a
  // player it is not retried until they reconnect; fine while destinations are CloudNet services.
  void releaseFromLimbo() {
    Optional<RegisteredServer> limboServer = this.limboServer();
    if (limboServer.isEmpty()) {
      return;
    }
    for (Player player : limboServer.get().getPlayersConnected()) {
      if (this.isQueued(player)) {
        continue; // the queue owns their turn; releasing them here would drop them from it
      }
      Optional<RegisteredServer> chosen =
          this.destinationFor(player, this.attempts.of(player.getUniqueId()));
      if (chosen.isPresent()) {
        var target = chosen.get();
        // A plugin may park the player here on purpose (service being woken up for them).
        this.proxy.getEventManager().fire(new LimboHoldEvent(player)).thenAccept(hold -> {
          if (hold.isHeld()) {
            return;
          }
          this.attempts.record(player.getUniqueId(), target.getServerInfo().getName());
          player.createConnectionRequest(target).fireAndForget();
          this.logger.info("{} leaves the limbo for {}",
              player.getUsername(), target.getServerInfo().getName());
        });
      }
    }
  }

  /**
   * Tells a player who just reached the limbo why they are there.
   *
   * @param event the completed server connection
   */
  @Subscribe
  public void onServerPostConnect(ServerPostConnectEvent event) {
    var player = event.getPlayer();
    var current = player.getCurrentServer().map(connection -> connection.getServerInfo().getName());
    // Connected means not refused: only a send that never completes keeps its mark (loop guard).
    current.ifPresent(name -> this.attempts.forget(player.getUniqueId(), name));
    boolean inLimbo = current.map(this::isLimbo).orElse(false);
    if (inLimbo) {
      // A plugin that holds the player on purpose gives its own reason instead of "no lobby".
      this.proxy.getEventManager().fire(new LimboHoldEvent(player)).thenAccept(hold ->
          player.sendMessage(hold.getReason()
              .orElseGet(() -> this.messages.get(this.limboMessageKey(player)))));
    }
  }

  // Why the player is in the limbo: maintenance outranks the queue, which outranks "no lobby".
  private String limboMessageKey(Player player) {
    if (this.heldByMaintenance(player)) {
      return "limbo.maintenance";
    }
    return this.isQueued(player) ? "limbo.queue" : "limbo.waiting";
  }

  /**
   * Whether the proxy queue holds this player. The queue sends its own players to the limbo when
   * {@code [queue] queue-server} names it, and moves them out itself when their turn comes.
   */
  private boolean isQueued(Player player) {
    return this.proxy.isQueueEnabled() && this.proxy.getQueueManager().isQueued(player);
  }

  void setMaintenance(boolean maintenance) {
    this.maintenance = maintenance;
  }

  // Maintenance is "no destination" for whoever cannot bypass it: the limbo fallback does the rest.
  private Optional<RegisteredServer> destinationFor(Player player, Set<String> excluded) {
    return this.heldByMaintenance(player) ? Optional.empty() : this.choose(excluded, false);
  }

  // A backend the proxy avoids is advice, not a refusal: when nothing else is left and no limbo can
  // hold the player, the least populated avoided one still beats a disconnect.
  private Optional<RegisteredServer> lastResort(Player player, Set<String> excluded) {
    return this.heldByMaintenance(player) ? Optional.empty() : this.choose(excluded, true);
  }

  private boolean heldByMaintenance(Player player) {
    return this.maintenance && !player.hasPermission(MAINTENANCE_BYPASS_PERMISSION);
  }

  /** {@code /btcmaintenance [on|off]}: shows or switches the maintenance state. */
  private final class MaintenanceCommand implements SimpleCommand {

    @Override
    public void execute(Invocation invocation) {
      var args = invocation.arguments();
      var source = invocation.source();
      var self = BtcArrivalRouterPlugin.this;
      if (args.length == 1 && args[0].equalsIgnoreCase("on")) {
        if (self.limbo == null) {
          source.sendMessage(self.messages.get("command.maintenance.no-limbo"));
          return;
        }
        self.setMaintenance(true);
        self.logger.warn("Maintenance enabled");
        source.sendMessage(self.messages.get("command.maintenance.enabled"));
      } else if (args.length == 1 && args[0].equalsIgnoreCase("off")) {
        self.setMaintenance(false);
        self.logger.info("Maintenance disabled");
        source.sendMessage(self.messages.get("command.maintenance.disabled"));
      } else {
        source.sendMessage(self.messages.get(self.maintenance
            ? "command.maintenance.status-on" : "command.maintenance.status-off"));
      }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
      return invocation.arguments().length <= 1 ? List.of("on", "off") : List.of();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
      return invocation.source().hasPermission(MAINTENANCE_PERMISSION);
    }
  }

  private boolean isLimbo(String serverName) {
    return this.limbo != null && this.limbo.equalsIgnoreCase(serverName);
  }

  private Optional<RegisteredServer> limboServer() {
    return Optional.ofNullable(this.limbo).flatMap(this.proxy::getServer);
  }

  // avoided false: the backends the proxy does not avoid; true: only the avoided ones.
  private Optional<RegisteredServer> choose(Set<String> excluded, boolean avoided) {
    List<ArrivalCandidate> candidates = new ArrayList<>();
    for (RegisteredServer server : this.proxy.getAllServers()) {
      String name = server.getServerInfo().getName();
      if (this.isLimbo(name)) {
        continue; // never a destination, even under a wildcard pattern
      }
      // Only what the policy could pick is counted: a cluster count can be a Redis round trip.
      if (!this.policy.accepts(name) || excluded.contains(name)) {
        continue;
      }
      // Its connections keep failing (proxy-side circuit breaker): preferably not sent there.
      if (this.proxy.isBackendAvoided(name) != avoided) {
        continue;
      }
      candidates.add(new ArrivalCandidate(name, this.counts.of(name)));
    }

    return this.policy.select(candidates, excluded)
        .flatMap(candidate -> this.proxy.getServer(candidate.name()));
  }
}
