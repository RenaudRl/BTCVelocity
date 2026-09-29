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

package com.btcvelocity.admission;

import com.google.inject.Inject;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns away connections whose address is a datacenter or a Tor exit, judged on local lists.
 *
 * <p>Nothing about a player leaves the proxy. The lists are public files fetched by URL; every
 * lookup is made in memory. The rule itself is in {@link AdmissionEngine}; this class wires it to
 * Velocity: the pre-login event decides what the address and the claimed name can settle, and the
 * login event settles the rest once the account is authenticated (nominative exemptions, and the
 * real address of a Bedrock player).
 *
 * <p>Floodgate is an <em>optional</em> dependency: without it, every Bedrock player behind Geyser
 * is admitted and journaled as "ip r&eacute;elle inconnue".
 */
@Plugin(
    id = "btc-admission-shield",
    name = "BTC Admission Shield",
    version = "1.0.0",
    description = "Refuses datacenter and Tor addresses on local lists; journals every decision.",
    authors = {"BTC Studio"},
    dependencies = {@Dependency(id = "floodgate", optional = true)})
public final class BtcAdmissionShieldPlugin {

  private static final String COMMAND = "admission";
  private static final short FIRST = 1000;
  private static final long FIRST_DOWNLOAD_DELAY_SECONDS = 2;

  private final ProxyServer proxy;
  private final Logger logger;
  private final Path dataDirectory;
  private final Clock clock = Clock.systemUTC();

  private volatile AdmissionEngine engine;
  private volatile AdmissionConfig config;
  private volatile AdmissionJournal journal;
  private volatile BedrockAddressSource bedrock = BedrockAddressSource.NONE;
  private volatile ScheduledTask refreshTask;

  /**
   * Creates the plugin.
   *
   * @param proxy the proxy
   * @param logger the plugin's logger
   * @param dataDirectory the directory owned by this plugin
   */
  @Inject
  public BtcAdmissionShieldPlugin(
      final ProxyServer proxy, final Logger logger, @DataDirectory final Path dataDirectory) {
    this.proxy = proxy;
    this.logger = logger;
    this.dataDirectory = dataDirectory;
  }

  /**
   * Loads the settings and the lists, and schedules their refresh.
   *
   * <p>A missing list never blocks the start and never refuses anyone: the module runs open,
   * with a warning, until the first download succeeds.
   *
   * @param event the proxy start
   */
  @Subscribe
  public void onProxyInitialize(final ProxyInitializeEvent event) {
    final AdmissionConfig settings =
        AdmissionConfig.loadOrCreate(this.dataDirectory.resolve("config.yml"), this.logger);
    final ListStore store =
        new ListStore(this.dataDirectory.resolve("lists"), this.logger, this.clock);
    store.loadAll(settings.sources());
    if (!store.hasAnyList()) {
      this.logger.warn("No list is loaded: connections are NOT filtered until a download"
          + " succeeds (mode {}).", settings.mode());
    }

    this.bedrock = floodgateSource();
    this.journal = new AdmissionJournal(LoggerFactory.getLogger(AdmissionJournal.LOGGER_NAME),
        this.clock);
    this.engine = new AdmissionEngine(settings, store);
    this.config = settings;
    this.proxy.getCommandManager().register(
        this.proxy.getCommandManager().metaBuilder(COMMAND).plugin(this).build(),
        new AdmissionCommand(this.journal, store, settings));
    scheduleRefresh(settings, store);
    this.logger.info("Admission shield up: mode {}, {} list(s) configured, refresh every {} h.",
        settings.mode(), settings.sources().size(), settings.refreshInterval().toHours());
  }

  /**
   * Stops the periodic refresh.
   *
   * @param event the proxy stop
   */
  @Subscribe
  public void onProxyShutdown(final ProxyShutdownEvent event) {
    final ScheduledTask task = this.refreshTask;
    if (task != null) {
      task.cancel();
    }
  }

  /**
   * Decides what the address and the claimed name can settle, before any authentication.
   *
   * @param event the pre-login
   */
  @Subscribe(priority = FIRST, async = false)
  public void onPreLogin(final PreLoginEvent event) {
    final AdmissionEngine current = this.engine;
    if (current == null || !event.getResult().isAllowed()) {
      return;
    }
    final InetAddress remote = event.getConnection().getRemoteAddress().getAddress();
    if (remote == null) {
      return;
    }
    final Attempt attempt = new Attempt(event.getUsername(), null, remote, null);
    final Verdict verdict = current.decide(attempt, Phase.PRE_LOGIN);
    this.journal.record(attempt, verdict, Phase.PRE_LOGIN, this.config.mode());
    if (verdict.action() == Verdict.Action.REFUSE) {
      event.setResult(PreLoginEvent.PreLoginComponentResult.denied(refusal(verdict)));
    }
  }

