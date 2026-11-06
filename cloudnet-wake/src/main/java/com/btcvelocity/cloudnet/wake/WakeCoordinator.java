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

import com.btcvelocity.api.event.player.LimboHoldEvent;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

/**
 * Wakes a stopped task for the players who ask for it, and parks them in the limbo until a server
 * of that task is registered in the proxy.
 *
 * <p>One <em>session</em> per task is the dedupe table: the first request opens it and starts the
 * service, every later request for the same task joins it, and the session closes on the first
 * registration, on the start failing, or on the timeout. The node deduplicates nothing, so without
 * this table five simultaneous requests would create five instances.
 *
 * <p>A player is parked, not routed: while a session holds them they answer {@link LimboHoldEvent}
 * so the arrival router leaves them in the limbo, and when the session closes without a server
 * (timeout, failure) the hold is simply dropped — the router's next sweep sends them back to a
 * lobby. Never blocks: the starter is asynchronous, connections are fire-and-complete, and the
 * timeout runs on the proxy scheduler.
 *
 * <p>Thread-safe: requests come from the event loop, registrations and orchestrator answers from
 * CloudNet and scheduler threads. A session is closed by removing it from {@link #sessions}, which
 * succeeds exactly once, so a player is released exactly once.
 */
final class WakeCoordinator {

  /** Delays a task on the proxy scheduler and returns the way to cancel it. */
  @FunctionalInterface
  interface TimeoutScheduler {

    Runnable schedule(Duration delay, Runnable task);
  }

  private static final String SERVER_COMMAND = "server";
  private static final String SERVER_PERMISSION = "velocity.command.server";
  private static final String INSTANCE_SEPARATORS = "-_.";
  private static final int SHORT_ID_LENGTH = 8;
  // A transfer refused with CONNECTION_IN_PROGRESS is retried: 10 tries a second apart.
  static final Duration TRANSFER_RETRY_DELAY = Duration.ofSeconds(1);
  static final int TRANSFER_MAX_ATTEMPTS = 10;

  private static final class Session {
    private final String task;
    private final Set<UUID> players = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean begun = new AtomicBoolean();
    private volatile Runnable cancelTimeout = () -> { };

    private Session(String task) {
      this.task = task;
    }
  }

  private final ProxyServer proxy;
  private final Logger logger;
  private final WakeSettings settings;
  private final WakeMessages messages;
  private final ServiceStarter starter;
  private final TimeoutScheduler scheduler;
  private final Map<String, Session> sessions = new ConcurrentHashMap<>();
  private final Map<UUID, String> holds = new ConcurrentHashMap<>();
  // Players whose connection to the limbo is under way; the value is a message an abort could not
  // deliver yet (a player switching server loses it), sent once the connection has settled.
  private final Map<UUID, Optional<Component>> parking = new ConcurrentHashMap<>();

  WakeCoordinator(ProxyServer proxy, Logger logger, WakeSettings settings, WakeMessages messages,
                  ServiceStarter starter, TimeoutScheduler scheduler) {
    this.proxy = proxy;
    this.logger = logger;
    this.settings = settings;
    this.messages = messages;
    this.starter = starter;
    this.scheduler = scheduler;
  }

  /**
   * Handles {@code /server <name>} when the name is a wakeable task nobody runs yet.
   *
   * @param player      the player who typed the command
   * @param commandLine the command line without the leading slash
   * @return {@code true} when the command was taken over and must not reach the proxy
   */
  boolean onServerCommand(Player player, String commandLine) {
    String[] words = commandLine.trim().split("\\s+");
    if (words.length != 2 || !words[0].equalsIgnoreCase(SERVER_COMMAND)) {
      return false;
    }
    Optional<String> task = this.settings.taskFor(words[1]);
    if (task.isEmpty() || this.proxy.getServer(words[1]).isPresent()
        || !mayUse(player, task.get())) {
      return false;
    }
    return this.request(player, task.get());
  }

  /**
   * A server appeared in the proxy: every session for its task is over.
   *
   * @param server the server just registered
   */
  void onServerRegistered(RegisteredServer server) {
    String name = server.getServerInfo().getName();
    this.sessions.values().stream()
        .filter(session -> this.isInstanceOf(session.task, name))
        .toList()
        .forEach(session -> this.finish(session, player -> this.transfer(player, session.task, server)));
  }

  /**
   * Answers the arrival router: a parked player stays in the limbo, with the wake-up text.
   *
   * @param event the question
   */
  void onLimboHold(LimboHoldEvent event) {
    String task = this.holds.get(event.getPlayer().getUniqueId());
    if (task != null) {
      event.hold(this.text("wake.waiting", task));
    }
  }

