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

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
 * answering pings. Players on a client older than 1.20.5 cannot be transferred and stay connected.
 * {@link #isDraining()} is a plain volatile read, safe on a Netty event loop; everything that
 * touches Redis runs on the command thread or the scheduler.</p>
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
   * @param stayingHere players that cannot be transferred (client older than 1.20.5)
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
  private volatile boolean draining;
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
    this.sent.clear();
    publishMark(true);
    this.stopTask = this.repeater.repeat(this::transferBatch);
    final int transferable = transferable().size();
    return Optional.of(new Started(transferable, this.players.get().size() - transferable,
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
    if (this.stopTask != null) {
      this.stopTask.run();
      this.stopTask = null;
    }
    publishMark(false);
    return true;
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
    if (!this.draining) {
      return;
    }
    final List<ConnectedPlayer> batch = transferable().stream().limit(BATCH_SIZE).toList();
    if (batch.isEmpty()) {
      return; // nothing to hand over: no Redis read either
    }
    final InetSocketAddress target = pickTarget();
    if (target == null) {
      return; // retried at the next pass
    }
    for (final ConnectedPlayer player : batch) {
      this.sent.add(player.getUniqueId());
      try {
        player.transferToHost(target);
      } catch (RuntimeException e) {
        LOGGER.warn("Could not transfer {} while draining: {}", player.getUsername(), e.toString());
      }
    }
  }

  private List<ConnectedPlayer> transferable() {
    return this.players.get().stream()
        .filter(player -> player.getProtocolVersion().noLessThan(ProtocolVersion.MINECRAFT_1_20_5))
        .filter(player -> !this.sent.contains(player.getUniqueId()))
        .toList();
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

  private @Nullable InetSocketAddress pickTarget() {
    try {
      return this.cluster.pickTarget();
    } catch (RuntimeException e) {
      LOGGER.warn("Could not pick a proxy to drain to: {}", e.toString());
      return null;
    }
  }
}
