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
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Async PostgreSQL/Valkey read-only backend for proxy-side native permissions. */
final class NativePermissionService implements AutoCloseable {

  private static final Logger LOGGER = LogManager.getLogger(NativePermissionService.class);
  /** Value of the {@code server} context at the proxy; see {@link #proxyContext(String)}. */
  static final String PROXY_SERVER_CONTEXT = "proxy";
  /**
   * How long a login waits for the player's snapshot, and how long the pool waits for a connection:
   * one number, so a database that cannot answer within the pool timeout never holds a login longer
   * than the pool would have.
   */
  static final Duration LOAD_TIMEOUT = Duration.ofSeconds(3);
  /**
   * Quiet time before a burst of group invalidations is served by one catalog read. Short enough to
   * keep a revocation well inside the two seconds the network promises, long enough to merge the
   * invalidations one grade edit publishes.
   */
  static final Duration GROUP_REFRESH_WINDOW = Duration.ofMillis(250);
  /**
   * Least time between two attempts to read a catalog that could not be read: an outage costs one
   * query per second, not one per permission test.
   */
  static final Duration CATALOG_RETRY_BACKOFF = Duration.ofSeconds(1);
  /** Bound of a reconnect reload: past it, the snapshots not yet replaced are evicted, not served. */
  static final Duration RECONCILE_DEADLINE = Duration.ofSeconds(10);
  /** Least time between two initialization attempts: a broken setup costs one attempt per period. */
  static final Duration INIT_RETRY_BACKOFF = Duration.ofSeconds(5);
  /** Server-side bound of one query ({@code statement_timeout}). */
  static final Duration QUERY_TIMEOUT = Duration.ofSeconds(2);
  /**
   * Client-side bound of one read on the socket, above {@link #QUERY_TIMEOUT} so the server cancels
   * first; it still ends a read when the server or the network stops answering altogether.
   */
  static final Duration SOCKET_TIMEOUT = Duration.ofSeconds(5);
  /** A few concurrent reads are enough: logins and reloads are short, and the proxy never writes. */
  private static final int POOL_MAX_SIZE = 4;
  private static final int POOL_MIN_IDLE = 1;
  private static final Duration POOL_VALIDATION_TIMEOUT = Duration.ofSeconds(2);
  /** An invalidation is a few ids; anything larger is not one and is not parsed. */
  private static final int MAX_PAYLOAD_BYTES = 8 * 1024;
  /** Guards the walk to a root cause against a pathological cause chain. */
  private static final int MAX_CAUSE_DEPTH = 16;
  private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

  private final ExecutorService ioExecutor = Executors.newVirtualThreadPerTaskExecutor();
  /** Runs a task after the given delay, on {@link #ioExecutor}; a manual executor in tests. */
  private final Function<Duration, Executor> delays;
  private final ConcurrentHashMap<UUID, NativePermissionSnapshot> snapshots = new ConcurrentHashMap<>();
  /**
   * Players whose snapshot may be cached, each with a token of the period it is tracked for: asked
   * for since their last eviction. A read caches only under the token it started with, so a read
   * still in flight when a player disconnects never puts a snapshot back, even after they return.
   */
  private final ConcurrentHashMap<UUID, Object> tracked = new ConcurrentHashMap<>();
  /**
   * Lowest row revision still cacheable, by tracked player: raised by each invalidation naming
   * them, so a read that began before it can no longer install the state it revoked.
   */
  private final ConcurrentHashMap<UUID, Long> revisionFloor = new ConcurrentHashMap<>();
  /** Live sessions of each account; each set is only touched inside a compute on its key. */
  private final ConcurrentHashMap<UUID, Set<Object>> sessions = new ConcurrentHashMap<>();
  /** Bumped after each catalog read, failed or not: a read that merged an older catalog is not cached. */
  private final AtomicLong catalogEpoch = new AtomicLong();
  private final AtomicLong nextCatalogRetryNanos = new AtomicLong(System.nanoTime());
  private final Set<String> pendingGroups = ConcurrentHashMap.newKeySet();
  private final AtomicBoolean groupRefreshScheduled = new AtomicBoolean();
  private final ConcurrentHashMap<UUID, CompletableFuture<NativePermissionSnapshot>> inFlight = new ConcurrentHashMap<>();
  private final Object initializationLock = new Object();
  private final AtomicBoolean closed = new AtomicBoolean();
  private volatile CompletableFuture<Void> initialization;
  private volatile long nextInitAttemptNanos;
  private volatile NativePermissionConfig.Config config;
  private volatile HikariDataSource dataSource;
  private volatile PermissionTables tables;
  private volatile RedisClient redisClient;
  private volatile StatefulRedisPubSubConnection<String, String> redisConnection;
  private volatile Map<String, NativePermissionSnapshot.Group> groupCatalog = Map.of();
  private volatile String lastFailureReason;
  private volatile boolean groupCatalogStale;
  private volatile long groupCatalogStaleSince;