  /**
   * Settles what the pre-login deferred, now that the account is authenticated.
   *
   * @param event the login
   */
  @Subscribe(priority = FIRST, async = false)
  public void onLogin(final LoginEvent event) {
    final AdmissionEngine current = this.engine;
    if (current == null || !event.getResult().isAllowed()) {
      return;
    }
    final Player player = event.getPlayer();
    final InetAddress remote = player.getRemoteAddress().getAddress();
    if (remote == null) {
      return;
    }
    final Attempt claimed = new Attempt(player.getUsername(), null, remote, null);
    if (current.decide(claimed, Phase.PRE_LOGIN).action() != Verdict.Action.DEFER) {
      return;
    }
    final Attempt attempt = new Attempt(player.getUsername(), player.getUniqueId(), remote,
        this.bedrock.realAddressOf(player.getUniqueId()).orElse(null));
    final Verdict verdict = current.decide(attempt, Phase.LOGIN);
    this.journal.record(attempt, verdict, Phase.LOGIN, this.config.mode());
    if (verdict.action() == Verdict.Action.REFUSE) {
      event.setResult(ResultedEvent.ComponentResult.denied(refusal(verdict)));
    }
  }

  private Component refusal(final Verdict verdict) {
    final IpClass ipClass = verdict.ipClass();
    final String text = this.config.messageFor(ipClass).replace("{code}", ipClass.causeCode());
    return MiniMessage.miniMessage().deserialize(text);
  }

  private BedrockAddressSource floodgateSource() {
    try {
      Class.forName("org.geysermc.floodgate.api.FloodgateApi", false,
          BtcAdmissionShieldPlugin.class.getClassLoader());
      final org.geysermc.floodgate.api.FloodgateApi api =
          org.geysermc.floodgate.api.FloodgateApi.getInstance();
      if (api != null) {
        return new FloodgateAddressSource(api);
      }
    } catch (ClassNotFoundException | LinkageError absent) {
      // Floodgate is optional; its absence is a fact about this deployment, not an error.
    }
    this.logger.info("No Floodgate: Bedrock players behind Geyser are admitted unjudged.");
    return BedrockAddressSource.NONE;
  }

  /**
   * Schedules the periodic download of the public lists.
   *
   * <p>The only outbound traffic of this module. A request carries a public URL and nothing about
   * any player.
   */
  private void scheduleRefresh(final AdmissionConfig settings, final ListStore store) {
    final ListRefresher refresher = new ListRefresher(store,
        ListFetcher.http(settings.downloadTimeout(), settings.maxDownloadBytes()), this.logger);
    final Duration interval = settings.refreshInterval();
    final List<ListSource> due = new ArrayList<>();
    Duration untilNextFull = interval;
    for (ListSource source : settings.sources()) {
      final Duration wait =
          ListRefresher.delayBeforeFirstRefresh(store.directory(), source, interval, this.clock);
      if (wait.isZero()) {
        due.add(source);
      } else if (wait.compareTo(untilNextFull) < 0) {
        untilNextFull = wait;
      }
    }
    // Missing or stale lists are fetched right after start; the full cycle then keeps its own
    // rhythm, counted from the files that are already fresh.
    if (!due.isEmpty()) {
      this.proxy.getScheduler()
          .buildTask(this, () -> refresh(refresher, due))
          .delay(Duration.ofSeconds(FIRST_DOWNLOAD_DELAY_SECONDS))
          .schedule();
    }
    this.refreshTask = this.proxy.getScheduler()
        .buildTask(this, () -> refresh(refresher, settings.sources()))
        .delay(untilNextFull)
        .repeat(interval)
        .schedule();
  }

  private void refresh(final ListRefresher refresher, final List<ListSource> sources) {
    try {
      final int replaced = refresher.refreshAll(sources);
      this.logger.info("List refresh done: {} of {} list(s) replaced.", replaced, sources.size());
    } catch (RuntimeException unexpected) {
      this.logger.error("List refresh failed unexpectedly; the lists in use are unchanged.",
          unexpected);
    }
  }
}
