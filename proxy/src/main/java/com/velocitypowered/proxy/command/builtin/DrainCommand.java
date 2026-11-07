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

package com.velocitypowered.proxy.command.builtin;

import com.btcvelocity.proxy.cluster.ProxyDrain;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.proxy.VelocityServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.translation.Argument;

/**
 * The branches of {@code /velocity drain}: {@code start}, {@code cancel} and {@code status}.
 *
 * <p>Update the whole proxy fleet before the first drain: a proxy on an older version ignores the
 * drain mark and keeps picking the drained proxy as a transfer target.</p>
 *
 * @param server the proxy
 * @param action what this branch does
 */
record DrainCommand(VelocityServer server, Action action) implements Command<CommandSource> {

  /** What a branch of the command does. */
  enum Action { START, CANCEL, STATUS }

  @Override
  public int run(final CommandContext<CommandSource> context) {
    final ProxyDrain drain = server.getProxyDrain();
    final CommandSource source = context.getSource();
    switch (action) {
      case START -> source.sendMessage(drain.start().map(DrainCommand::started)
          .orElseGet(() -> Component.translatable("velocity.command.drain.already")));
      case CANCEL -> source.sendMessage(Component.translatable(drain.cancel()
          ? "velocity.command.drain.cancelled" : "velocity.command.drain.not-draining"));
      case STATUS -> source.sendMessage(status(drain.status()));
      default -> throw new AssertionError(action);
    }
    return Command.SINGLE_SUCCESS;
  }

  private static Component started(final ProxyDrain.Started started) {
    final String key = started.hasTarget() ? "velocity.command.drain.started"
        : "velocity.command.drain.started-no-target";
    return Component.translatable(key).arguments(
        Argument.numeric("transferable", started.transferable()),
        Argument.numeric("staying", started.stayingHere()));
  }

  private static Component status(final ProxyDrain.Status status) {
    if (!status.draining()) {
      return Component.translatable("velocity.command.drain.not-draining");
    }
    return Component.translatable("velocity.command.drain.status").arguments(
        Argument.numeric("connected", status.connected()),
        Argument.numeric("transferred", status.transferred()));
  }
}
