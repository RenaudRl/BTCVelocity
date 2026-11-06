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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.btcvelocity.api.event.player.LimboHoldEvent;
import com.btcvelocity.cloudnet.wake.ServiceStarter.Outcome;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import eu.cloudnetservice.driver.provider.CloudServiceFactory;
import eu.cloudnetservice.driver.provider.CloudServiceProvider;
import eu.cloudnetservice.driver.provider.ServiceTaskProvider;
import eu.cloudnetservice.driver.service.ServiceConfiguration;
import eu.cloudnetservice.driver.service.ServiceTask;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;
import org.slf4j.helpers.NOPLogger;

class WakeCoordinatorTest {

  private static final String TASK = "Donjon";
  private static final String COMMAND = "server Donjon";
  private static final Duration TIMEOUT = Duration.ofSeconds(90);
  private static final int SIMULTANEOUS_PLAYERS = 5;

  private final ProxyServer proxy = mock(ProxyServer.class);
  private final List<RegisteredServer> registered = new CopyOnWriteArrayList<>();
  private final RegisteredServer limbo = this.server("limbo");
  private final List<Runnable> timers = new ArrayList<>();
  private final List<Duration> delays = new ArrayList<>();
  private final AtomicInteger cancelled = new AtomicInteger();
  private final AtomicInteger starts = new AtomicInteger();
  private CompletableFuture<Outcome> startAnswer = new CompletableFuture<>();
  private final WakeMessages messages = WakeMessages.defaults();
  private final WakeCoordinator coordinator = new WakeCoordinator(
      this.proxy, NOPLogger.NOP_LOGGER,
      new WakeSettings(List.of(TASK), TIMEOUT, "limbo", true), this.messages,
      task -> {
        this.starts.incrementAndGet();
        return this.startAnswer;
      },
      (delay, task) -> {
        this.delays.add(delay);
        this.timers.add(task);
        return this.cancelled::incrementAndGet;
      });

  WakeCoordinatorTest() {
    doReturn(this.registered).when(this.proxy).getAllServers();
  }

  private RegisteredServer server(String name) {
    var server = mock(RegisteredServer.class);
    when(server.getServerInfo()).thenReturn(new ServerInfo(name, new InetSocketAddress(25599)));
    doReturn(Optional.of(server)).when(this.proxy).getServer(name);
    this.registered.add(server);
    return server;
  }

  /** A player whose connection requests succeed; the requests are recorded per target server. */
  private Player player() {
    var player = mock(Player.class);
    var id = UUID.randomUUID();
    when(player.getUniqueId()).thenReturn(id);
    doReturn(Optional.of(player)).when(this.proxy).getPlayer(id);
    var request = mock(ConnectionRequestBuilder.class);
    var result = mock(ConnectionRequestBuilder.Result.class);
    when(result.getStatus()).thenReturn(ConnectionRequestBuilder.Status.SUCCESS);
    when(result.isSuccessful()).thenReturn(true);
    when(request.connect()).thenReturn(CompletableFuture.completedFuture(result));
    when(player.createConnectionRequest(any())).thenReturn(request);
    return player;
  }

  private boolean holds(Player player) {
    var event = new LimboHoldEvent(player);
    this.coordinator.onLimboHold(event);
    return event.isHeld();
  }

  private void register(String name) {
    var server = this.server(name);
    this.coordinator.onServerRegistered(server);
  }

  private Component sentTo(Player player) {
    var sent = ArgumentCaptor.forClass(Component.class);
    verify(player).sendMessage(sent.capture());
    return sent.getValue();
  }

  @Test
  void fiveSimultaneousRequestsStartTheTaskOnce() throws Exception {
    // Arrange
    var players = new ArrayList<Player>();
    for (int i = 0; i < SIMULTANEOUS_PLAYERS; i++) {
      players.add(this.player());
    }
    var ready = new CountDownLatch(SIMULTANEOUS_PLAYERS);
    var go = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(SIMULTANEOUS_PLAYERS);

    // Act — five threads release their `/server Donjon` at the same instant
    try {
      for (Player player : players) {
        pool.submit(() -> {
          ready.countDown();
          go.await();
          return this.coordinator.onServerCommand(player, COMMAND);
        });
      }
      assertTrue(ready.await(10, TimeUnit.SECONDS));
      go.countDown();
    } finally {
      pool.shutdown();
      assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
    }

    // Assert — one start, one timer, all five held in the limbo
    assertEquals(1, this.starts.get());
    assertEquals(1, this.timers.size());
    assertTrue(players.stream().allMatch(this::holds));
  }

