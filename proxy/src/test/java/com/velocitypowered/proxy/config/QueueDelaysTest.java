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

package com.velocitypowered.proxy.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.electronwill.nightconfig.core.CommentedConfig;
import org.junit.jupiter.api.Test;

class QueueDelaysTest {

  @Test
  void wholeNumberDelaysAreAccepted() {
    final CommentedConfig config = CommentedConfig.inMemory();
    config.set("send-delay", 2);
    config.set("queue-delay", 0);
    config.set("message-delay", 3);
    config.set("backend-ping-interval", 10);

    final VelocityConfiguration.Queue queue = new VelocityConfiguration.Queue(config);

    assertEquals(2.0, queue.getSendDelay());
    assertEquals(0.0, queue.getQueueDelay());
    assertEquals(3.0, queue.getMessageDelay());
    assertEquals(10.0, queue.getBackendPingInterval());
  }

  @Test
  void decimalAndMissingDelaysKeepTheirMeaning() {
    final CommentedConfig config = CommentedConfig.inMemory();
    config.set("send-delay", 1.5);

    final VelocityConfiguration.Queue queue = new VelocityConfiguration.Queue(config);

    assertEquals(1.5, queue.getSendDelay());
    assertEquals(0.0, queue.getQueueDelay());
    assertEquals(1.0, queue.getMessageDelay());
    assertEquals(5.0, queue.getBackendPingInterval());
  }
}
