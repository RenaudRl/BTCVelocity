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

package com.btcvelocity.clientpolicy;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;

/** {@code /clientpolicy <player>}: what a connected player's client has declared. Staff only. */
final class ClientPolicyCommand implements SimpleCommand {

  private final ProxyServer proxy;
  private final Supplier<ClientPolicyConfig> config;
  private final Function<Player, List<String>> describer;

  ClientPolicyCommand(ProxyServer proxy, Supplier<ClientPolicyConfig> config,
                      Function<Player, List<String>> describer) {
    this.proxy = proxy;
    this.config = config;
    this.describer = describer;
  }

  @Override
  public void execute(Invocation invocation) {
    String[] arguments = invocation.arguments();
    if (arguments.length != 1) {
      invocation.source().sendMessage(Component.text("Usage: /clientpolicy <player>"));
      return;
    }

    var target = this.proxy.getPlayer(arguments[0]);
    if (target.isEmpty()) {
      invocation.source().sendMessage(Component.text(
          "No connected player named " + ObservationLog.quote(arguments[0]) + "."));
      return;
    }
    this.describer.apply(target.get())
        .forEach(line -> invocation.source().sendMessage(Component.text(line)));
  }

  @Override
  public boolean hasPermission(Invocation invocation) {
    ClientPolicyConfig current = this.config.get();
    return current != null && invocation.source().hasPermission(current.viewPermission());
  }

  @Override
  public List<String> suggest(Invocation invocation) {
    String[] arguments = invocation.arguments();
    if (arguments.length > 1) {
      return List.of();
    }
    String prefix = arguments.length == 0 ? "" : arguments[0].toLowerCase(Locale.ROOT);
    return this.proxy.getAllPlayers().stream().map(Player::getUsername)
        .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
  }
}