  @Test
  void theSessionIsRemovedOnRegistrationSoALaterWakeStartsAgain() {
    // Arrange
    var first = this.player();
    this.coordinator.onServerCommand(first, COMMAND);

    // Act — the server registers (closing the session), later it is gone and someone asks again
    this.register("Donjon-1");
    assertEquals(1, this.starts.get());
    this.registered.removeIf(s -> s.getServerInfo().getName().equals("Donjon-1"));
    this.coordinator.onServerCommand(this.player(), COMMAND);

    // Assert
    assertEquals(2, this.starts.get());
  }

  @Test
  void fiveRequestsWhileTheNodeIsStillAnsweringCreateOneServiceNotFive() {
    // Arrange — the real starter over mocked CloudNet; the node's create call is still in flight
    var services = mock(CloudServiceProvider.class);
    var factory = mock(CloudServiceFactory.class);
    var tasks = mock(ServiceTaskProvider.class);
    when(services.servicesByTaskAsync(TASK))
        .thenReturn(CompletableFuture.completedFuture(List.of()));
    when(tasks.serviceTaskAsync(TASK))
        .thenReturn(CompletableFuture.completedFuture(mock(ServiceTask.class)));
    when(factory.createCloudServiceAsync(any())).thenReturn(new CompletableFuture<>());
    var wired = new WakeCoordinator(this.proxy, NOPLogger.NOP_LOGGER,
        new WakeSettings(List.of(TASK), TIMEOUT, "limbo", true), this.messages,
        new CloudNetServiceStarter(NOPLogger.NOP_LOGGER, services, factory, tasks,
            task -> mock(ServiceConfiguration.class)),
        (delay, task) -> () -> { });

    // Act
    for (int i = 0; i < SIMULTANEOUS_PLAYERS; i++) {
      wired.onServerCommand(this.player(), COMMAND);
    }

    // Assert
    verify(factory, times(1)).createCloudServiceAsync(any());
  }

  @Test
  void aRequestedStoppedTaskParksThePlayerInTheLimboAndTakesTheCommandOver() {
    // Arrange
    var player = this.player();

    // Act
    var handled = this.coordinator.onServerCommand(player, COMMAND);

    // Assert
    assertTrue(handled);
    verify(player).createConnectionRequest(this.limbo);
    assertTrue(this.holds(player));
  }

  @Test
  void theHoldEventCarriesTheWaitingTextOfThatTask() {
    // Arrange
    var player = this.player();
    this.coordinator.onServerCommand(player, COMMAND);
    var event = new LimboHoldEvent(player);

    // Act
    this.coordinator.onLimboHold(event);

    // Assert
    assertEquals(this.messages.get("wake.waiting", TASK, TIMEOUT.toSeconds()),
        event.getReason().orElseThrow());
  }

  @Test
  void aRegistrationReleasesEveryWaiterToTheNewServerAndClosesTheSession() {
    // Arrange
    var first = this.player();
    var second = this.player();
    this.coordinator.onServerCommand(first, COMMAND);
    this.coordinator.onServerCommand(second, COMMAND);

    // Act
    this.register("Donjon-1");

    // Assert
    var donjon = this.proxy.getServer("Donjon-1").orElseThrow();
    verify(first).createConnectionRequest(donjon);
    verify(second).createConnectionRequest(donjon);
    assertFalse(this.holds(first));
    assertFalse(this.holds(second));
    assertEquals(1, this.cancelled.get(), "the timeout of a closed session is cancelled");
  }

