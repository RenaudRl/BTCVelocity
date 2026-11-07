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

package com.btcvelocity.proxy.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The drain of a proxy (arrivee-limbo-et-reveil 1.6): nothing changes until it starts, a started
 * drain refuses logins and marks the proxy, hands players over in batches, and can be cancelled.
 */
class ProxyDrainTest {

  private static final InetSocketAddress PEER = InetSocketAddress.createUnresolved("proxy-b", 25565);

  private final List<ConnectedPlayer> online = new ArrayList<>();
  private final List<Boolean> marks = new ArrayList<>();
  private final AtomicReference<InetSocketAddress> target = new AtomicReference<>(PEER);
  private final AtomicBoolean markFails = new AtomicBoolean();
  private final AtomicInteger pickCalls = new AtomicInteger();
  private final AtomicInteger stops = new AtomicInteger();
  private final AtomicReference<Runnable> task = new AtomicReference<>();

  private final ProxyDrain drain = new ProxyDrain(new ProxyDrain.Cluster() {
    @Override
    public void publishMark(final boolean draining) {
      if (markFails.get()) {
        throw new IllegalStateException("redis down");
      }
      marks.add(draining);
    }

    @Override
    public InetSocketAddress pickTarget() {
      pickCalls.incrementAndGet();
      return target.get();
    }
  }, () -> List.copyOf(online), repeated -> {
    task.set(repeated);
    return stops::incrementAndGet;
  });

  private ConnectedPlayer join(final ProtocolVersion version) {
    final ConnectedPlayer player = mock(ConnectedPlayer.class);
    when(player.getUniqueId()).thenReturn(UUID.randomUUID());
    when(player.getProtocolVersion()).thenReturn(version);
    online.add(player);
    return player;
  }

  private void pass() {
    task.get().run();
  }

  @Test
  void aProxyThatWasNeverDrainedRefusesNothingAndPublishesNothing() {
    join(ProtocolVersion.MAXIMUM_VERSION);

    assertFalse(drain.isDraining());
    assertFalse(drain.status().draining());
    assertTrue(marks.isEmpty());
    assertEquals(0, pickCalls.get());
  }

  @Test
  void startingRefusesLoginsPublishesTheMarkAndReportsWhatItWillDo() {
    join(ProtocolVersion.MAXIMUM_VERSION);
    join(ProtocolVersion.MINECRAFT_1_20_3);

    final ProxyDrain.Started started = drain.start().orElseThrow();

    assertTrue(drain.isDraining());
    assertEquals(List.of(true), marks);
    assertEquals(1, started.transferable());
    assertEquals(1, started.stayingHere());
    assertTrue(started.hasTarget());
  }

  @Test
  void startingTwiceIsRefusedAndDoesNotRestartTheTransfers() {
    drain.start();

    assertTrue(drain.start().isEmpty());
    assertEquals(List.of(true), marks);
  }

  @Test
  void aPassHandsOverAtMostOneBatchAndNeverTheSamePlayerTwice() {
    final List<ConnectedPlayer> players = new ArrayList<>();
    for (int i = 0; i < ProxyDrain.BATCH_SIZE + 5; i++) {
      players.add(join(ProtocolVersion.MAXIMUM_VERSION));
    }
    drain.start();

    pass();
    assertEquals(ProxyDrain.BATCH_SIZE, drain.status().transferred());
    pass();
    pass();

    assertEquals(ProxyDrain.BATCH_SIZE + 5, drain.status().transferred());
    for (final ConnectedPlayer player : players) {
      verify(player).transferToHost(PEER);
    }
  }

  @Test
  void aClientTooOldToBeTransferredIsLeftAlone() {
    final ConnectedPlayer old = join(ProtocolVersion.MINECRAFT_1_20_3);
    drain.start();

    pass();

    verify(old, never()).transferToHost(any());
    assertEquals(1, drain.status().connected());
    assertEquals(0, drain.status().transferred());
  }

  @Test
  void withoutALivePeerNobodyIsSentAndThePassRetriesLater() {
    final ConnectedPlayer player = join(ProtocolVersion.MAXIMUM_VERSION);
    target.set(null);
    final ProxyDrain.Started started = drain.start().orElseThrow();

    pass();
    assertFalse(started.hasTarget());
    verify(player, never()).transferToHost(any());

    target.set(PEER);
    pass();
    verify(player).transferToHost(PEER);
  }

  @Test
  void thePeerIsPickedAgainOnEveryPassSoADeadOneGetsNobodyMore() {
    final ConnectedPlayer first = join(ProtocolVersion.MAXIMUM_VERSION);
    drain.start();
    pass();
    final InetSocketAddress other = InetSocketAddress.createUnresolved("proxy-c", 25565);
    target.set(other);
    final ConnectedPlayer second = join(ProtocolVersion.MAXIMUM_VERSION);

    pass();

    verify(first).transferToHost(PEER);
    verify(second).transferToHost(other);
  }

  @Test
  void aPassWithNobodyLeftToSendDoesNotAskRedisForATarget() {
    drain.start();
    final int afterStart = pickCalls.get();

    pass();

    assertEquals(afterStart, pickCalls.get());
  }

  @Test
  void cancellingStopsTheTransfersClearsTheMarkAndAcceptsLoginsAgain() {
    final ConnectedPlayer player = join(ProtocolVersion.MAXIMUM_VERSION);
    drain.start();

    assertTrue(drain.cancel());
    task.get().run(); // a pass already in flight at the cancel

    assertFalse(drain.isDraining());
    assertEquals(1, stops.get());
    assertEquals(List.of(true, false), marks);
    verify(player, never()).transferToHost(any());
  }

  @Test
  void cancellingWhenNothingIsDrainingChangesNothing() {
    assertFalse(drain.cancel());
    assertTrue(marks.isEmpty());
  }

  @Test
  void aDrainCanStartAgainAfterACancelAndTransfersEveryoneAgain() {
    final ConnectedPlayer player = join(ProtocolVersion.MAXIMUM_VERSION);
    drain.start();
    pass();
    drain.cancel();

    drain.start();
    pass();

    assertEquals(1, drain.status().transferred());
    verify(player, times(2)).transferToHost(PEER);
  }

  @Test
  void aRedisFailureKeepsTheLocalDrainSoLoginsStayRefused() {
    markFails.set(true);

    final ProxyDrain.Started started = drain.start().orElseThrow();

    assertTrue(drain.isDraining());
    assertTrue(started.hasTarget());
    assertTrue(drain.cancel());
    assertFalse(drain.isDraining());
  }

  @Test
  void aPlayerWhoseTransferThrowsDoesNotStopTheRestOfTheBatch() {
    final ConnectedPlayer broken = join(ProtocolVersion.MAXIMUM_VERSION);
    doThrow(new IllegalArgumentException("no")).when(broken).transferToHost(any());
    final ConnectedPlayer fine = join(ProtocolVersion.MAXIMUM_VERSION);
    drain.start();

    pass();

    verify(fine).transferToHost(PEER);
    assertEquals(2, drain.status().transferred());
  }
}
