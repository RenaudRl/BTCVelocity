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

package com.btcvelocity.proxy.queue;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.btcvelocity.api.queue.QueueEntryData;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.config.VelocityConfiguration;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.server.VelocityRegisteredServer;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class VelocityQueueEntryKickReasonTest {

  private static final String TARGET = "Survival-1";

  private final UUID id = UUID.randomUUID();
  private final VelocityServer proxy = mock(VelocityServer.class);
  private final VelocityQueue queue = mock(VelocityQueue.class);
  private final ConnectedPlayer player = mock(ConnectedPlayer.class);
  private final VelocityRegisteredServer target = mock(VelocityRegisteredServer.class);
  private final ConnectionRequestBuilder request = mock(ConnectionRequestBuilder.class);
  private final VelocityConfiguration.Queue queueConfig = mock(VelocityConfiguration.Queue.class);

  private VelocityQueueEntry entryWhoseConnectionEndsWith(final ConnectionRequestBuilder.Result result) {
    final VelocityConfiguration config = mock(VelocityConfiguration.class);
    when(config.getQueue()).thenReturn(queueConfig);
    // connect() is the path taken when forward-kick-reason is off: it never fires the kick handling.
    when(queueConfig.isForwardKickReason()).thenReturn(false);
    when(proxy.getConfiguration()).thenReturn(config);
    when(proxy.getPlayer(id)).thenReturn(Optional.of(player));
    when(proxy.getServer(TARGET)).thenReturn(Optional.of(target));
    when(queue.getName()).thenReturn(TARGET);
    when(target.ping()).thenReturn(new CompletableFuture<>());
    when(player.createConnectionRequest(target)).thenReturn(request);
    when(request.connect()).thenReturn(CompletableFuture.completedFuture(result));
    return new VelocityQueueEntry(proxy, queue, new QueueEntryData(id, "Steve", 0, false, false));
  }

  @Test
  void aBackendKickIsHandedToThePlayerSoTheyLearnWhy() {
    final ConnectionRequestBuilder.Result result = mock(ConnectionRequestBuilder.Result.class);

    entryWhoseConnectionEndsWith(result).handleTransfer();

    verify(player).handleServerDisconnectResult(target, result);
  }

  @Test
  void aSuccessfulConnectionStillLeavesTheQueue() {
    final ConnectionRequestBuilder.Result result = mock(ConnectionRequestBuilder.Result.class);
    when(result.isSuccessful()).thenReturn(true);

    entryWhoseConnectionEndsWith(result).handleTransfer();

    verify(queue).dequeue(id);
  }
}