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

package com.btcvelocity.proxy.redis.depot.player;

import com.btcvelocity.proxy.redis.LogThrottle;
import com.btcvelocity.proxy.redis.OrderedRedisExecutor;
import com.btcvelocity.proxy.redis.VelocityRedis;
import com.btcvelocity.proxy.redis.data.VelocityKick;
import com.btcvelocity.proxy.redis.depot.AbstractDepotService;
import com.velocitypowered.api.proxy.player.PlayerSettings;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.plugin.virtual.VelocityVirtualPlugin;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Represents an extension of the {@link AbstractDepotService} for the player depot, including
 * functionality to track certain information about a single player, or multiple players.
 *
 * <p>The player lifecycle hooks are called from Netty event loops. They never touch Redis on the
 * calling thread: every write goes through one ordered worker ({@link OrderedRedisExecutor}), and
 * the counts read on hot paths come from a snapshot refreshed in the background.</p>
 */
public final class PlayerDepotService extends AbstractDepotService<UUID, PlayerEntry> {

  /**
   * How long a login waits for the cross-proxy duplicate check before letting the player in.
   * Redis is optional: when it does not answer in time, the login goes on and the periodic
   * repair registers the player once Redis is back.
   */
  public static final Duration CONNECT_CHECK_TIMEOUT = Duration.ofMillis(500);

  /**
   * Past this age the cluster snapshot is considered dead (Redis unreachable) and per-server
   * counts fall back to this proxy's own players.
   */
  public static final Duration SNAPSHOT_MAX_AGE = Duration.ofSeconds(5);

  /**
   * How often the snapshot is rebuilt and the depot is repaired against this proxy's players.
   */
  public static final Duration REFRESH_INTERVAL = Duration.ofSeconds(1);

  /**
   * Maximum number of player writes waiting for Redis. Beyond it, writes are dropped and the
   * periodic repair restores the entries.
   */
  public static final int WRITE_QUEUE_CAPACITY = 4096;

  /**
   * How long shutdown waits for queued player writes before dropping them.
   */
  private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(3);

  private static final Logger LOGGER = LoggerFactory.getLogger(PlayerDepotService.class);

  /**
   * The Redis manager used to coordinate multi-proxy player synchronization.
   */
  private final VelocityRedis redis;

  /**
   * The proxy server instance associated with this depot service.
   */
  private final VelocityServer server;

  /**
   * The single ordered worker for every Redis access of this service.
   */
  private final OrderedRedisExecutor worker;

  /**
   * Scheduled task that queues a {@link #refreshNow() refresh} every {@link #REFRESH_INTERVAL}.
   */
  private final ScheduledTask refreshTask;

  /**
   * Whether a refresh is already waiting in the worker queue, so an outage queues one at most.
   */
  private final AtomicBoolean refreshPending = new AtomicBoolean();

  /**
   * Throttles the warning for logins let in without a duplicate check.
   */
  private final LogThrottle uncheckedLogins = new LogThrottle(LogThrottle.DEFAULT_INTERVAL);

  /**
   * The last cluster view read from Redis, or {@code null} before the first successful read.
   */
  private volatile @Nullable Snapshot snapshot;

  /**
   * Constructs a new {@link PlayerDepotService}.
   *
   * @param redis the {@link VelocityRedis} instance
   */
  public PlayerDepotService(final @NotNull VelocityRedis redis) {
    super(PlayerEntry.class, redis.getProvider());

    this.redis = redis;
    this.server = redis.getServer();
    this.worker = new OrderedRedisExecutor("btc-redis-players", WRITE_QUEUE_CAPACITY);

    this.refreshTask = redis.getServer().getScheduler()
            .buildTask(VelocityVirtualPlugin.INSTANCE, this::queueRefresh)
            .repeat(REFRESH_INTERVAL)
            .schedule();
  }

  @Override
  public void teardown() {
    if (this.refreshTask != null) {
      this.refreshTask.cancel();
    }

    // Writes queued after the shutdown flag bail out at once; wait for the one in flight.
    this.worker.shutdown(SHUTDOWN_GRACE);

    // Remove by owner rather than by local players: by now the players are gone locally, and the
    // disconnect writes they queued were skipped by the shutdown flag.
    for (PlayerEntry entry : getPlayerEntriesOnProxy(this.redis.getProxyId())) {
      entry.remove();
    }
  }

