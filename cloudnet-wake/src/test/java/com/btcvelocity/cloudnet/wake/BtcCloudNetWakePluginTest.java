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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.btcvelocity.api.event.player.LimboHoldEvent;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

/** The plugin as shipped: nothing declared in the environment, so nothing may change. */
class BtcCloudNetWakePluginTest {

  private final ProxyServer proxy = mock(ProxyServer.class);
  private final BtcCloudNetWakePlugin plugin =
      new BtcCloudNetWakePlugin(this.proxy, NOPLogger.NOP_LOGGER, null);

  @Test
  void offByDefaultTheServerCommandIsLeftToTheProxyAndNothingIsTouched() {
    // Arrange — BTC_WAKE_TASKS is not set in the test environment
    this.plugin.onProxyInitialize(new ProxyInitializeEvent());
    var player = mock(Player.class);
    var command = new CommandExecuteEvent(player, "server Donjon");

    // Act
    this.plugin.onCommand(command);

    // Assert
    assertTrue(command.getResult().isAllowed());
    verifyNoInteractions(this.proxy);
  }

  @Test
  void offByDefaultTheLimboQuestionIsNotAnswered() {
    // Arrange
    this.plugin.onProxyInitialize(new ProxyInitializeEvent());
    var question = new LimboHoldEvent(mock(Player.class));

    // Act
    this.plugin.onLimboHold(question);

    // Assert
    assertFalse(question.isHeld());
  }
}
