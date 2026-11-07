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

import com.btcvelocity.api.connection.ListenerOrigin;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.protocol.StateRegistry;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Drains this proxy so it can be stopped or updated without kicking anyone (arrivee-limbo-et-reveil
 * 1.6): it refuses new logins, announces the drain to the cluster so the others stop picking it as
 * a transfer target, and hands its players over to a live peer a few at a time.
 *
 * <p>Nothing happens until {@link #start()}. The drain can be {@link #cancel() cancelled}; players
 * already sent stay where they went. The server list status is left alone: the proxy keeps
 * answering pings. Players on a client older than 1.20.5 and Bedrock players (Geyser has no host
 * to give them) cannot be transferred and stay connected. {@link #isDraining()} is a plain
 * volatile read, safe on a Netty event loop; everything that touches Redis runs on the command
 * thread or the scheduler.</p>
 *
 * <p>Every proxy of the cluster must run a version that knows the drain mark before the first
 * drain: an older one ignores it and keeps picking the drained proxy as a transfer target.</p>
 */
public final class ProxyDrain {

  private static final Logger LOGGER = LogManager.getLogger(ProxyDrain.class);

  /** How many players one pass hands to a peer: a login storm on the target is the risk. */
  public static final int BATCH_SIZE = 25;

  /** Time between two passes. */
  public static final Duration BATCH_INTERVAL = Duration.ofSeconds(1);

  /**
   * What the drain needs from the cluster.
   */
  public interface Cluster {

    /**
     * Publishes or clears the drain mark the other proxies read. Blocking.
     *
     * @param draining {@code true} to publish the mark
     */
    void publishMark(boolean draining);

    /**
     * Picks the peer that receives players. Blocking.
     *
     * @return the address, or {@code null} if no live proxy can take players
     */
    @Nullable InetSocketAddress pickTarget();

    /**
     * Frees the players' Redis entries so the peer's claim finds them unowned. Blocking, bounded.
     *
     * @param players the players about to be sent
     * @return {@code false} if they could not be freed in time: they must not be sent yet
     */
    boolean releasePlayers(Collection<UUID> players);

    /**
     * Takes back players released and not gone (drain cancelled, pass abandoned, transfer refused):
     * their entries are written again. The others stay released; their hold expires by itself.
     * Not blocking.
     *
     * @param players the players that stay on this proxy
     */
    void keepPlayers(Collection<UUID> players);
  }

  /**
   * Runs a task once per {@link #BATCH_INTERVAL} until the returned action is run.
   */
  @FunctionalInterface
  public interface Repeater {

    /**
     * Starts repeating a task.
     *
     * @param task what to run
     * @return the action that stops it
     */
    Runnable repeat(Runnable task);
  }

  /**
   * What {@link #start()} did.
   *
   * @param transferable players that will be handed to a peer
   * @param stayingHere players that cannot be transferred (client older than 1.20.5, Bedrock)
   * @param hasTarget whether a live peer could take players at the start
   */
  public record Started(int transferable, int stayingHere, boolean hasTarget) {
  }

  /**
   * Where the drain stands.
   *
   * @param draining whether the drain is running
   * @param connected players still connected to this proxy
   * @param transferred players handed to a peer since the start
   */
  public record Status(boolean draining, int connected, int transferred) {
  }

  private final Cluster cluster;
  private final Supplier<Collection<ConnectedPlayer>> players;
  private final Repeater repeater;
  private final Set<UUID> sent = ConcurrentHashMap.newKeySet();
  /** Players whose transfer was denied or failed: not tried again until the next start. */
  private final Set<UUID> refused = ConcurrentHashMap.newKeySet();
  /** Held by a pass, so a slow one is not overtaken by the next: both would pick the same batch. */
  private final ReentrantLock passLock = new ReentrantLock();
  /** The batch released and not yet sent: what a cancel takes back. Cleared under {@code this}. */
  private volatile List<UUID> unsent = List.of();
  private volatile boolean draining;
  private volatile boolean passesStopped;
  private @Nullable Runnable stopTask; // guarded by this

  /**
   * Creates an idle drain.
   *
   * @param cluster the cluster side: drain mark and transfer target
   * @param players the players connected to this proxy
   * @param repeater runs the transfer passes
   */
  public ProxyDrain(final Cluster cluster, final Supplier<Collection<ConnectedPlayer>> players,
                    final Repeater repeater) {
    this.cluster = cluster;
    this.players = players;
    this.repeater = repeater;
  }

  /**
   * Whether new logins must be refused.
   *
   * @return {@code true} from {@link #start()} until {@link #cancel()}
   */
  public boolean isDraining() {
    return this.draining;
  }

  /**
   * Starts draining: refuses new logins, publishes the mark, begins handing players over.
   *
   * @return what the drain will do, or empty if it was already running
   */
  public synchronized Optional<Started> start() {
    if (this.draining) {
      return Optional.empty();
    }
    // Refuse first: from here no login gets in, whatever Redis does next.
    this.draining = true;
    this.passesStopped = false;
    this.sent.clear();
    this.refused.clear();
    publishMark(true);
    this.stopTask = this.repeater.repeat(this::transferBatch);
    final Collection<ConnectedPlayer> online = this.players.get();
    final int transferable = (int) online.stream().filter(ProxyDrain::canBeTransferred).count();
    return Optional.of(new Started(transferable, online.size() - transferable,
        pickTarget() != null));
  }

  /**
   * Stops draining: logins are accepted again and the mark is cleared. Players already sent
   * stay where they went.
   *
   * @return {@code false} if no drain was running
   */
  public synchronized boolean cancel() {
    if (!this.draining) {
      return false;
    }
    this.draining = false;
    stopPasses();
    // Only what did not leave: players already sent are not on this proxy any more, and their
    // hold expires by itself. Lifting it would let the repair recreate an entry the target refuses.
    if (!this.unsent.isEmpty()) {
      this.cluster.keepPlayers(this.unsent);
      this.unsent = List.of();
    }
    publishMark(false);
    return true;
  }

  /**
   * Stops the transfer passes and leaves the drain mark and the refusal of logins as they are.
   * Called when the proxy shuts down: the shutdown hands the players over itself, and a pass
   * running beside it would send some of them twice.
   */
  public synchronized void stopPasses() {
    this.passesStopped = true;
    if (this.stopTask != null) {
      this.stopTask.run();
      this.stopTask = null;
    }
  }

  /**
   * Reads the state of the drain.
   *
   * @return the state now
   */
  public Status status() {
    return new Status(this.draining, this.players.get().size(), this.sent.size());
  }

  /**
   * One pass: hands the next {@link #BATCH_SIZE} transferable players to a live peer. The target is
   * picked again on every pass, so a peer that died meanwhile gets nobody more.
   */
  void transferBatch() {
    // A pass still waiting on Redis is not overtaken: the next one would pick the same players.
    if (!this.passLock.tryLock()) {
      return;
    }
    try {
      sendNextBatch();
    } finally {
      this.passLock.unlock();
    }
  }

  private void sendNextBatch() {
    if (!isRunning()) {
      return;
    }
    final List<ConnectedPlayer> batch = this.players.get().stream()
        .filter(ProxyDrain::canBeTransferred)
        .filter(ProxyDrain::isInTransferableState)
        .filter(player -> !this.sent.contains(player.getUniqueId()))
        .filter(player -> !this.refused.contains(player.getUniqueId()))
        .limit(BATCH_SIZE)
        .toList();
    if (batch.isEmpty()) {
      return; // nothing to hand over: no Redis read either
    }
    final InetSocketAddress target = pickTarget();
    // Read again: a cancel during the Redis read must not send anyone.
    if (target == null || !isRunning()) {
      return; // retried at the next pass
    }
    // Without their entries, the peer's claim cannot be refused as a duplicate of this proxy's.
    final List<UUID> uniqueIds = batch.stream().map(ConnectedPlayer::getUniqueId).toList();
    this.unsent = uniqueIds;
    if (!releasePlayers(uniqueIds)) {
      this.unsent = List.of(); // not released: the service holds nothing back
      return; // retried at the next pass
    }
    // Read and send under the monitor: a cancel cannot come between the two (requestTransfer only
    // queues the packet), so it takes back exactly the players not sent.
    synchronized (this) {
      if (isRunning()) {
        for (final ConnectedPlayer player : batch) {
          send(player, target);
        }
      } else {
        this.cluster.keepPlayers(uniqueIds); // cancelled while releasing: the entries go back
      }
      this.unsent = List.of();
    }
  }

  private boolean isRunning() {
    return this.draining && !this.passesStopped;
  }

  private void send(final ConnectedPlayer player, final InetSocketAddress target) {
    final UUID uniqueId = player.getUniqueId();
    this.sent.add(uniqueId);
    try {
      player.requestTransfer(target).whenComplete((accepted, failure) -> {
        if (failure != null || !accepted) {
          refuse(uniqueId);
        }
      });
    } catch (RuntimeException e) {
      LOGGER.warn("Could not transfer {} while draining: {}", player.getUsername(), e.toString());
      refuse(uniqueId);
    }
  }

  /** A player who was not sent stays here: not counted as sent, not tried again every pass. */
  private void refuse(final UUID uniqueId) {
    this.refused.add(uniqueId);
    this.sent.remove(uniqueId);
    // It stays here: otherwise its entry would only come back when the hold expires.
    this.cluster.keepPlayers(List.of(uniqueId));
  }

  /** Clients older than 1.20.5 have no transfer packet; Geyser has no Bedrock host to give. */
  private static boolean canBeTransferred(final ConnectedPlayer player) {
    return player.getProtocolVersion().noLessThan(ProtocolVersion.MINECRAFT_1_20_5)
        && ListenerOrigin.of(player).filter(ListenerOrigin.GEYSER::equals).isEmpty();
  }

  /** A login still in progress does not take a transfer packet. */
  private static boolean isInTransferableState(final ConnectedPlayer player) {
    final StateRegistry state = player.getConnection().getState();
    return state == StateRegistry.PLAY || state == StateRegistry.CONFIG;
  }

  private void publishMark(final boolean mark) {
    try {
      this.cluster.publishMark(mark);
    } catch (RuntimeException e) {
      // Local state wins; the heartbeat publishes the mark once Redis answers, or lets it expire.
      LOGGER.warn("Could not {} the drain mark in Redis ({}); the local drain state is kept",
          mark ? "publish" : "clear", e.toString());
    }
  }

  private boolean releasePlayers(final Collection<UUID> uniqueIds) {
    try {
      return this.cluster.releasePlayers(uniqueIds);
    } catch (RuntimeException e) {
      LOGGER.warn("Could not release the players of this batch in Redis: {}", e.toString());
      return false;
    }
  }

  private @Nullable InetSocketAddress pickTarget() {
    try {
      return this.cluster.pickTarget();
    } catch (RuntimeException e) {
      LOGGER.warn("Could not pick a proxy to drain to: {}", e.toString());
      return null;
    }
  }
}
