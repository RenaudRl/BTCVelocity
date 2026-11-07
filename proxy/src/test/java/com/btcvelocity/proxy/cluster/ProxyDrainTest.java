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
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.btcvelocity.api.connection.ListenerOrigin;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.protocol.StateRegistry;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
  /** What happened, in order: "release" when players were freed, "send" for each transfer. */
  private final List<String> events = new CopyOnWriteArrayList<>();
  private final AtomicBoolean releaseFails = new AtomicBoolean();
  private final AtomicInteger kept = new AtomicInteger();
  /** Runs inside {@code pickTarget}, to let a test act while a pass waits on Redis. */
  private final AtomicReference<Runnable> duringPick = new AtomicReference<>(() -> { });

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
      duringPick.get().run();
      return target.get();
    }

    @Override
    public boolean releasePlayers(final Collection<UUID> players) {
      events.add("release");
      return !releaseFails.get();
    }

    @Override
    public void keepPlayers() {
      kept.incrementAndGet();
    }
  }, () -> List.copyOf(online), repeated -> {
    task.set(repeated);
    return stops::incrementAndGet;
  });

  private ConnectedPlayer join(final ProtocolVersion version) {
    return join(version, StateRegistry.PLAY);
  }

  private ConnectedPlayer join(final ProtocolVersion version, final StateRegistry state) {
    final ConnectedPlayer player = mock(ConnectedPlayer.class);
    when(player.getUniqueId()).thenReturn(UUID.randomUUID());
    when(player.getProtocolVersion()).thenReturn(version);
    final MinecraftConnection connection = mock(MinecraftConnection.class);
    when(connection.getState()).thenReturn(state);
    when(player.getConnection()).thenReturn(connection);
    when(player.requestTransfer(any())).thenAnswer(inv -> {
      events.add("send");
      return CompletableFuture.completedFuture(true);
    });
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
      verify(player).requestTransfer(PEER);
    }
  }

  @Test
  void aClientTooOldToBeTransferredIsLeftAlone() {
    final ConnectedPlayer old = join(ProtocolVersion.MINECRAFT_1_20_3);
    drain.start();

    pass();

    verify(old, never()).requestTransfer(any());
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
    verify(player, never()).requestTransfer(any());

    target.set(PEER);
    pass();
    verify(player).requestTransfer(PEER);
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

    verify(first).requestTransfer(PEER);
    verify(second).requestTransfer(other);
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
    verify(player, never()).requestTransfer(any());
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
    verify(player, times(2)).requestTransfer(PEER);
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
    doThrow(new IllegalArgumentException("no")).when(broken).requestTransfer(any());
    final ConnectedPlayer fine = join(ProtocolVersion.MAXIMUM_VERSION);
    drain.start();

    pass();

    verify(fine).requestTransfer(PEER);
    assertEquals(1, drain.status().transferred(), "the broken one was not sent");
    pass();
    verify(broken, times(1)).requestTransfer(any()); // and is not tried again every second
  }

  @Test
  void aTransferDeniedByAPluginIsNotCountedAsSentNorRetried() {
    final ConnectedPlayer denied = join(ProtocolVersion.MAXIMUM_VERSION);
    when(denied.requestTransfer(any())).thenReturn(CompletableFuture.completedFuture(false));
    final ConnectedPlayer fine = join(ProtocolVersion.MAXIMUM_VERSION);
    drain.start();

    pass();
    pass();

    assertEquals(1, drain.status().transferred());
    verify(denied, times(1)).requestTransfer(PEER);
    verify(fine, times(1)).requestTransfer(PEER);
  }

  @Test
  void aTransferThatFailsLaterIsNotCountedAsSentEither() {
    final ConnectedPlayer player = join(ProtocolVersion.MAXIMUM_VERSION);
    final CompletableFuture<Boolean> event = new CompletableFuture<>();
    when(player.requestTransfer(any())).thenReturn(event);
    drain.start();

    pass();
    assertEquals(1, drain.status().transferred(), "counted once the packet is on its way");
    event.completeExceptionally(new IllegalStateException("handler failed"));

    assertEquals(0, drain.status().transferred());
  }

  @Test
  void aBedrockPlayerThroughGeyserIsNeverTransferredAndCountsAsStaying() {
    final ConnectedPlayer bedrock = join(ProtocolVersion.MAXIMUM_VERSION);
    when(bedrock.getListenerName()).thenReturn(Optional.of(ListenerOrigin.GEYSER));
    final ConnectedPlayer java = join(ProtocolVersion.MAXIMUM_VERSION);
    when(java.getListenerName()).thenReturn(Optional.of("other-listener"));

    final ProxyDrain.Started started = drain.start().orElseThrow();
    pass();

    assertEquals(1, started.transferable());
    assertEquals(1, started.stayingHere());
    verify(bedrock, never()).requestTransfer(any());
    verify(java).requestTransfer(PEER);
  }

  @Test
  void aLoginStillInProgressIsLeftForALaterPassAndStillCounted() {
    final ConnectedPlayer logging = join(ProtocolVersion.MAXIMUM_VERSION, StateRegistry.LOGIN);
    final ConnectedPlayer configuring = join(ProtocolVersion.MAXIMUM_VERSION, StateRegistry.CONFIG);

    final ProxyDrain.Started started = drain.start().orElseThrow();
    pass();

    assertEquals(2, started.transferable(), "it will be sent once it reaches the game");
    verify(logging, never()).requestTransfer(any());
    verify(configuring).requestTransfer(PEER);

    when(logging.getConnection().getState()).thenReturn(StateRegistry.PLAY);
    pass();
    verify(logging).requestTransfer(PEER);
  }

  @Test
  void playersAreFreedInRedisBeforeAnyTransferPacketLeaves() {
    join(ProtocolVersion.MAXIMUM_VERSION);
    join(ProtocolVersion.MAXIMUM_VERSION);
    drain.start();

    pass();

    assertEquals(List.of("release", "send", "send"), events);
  }

  @Test
  void aBatchThatCouldNotBeFreedIsNotSentAndIsRetriedByTheNextPass() {
    final ConnectedPlayer player = join(ProtocolVersion.MAXIMUM_VERSION);
    releaseFails.set(true);
    drain.start();

    pass();
    verify(player, never()).requestTransfer(any());
    assertEquals(0, drain.status().transferred());

    releaseFails.set(false);
    pass();
    verify(player).requestTransfer(PEER);
  }

  @Test
  void cancellingWhileAPassWaitsOnRedisSendsNobodyAndGivesTheEntriesBack() {
    final ConnectedPlayer player = join(ProtocolVersion.MAXIMUM_VERSION);
    drain.start();
    duringPick.set(drain::cancel);
    final int keptBefore = kept.get();

    pass();

    verify(player, never()).requestTransfer(any());
    assertEquals(keptBefore + 1, kept.get(), "only the cancel gives entries back");
    assertFalse(events.contains("release"), "the pass stopped before freeing anyone");
  }

  @Test
  void cancellingGivesTheFreedEntriesBack() {
    drain.start();
    final int keptBefore = kept.get();

    drain.cancel();

    assertEquals(keptBefore + 1, kept.get());
  }

  @Test
  void stoppingThePassesKeepsTheMarkAndTheRefusalOfLogins() {
    final ConnectedPlayer player = join(ProtocolVersion.MAXIMUM_VERSION);
    drain.start();

    drain.stopPasses();
    task.get().run(); // a run already handed to the scheduler

    assertEquals(1, stops.get());
    assertTrue(drain.isDraining());
    assertEquals(List.of(true), marks);
    verify(player, never()).requestTransfer(any());
  }

  @Test
  void twoPassesAtTheSameTimeSendAtMostOneBatchWithoutDuplicates() throws Exception {
    final List<ConnectedPlayer> players = new ArrayList<>();
    for (int i = 0; i < 2 * ProxyDrain.BATCH_SIZE; i++) {
      players.add(join(ProtocolVersion.MAXIMUM_VERSION));
    }
    drain.start();
    final CountDownLatch inFirstPick = new CountDownLatch(1);
    final CountDownLatch redisAnswers = new CountDownLatch(1);
    duringPick.set(() -> {
      inFirstPick.countDown();
      try {
        redisAnswers.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      duringPick.set(() -> { });
    });
    final Thread slowPass = new Thread(this::pass);
    slowPass.start();
    assertTrue(inFirstPick.await(5, TimeUnit.SECONDS));

    pass(); // the next scheduler run, while the first still waits on Redis
    redisAnswers.countDown();
    slowPass.join(5_000);

    assertEquals(ProxyDrain.BATCH_SIZE, drain.status().transferred());
    for (final ConnectedPlayer player : players) {
      verify(player, atMost(1)).requestTransfer(PEER);
    }
    assertEquals(1, events.stream().filter("release"::equals).count());
  }
}
