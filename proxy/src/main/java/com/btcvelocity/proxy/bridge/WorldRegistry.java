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

package com.btcvelocity.proxy.bridge;

import com.btcvelocity.api.bridge.BridgeMessage;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Thread-safe registry that tracks which worlds are currently loaded on which backend servers.
 *
 * <p>The registry is populated from {@link BridgeMessage.WorldLoaded} and
 * {@link BridgeMessage.WorldUnloaded} messages received over the {@code btc:bridge} channel.
 * It lets the proxy decide whether a world still needs to be preloaded before transferring a
 * queued player, and whether a server is already prepared to accept a player bound for a
 * specific world.</p>
 *
 * <p><b>What a silent backend says expires.</b> A world is only ever reported loaded once; nothing
 * would ever contradict that report if the backend died. Each server's entry therefore carries the
 * instant of its last signal — a world event, or the periodic health report that lists the loaded
 * worlds — and stops being believed after {@link #DEFAULT_FRESHNESS_MILLIS}. A stale entry reads as
 * "no world loaded", never as the last thing that was true.</p>
 */
public final class WorldRegistry {

  /**
   * How long the world view of a server stays current. Backends report their health, loaded worlds
   * included, every ten seconds: this tolerates two missed reports.
   */
  public static final long DEFAULT_FRESHNESS_MILLIS = 30_000L;

  private final Map<String, Entry> worldsByServer = new ConcurrentHashMap<>();
  private final long freshnessMillis;
  private final LongSupplier clock;

  /** The worlds a server is known to hold, and when it last said anything about them. */
  private record Entry(Set<String> worlds, long signalledAt) {
  }

  /** Creates a registry using the default freshness window and the system clock. */
  public WorldRegistry() {
    this(DEFAULT_FRESHNESS_MILLIS, System::currentTimeMillis);
  }

  /**
   * Creates a registry.
   *
   * @param freshnessMillis how long a server's world view stays current, in milliseconds
   * @param clock           the source of the current time, in milliseconds
   */
  public WorldRegistry(final long freshnessMillis, final LongSupplier clock) {
    if (freshnessMillis <= 0) {
      throw new IllegalArgumentException("freshness must be positive");
    }
    this.freshnessMillis = freshnessMillis;
    this.clock = clock;
  }

  /**
   * Records that a world has been loaded on the given server.
   *
   * @param message the world-loaded message
   */
  public void onWorldLoaded(final BridgeMessage.WorldLoaded message) {
    final Entry entry = refreshed(message.serverName());
    entry.worlds().add(message.worldName());
  }

  /**
   * Bridge-message listener entry point suitable for registration with
   * {@link com.btcvelocity.api.bridge.BridgeChannel#registerListener}. It ignores every
   * message type other than {@link BridgeMessage.WorldLoaded}.
   *
   * @param sourceServer the name of the server that sent the message
   * @param message      the decoded bridge message
   */
  public void onWorldLoaded(final String sourceServer, final BridgeMessage message) {
    if (message instanceof BridgeMessage.WorldLoaded loaded) {
      onWorldLoaded(loaded);
    }
  }

  /**
   * Reconciles a server's world view from its periodic health report.
   *
   * <p>The health report lists every loaded world, so it is a full statement, not an increment:
   * it replaces the set instead of adding to it. A world unloaded during a lost {@code
   * world_unloaded} message disappears here, which is the point — the registry converges on what
   * the backend actually holds rather than on the sum of the events that happened to arrive.</p>
   *
   * @param message the health report
   */
  public void onHealth(final BridgeMessage.Health message) {
    final Set<String> worlds = ConcurrentHashMap.<String>newKeySet();
    worlds.addAll(message.loadedWorlds());
    worldsByServer.put(message.serverName(), new Entry(worlds, clock.getAsLong()));
  }

  /**
   * Bridge-message listener entry point for health reports.
   *
   * @param sourceServer the name of the server that sent the message
   * @param message      the decoded bridge message
   */
  public void onHealth(final String sourceServer, final BridgeMessage message) {
    if (message instanceof BridgeMessage.Health health) {
      onHealth(health);
    }
  }

  /**
   * Forgets everything known about a server, used when the proxy learns it is gone rather than
   * merely quiet.
   *
   * @param serverName the server to forget
   */
  public void forget(final String serverName) {
    worldsByServer.remove(serverName);
  }

  /**
   * Records that a world has been unloaded from the given server.
   *
   * @param message the world-unloaded message
   */
  public void onWorldUnloaded(final BridgeMessage.WorldUnloaded message) {
    final Entry entry = current(message.serverName());
    if (entry == null) {
      return;
    }
    entry.worlds().remove(message.worldName());
    // The entry is kept even when empty: "this server holds no world" is a fresh statement, and
    // dropping it would make it indistinguishable from "this server has gone quiet".
    refreshed(message.serverName());
  }

  /**
   * Bridge-message listener entry point suitable for registration with
   * {@link com.btcvelocity.api.bridge.BridgeChannel#registerListener}. It ignores every
   * message type other than {@link BridgeMessage.WorldUnloaded}.
   *
   * @param sourceServer the name of the server that sent the message
   * @param message      the decoded bridge message
   */
  public void onWorldUnloaded(final String sourceServer, final BridgeMessage message) {
    if (message instanceof BridgeMessage.WorldUnloaded unloaded) {
      onWorldUnloaded(unloaded);
    }
  }

  /**
   * Returns whether the given world is currently loaded on the named server.
   *
   * @param serverName the name of the server to check
   * @param worldName  the world to check
   * @return {@code true} if the world is loaded on that server
   */
  public boolean isWorldLoaded(final String serverName, final String worldName) {
    final Entry entry = current(serverName);
    return entry != null && entry.worlds().contains(worldName);
  }

  /**
   * Returns whether what the registry knows about a server has expired.
   *
   * @param serverName the name of the server to check
   * @return {@code true} when the server said something once, but not recently enough to be
   *         believed
   */
  public boolean isStale(final String serverName) {
    final Entry entry = worldsByServer.get(serverName);
    return entry != null && expired(entry);
  }

  /**
   * Returns an immutable snapshot of the worlds currently loaded on the named server.
   *
   * @param serverName the name of the server to look up
   * @return the set of loaded world names, never {@code null}
   */
  public Set<String> getLoadedWorlds(final String serverName) {
    final Entry entry = current(serverName);
    return entry == null ? Collections.emptySet() : Set.copyOf(entry.worlds());
  }

  /**
   * Clears all tracked worlds from the registry.
   */
  public void clear() {
    worldsByServer.clear();
  }

  /** The entry for a server, created or its signal instant refreshed. */
  private Entry refreshed(final String serverName) {
    return worldsByServer.compute(serverName, (name, existing) -> {
      final Set<String> worlds = existing == null || expired(existing)
          ? ConcurrentHashMap.newKeySet() : existing.worlds();
      return new Entry(worlds, clock.getAsLong());
    });
  }

  /**
   * The entry for a server when it is still current.
   *
   * <p>An expired entry is <em>hidden, not removed</em>, for the same reason as in
   * {@link BackendHealthRegistry}: a reader that erased the expiry would make {@link #isStale}
   * answer {@code false} immediately afterwards, turning "stopped speaking" into "never spoke".
   * {@link #forget} is how an entry actually goes away.</p>
   */
  private Entry current(final String serverName) {
    final Entry entry = worldsByServer.get(serverName);
    return entry == null || expired(entry) ? null : entry;
  }

  private boolean expired(final Entry entry) {
    return clock.getAsLong() - entry.signalledAt() > freshnessMillis;
  }
}