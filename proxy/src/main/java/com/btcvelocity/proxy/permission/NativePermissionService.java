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

package com.btcvelocity.proxy.permission;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Async PostgreSQL/Valkey read-only backend for proxy-side native permissions. */
final class NativePermissionService implements AutoCloseable {

  private static final Logger LOGGER = LogManager.getLogger(NativePermissionService.class);
  /** Value of the {@code server} context at the proxy; see {@link #proxyContext(String)}. */
  static final String PROXY_SERVER_CONTEXT = "proxy";
  private static final Gson GSON =new GsonBuilder().disableHtmlEscaping().create();

  private final ExecutorService ioExecutor = Executors.newVirtualThreadPerTaskExecutor();
  private final ConcurrentHashMap<UUID, NativePermissionSnapshot> snapshots = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, CompletableFuture<NativePermissionSnapshot>> inFlight = new ConcurrentHashMap<>();
  private final Object initializationLock = new Object();
  private final AtomicBoolean closed = new AtomicBoolean();
  private volatile CompletableFuture<Void> initialization;
  private volatile NativePermissionConfig.Config config;
  private volatile HikariDataSource dataSource;
  private volatile RedisClient redisClient;
  private volatile StatefulRedisPubSubConnection<String, String> redisConnection;
  private volatile Map<String, NativePermissionSnapshot.Group> groupCatalog = Map.of();
  private volatile String lastFailureReason;
  private volatile boolean groupCatalogStale;
  private volatile long groupCatalogStaleSince;

  CompletableFuture<NativePermissionSnapshot> load(final UUID subject) {
    final NativePermissionSnapshot cached = snapshots.get(subject);
    if (cached != null) {
      return CompletableFuture.completedFuture(cached);
    }
    return inFlight.computeIfAbsent(subject, key -> ensureInitialized()
        .handle((ignored, error) -> {
          if (error != null) {
            LOGGER.warn("Native BTC permissions initialization is unavailable; snapshot load will retry.", error);
          }
          return null;
        })
        .thenComposeAsync(ignored -> readSnapshot(key), ioExecutor)
        .whenComplete((ignored, error) -> inFlight.remove(key)));
  }

  NativePermissionSnapshot snapshot(final UUID subject) {
    return snapshots.get(subject);
  }

  /** Evaluation context of every proxy-side decision; empty while the backend has no configuration. */
  Map<String, String> context() {
    final NativePermissionConfig.Config current = config;
    return current == null ? Map.of() : proxyContext(current.networkId());
  }

  /**
   * Context of a proxy decision: {@code network=<networkId>} and {@code server=proxy}, never a world
   * or a dimension, wherever the player currently is.
   *
   * <p>{@code server=proxy} is a convention written into the grade data: a grade scopes a node to the
   * proxy with that exact value, so changing {@link #PROXY_SERVER_CONTEXT} later means rewriting
   * those grades. It is deliberately not the player's current backend: the CloudNet service name
   * ({@code Lobby-1}) differs from the task name backends evaluate ({@code lobby}), and a node scoped
   * to a backend must never apply to a command the proxy runs.
   */
  static Map<String, String> proxyContext(final String networkId) {
    return Map.of("network", networkId, "server", PROXY_SERVER_CONTEXT);
  }

  /** État observable du backend : voir {@link NativePermissionHealth} pour le pourquoi. */
  NativePermissionHealth health() {
    return new NativePermissionHealth(
        dataSource != null && config != null && !closed.get(),
        lastFailureReason,
        groupCatalog.size(),
        groupCatalogStale,
        groupCatalogStaleSince);
  }

  /**
   * Relit le catalogue de groupes ; rend {@code true} si le catalogue détenu a été remplacé.
   *
   * <p>Extraite du corps de {@link #handleInvalidation(String)} pour que la politique de
   * remplacement du catalogue soit une unité nommée, observable et testable indépendamment du
   * transport Valkey qui la déclenche en production.
   */
  CompletableFuture<Boolean> refreshGroupCatalog() {
    return CompletableFuture.supplyAsync(() -> {
      try {
        groupCatalog = readGroups();
        markGroupCatalogFresh();
        return true;
      } catch (SQLException | RuntimeException error) {
        LOGGER.warn("Native BTC permissions group refresh failed; keeping the last valid catalog.", error);
        markGroupCatalogStale(error);
        return false;
      }
    }, ioExecutor);
  }