  /**
   * A player left the proxy: nothing to hold any more.
   *
   * @param player the player who left
   */
  void onDisconnect(UUID player) {
    this.holds.remove(player);
    this.parking.remove(player);
  }

  private boolean request(Player player, String task) {
    Optional<? extends RegisteredServer> limbo = this.limboServer();
    if (limbo.isEmpty()) {
      return false; // nowhere to park them: leave the command to the proxy, unchanged
    }
    Optional<? extends RegisteredServer> running = this.registeredInstance(task);
    if (running.isPresent()) {
      player.createConnectionRequest(running.get()).fireAndForget();
      return true;
    }

    UUID id = player.getUniqueId();
    this.holds.put(id, task);
    this.parking.put(id, Optional.empty());
    Session session = this.sessions.compute(task, (key, existing) -> {
      Session joined = existing != null ? existing : new Session(key);
      joined.players.add(id);
      return joined;
    });
    this.logger.info("Player {} asks for stopped task {}", shortId(id), task);
    if (session.begun.compareAndSet(false, true)) {
      this.begin(session);
    }
    if (!task.equals(this.holds.get(id))) {
      // The session already ended (start refused at once): the player stays where they are, and
      // an abort that was deferred while "parking" is delivered here.
      this.parking.remove(id).ifPresent(player::sendMessage);
      return true;
    }
    player.createConnectionRequest(limbo.get()).connect().whenComplete(this.guarded("park",
        (result, error) -> this.onParked(player, task, result, error),
        () -> {
          this.holds.remove(id, task);
          this.parking.remove(id);
        }));
    // A registration between the first look and joining the session fired its event too early.
    this.registeredInstance(task).ifPresent(server ->
        this.finish(session, parked -> this.transfer(parked, task, server)));
    return true;
  }

  private void begin(Session session) {
    try {
      session.cancelTimeout =
          this.scheduler.schedule(this.settings.timeout(), () -> this.expire(session));
    } catch (RuntimeException error) {
      // No timeout means a session that could never end: close it now rather than start the task.
      this.logger.warn("Could not schedule the wake timeout of task {}", session.task, error);
      this.finish(session, player -> this.abort(player, session.task, "wake.failed"));
      return;
    }
    this.logger.info("Waking task {} (timeout {} s)", session.task,
        this.settings.timeout().toSeconds());
    CompletableFuture<ServiceStarter.Outcome> started;
    try {
      started = this.starter.ensureStarted(session.task);
    } catch (RuntimeException error) {
      started = CompletableFuture.failedFuture(error);
    }
    started.whenComplete(this.guarded("start",
        (outcome, error) -> this.onStarted(session, outcome, error), () -> { }));
  }

  private void onStarted(Session session, ServiceStarter.Outcome outcome, Throwable error) {
    if (error == null && outcome.waitable()) {
      this.logger.info("Task {}: {}, waiting for it to register", session.task, outcome);
      return;
    }
    this.logger.warn("Task {} could not be woken ({})", session.task,
        error == null ? outcome : error.toString());
    this.finish(session, player -> this.abort(player, session.task, "wake.failed"));
  }

  private void expire(Session session) {
    if (this.sessions.get(session.task) == session) {
      this.logger.warn("Task {} did not register within {} s, releasing its players",
          session.task, this.settings.timeout().toSeconds());
    }
    this.finish(session, player -> this.abort(player, session.task, "wake.timeout"));
  }

  /** Closes the session exactly once and applies {@code action} to each player still held by it. */
  private void finish(Session session, Consumer<Player> action) {
    if (!this.sessions.remove(session.task, session)) {
      return;
    }
    session.cancelTimeout.run();
    for (UUID id : session.players) {
      if (session.task.equals(this.holds.get(id))) {
        // One player failing must neither strand the others nor keep their own hold forever.
        try {
          this.proxy.getPlayer(id).ifPresentOrElse(action, () -> this.holds.remove(id, session.task));
        } catch (RuntimeException error) {
          this.holds.remove(id, session.task);
          this.logger.warn("Releasing player {} from task {} failed", shortId(id), session.task, error);
        }
      }
    }
  }

  // The hold is kept until the connection settles: dropping it earlier would let the router's
  // sweep send the player to a lobby while they are on their way to the woken server.
  private void transfer(Player player, String task, RegisteredServer server) {
    player.sendMessage(this.text("wake.ready", task));
    this.attemptTransfer(player, task, server, 1);
  }

  private void attemptTransfer(Player player, String task, RegisteredServer server, int attempt) {
    UUID id = player.getUniqueId();
    if (!task.equals(this.holds.get(id))) {
      return; // left the proxy, or released by someone else meanwhile
    }
    player.createConnectionRequest(server).connect().whenComplete(this.guarded("transfer",
        (result, error) -> this.onTransferSettled(player, task, server, attempt, result, error),
        () -> this.holds.remove(id, task)));
  }