  NativePermissionService() {
    this.delays = delay -> CompletableFuture.delayedExecutor(delay.toMillis(), TimeUnit.MILLISECONDS, ioExecutor);
  }

  /**
   * A service already wired to {@code tables}, with no pool and no bus: the seam the cache-lifecycle
   * tests use, with {@code delay} standing for every delay (refresh window, retries) so a test
   * decides when each ends. The catalog is read here, on the calling thread, as initialization does.
   */
  NativePermissionService(
      final PermissionTables tables,
      final NativePermissionConfig.Config config,
      final Executor delay
  ) {
    this.delays = ignored -> delay;
    this.tables = tables;
    this.config = config;
    this.initialization = CompletableFuture.completedFuture(null);
    refreshCatalogBlocking();
  }

  /**
   * Starts reading the snapshot of {@code subject} and tells when a login may go on: when the
   * snapshot is cached, or {@link #LOAD_TIMEOUT} after the call, whichever comes first. The returned
   * future never fails.
   *
   * <p>It never blocks: the read runs on this service's executor and the timeout is a stage of the
   * future, so a Netty event loop can compose on it. When the timeout wins, the load carries on and
   * caches the snapshot on arrival; the player simply entered without rights in the meantime.
   */
  CompletableFuture<Void> awaitLoaded(final UUID subject) {
    return awaitLoaded(subject, LOAD_TIMEOUT);
  }

  /**
   * {@link #awaitLoaded(UUID)} for a session: {@code session} stands for one connection of the
   * account and holds its snapshot until {@link #release} — the cache of an account lives as long
   * as one of its sessions does.
   */
  CompletableFuture<Void> awaitLoaded(final Object session, final UUID subject) {
    sessions.compute(subject, (key, live) -> {
      final Set<Object> held = live == null ? new HashSet<>() : live;
      held.add(session);
      return held;
    });
    return awaitLoaded(subject);
  }

  /**
   * Ends {@code session}, and evicts the account when it was its last (M4). A session that never
   * loaded holds nothing, so a refused duplicate login evicts nothing. The eviction runs inside the
   * same atomic step as {@link #awaitLoaded(Object, UUID)}, so a login racing a logout either keeps
   * the account alive or starts after the eviction, never has its own read evicted.
   */
  void release(final Object session, final UUID subject) {
    sessions.computeIfPresent(subject, (key, live) -> {
      if (!live.remove(session) || !live.isEmpty()) {
        return live;
      }
      evict(key);
      return null;
    });
  }

  CompletableFuture<Void> awaitLoaded(final UUID subject, final Duration timeout) {
    return load(subject)
        .<Void>handle((snapshot, error) -> null)
        .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
        .exceptionally(timedOut -> {
          LOGGER.warn("Native BTC permissions snapshot of {} is not ready after {} ms; the player enters"
              + " without rights and the load continues.", subject, timeout.toMillis());
          return null;
        });
  }

