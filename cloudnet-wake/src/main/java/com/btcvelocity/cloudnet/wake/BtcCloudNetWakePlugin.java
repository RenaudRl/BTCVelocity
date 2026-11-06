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

package com.btcvelocity.cloudnet.wake;

import com.btcvelocity.api.event.player.LimboHoldEvent;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.server.ServerRegisteredEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import eu.cloudnetservice.driver.inject.InjectionLayer;
import eu.cloudnetservice.driver.provider.CloudServiceFactory;
import eu.cloudnetservice.driver.provider.CloudServiceProvider;
import eu.cloudnetservice.driver.provider.ServiceTaskProvider;
import eu.cloudnetservice.driver.service.ServiceConfiguration;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;

/**
 * Starts a stopped CloudNet task when a player asks for it with {@code /server <task>}, parks the
 * player in the limbo while it starts, and sends them to the server once it registers (task 2.2).
 *
 * <p>Off unless {@value WakeSettings#TASKS_VARIABLE} names at least one task, and inert outside a
 * CloudNet service. It needs the limbo and the sweep of {@code btc-arrival-router}: the limbo is
 * where the player waits, and the sweep is what sends them back to a lobby when the wait ends
 * without a server. No LazyMC: CloudNet stays the only orchestrator.
 *
 * <p>Only {@code /server} is taken over. Velocity cannot express a connection to a server it does
 * not know, so a backend's own {@code Connect} request or a {@code btc:bridge} transfer to a
 * stopped task still fails as before.
 */
@Plugin(
    id = "btc-cloudnet-wake",
    name = "BTC CloudNet Wake",
    version = "0.1",
    description = "Wakes a stopped CloudNet task for the player who asks for it, via the limbo.",
    authors = {"BTC Studio"})
public final class BtcCloudNetWakePlugin {

  private final Logger logger;
  private final ProxyServer proxy;
  private final Path dataDirectory;

  private volatile WakeCoordinator coordinator;

  @Inject
  public BtcCloudNetWakePlugin(ProxyServer proxy, Logger logger,
                               @DataDirectory Path dataDirectory) {
    this.proxy = proxy;
    this.logger = logger;
    this.dataDirectory = dataDirectory;
  }

  @Subscribe
  public void onProxyInitialize(ProxyInitializeEvent event) {
    var settings = WakeSettings.fromEnvironment(System::getenv);
    if (settings.tasks().isEmpty()) {
      this.logger.info("Wake on demand is off: set {} to enable it.", WakeSettings.TASKS_VARIABLE);
      return;
    }
    if (!settings.enabled()) {
      // Without the router's sweep a player parked in the limbo would never be sent back.
      this.logger.warn("Wake on demand stays off: {} names tasks but {} is not set.",
          WakeSettings.TASKS_VARIABLE, String.join(", ", settings.missingVariables()));
      return;
    }
    if (!runningAsCloudNetService()) {
      this.logger.info("Not running as a CloudNet service, wake on demand stays idle.");
      return;
    }

    var layer = InjectionLayer.ext();
    var starter = new CloudNetServiceStarter(this.logger,
        layer.instance(CloudServiceProvider.class),
        layer.instance(CloudServiceFactory.class),
        layer.instance(ServiceTaskProvider.class),
        task -> ServiceConfiguration.builder(task).build());
    this.coordinator = new WakeCoordinator(this.proxy, this.logger, settings,
        WakeMessages.load(this.dataDirectory), starter, this::schedule);
    this.logger.info("Wake on demand for {} (limbo {}, timeout {} s)",
        String.join(", ", settings.tasks()), settings.limbo(), settings.timeout().toSeconds());
  }

  @Subscribe
  public void onCommand(CommandExecuteEvent event) {
    var current = this.coordinator;
    if (current != null && untouched(event.getResult())
        && event.getCommandSource() instanceof Player player
        && current.onServerCommand(player, event.getCommand())) {
      event.setResult(CommandExecuteEvent.CommandResult.denied());
    }
  }

  // Another plugin that denied, rewrote or forwarded the command has decided: leave it alone.
  private static boolean untouched(CommandExecuteEvent.CommandResult result) {
    return result.isAllowed() && result.getCommand().isEmpty() && !result.isForwardToServer();
  }

  void setCoordinator(WakeCoordinator coordinator) {
    this.coordinator = coordinator;
  }

  @Subscribe
  public void onServerRegistered(ServerRegisteredEvent event) {
    var current = this.coordinator;
    if (current != null) {
      current.onServerRegistered(event.registeredServer());
    }
  }

  @Subscribe
  public void onLimboHold(LimboHoldEvent event) {
    var current = this.coordinator;
    if (current != null) {
      current.onLimboHold(event);
    }
  }

  @Subscribe
  public void onDisconnect(DisconnectEvent event) {
    var current = this.coordinator;
    if (current != null) {
      current.onDisconnect(event.getPlayer().getUniqueId());
    }
  }

  private Runnable schedule(Duration delay, Runnable task) {
    return this.proxy.getScheduler().buildTask(this, task)
        .delay(delay.toMillis(), TimeUnit.MILLISECONDS)
        .schedule()::cancel;
  }

  private static boolean runningAsCloudNetService() {
    try {
      Class.forName("eu.cloudnetservice.wrapper.holder.ServiceInfoHolder");
      return true;
    } catch (ClassNotFoundException exception) {
      return false;
    }
  }
}