  private void markGroupCatalogFresh() {
    groupCatalogStale = false;
    groupCatalogStaleSince = 0L;
  }

  /**
   * Marque le catalogue détenu comme périmé, en datant le début de la péremption.
   *
   * <p>La date n'est posée qu'au premier échec : une suite d'échecs est un seul incident, et
   * réécrire l'horodatage à chaque tentative ferait paraître neuve une péremption vieille d'un jour.
   */
  private void markGroupCatalogStale(final Throwable error) {
    lastFailureReason = describeFailure(error);
    if (!groupCatalogStale) {
      groupCatalogStaleSince = System.currentTimeMillis();
      groupCatalogStale = true;
    }
  }

  /**
   * Décrit un échec pour affichage : type de la cause racine et message, sans chaîne de connexion.
   *
   * <p>Un état de santé est destiné à être affiché — journal, healthcheck, commande d'exploitation.
   * Les messages de pilotes JDBC et de pools citent volontiers l'URL et parfois l'utilisateur ; tout
   * lexème portant {@code ://} ou {@code @} est donc retiré plutôt que relayé.
   */
  private static String describeFailure(final Throwable error) {
    Throwable root = error;
    for (int depth = 0; root.getCause() != null && root.getCause() != root && depth < 16; depth++) {
      root = root.getCause();
    }
    final String message = root.getMessage();
    final String detail = message == null || message.isBlank() ? "aucun message" : redact(message);
    return root.getClass().getSimpleName() + ": " + detail;
  }

  private static String redact(final String message) {
    final StringBuilder redacted = new StringBuilder(message.length());
    for (final String token : message.split(" ")) {
      redacted.append(token.contains("://") || token.indexOf('@') >= 0 ? "<retiré>" : token).append(' ');
    }
    return redacted.toString().strip();
  }

  private CompletableFuture<Void> ensureInitialized() {
    CompletableFuture<Void> current = initialization;
    // Le court-circuit doit exclure une tentative échouée. Sans cette condition, la première
    // initialisation ratée était rendue indéfiniment à tous les appelants suivants : le bloc
    // synchronisé ci-dessous, qui porte pourtant la relance, n'était plus jamais atteint, et une
    // panne de base d'une seconde condamnait le backend pour la durée de vie du proxy.
    if (current != null && !current.isCompletedExceptionally()) {
      return current;
    }
    synchronized (initializationLock) {
      current = initialization;
      if (current == null || current.isCompletedExceptionally()) {
        current = CompletableFuture.runAsync(this::initializeBlocking, ioExecutor);
        initialization = current;
      }
      return current;
    }
  }

  private void initializeBlocking() {
    try {
      final NativePermissionConfig.Config loaded = NativePermissionConfig.load(NativePermissionConfig.configPath());
      config = loaded;
      dataSource = new HikariDataSource(hikariConfig(loaded));

      try {
        groupCatalog = readGroups();
        markGroupCatalogFresh();
      } catch (Throwable error) {
        LOGGER.warn("Native BTC permissions group catalog is not available yet; retrying on demand.", error);
        markGroupCatalogStale(error);
      }

      if (!loaded.valkeyUri().isBlank()) {
        startRedis(loaded);
      }
      lastFailureReason = null;
      LOGGER.info("Native BTC permissions backend configured for network {} and server {}.",
          loaded.networkId(), loaded.serverId());
    } catch (Throwable error) {
      // Neither the original exception nor its cause is logged or chained: a pool or client error
      // can quote the connection string, password included. The type is enough to triage; the
      // redacted detail is in the health state.
      LOGGER.error("Native BTC permissions backend could not initialize ({}); proxy permissions remain undefined until it recovers.",
          error.getClass().getSimpleName());
      lastFailureReason = describeFailure(error);
      final HikariDataSource source = dataSource;
      dataSource = null;
      config = null;
      if (source != null) {
        source.close();
      }
      throw new IllegalStateException(
          "Native BTC permissions initialization failed (" + error.getClass().getSimpleName() + ")");
    }
  }