  CompletableFuture<NativePermissionSnapshot> load(final UUID subject) {
    final Object period = tracked.computeIfAbsent(subject, key -> new Object());
    final NativePermissionSnapshot cached = snapshots.get(subject);
    if (cached != null) {
      return CompletableFuture.completedFuture(cached);
    }
    final CompletableFuture<NativePermissionSnapshot> pending = inFlight.computeIfAbsent(subject, key -> ensureInitialized()
        .handle((ignored, error) -> {
          if (error != null) {
            LOGGER.warn("Native BTC permissions initialization is unavailable; snapshot load will retry.", error);
          }
          return null;
        })
        .thenComposeAsync(ignored -> readSnapshot(key, period), ioExecutor));
    // Outside the compute: a read already done would run this inline, a recursive update of the map.
    // Conditional, so a finished read never removes the newer read of a returning player.
    pending.whenComplete((ignored, error) -> inFlight.remove(subject, pending));
    return pending;
  }

  NativePermissionSnapshot snapshot(final UUID subject) {
    return snapshots.get(subject);
  }

  /**
   * Forgets {@code subject}: its cached snapshot goes, and a read still in flight no longer caches
   * its result. Called when the player's last session ends; a later test of the same account loads
   * it again.
   */
  void evict(final UUID subject) {
    // Untrack first: a read finishing meanwhile then finds its token gone. Dropping the in-flight
    // read lets a returning player start a fresh one instead of joining a read that cannot cache.
    tracked.remove(subject);
    inFlight.remove(subject);
    snapshots.remove(subject);
    revisionFloor.remove(subject);
  }

  /**
   * The bus confirmed its resubscription (see {@link NativePermissionBus}): invalidations published
   * during the outage are lost, so nothing cached can be trusted. The catalog is read again and
   * every cached snapshot is replaced by a fresh read.
   *
   * <p>Each player keeps the old snapshot until its new one arrives, so a connected player never
   * falls to "everything refused" for the length of the reload. What cannot be verified is dropped
   * instead: if the catalog or a row cannot be read, or {@link #RECONCILE_DEADLINE} passes first,
   * what is not reloaded is evicted, since a right that cannot be confirmed must not outlive the
   * outage; the next permission test loads again.
   *
   * <p>Called from the Valkey client's event loop, so it only schedules the reads.
   */
  CompletableFuture<Void> reconcileAfterReconnect() {
    return CompletableFuture.runAsync(this::reconcileBlocking, ioExecutor);
  }

  private void reconcileBlocking() {
    final PermissionTables source = tables;
    if (source == null || closed.get()) {
      return;
    }
    if (!refreshCatalogBlocking()) {
      snapshots.clear();
      return;
    }
    final Set<UUID> reloaded = new HashSet<>();
    final long deadline = System.nanoTime() + RECONCILE_DEADLINE.toNanos();
    for (final UUID subject : List.copyOf(snapshots.keySet())) {
      if (System.nanoTime() - deadline >= 0) {
        LOGGER.warn("Native BTC permissions reconnect reload passed its {} ms deadline; dropping the snapshots"
            + " not yet reloaded.", RECONCILE_DEADLINE.toMillis());
        snapshots.keySet().removeIf(key -> !reloaded.contains(key));
        return;
      }
      try {
        reloadCached(source, subject);
        reloaded.add(subject);
      } catch (SQLException | RuntimeException error) {
        LOGGER.warn("Native BTC permissions reconnect reload failed; dropping the snapshots not yet reloaded.", error);
        lastFailureReason = describeFailure(error);
        // One failure stops the pass: the next reads would each wait out the pool timeout.
        snapshots.keySet().removeIf(key -> !reloaded.contains(key));
        return;
      }
    }
  }

  /** Replaces the cached snapshot of {@code subject} by a fresh read, or drops it if that cannot be cached. */
  private void reloadCached(final PermissionTables source, final UUID subject) throws SQLException {
    final NativePermissionSnapshot old = snapshots.get(subject);
    final Object period = tracked.get(subject);
    final Read read = period == null ? null : read(source, subject, period);
    if (read == null || install(subject, read) != read.snapshot()) {
      // Only the unverified snapshot goes; one a concurrent load installed meanwhile stays.
      snapshots.remove(subject, old);
    }
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
    return CompletableFuture.supplyAsync(this::refreshCatalogBlocking, ioExecutor);
  }