  @Test
  void theHoldLastsUntilTheTransferSettlesSoTheSweepCannotStealThePlayer() {
    // Arrange — the transfer to the woken server is still in flight
    var player = this.player();
    var inFlight = new CompletableFuture<ConnectionRequestBuilder.Result>();
    this.coordinator.onServerCommand(player, COMMAND);
    var toDonjon = mock(ConnectionRequestBuilder.class);
    when(toDonjon.connect()).thenReturn(inFlight);
    var donjon = this.server("Donjon-1");
    when(player.createConnectionRequest(donjon)).thenReturn(toDonjon);

    // Act
    this.coordinator.onServerRegistered(donjon);

    // Assert — still held while connecting, released once it settled
    assertTrue(this.holds(player));
    var result = mock(ConnectionRequestBuilder.Result.class);
    when(result.isSuccessful()).thenReturn(true);
    inFlight.complete(result);
    assertFalse(this.holds(player));
  }

  @Test
  void aServerOfAnotherTaskDoesNotReleaseTheWaiters() {
    // Arrange
    var player = this.player();
    this.coordinator.onServerCommand(player, COMMAND);

    // Act — a name that merely starts like the task is not one of its instances
    this.register("Donjon2-1");
    this.register("Donjonx");
    this.register("Lobby-1");

    // Assert
    assertTrue(this.holds(player));
  }

  @Test
  void theTimeoutTellsThePlayersAndDropsTheHoldSoTheSweepSendsThemBackToALobby() {
    // Arrange
    var player = this.player();
    this.coordinator.onServerCommand(player, COMMAND);

    // Act
    this.timers.get(0).run();

    // Assert
    assertEquals(this.messages.get("wake.timeout", TASK, TIMEOUT.toSeconds()), this.sentTo(player));
    assertFalse(this.holds(player));
  }

  @Test
  void afterTheTimeoutANewRequestWakesTheTaskAgain() {
    // Arrange
    this.coordinator.onServerCommand(this.player(), COMMAND);
    this.timers.get(0).run();

    // Act
    this.coordinator.onServerCommand(this.player(), COMMAND);

    // Assert
    assertEquals(2, this.starts.get());
  }

  @Test
  void aFailedStartReleasesEveryoneWithTheFailureText() {
    assertFailureReleases(Outcome.FAILED);
  }

  @Test
  void aDeferredCreationReleasesEveryoneWithTheFailureText() {
    assertFailureReleases(Outcome.DEFERRED);
  }

  @Test
  void anUnknownTaskReleasesEveryoneWithTheFailureText() {
    assertFailureReleases(Outcome.UNKNOWN_TASK);
  }

  private void assertFailureReleases(Outcome outcome) {
    // Arrange
    var player = this.player();
    this.coordinator.onServerCommand(player, COMMAND);

    // Act
    this.startAnswer.complete(outcome);

    // Assert
    assertEquals(this.messages.get("wake.failed", TASK, TIMEOUT.toSeconds()), this.sentTo(player));
    assertFalse(this.holds(player));
    assertEquals(1, this.cancelled.get());
  }

  @Test
  void aStarterThatThrowsIsTreatedAsAFailedStart() {
    // Arrange
    var failing = new WakeCoordinator(this.proxy, NOPLogger.NOP_LOGGER,
        new WakeSettings(List.of(TASK), TIMEOUT, "limbo", true), this.messages,
        task -> {
          throw new IllegalStateException("driver missing");
        },
        (delay, task) -> () -> { });
    var player = this.player();

    // Act
    failing.onServerCommand(player, COMMAND);

    // Assert
    assertEquals(this.messages.get("wake.failed", TASK, TIMEOUT.toSeconds()), this.sentTo(player));
  }

  @Test
  void aStartedTaskKeepsTheWaitersUntilItRegisters() {
    // Arrange
    var player = this.player();
    this.coordinator.onServerCommand(player, COMMAND);

    // Act
    this.startAnswer.complete(Outcome.STARTED);

    // Assert
    assertTrue(this.holds(player));
    verify(player, never()).sendMessage(any(Component.class));
  }

  /** A server the proxy can resolve but has not registered yet: the test registers it by event. */
  private RegisteredServer notYetRegistered(String name) {
    var server = this.server(name);
    this.registered.remove(server);
    return server;
  }