  private void onTransferSettled(Player player, String task, RegisteredServer server, int attempt,
                                 ConnectionRequestBuilder.Result result, Throwable error) {
    UUID id = player.getUniqueId();
    // IN_PROGRESS is not a refusal: the player is still joining the limbo or already switching.
    if (error == null && result.getStatus() == ConnectionRequestBuilder.Status.CONNECTION_IN_PROGRESS
        && attempt < TRANSFER_MAX_ATTEMPTS) {
      this.scheduler.schedule(TRANSFER_RETRY_DELAY, () -> this.retryTransfer(player, task, server, attempt + 1));
      return;
    }
    this.holds.remove(id, task);
    if (error != null || !result.isSuccessful()) {
      this.logger.warn("Player {} could not be sent to {}", shortId(id),
          server.getServerInfo().getName());
      player.sendMessage(this.text("wake.failed", task));
    }
  }

  private void retryTransfer(Player player, String task, RegisteredServer server, int attempt) {
    try {
      this.attemptTransfer(player, task, server, attempt);
    } catch (RuntimeException error) {
      this.holds.remove(player.getUniqueId(), task);
      this.logger.warn("Transfer retry of player {} failed", shortId(player.getUniqueId()), error);
    }
  }

  /** Wraps a completion callback: an exception is logged, and {@code cleanup} lifts the hold. */
  private <T> BiConsumer<T, Throwable> guarded(String what, BiConsumer<T, Throwable> body,
                                               Runnable cleanup) {
    return (value, error) -> {
      try {
        body.accept(value, error);
      } catch (RuntimeException failure) {
        this.logger.warn("Wake callback {} failed", what, failure);
        cleanup.run();
      }
    };
  }

  private void abort(Player player, String task, String messageKey) {
    UUID id = player.getUniqueId();
    this.holds.remove(id, task);
    Component message = this.text(messageKey, task);
    boolean[] deferred = new boolean[1];
    // Atomic against onParked: either the message is parked here, or it is sent right now.
    this.parking.computeIfPresent(id, (key, pending) -> {
      deferred[0] = true;
      return pending.isPresent() ? pending : Optional.of(message);
    });
    if (!deferred[0]) {
      player.sendMessage(message);
    }
  }

  private void onParked(Player player, String task, ConnectionRequestBuilder.Result result,
                        Throwable error) {
    Optional<Component> pending = this.parking.remove(player.getUniqueId());
    if (pending.isPresent()) {
      player.sendMessage(pending.get());
      return;
    }
    var status = error == null
        ? result.getStatus() : ConnectionRequestBuilder.Status.CONNECTION_CANCELLED;
    switch (status) {
      // Already there: no arrival event will tell them, so do it here.
      case ALREADY_CONNECTED -> player.sendMessage(this.text("wake.waiting", task));
      case CONNECTION_CANCELLED, SERVER_DISCONNECTED -> this.abort(player, task, "wake.failed");
      // SUCCESS: the router shows the wake-up text on arrival; IN_PROGRESS: one is under way.
      default -> { }
    }
  }

  private Component text(String key, String task) {
    return this.messages.get(key, task, this.settings.timeout().toSeconds());
  }

  private Optional<? extends RegisteredServer> limboServer() {
    return this.proxy.getServer(this.settings.limbo());
  }

  private Optional<? extends RegisteredServer> registeredInstance(String task) {
    return this.proxy.getAllServers().stream()
        .filter(server -> this.isInstanceOf(task, server.getServerInfo().getName()))
        .min(Comparator.comparing(server -> server.getServerInfo().getName()));
  }

  /** {@code Donjon-3} is an instance of {@code Donjon}; the limbo never is. */
  private boolean isInstanceOf(String task, String serverName) {
    int prefix = task.length();
    return serverName.length() > prefix + 1
        && serverName.regionMatches(true, 0, task, 0, prefix)
        && INSTANCE_SEPARATORS.indexOf(serverName.charAt(prefix)) >= 0
        && serverName.substring(prefix + 1).chars().allMatch(Character::isDigit)
        && !serverName.equalsIgnoreCase(this.settings.limbo());
  }

  private static boolean mayUse(Player player, String task) {
    return player.getPermissionValue(SERVER_PERMISSION) != Tristate.FALSE
        && player.getPermissionValue(SERVER_PERMISSION + "." + task) != Tristate.FALSE;
  }

  private static String shortId(UUID id) {
    return id.toString().substring(0, SHORT_ID_LENGTH);
  }
}