  /**
   * Reads the catalog. The epoch moves after the outcome is written, never before: a read that saw
   * the old epoch is then rejected at install, and one that saw the new epoch also sees the outcome.
   */
  private boolean refreshCatalogBlocking() {
    try {
      groupCatalog = readGroups();
      markGroupCatalogFresh();
      return true;
    } catch (SQLException | RuntimeException error) {
      if (groupCatalogStale) {
        // Same incident, already logged with its trace: one line per retry, not one trace.
        LOGGER.debug("Native BTC permissions group catalog still unreadable: {}", describeFailure(error));
      } else {
        LOGGER.warn("Native BTC permissions group refresh failed; the catalog is stale and grants nothing.", error);
      }
      markGroupCatalogStale(error);
      return false;
    } finally {
      catalogEpoch.incrementAndGet();
    }
  }

  /** Whether the held catalog may be merged: a stale one is read again first, at most once per backoff. */
  private boolean catalogUsable() {
    if (!groupCatalogStale) {
      return true;
    }
    final long due = nextCatalogRetryNanos.get();
    final long now = System.nanoTime();
    if (now - due < 0 || !nextCatalogRetryNanos.compareAndSet(due, now + CATALOG_RETRY_BACKOFF.toNanos())) {
      return false;
    }
    return refreshCatalogBlocking();
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
    for (int depth = 0; root.getCause() != null && root.getCause() != root && depth < MAX_CAUSE_DEPTH; depth++) {
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
      // A failed attempt is answered as is until INIT_RETRY_BACKOFF has passed.
      if (current == null || (current.isCompletedExceptionally() && System.nanoTime() - nextInitAttemptNanos >= 0)) {
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
      tables = new JdbcPermissionTables(dataSource, loaded.tablePrefix());
      refreshCatalogBlocking();
      startRedis(loaded);
      lastFailureReason = null;
      LOGGER.info("Native BTC permissions backend configured for network {} and server {}.",
          loaded.networkId(), loaded.serverId());
    } catch (Throwable error) {
      // Neither the original exception nor its cause is logged or chained: a pool or client error
      // can quote the connection string, password included. The type is enough to triage; the
      // redacted detail is in the health state.
      LOGGER.error("Native BTC permissions backend could not initialize ({}); player permissions are refused until it recovers.",
          error.getClass().getSimpleName());
      lastFailureReason = describeFailure(error);
      nextInitAttemptNanos = System.nanoTime() + INIT_RETRY_BACKOFF.toNanos();
      final HikariDataSource source = dataSource;
      dataSource = null;
      tables = null;
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
    // Without these a query the server never answers holds a pool connection, and a reload, forever.
    hikari.addDataSourceProperty("options", "-c statement_timeout=" + QUERY_TIMEOUT.toMillis());
    hikari.addDataSourceProperty("socketTimeout", String.valueOf(SOCKET_TIMEOUT.toSeconds()));
    hikari.setMaximumPoolSize(POOL_MAX_SIZE);
    hikari.setMinimumIdle(POOL_MIN_IDLE);
    hikari.setConnectionTimeout(LOAD_TIMEOUT.toMillis());
    hikari.setValidationTimeout(POOL_VALIDATION_TIMEOUT.toMillis());
    hikari.setPoolName("btc-velocity-permissions");
    return hikari;
  }

  private void startRedis(final NativePermissionConfig.Config loaded) {
    final RedisURI valkeyUri = RedisURI.create(loaded.valkeyUri());
    final RedisClient client = RedisClient.create(valkeyUri);
    try {
      final StatefulRedisPubSubConnection<String, String> connection = client.connectPubSub();
      bus(loaded.redisChannel()).attach(client, connection);
      redisClient = client;
      redisConnection = connection;
    } catch (RuntimeException error) {
      // The client owns Netty threads: a failed attempt that kept it would leak them every retry.
      client.shutdown();
      throw error;
    }
  }

  /**
   * The bus wired to this service: messages are invalidations, a confirmed resubscription reloads
   * the cache, and a reconnection left unconfirmed empties it.
   */
  NativePermissionBus bus(final String channel) {
    return new NativePermissionBus(channel, this::handleInvalidation, this::reconcileAfterReconnect,
        snapshots::clear, delays.apply(NativePermissionBus.RESUBSCRIBE_GRACE));
  }

  private CompletableFuture<NativePermissionSnapshot> readSnapshot(final UUID subject, final Object period) {
    return CompletableFuture.supplyAsync(() -> {
      final PermissionTables source = tables;
      if (source == null || closed.get()) {
        return null;
      }
      try {
        final Read read = read(source, subject, period);
        return read == null ? null : install(subject, read);
      } catch (SQLException | RuntimeException error) {
        LOGGER.warn("Native BTC permissions snapshot load failed for subject {}; will retry asynchronously.", subject, error);
        lastFailureReason = describeFailure(error);
        return null;
      }
    }, ioExecutor);
  }

  /**
   * Reads {@code subject} from the tables and merges the held catalog into it, without caching; or
   * {@code null}, leaving the player refused, while the catalog is stale.
   *
   * <p>A stale catalog grants nothing (H1): a row merged with it would keep the nodes a grade lost,
   * or, when it could never be read, the copies each row embeds. A subject with no row gets the
   * empty snapshot, cached so a player the database never saw costs one read.
   *
   * <p>The epoch is taken before the catalog and the catalog before the row, so whatever happens to
   * the catalog during the row read shows as a newer epoch at {@link #install}.
   */
  private Read read(final PermissionTables source, final UUID subject, final Object period) throws SQLException {
    if (!catalogUsable()) {
      return null;
    }
    final long epoch = catalogEpoch.get();
    // A failure marks the catalog stale before it moves the epoch: seeing that epoch means seeing it.
    if (groupCatalogStale) {
      return null;
    }
    final Map<String, NativePermissionSnapshot.Group> catalog = groupCatalog;
    final NativePermissionSnapshot stored = source.readPlayer(subject);
    if (stored == null) {
      return new Read(neverSeen(subject, catalog), period, epoch);
    }
    normalize(stored, subject, catalog);
    return new Read(stored, period, epoch);
  }

  /**
   * Caches {@code read} unless something it could not see happened since it began: its player left,
   * the catalog it merged was read again, or an invalidation named a newer revision (H2). Returns
   * what is cached afterwards. The revision guard keeps a concurrent load that holds a newer row.
   */
  private NativePermissionSnapshot install(final UUID subject, final Read read) {
    final NativePermissionSnapshot fetched = read.snapshot();
    return snapshots.compute(subject, (key, previous) -> {
      final boolean outdated = tracked.get(key) != read.period()
          || catalogEpoch.get() != read.catalogEpoch()
          || fetched.revision < revisionFloor.getOrDefault(key, Long.MIN_VALUE);
      if (outdated) {
        return previous;
      }
      return previous == null || fetched.revision >= previous.revision ? fetched : previous;
    });
  }

  /** A row merged with the catalog of {@code catalogEpoch}, read for the tracking period {@code period}. */
  private record Read(NativePermissionSnapshot snapshot, Object period, long catalogEpoch) {
  }

  private Map<String, NativePermissionSnapshot.Group> readGroups() throws SQLException {
    final PermissionTables source = tables;
    if (source == null) {
      // Ne jamais rendre une map vide ici : l'appelant remplace le catalogue détenu par ce qu'il
      // reçoit, et « aucun groupe en base » aurait alors la même forme que « backend indisponible ».
      // Les deux se soldent par le retrait de toutes les permissions de groupe du réseau, mais le
      // second est un incident à signaler, pas un état à propager.
      throw new IllegalStateException("Native BTC permissions backend is not available");
    }
    return source.readGroups();
  }

  /**
   * Snapshot of a subject the database holds no row for: no grant of its own, only what the base
   * group of {@code catalog} gives everyone, and nothing at all when the catalog has none.
   *
   * <p>"No row" is not "no grade": the base group applies to a player nobody ever granted anything.
   */
  static NativePermissionSnapshot neverSeen(
      final UUID subject,
      final Map<String, NativePermissionSnapshot.Group> catalog
  ) {
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
    snapshot.groups.forEach((key, value) -> merged.put(key.toLowerCase(Locale.ROOT), value));
    merged.putAll(catalog);
    snapshot.groups = merged;
  }

  private static boolean isDefaultGroup(final NativePermissionSnapshot.Group group) {
    return group != null && group.isDefault;
  }

  /** Entry point of every bus message; package-private so tests feed it real payloads. */
  void handleInvalidation(final String payload) {
    try {
      if (payload == null
          || payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
        return;
      }
      final Invalidation invalidation = GSON.fromJson(payload, Invalidation.class);
      final NativePermissionConfig.Config current = config;
      // No filter on the sender: the proxy never publishes, so a backend sharing its server id is
      // still another publisher whose change must apply here.
      if (invalidation == null || current == null || invalidation.version != 1
          || invalidation.revision < 0 || !current.networkId().equals(invalidation.networkId)
          || ((invalidation.subject == null) == (invalidation.group == null))) {
        return;
      }
      if (invalidation.subject != null) {
        invalidateSubject(invalidation.subject, invalidation.revision);
      } else {
        requestGroupRefresh(invalidation.group.toLowerCase(Locale.ROOT));
      }
    } catch (RuntimeException error) {
      LOGGER.warn("Invalid native BTC permissions Redis payload ignored.", error);
    }
  }

  /**
   * Drops what is cached for {@code subject} below {@code revision}, and refuses it to the reads
   * still in flight even when nothing is cached: the floor is raised before the eviction, so a read
   * installing in between is evicted, and one installing after is refused. Untracked players get no
   * floor: any read of theirs starts after this message, so after the change it announces.
   */
  private void invalidateSubject(final UUID subject, final long revision) {
    if (tracked.containsKey(subject)) {
      revisionFloor.merge(subject, revision, Math::max);
    }
    snapshots.computeIfPresent(subject, (key, existing) -> revision > existing.revision ? null : existing);
  }

  /**
   * Queues {@code group} for the next catalog read. The first request of a burst schedules that read
   * {@link #GROUP_REFRESH_WINDOW} later; the others only join it, so a burst costs one read.
   */
  private void requestGroupRefresh(final String group) {
    scheduleGroupRefresh(Set.of(group), GROUP_REFRESH_WINDOW);
  }

  private void scheduleGroupRefresh(final Set<String> groups, final Duration delay) {
    pendingGroups.addAll(groups);
    if (groupRefreshScheduled.compareAndSet(false, true)) {
      delays.apply(delay).execute(this::refreshQueuedGroupsOrEmpty);
    }
  }

  /** The delayed executor drops what a task throws: an unknown failure is logged and refuses all. */
  private void refreshQueuedGroupsOrEmpty() {
    try {
      refreshQueuedGroups();
    } catch (RuntimeException error) {
      LOGGER.error("Native BTC permissions group refresh failed; the cache is emptied.", error);
      snapshots.clear();
    }
  }

  private void refreshQueuedGroups() {
    // Reopen the window before draining: a request landing meanwhile schedules the next read.
    groupRefreshScheduled.set(false);
    final Set<String> groups = new HashSet<>();
    for (final String group : pendingGroups) {
      if (pendingGroups.remove(group)) {
        groups.add(group);
      }
    }
    if (groups.isEmpty() || closed.get()) {
      return;
    }
    if (!refreshCatalogBlocking()) {
      // What changed is unknown, so the members lose what the outdated catalog gave them (reloads
      // stay refused while it is stale) and the change is retried, never dropped.
      snapshots.entrySet().removeIf(entry -> usesAny(entry.getValue(), groups));
      scheduleGroupRefresh(groups, CATALOG_RETRY_BACKOFF);
      return;
    }
    // A group that just became the base grade is in no cached snapshot yet, yet applies to all.
    final boolean becameDefault = groups.stream().anyMatch(group -> isDefaultGroup(groupCatalog.get(group)));
    snapshots.entrySet().removeIf(entry -> becameDefault || usesAny(entry.getValue(), groups));
  }

  private static boolean usesAny(final NativePermissionSnapshot snapshot, final Set<String> groups) {
    return groups.stream().anyMatch(group -> NativePermissionEvaluator.usesGroup(snapshot, group));
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