  /** A player whose first transfer to {@code donjon} answers the given statuses, in order. */
  private Player playerTransferring(RegisteredServer donjon,
                                    ConnectionRequestBuilder.Status... statuses) {
    var player = this.player();
    var toDonjon = mock(ConnectionRequestBuilder.class);
    var answers = new ArrayList<CompletableFuture<ConnectionRequestBuilder.Result>>();
    for (var status : statuses) {
      var result = mock(ConnectionRequestBuilder.Result.class);
      when(result.getStatus()).thenReturn(status);
      when(result.isSuccessful()).thenReturn(status == ConnectionRequestBuilder.Status.SUCCESS);
      answers.add(CompletableFuture.completedFuture(result));
    }
    when(toDonjon.connect()).thenReturn(answers.get(0),
        answers.subList(1, answers.size()).toArray(new CompletableFuture[0]));
    when(player.createConnectionRequest(donjon)).thenReturn(toDonjon);
    return player;
  }

  private Component failedText() {
    return this.messages.get("wake.failed", TASK, TIMEOUT.toSeconds());
  }

  @Test
  void aTransferRefusedBecauseAConnectionIsInProgressIsRetriedNotReportedAsFailed() {
    // Arrange — the server registers while the player is still joining the limbo
    var donjon = this.notYetRegistered("Donjon-1");
    var player = this.playerTransferring(donjon,
        ConnectionRequestBuilder.Status.CONNECTION_IN_PROGRESS,
        ConnectionRequestBuilder.Status.SUCCESS);
    this.coordinator.onServerCommand(player, COMMAND);

    // Act — first attempt: in progress
    this.coordinator.onServerRegistered(donjon);

    // Assert — still held, no failure text, a retry is queued
    assertTrue(this.holds(player));
    assertEquals(2, this.timers.size());
    assertEquals(WakeCoordinator.TRANSFER_RETRY_DELAY, this.delays.get(1));
    verify(player, never()).sendMessage(this.failedText());

    // Act — the retry succeeds
    this.timers.get(1).run();

    // Assert — released once, one "ready" text, still no failure
    assertFalse(this.holds(player));
    verify(player, times(2)).createConnectionRequest(donjon);
    verify(player, never()).sendMessage(this.failedText());
    verify(player, times(1)).sendMessage(this.messages.get("wake.ready", TASK, TIMEOUT.toSeconds()));
  }

  @Test
  void aTransferStuckInProgressGivesUpAfterTheBoundedRetriesWithTheFailureText() {
    // Arrange
    var donjon = this.notYetRegistered("Donjon-1");
    var player = this.playerTransferring(donjon,
        ConnectionRequestBuilder.Status.CONNECTION_IN_PROGRESS);
    this.coordinator.onServerCommand(player, COMMAND);
    this.coordinator.onServerRegistered(donjon);

    // Act — every retry stays in progress
    for (int retry = 1; retry < WakeCoordinator.TRANSFER_MAX_ATTEMPTS; retry++) {
      assertTrue(this.holds(player));
      this.timers.get(retry).run();
    }

    // Assert
    assertFalse(this.holds(player));
    verify(player, times(WakeCoordinator.TRANSFER_MAX_ATTEMPTS)).createConnectionRequest(donjon);
    verify(player).sendMessage(this.failedText());
  }

  @Test
  void aPlayerWhoLeavesBetweenTwoTransferAttemptsIsNotRetried() {
    // Arrange
    var donjon = this.notYetRegistered("Donjon-1");
    var player = this.playerTransferring(donjon,
        ConnectionRequestBuilder.Status.CONNECTION_IN_PROGRESS);
    this.coordinator.onServerCommand(player, COMMAND);
    this.coordinator.onServerRegistered(donjon);

    // Act
    this.coordinator.onDisconnect(player.getUniqueId());
    this.timers.get(1).run();

    // Assert
    verify(player, times(1)).createConnectionRequest(donjon);
  }

  @Test
  void aPlayerWhoseReleaseThrowsDoesNotStrandTheOthersNorKeepTheirOwnHold() {
    // Arrange — two waiters; one of them cannot even be messaged
    var broken = this.player();
    var fine = this.player();
    doThrow(new IllegalStateException("player gone")).when(broken).sendMessage(any(Component.class));
    this.coordinator.onServerCommand(broken, COMMAND);
    this.coordinator.onServerCommand(fine, COMMAND);

    // Act
    this.timers.get(0).run();

    // Assert — whatever the iteration order, both are released and the other still got its text
    assertFalse(this.holds(broken));
    assertFalse(this.holds(fine));
    assertEquals(this.messages.get("wake.timeout", TASK, TIMEOUT.toSeconds()), this.sentTo(fine));
  }