  /**
   * Called when a {@link ConnectedPlayer} connects to the proxy. Returns at once; the Redis work
   * runs on the ordered worker.
   *
   * @param player the player that connected
   * @return a future completed with {@code false} if the player was refused (and disconnected),
   *         {@code true} otherwise, including when Redis does not answer within
   *         {@link #CONNECT_CHECK_TIMEOUT}
   */
  public CompletableFuture<Boolean> onPlayerConnect(final ConnectedPlayer player) {
    if (this.redis.isShutdown()) {
      return CompletableFuture.completedFuture(false);
    }

    return this.worker.supply(() -> connectNow(player))
        .completeOnTimeout(null, CONNECT_CHECK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
        .exceptionally(throwable -> null)
        .thenApply(allowed -> {
          if (allowed != null) {
            return allowed;
          }
          final long quiet = this.uncheckedLogins.acquire();
          if (quiet >= 0) {
            LOGGER.warn("Redis did not answer the duplicate-login check for {}; letting them in "
                + "unchecked ({} other unchecked logins not logged)", player.getUsername(), quiet);
          }
          return Boolean.TRUE;
        });
  }

  /**
   * Called when a {@link ConnectedPlayer} disconnects from the proxy. Returns at once.
   *
   * @param player the player that disconnected
   */
  public void onPlayerDisconnect(final ConnectedPlayer player) {
    if (!this.redis.isShutdown()) {
      this.worker.execute(() -> disconnectNow(player));
    }
  }

  /**
   * Called when a {@link ConnectedPlayer} switches servers. Returns at once.
   *
   * @param player the player that switched servers
   * @param serverName the name of the server that the player switched to
   */
  public void onPlayerSwitchServer(final ConnectedPlayer player, final String serverName) {
    this.worker.execute(() -> {
      final PlayerEntry playerEntry = this.getPlayerEntry(player.getUniqueId());
      if (playerEntry == null) {
        return;
      }

      playerEntry.setServerName(serverName);
      playerEntry.upsert();
    });
  }

  /**
   * Called when a {@link ConnectedPlayer} changes its {@link PlayerSettings}. Returns at once.
   *
   * @param player the player that got its settings changed
   * @param settings the new settings
   */
  public void onPlayerSettingsChange(final ConnectedPlayer player, final PlayerSettings settings) {
    this.worker.execute(() -> {
      final PlayerEntry playerEntry = this.getPlayerEntry(player.getUniqueId());
      if (playerEntry == null) {
        return;
      }

      playerEntry.setClientListingAllowed(settings.isClientListingAllowed());
      playerEntry.upsert();
    });
  }

  /**
   * Get the total player count across all proxies, from the last snapshot. No I/O.
   *
   * @return the total player count
   */
  public int getTotalPlayerCount() {
    final Snapshot current = this.snapshot;
    return current == null ? 0 : current.entries().size();
  }

  /**
   * Gets the number of players on a server from the last snapshot. No I/O.
   *
   * @param serverName the backend server name, case-insensitive
   * @return the cluster-wide count, or empty if the snapshot is missing or older than
   *         {@link #SNAPSHOT_MAX_AGE}
   */
  public OptionalInt getCachedPlayersOnServer(final @NotNull String serverName) {
    final Snapshot current = this.snapshot;
    return current == null ? OptionalInt.empty() : current.countOn(serverName, System.nanoTime());
  }

  /**
   * Gets every player entry from the last snapshot. No I/O.
   *
   * @return the entries read by the last refresh, or empty if the snapshot is missing or older
   *         than {@link #SNAPSHOT_MAX_AGE}
   */
  public Optional<@Unmodifiable List<PlayerEntry>> getCachedEntries() {
    final Snapshot current = this.snapshot;
    return current == null || current.isStale(System.nanoTime())
        ? Optional.empty() : Optional.of(current.entries());
  }

  /**
   * Get a player entry by their unique ID.
   *
   * @param uniqueId the unique ID of the player
   * @return the player entry, or {@code null} if the player is not present in the depot
   */
  public @Nullable PlayerEntry getPlayerEntry(final UUID uniqueId) {
    return this.depot.get(uniqueId);
  }

  /**
   * Get a player entry by their username.
   *
   * @param username the username of the player
   * @return the player entry, or {@code null} if the player is not present in the depot
   */
  public @Nullable PlayerEntry getPlayerEntry(final String username) {
    for (PlayerEntry entry : this.depot.values()) {
      if (entry.getUsername().equalsIgnoreCase(username)) {
        return entry;
      }
    }

    return null;
  }

  /**
   * Checks whether a player is online.
   *
   * @param uniqueId the unique ID of the player
   * @return {@code true} if the player is online, {@code false} otherwise
   */
  public boolean isPlayerOnline(final UUID uniqueId) {
    return this.depot.contains(uniqueId);
  }

  /**
   * Checks whether a player is online.
   *
   * @param username the username of the player
   * @return {@code true} if the player is online, {@code false} otherwise
   */
  public boolean isPlayerOnline(final String username) {
    for (PlayerEntry entry : this.depot.values()) {
      if (entry.getUsername().equalsIgnoreCase(username)) {
        return true;
      }
    }

    return false;
  }

  /**
   * Retrieves a list of player entries associated with a specific server.
   *
   * @param serverName the name of the server whose player entries are to be retrieved; must not be null
   * @return an unmodifiable list of {@link PlayerEntry} objects representing the players currently on the specified server; never null
   */
  public @NotNull @Unmodifiable List<PlayerEntry> getPlayerEntriesInServer(final @NotNull String serverName) {
    return List.copyOf(this.queryAll(playerEntry -> serverName.equalsIgnoreCase(playerEntry.getServerName())));
  }

  /**
   * Retrieves a list of player entries associated with a specific proxy.
   *
   * @param proxyId the identifier of the proxy whose player entries are to be retrieved;
   *                must not be null or empty
   * @return an unmodifiable list of {@link PlayerEntry} objects representing players
   *         currently associated with the specified proxy; never null
   */
  public @NotNull @Unmodifiable List<PlayerEntry> getPlayerEntriesOnProxy(final String proxyId) {
    return List.copyOf(this.queryAll(playerEntry -> playerEntry.getProxyId().equalsIgnoreCase(proxyId)));
  }

  /**
   * Upserts a player's entry in the depot. If an entry for the given player already exists,
   * it is updated with the latest details. If it doesn't exist, a new entry is created.
   *
   * @param player the {@link ConnectedPlayer} object representing the player for whom the entry is to be upserted; must not be null
   * @return the {@link PlayerEntry} object representing the player's entry; never null
   */
  public @NotNull PlayerEntry upsertPlayerEntry(final @NotNull ConnectedPlayer player) {
    final PlayerEntry playerEntry = new PlayerEntry(player, this.redis.getProxyId());
    playerEntry.setDepot(this.depot);

    this.depot.upsert(playerEntry);
    return playerEntry;
  }

  /**
   * Runs on the worker: refuses a duplicate held by another proxy, then registers the player.
   */
  private boolean connectNow(final ConnectedPlayer player) {
    if (this.redis.isShutdown() || !isCurrentSession(player)) {
      // A stale task must neither kick the live session nor write a ghost entry.
      return false;
    }

    final PlayerEntry existing = this.depot.get(player.getUniqueId());
    // An entry owned by this proxy is a leftover of the player's previous session here, whose
    // removal may still be queued: registerConnection() already arbitrated local duplicates.
    if (existing != null && !existing.getProxyId().equalsIgnoreCase(this.redis.getProxyId())) {
      if (this.server.getConfiguration().isKickExistingPlayers()) {
        final Component component = Component.translatable("multiplayer.disconnect.duplicate_login");
        this.redis.publish(new VelocityKick(player.getUniqueId(), component, existing.getProxyId()));
      } else {
        final Component component = Component.translatable("velocity.error.already-connected-proxy.remote");
        player.disconnect0(component, true);
        return false;
      }
    }

    this.upsertPlayerEntry(player);
    return true;
  }

  /**
   * Runs on the worker: removes the player's entry if this proxy and this session still own it.
   */
  private void disconnectNow(final ConnectedPlayer player) {
    if (this.redis.isShutdown()) {
      return;
    }

    final PlayerEntry existing = this.depot.get(player.getUniqueId());
    if (existing == null) {
      return;
    }

    if (!existing.getProxyId().equalsIgnoreCase(this.redis.getProxyId())) {
      return;
    }

    final ConnectedPlayer currentPlayer = this.server.getPlayer(player.getUniqueId()).orElse(null);
    if (currentPlayer != null && currentPlayer != player) {
      return;
    }

    this.depot.remove(player.getUniqueId());
  }

  private boolean isCurrentSession(final ConnectedPlayer player) {
    return this.server.getPlayer(player.getUniqueId()).orElse(null) == player;
  }

  /**
   * Queues one refresh on the worker unless one is already waiting.
   */
  private void queueRefresh() {
    if (this.redis.isShutdown() || !this.refreshPending.compareAndSet(false, true)) {
      return;
    }

    final boolean queued = this.worker.execute(() -> {
      try {
        refreshNow();
      } finally {
        this.refreshPending.set(false);
      }
    });
    if (!queued) {
      this.refreshPending.set(false);
    }
  }

  /**
   * Reads the depot once, repairs this proxy's entries against its live players, and publishes
   * the snapshot. Repairing is what makes the dropped or failed writes of an outage converge
   * once Redis answers again. Runs on the worker, so it is ordered with the lifecycle writes.
   */
  void refreshNow() {
    if (this.redis.isShutdown()) {
      return;
    }

    final Collection<PlayerEntry> entries = this.depot.values();
    final String proxyId = this.redis.getProxyId();
    final Map<UUID, PlayerEntry> byId = new HashMap<>();
    for (PlayerEntry entry : entries) {
      byId.putIfAbsent(entry.getUniqueId(), entry);
    }

    for (ConnectedPlayer player : this.server.getAllPlayers()) {
      final PlayerEntry entry = byId.get(player.getUniqueId());
      if (entry == null || (entry.getProxyId().equalsIgnoreCase(proxyId)
          && !Objects.equals(entry.getServerName(), currentServerName(player)))) {
        this.upsertPlayerEntry(player);
      }
    }

    for (PlayerEntry entry : entries) {
      if (entry.getProxyId().equalsIgnoreCase(proxyId)
          && this.server.getPlayer(entry.getUniqueId()).isEmpty()) {
        entry.remove();
      }
    }

    this.snapshot = Snapshot.of(entries, System.nanoTime());
  }

  private static @Nullable String currentServerName(final ConnectedPlayer player) {
    return player.getCurrentServer().map(connection -> connection.getServerInfo().getName()).orElse(null);
  }

  /**
   * An immutable cluster view: every entry and the count per lower-cased server name.
   *
   * @param entries every player entry read
   * @param countsByServer player count per lower-cased server name
   * @param takenAtNanos {@link System#nanoTime()} when the entries were read
   */
  record Snapshot(List<PlayerEntry> entries, Map<String, Integer> countsByServer, long takenAtNanos) {

    static Snapshot of(final Collection<PlayerEntry> entries, final long takenAtNanos) {
      final Map<String, Integer> counts = new HashMap<>();
      for (PlayerEntry entry : entries) {
        if (entry.getServerName() != null) {
          counts.merge(entry.getServerName().toLowerCase(Locale.ROOT), 1, Integer::sum);
        }
      }
      return new Snapshot(List.copyOf(entries), Map.copyOf(counts), takenAtNanos);
    }

    boolean isStale(final long nowNanos) {
      return nowNanos - this.takenAtNanos > SNAPSHOT_MAX_AGE.toNanos();
    }

    OptionalInt countOn(final String serverName, final long nowNanos) {
      if (isStale(nowNanos)) {
        return OptionalInt.empty();
      }
      return OptionalInt.of(this.countsByServer.getOrDefault(serverName.toLowerCase(Locale.ROOT), 0));
    }
  }
}
