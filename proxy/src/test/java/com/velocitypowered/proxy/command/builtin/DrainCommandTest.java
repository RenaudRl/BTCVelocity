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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.btcvelocity.proxy.cluster.ProxyDrain;
import com.mojang.brigadier.context.CommandContext;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.proxy.VelocityServer;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@code /velocity drain}: the verbs reach the drain, and each answer is a translatable message.
 */
class DrainCommandTest {

  private final ProxyDrain drain = mock(ProxyDrain.class);
  private final CommandSource source = mock(CommandSource.class);

  private String run(final DrainCommand.Action action) {
    final VelocityServer server = mock(VelocityServer.class);
    when(server.getProxyDrain()).thenReturn(drain);
    @SuppressWarnings("unchecked")
    final CommandContext<CommandSource> context = mock(CommandContext.class);
    when(context.getSource()).thenReturn(source);

    new DrainCommand(server, action).run(context);

    final ArgumentCaptor<Component> sent = ArgumentCaptor.forClass(Component.class);
    verify(source).sendMessage(sent.capture());
    return ((TranslatableComponent) sent.getValue()).key();
  }

  @Test
  void startAnnouncesWhatTheDrainWillDo() {
    when(drain.start()).thenReturn(Optional.of(new ProxyDrain.Started(3, 1, true)));

    assertEquals("velocity.command.drain.started", run(DrainCommand.Action.START));
  }

  @Test
  void startWithoutALivePeerSaysSo() {
    when(drain.start()).thenReturn(Optional.of(new ProxyDrain.Started(3, 0, false)));

    assertEquals("velocity.command.drain.started-no-target", run(DrainCommand.Action.START));
  }

  @Test
  void startOnADrainingProxyIsRefused() {
    when(drain.start()).thenReturn(Optional.empty());

    assertEquals("velocity.command.drain.already", run(DrainCommand.Action.START));
  }

  @Test
  void cancelStopsTheDrain() {
    when(drain.cancel()).thenReturn(true);

    assertEquals("velocity.command.drain.cancelled", run(DrainCommand.Action.CANCEL));
  }

  @Test
  void cancelWhenNothingDrainsSaysSo() {
    when(drain.cancel()).thenReturn(false);

    assertEquals("velocity.command.drain.not-draining", run(DrainCommand.Action.CANCEL));
  }

  @Test
  void statusReportsTheProgressAndNeverStartsAnything() {
    when(drain.status()).thenReturn(new ProxyDrain.Status(true, 12, 30));

    assertEquals("velocity.command.drain.status", run(DrainCommand.Action.STATUS));
    verify(drain, never()).start();
    verify(drain, never()).cancel();
  }

  @Test
  void statusOfAnIdleProxySaysItIsNotDraining() {
    when(drain.status()).thenReturn(new ProxyDrain.Status(false, 12, 0));

    assertEquals("velocity.command.drain.not-draining", run(DrainCommand.Action.STATUS));
    verify(source).sendMessage(any(Component.class));
  }
}
