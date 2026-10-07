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
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
 * <p>Floodgate is an <em>optional</em> dependency: without it nobody is recognised as a Bedrock
 * player, so a connection from a {@code geyser-addresses} entry is judged on that address. The
 * setting is empty by default until the bench has measured what the proxy sees (task 13.1).
 *
 * <p>Each refusal is also told to the staff online ({@value AdmissionCommand#PERMISSION}), at
 * most one message every {@link StaffAlerts#WINDOW}.
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
  /**
   * Velocity runs the listener with the HIGHEST priority first ({@code PostOrder.FIRST} is
   * {@code Short.MAX_VALUE - 1}, {@code LAST} is {@code Short.MIN_VALUE + 1}). The pre-login
   * refusal goes early, so that a refused connection costs nothing more; it is applied again at
   * the login.
   */
  static final short PRE_LOGIN_PRIORITY = 1000;

  /**
   * The login refusal runs after every other listener, {@code PostOrder.LAST} included, so that
   * none of them can turn it back into an admission.
   */
  static final short LOGIN_PRIORITY = Short.MIN_VALUE;

  /** The net under the login refusal. */
  static final short POST_LOGIN_PRIORITY = Short.MIN_VALUE;
  private static final long FIRST_DOWNLOAD_DELAY_SECONDS = 2;
  private static final Duration SETTLE_PERIOD = Duration.ofMinutes(1);
  private static final String LOGGING_FRAGMENT = "log4j2-admission-journal.xml";

  private final ProxyServer proxy;
  private final Logger logger;
  private final Path dataDirectory;
  private final Clock clock = Clock.systemUTC();

  private volatile AdmissionEngine engine;
  private volatile AdmissionConfig config;
  private final RefusalLedger refusals = new RefusalLedger();
  private volatile ListStore store;
  private volatile ScheduledTask settleTask;
  private volatile AdmissionJournal journal;
  private volatile BedrockAddressSource bedrock = BedrockAddressSource.NONE;
  private volatile ScheduledTask refreshTask;
  private volatile ScheduledTask alertTask;
  private volatile StaffAlerts alerts;

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
    writeLoggingFragment();
    final ListStore store = new ListStore(this.dataDirectory.resolve("lists"), this.logger,
        this.clock, settings.maxEntries(), settings.embeddedBaseline());
    store.loadAll(settings.sources());
    if (!store.hasAnyList()) {
      this.logger.warn("No list is loaded: connections are NOT filtered until a download"
          + " succeeds (mode {}).", settings.mode());
    }

    this.bedrock = floodgateSource();
    if (!settings.geyserAddresses().isEmpty() && this.bedrock == BedrockAddressSource.NONE) {
      this.logger.warn("geyser-addresses is set but Floodgate is absent: nobody can be"
          + " recognised as a Bedrock player, so every connection from those addresses is judged"
          + " on them.");
    }
    this.journal = new AdmissionJournal(LoggerFactory.getLogger(AdmissionJournal.LOGGER_NAME),
        this.clock);
    this.alerts = new StaffAlerts(this.clock, this::tellStaff);
    this.store = store;
    this.engine = new AdmissionEngine(settings, store, store.quarantine().shadow());
    this.config = settings;
    this.proxy.getCommandManager().register(
        this.proxy.getCommandManager().metaBuilder(COMMAND).plugin(this).build(),
        new AdmissionCommand(this.journal, store, settings));
    this.alertTask = this.proxy.getScheduler()
        .buildTask(this, () -> {
          this.alerts.flushIfDue();
          this.journal.flushExpired();
        })
        .delay(StaffAlerts.WINDOW)
        .repeat(StaffAlerts.WINDOW)
        .schedule();
    scheduleRefresh(settings, store);
    this.logger.info("Admission shield up: mode {}, {} list(s) configured, refresh every {} h.",
        settings.mode(), settings.sources().size(), settings.refreshInterval().toHours());
  }

  /**
   * Stops the periodic tasks.
   *
   * @param event the proxy stop
   */
  @Subscribe
  public void onProxyShutdown(final ProxyShutdownEvent event) {
    for (ScheduledTask task :
        new ScheduledTask[] {this.refreshTask, this.alertTask, this.settleTask}) {
      if (task != null) {
        task.cancel();
      }
    }
  }

  /**
   * Decides what the address and the claimed name can settle, before any authentication.
   *
   * @param event the pre-login
   */
  @Subscribe(priority = PRE_LOGIN_PRIORITY, async = false)
  public void onPreLogin(final PreLoginEvent event) {
    final AdmissionEngine current = this.engine;
    if (current == null || !event.getResult().isAllowed()) {
      return;
    }
    final InetAddress remote = event.getConnection().getRemoteAddress().getAddress();
    if (remote == null) {
      return;
    }
    final Attempt attempt = Attempt.claimed(event.getUsername(), remote);
    final Verdict verdict = current.decide(attempt, Phase.PRE_LOGIN);
    final Optional<AdmissionJournal.Entry> entry =
        this.journal.record(attempt, verdict, Phase.PRE_LOGIN, this.config.mode());
    if (verdict.action() == Verdict.Action.REFUSE) {
      event.setResult(PreLoginEvent.PreLoginComponentResult.denied(refusal(verdict)));
      this.refusals.note(event.getConnection().getRemoteAddress(), verdict);
      entry.ifPresent(this.alerts::onRefusal);
    }
  }

  /**
   * Settles what the pre-login deferred, now that the account is authenticated, and applies again
   * a pre-login refusal that a later listener may have overturned.
   *
   * @param event the login
   */
  @Subscribe(priority = LOGIN_PRIORITY, async = false)
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
    final UUID uuid = player.getUniqueId();
    final Attempt attempt = Attempt.claimed(player.getUsername(), remote).authenticated(
        player.getUsername(), uuid, this.bedrock.isFloodgatePlayer(uuid),
        this.bedrock.realAddressOf(uuid).orElse(null));
    current.wouldRefuseInAddition(attempt).ifPresent(extra -> {
      if (this.store.quarantine().noteWouldRefuse(extra.sourceId(), uuid)) {
        this.journal.recordWouldRefuse(attempt, extra, this.config.mode());
      }
    });
    final Optional<Verdict> settled = current.decideAtLogin(attempt);
    if (settled.isEmpty()) {
      return;
    }
    final Verdict verdict = settled.get();
    final Optional<AdmissionJournal.Entry> entry =
        this.journal.record(attempt, verdict, Phase.LOGIN, this.config.mode());
    if (verdict.origin() == Verdict.Origin.GEYSER_ADDRESS_NOT_FLOODGATE) {
      this.logger.warn("{} connected from Geyser address {} without being a Floodgate player;"
          + " judged on that address like anyone ({}).",
          AdmissionJournal.value(player.getUsername()), remote.getHostAddress(),
          Verdict.Basis.GEYSER_ADDRESS_NOT_FLOODGATE.label());
    }
    if (verdict.action() == Verdict.Action.REFUSE) {
      event.setResult(ResultedEvent.ComponentResult.denied(refusal(verdict)));
      this.refusals.note(player.getRemoteAddress(), verdict);
      entry.ifPresent(this.alerts::onRefusal);
    }
  }

  /**
   * The last net: closes a connection that was refused and got through anyway, because a listener
   * overturned the refusal after this plugin's.
   *
   * @param event the post-login
   */
  @Subscribe(priority = POST_LOGIN_PRIORITY, async = false)
  public void onPostLogin(final PostLoginEvent event) {
    final Player player = event.getPlayer();
    this.refusals.take(player.getRemoteAddress()).ifPresent(verdict -> {
      this.logger.error("{} was refused ({}) but a later listener let the connection through;"
          + " disconnecting it.", AdmissionJournal.value(player.getUsername()),
          verdict.ipClass() == null ? "no class" : verdict.ipClass().key());
      player.disconnect(refusal(verdict));
    });
  }

  private void tellStaff(final String message) {
    final Component text = Component.text(message, NamedTextColor.RED);
    for (Player online : this.proxy.getAllPlayers()) {
      if (online.hasPermission(AdmissionCommand.PERMISSION)) {
        online.sendMessage(text);
      }
    }
  }

  /**
   * Puts the log4j2 fragment for the journal next to the configuration, once, so that the
   * operator has it at hand (see the module README: it only takes effect at the next start of
   * the proxy, through {@code -Dlog4j2.configurationFile}).
   */
  private void writeLoggingFragment() {
    final Path target = this.dataDirectory.resolve(LOGGING_FRAGMENT);
    if (Files.exists(target)) {
      return;
    }
    try (InputStream fragment =
        BtcAdmissionShieldPlugin.class.getResourceAsStream("/" + LOGGING_FRAGMENT)) {
      if (fragment == null) {
        this.logger.warn("The bundled {} is missing from the jar.", LOGGING_FRAGMENT);
        return;
      }
      Files.createDirectories(this.dataDirectory);
      Files.copy(fragment, target);
      this.logger.info("Wrote {}: add it to -Dlog4j2.configurationFile to give the journal its"
          + " own file, rotated daily and purged after 180 days.", target);
    } catch (IOException failure) {
      this.logger.warn("Could not write {}: {}", target, failure.toString());
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
        ListFetcher.http(settings.downloadTimeout(), settings.maxDownloadBytes()), this.logger,
        settings.quarantine());
    if (!settings.quarantine().isZero()) {
      this.settleTask = this.proxy.getScheduler()
          .buildTask(this, () -> settleQuarantines(store, settings))
          .delay(SETTLE_PERIOD)
          .repeat(SETTLE_PERIOD)
          .schedule();
    }
    final int purged = refresher.purgeLeftovers();
    if (purged > 0) {
      this.logger.info("Deleted {} unfinished download(s) left by a previous run.", purged);
    }
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

  /** Ends the quarantines that are due; a rejected version is told to the staff online. */
  private void settleQuarantines(final ListStore lists, final AdmissionConfig settings) {
    try {
      for (ListQuarantine.Outcome outcome
          : lists.quarantine().settleDue(settings.quarantineMaxExtraPlayers())) {
        if (!outcome.promoted()) {
          tellStaff(outcome.staffMessage());
        }
      }
    } catch (RuntimeException unexpected) {
      this.logger.error("Settling the list quarantines failed unexpectedly; they stay as they"
          + " were.", unexpected);
    }
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
