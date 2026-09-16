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

package com.btcvelocity.proxy.command.builtin;

import com.btcvelocity.api.bridge.BridgeMessage;
import com.btcvelocity.proxy.bridge.BackendHealthRegistry;
import com.btcvelocity.proxy.bridge.BridgeMetrics;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.command.builtin.BuiltinCommand;
import java.util.Map;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Implements {@code /btcbridge}: what the bridge has done, and what each backend last said.
 *
 * <p>The counters and the health registry were written and never read — no command, no routing
 * decision consulted them — so nothing the bridge did had an observable effect on the proxy. A
 * control that cannot be read cannot be confronted.
 *
 * <p>Deliberately carries no player UUID and no payload: counts, server names the operator
 * configured, and the backend's own figures. Nothing the other end of the wire could use to grow
 * this output by its own choosing.
 */
public final class BridgeStatusCommand implements BuiltinCommand {

  private final VelocityServer server;

  public BridgeStatusCommand(final VelocityServer server) {
    this.server = server;
  }

  @Override
  public String label() {
    return "btcbridge";
  }

  @Override
  public BrigadierCommand build() {
    final LiteralArgumentBuilder<CommandSource> rootNode = BrigadierCommand
        .literalArgumentBuilder(label())
        .requires(source -> source.getPermissionValue("velocity.command.btcbridge") == Tristate.TRUE)
        .executes(this::status);
    return new BrigadierCommand(rootNode);
  }

  private int status(final CommandContext<CommandSource> context) {
    final CommandSource source = context.getSource();
    final long now = System.currentTimeMillis();

    source.sendMessage(Component.text("btc:bridge — counters", NamedTextColor.GOLD));
    final Map<BridgeMetrics.Event, Long> counts = server.getBridgeChannel().metrics().snapshot();
    for (final BridgeMetrics.Event event : BridgeMetrics.Event.values()) {
      final long count = counts.getOrDefault(event, 0L);
      source.sendMessage(Component.text("  " + event.name().toLowerCase() + " = " + count,
          count == 0 ? NamedTextColor.GRAY : NamedTextColor.WHITE));
    }

    source.sendMessage(Component.text("btc:bridge — backend health", NamedTextColor.GOLD));
    final BackendHealthRegistry health = server.getBackendHealthRegistry();
    for (final RegisteredServer registered : server.getAllServers()) {
      final String name = registered.getServerInfo().getName();
      final Optional<BridgeMessage.Health> report = health.getHealth(name);
      if (report.isEmpty()) {
        final String state = health.isStale(name) ? "stale (stopped reporting)" : "never reported";
        source.sendMessage(Component.text("  " + name + " : " + state, NamedTextColor.RED));
        continue;
      }
      final BridgeMessage.Health h = report.get();
      final long ageSeconds = Math.max(0L, (now - h.envelope().issuedAt()) / 1000L);
      source.sendMessage(Component.text(String.format(
          "  %s : mspt=%.1f tps=%.1f players=%d worlds=%d age=%ds",
          name, h.mspt(), h.tps(), h.playerCount(), h.loadedWorlds().size(), ageSeconds),
          NamedTextColor.GREEN));
    }
    return Command.SINGLE_SUCCESS;
  }
}