  /** Pool settings; read-only because the proxy never writes the permission tables (AD-041). */
  static HikariConfig hikariConfig(final NativePermissionConfig.Config loaded) {
    final HikariConfig hikari = new HikariConfig();
    hikari.setJdbcUrl(loaded.jdbcUrl());
    hikari.setUsername(loaded.username());
    hikari.setPassword(loaded.password());
    hikari.setReadOnly(true);
    // pgjdbc's default readOnlyMode=transaction sends nothing in autocommit, so setReadOnly alone
    // would not protect; "always" makes the driver enforce it. Set here, not in the user's URL.
    hikari.addDataSourceProperty("readOnlyMode", "always");
    hikari.setMaximumPoolSize(4);
    hikari.setMinimumIdle(1);
    hikari.setConnectionTimeout(3_000);
    hikari.setValidationTimeout(2_000);
    hikari.setPoolName("btc-velocity-permissions");
    return hikari;
  }

  private void startRedis(final NativePermissionConfig.Config loaded) {
    final RedisURI valkeyUri = RedisURI.create(loaded.valkeyUri());
    final RedisClient client = RedisClient.create(valkeyUri);
    final StatefulRedisPubSubConnection<String, String> connection = client.connectPubSub();
    connection.addListener(new RedisPubSubAdapter<>() {
      @Override
      public void message(final String channel, final String message) {
        if (loaded.redisChannel().equals(channel)) {
          handleInvalidation(message);
        }
      }
    });
    connection.sync().subscribe(loaded.redisChannel());
    redisClient = client;
    redisConnection = connection;
  }