  @Test
  void ifTheTimeoutCannotBeScheduledTheSessionClosesInFailureAndTheTaskIsNotStarted() {
    // Arrange
    var failing = new WakeCoordinator(this.proxy, NOPLogger.NOP_LOGGER,
        new WakeSettings(List.of(TASK), TIMEOUT, "limbo", true), this.messages,
        task -> {
          this.starts.incrementAndGet();
          return this.startAnswer;
        },
        (delay, task) -> {
          throw new IllegalStateException("scheduler shut down");
        });
    var player = this.player();

    // Act
    var handled = failing.onServerCommand(player, COMMAND);

    // Assert — taken over, told, not held, nothing started; a later request is not a joiner
    assertTrue(handled);
    assertEquals(this.failedText(), this.sentTo(player));
    assertEquals(0, this.starts.get());
    var again = this.player();
    failing.onServerCommand(again, COMMAND);
    assertEquals(this.failedText(), this.sentTo(again));
  }

  @Test
  void anExceptionInACompletionCallbackIsLoggedNotSwallowed() {
    // Arrange — the logger throws when the start outcome is logged, inside the callback
    var logger = mock(Logger.class);
    doThrow(new IllegalStateException("log failure")).when(logger)
        .info(eq("Task {}: {}, waiting for it to register"), any(Object.class), any(Object.class));
    var noisy = new WakeCoordinator(this.proxy, logger,
        new WakeSettings(List.of(TASK), TIMEOUT, "limbo", true), this.messages,
        task -> CompletableFuture.completedFuture(Outcome.STARTED), (delay, task) -> () -> { });

    // Act
    noisy.onServerCommand(this.player(), COMMAND);

    // Assert
    verify(logger).warn(anyString(), eq("start"), any(Throwable.class));
  }

  @Test
  void aTaskAlreadyRegisteredSendsThePlayerStraightThereWithoutWaking() {
    // Arrange
    var donjon = this.server("Donjon-2");
    var player = this.player();

    // Act
    var handled = this.coordinator.onServerCommand(player, COMMAND);

    // Assert
    assertTrue(handled);
    verify(player).createConnectionRequest(donjon);
    assertEquals(0, this.starts.get());
    assertFalse(this.holds(player));
  }

  @Test
  void aPlayerLeavingStopsBeingHeld() {
    // Arrange
    var player = this.player();
    this.coordinator.onServerCommand(player, COMMAND);

    // Act
    this.coordinator.onDisconnect(player.getUniqueId());

    // Assert
    assertFalse(this.holds(player));
  }

  @Test
  void commandsThatAreNotAWakeAreLeftToTheProxy() {
    // Arrange
    var player = this.player();
    this.server("Donjon"); // a server really named like the task: the proxy knows it

    // Act + Assert
    assertFalse(this.coordinator.onServerCommand(player, COMMAND));
    assertFalse(this.coordinator.onServerCommand(player, "server Lobby"));
    assertFalse(this.coordinator.onServerCommand(player, "hub"));
    assertFalse(this.coordinator.onServerCommand(player, "server"));
    assertFalse(this.coordinator.onServerCommand(player, "server Donjon extra"));
    assertEquals(0, this.starts.get());
  }

  @Test
  void aPlayerWhoMayNotUseServerCannotWakeAnything() {
    // Arrange
    var player = this.player();
    when(player.getPermissionValue("velocity.command.server")).thenReturn(Tristate.FALSE);

    // Act + Assert
    assertFalse(this.coordinator.onServerCommand(player, COMMAND));
    assertEquals(0, this.starts.get());
  }

  @Test
  void withoutALimboNothingIsTakenOver() {
    // Arrange — the limbo is not registered: nowhere to park, so the proxy answers as before
    this.registered.remove(this.limbo);
    doReturn(Optional.empty()).when(this.proxy).getServer("limbo");
    var player = this.player();

    // Act + Assert
    assertFalse(this.coordinator.onServerCommand(player, COMMAND));
    assertEquals(0, this.starts.get());
  }
}