  private CompletableFuture<NativePermissionSnapshot> readSnapshot(final UUID subject) {
    return CompletableFuture.supplyAsync(() -> {
      final HikariDataSource source = dataSource;
      final NativePermissionConfig.Config current = config;
      if (source == null || current == null || closed.get()) {
        return null;
      }
      try (Connection connection = source.getConnection()) {
        try (var statement = connection.prepareStatement(
            "SELECT revision, payload FROM " + current.tablePrefix() + "players "
                + "WHERE player_uuid = ? AND profile_id IS NULL")) {
          statement.setString(1, subject.toString());
          try (ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
              final NativePermissionSnapshot unseen = neverSeen(subject, groupCatalog);
              if (unseen == null) {
                return null;
              }
              // putIfAbsent: a concurrent load of the same subject may already hold a real row.
              final NativePermissionSnapshot previous = snapshots.putIfAbsent(subject, unseen);
              return previous != null ? previous : unseen;
            }
            final NativePermissionSnapshot snapshot = GSON.fromJson(result.getString("payload"), NativePermissionSnapshot.class);
            snapshot.revision = Math.max(snapshot.revision, result.getLong("revision"));
            normalize(snapshot, subject, groupCatalog);
            snapshots.compute(subject, (key, previous) -> previous == null || snapshot.revision >= previous.revision ? snapshot : previous);
            return snapshots.get(subject);
          }
        }
      } catch (SQLException | RuntimeException error) {
        LOGGER.warn("Native BTC permissions snapshot load failed for subject {}; will retry asynchronously.", subject, error);
        lastFailureReason = describeFailure(error);
        return null;
      }
    }, ioExecutor);
  }

  private Map<String, NativePermissionSnapshot.Group> readGroups() throws SQLException {
    final HikariDataSource source = dataSource;
    final NativePermissionConfig.Config current = config;
    if (source == null || current == null) {
      // Ne jamais rendre une map vide ici : l'appelant remplace le catalogue détenu par ce qu'il
      // reçoit, et « aucun groupe en base » aurait alors la même forme que « backend indisponible ».
      // Les deux se soldent par le retrait de toutes les permissions de groupe du réseau, mais le
      // second est un incident à signaler, pas un état à propager.
      throw new IllegalStateException("Native BTC permissions backend is not available");
    }
    final Map<String, NativePermissionSnapshot.Group> groups = new HashMap<>();
    try (Connection connection = source.getConnection();
         var statement = connection.createStatement();
         ResultSet result = statement.executeQuery(
             "SELECT group_name, payload FROM " + current.tablePrefix() + "groups")) {
      while (result.next()) {
        final NativePermissionSnapshot.Group group = GSON.fromJson(result.getString("payload"), NativePermissionSnapshot.Group.class);
        if (group != null && group.name != null && !group.name.isBlank()) {
          groups.put(result.getString("group_name").toLowerCase(), group);
        }
      }
    }
    return Map.copyOf(groups);
  }

  /**
   * Snapshot of a subject the database holds no row for, or {@code null} when it must stay unknown.
   *
   * <p>"No row" is not "no grade": the base group applies to a player nobody ever granted anything.
   * Without a default group in the catalog the answer stays {@code null}, as before, so a catalog that
   * is empty because it could not be read yet never caches a subject as grade-less.
   */
  static NativePermissionSnapshot neverSeen(
      final UUID subject,
      final Map<String, NativePermissionSnapshot.Group> catalog
  ) {
    if (catalog.values().stream().noneMatch(NativePermissionService::isDefaultGroup)) {
      return null;
    }
    final NativePermissionSnapshot snapshot = new NativePermissionSnapshot();
    normalize(snapshot, subject, catalog);
    return snapshot;
  }

  static void normalize(
      final NativePermissionSnapshot snapshot,
      final UUID subject,
      final Map<String, NativePermissionSnapshot.Group> catalog
  ) {
    snapshot.subject = subject;
    if (snapshot.permissions == null) {
      snapshot.permissions = new java.util.ArrayList<>();
    }
    if (snapshot.groups == null) {
      snapshot.groups = new HashMap<>();
    }
    // Same merge as the Paper extension (`loaded.groups + groupCatalog`): the catalog wins over the
    // copy each stored row embeds, so a node removed from a grade is removed here too. An embedded
    // copy of a group the catalog does not hold is kept, exactly as the extension keeps it.
    final Map<String, NativePermissionSnapshot.Group> merged = new HashMap<>();
    snapshot.groups.forEach((key, value) -> merged.put(key.toLowerCase(), value));
    merged.putAll(catalog);
    snapshot.groups = merged;
  }

  private static boolean isDefaultGroup(final NativePermissionSnapshot.Group group) {
    return group != null && group.isDefault;
  }

  private void handleInvalidation(final String payload) {
    try {
      if (payload == null || payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 8 * 1024) {
        return;
      }
      final Invalidation invalidation = GSON.fromJson(payload, Invalidation.class);
      final NativePermissionConfig.Config current = config;
      if (invalidation == null || current == null || invalidation.version != 1
          || invalidation.revision < 0 || !current.networkId().equals(invalidation.networkId)
          || current.serverId().equals(invalidation.serverId)
          || ((invalidation.subject == null) == (invalidation.group == null))) {
        return;
      }
      if (invalidation.subject != null) {
        snapshots.computeIfPresent(invalidation.subject, (key, existing) ->
            invalidation.revision > existing.revision ? null : existing);
      } else {
        final String group = invalidation.group.toLowerCase();
        // L'éviction n'a lieu que si le catalogue a réellement été rafraîchi : évincer les snapshots
        // après un rafraîchissement échoué les ferait recharger contre le catalogue périmé.
        refreshGroupCatalog().thenAccept(refreshed -> {
          if (refreshed) {
            // A group that just became the base grade is in no cached snapshot yet, yet applies to all.
            final boolean becameDefault = isDefaultGroup(groupCatalog.get(group));
            snapshots.entrySet().removeIf(entry ->
                becameDefault || NativePermissionEvaluator.usesGroup(entry.getValue(), group));
          }
        });
      }
    } catch (RuntimeException error) {
      LOGGER.warn("Invalid native BTC permissions Redis payload ignored.", error);
    }
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    final StatefulRedisPubSubConnection<String, String> connection = redisConnection;
    if (connection != null) {
      connection.close();
    }
    final RedisClient client = redisClient;
    if (client != null) {
      client.shutdown();
    }
    final HikariDataSource source = dataSource;
    if (source != null) {
      source.close();
    }
    snapshots.clear();
    ioExecutor.shutdownNow();
  }

  private static final class Invalidation {
    int version;
    String networkId;
    String serverId;
    UUID subject;
    String group;
    long revision;
  }
}
