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

package com.btcvelocity.proxy.redis.provider;

import com.btcvelocity.proxy.redis.OrderedRedisExecutor;
import com.btcvelocity.proxy.redis.depot.Depot;
import com.btcvelocity.proxy.redis.depot.DepotEntry;
import com.btcvelocity.proxy.redis.handler.RouteHandler;
import com.btcvelocity.proxy.redis.packet.DataPacket;
import com.btcvelocity.proxy.redis.packet.PacketSerializer;
import com.btcvelocity.proxy.redis.transaction.Transaction;
import com.btcvelocity.proxy.redis.transaction.TransactionHandler;
import com.google.common.base.Preconditions;
import com.google.gson.Gson;
import com.velocitypowered.api.scheduler.Scheduler;
import com.velocitypowered.proxy.config.VelocityConfiguration;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisBusyException;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.RedisLoadingException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.async.RedisPubSubAsyncCommands;
import io.lettuce.core.pubsub.api.sync.RedisPubSubCommands;
import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Represents the Lettuce implementation of the {@link RedisProvider} interface.
 */
public final class LettuceProvider extends AbstractRedisProvider {

  /**
   * Logger used for all Lettuce provider operations and diagnostics.
   */
  private static final Logger LOGGER = LoggerFactory.getLogger(LettuceProvider.class);

  /**
   * Upper bound of every Redis command, sync or async. A Redis that stops answering costs a
   * caller this long at most, instead of Lettuce's 60 s default (or forever for async commands).
   */
  public static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(2);

  /**
   * Maximum number of received pub/sub messages waiting for their handler.
   */
  private static final int MESSAGE_QUEUE_CAPACITY = 8192;

  /**
   * How long {@link #disconnect()} waits for queued messages to be handled.
   */
  private static final Duration MESSAGE_DRAIN_GRACE = Duration.ofSeconds(2);

  /**
   * Commands waiting on one connection before new ones are rejected, instead of piling up in
   * memory while Redis does not answer.
   */
  private static final int REQUEST_QUEUE_SIZE = 10_000;

  /**
   * Lua: lower-cased {@code proxyId} of a JSON entry, or nil. Shared by the owner-aware scripts.
   * Every key is passed in KEYS, as Dragonfly requires.
   */
  private static final String OWNER_FUNCTION = "local function owner(json) "
      + "local ok, entry = pcall(cjson.decode, json) "
      + "if ok and type(entry) == 'table' and type(entry.proxyId) == 'string' then "
      + "return string.lower(entry.proxyId) end "
      + "return nil end\n";

  /**
   * Lua with cjson, as the owner-aware scripts use it: answers 1 where they can run.
   */
  private static final String SCRIPTING_PROBE = "return cjson.decode('{\"a\":1}').a";

  /**
   * KEYS[1] hash, ARGV: field, json, proxyId, force ("1"). Writes unless another proxy owns the
   * field (or force); returns that proxy's entry, or nil.
   */
  private static final String CLAIM_SCRIPT = OWNER_FUNCTION
      + "local current = redis.call('HGET', KEYS[1], ARGV[1])\n"
      + "if current then\n"
      + "  local holder = owner(current)\n"
      + "  if holder and holder ~= string.lower(ARGV[3]) then\n"
      + "    if ARGV[4] == '1' then redis.call('HSET', KEYS[1], ARGV[1], ARGV[2]) end\n"
      + "    return current\n"
      + "  end\n"
      + "end\n"
      + "redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])\n"
      + "return false\n";

  /**
   * KEYS[1] hash, ARGV: field, proxyId. Deletes the field only if that proxy owns it.
   */
  private static final String REMOVE_IF_OWNER_SCRIPT = OWNER_FUNCTION
      + "local current = redis.call('HGET', KEYS[1], ARGV[1])\n"
      + "if current and owner(current) == string.lower(ARGV[2]) then\n"
      + "  return redis.call('HDEL', KEYS[1], ARGV[1])\n"
      + "end\n"
      + "return 0\n";

  /**
   * KEYS[1] hash, ARGV: proxyId. Deletes every field that proxy owns.
   */
  private static final String REMOVE_ALL_OWNED_SCRIPT = OWNER_FUNCTION
      + "local all = redis.call('HGETALL', KEYS[1])\n"
      + "local removed = 0\n"
      + "for i = 1, #all, 2 do\n"
      + "  if owner(all[i + 1]) == string.lower(ARGV[1]) then\n"
      + "    removed = removed + redis.call('HDEL', KEYS[1], all[i])\n"
      + "  end\n"
      + "end\n"
      + "return removed\n";

  /**
   * KEYS[1] lease, ARGV: owner, ttl in ms. Extends the lease only if that owner holds it.
   */
  private static final String RENEW_LEASE_SCRIPT =
      "if redis.call('GET', KEYS[1]) == ARGV[1] then\n"
      + "  return redis.call('PEXPIRE', KEYS[1], ARGV[2])\n"
      + "end\n"
      + "return 0\n";

  /**
   * KEYS[1] lease, ARGV: owner. Deletes the lease only if that owner holds it.
   */
  private static final String RELEASE_LEASE_SCRIPT =
      "if redis.call('GET', KEYS[1]) == ARGV[1] then\n"
      + "  return redis.call('DEL', KEYS[1])\n"
      + "end\n"
      + "return 0\n";

  /**
   * Runs the pub/sub handlers off Lettuce's I/O thread, in arrival order. Handlers may call the
   * sync depot API; doing that on the I/O thread blocks it, and can stall the very connection
   * that must deliver the answer.
   */
  private final OrderedRedisExecutor messageHandlers =
      new OrderedRedisExecutor("btc-redis-pubsub", MESSAGE_QUEUE_CAPACITY);

  /**
   * The Redis configuration used by this provider.
   */
  private final VelocityConfiguration.Redis config;

  /**
   * The underlying Lettuce {@link RedisClient} used to establish Redis connections.
   */
  private final RedisClient client;

  /**
   * The asynchronous Redis Pub/Sub command API used to publish packets and manage subscriptions.
   */
  private RedisPubSubAsyncCommands<String, String> publisher;

  /**
   * A synchronous pub/sub connection used for raw key operations such as setting keys with expiry,
   * checking key existence, and deleting keys.
   */
  private RedisPubSubCommands<String, String> syncPublisher;

  /**
   * A shared (non-pub/sub) Redis connection used by all {@link LettuceDepot} instances for
   * hash operations. Sharing a single connection avoids opening one extra connection per depot.
   */
  private StatefulRedisConnection<String, String> depotConnection;

  /**
   * Constructs a new {@link LettuceProvider}.
   *
   * @param config the {@link VelocityConfiguration.Redis} instance to use for connection credentials
   * @param scheduler the scheduler used for transaction timeout tasks
   * @param packetSerializer the serializer for packet (de)serialization
   */
  public LettuceProvider(final VelocityConfiguration.Redis config,
                         final @NotNull Scheduler scheduler,
                         final @NotNull PacketSerializer packetSerializer) {
    super(scheduler, packetSerializer);

    this.config = config;
    this.client = RedisClient.create(RedisURI.Builder.redis(config.getHost(), config.getPort())
            .withAuthentication(Objects.requireNonNullElse(config.getUsername(), ""),
                    Objects.requireNonNullElse(config.getPassword(), ""))
            .withSsl(config.isUseSsl())
            .withTimeout(COMMAND_TIMEOUT)
            .build());
    // Without the timeout, async commands never time out and the sync ones wait Lettuce's 60 s
    // default. While disconnected, commands fail at once instead of queueing until a reconnect.
    this.client.setOptions(ClientOptions.builder()
        .timeoutOptions(TimeoutOptions.enabled(COMMAND_TIMEOUT))
        .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
        .requestQueueSize(REQUEST_QUEUE_SIZE)
        .build());
  }

  /**
   * Checks once that the server runs the Lua scripts the player depot relies on. A server that
   * refuses them (Lua disabled, ACL {@code -@scripting}, out of memory, no cjson) makes every
   * duplicate-login check fail, so this names the cause at startup. Never blocks longer than one
   * command: a server that cannot be reached is only warned about, as before.
   *
   * @return {@code false} if the server refused the script (logged as an error)
   */
  public boolean verifyScripting() {
    try {
      final Long answer = this.depotConnection.sync().eval(SCRIPTING_PROBE, ScriptOutputType.INTEGER);
      if (answer != null && answer == 1) {
        return true;
      }
      LOGGER.error("{} ran the Lua/cjson probe but answered {} instead of 1: the duplicate-login "
          + "check cannot work.", this.config.getBackend(), answer);
      return false;
    } catch (RuntimeException e) {
      if (isScriptRefusal(e)) {
        LOGGER.error("{} refused the Lua scripts BTCVelocity needs ({}). Every duplicate-login "
            + "check will fail: with redis.login-check-failure = \"deny\" NO player can log in. "
            + "Allow EVAL with cjson (Lua enabled, no ACL -@scripting, memory not full).",
            this.config.getBackend(), e.getMessage());
        return false;
      }
      LOGGER.warn("Could not check {} Lua support at startup: {}", this.config.getBackend(),
          e.toString());
      return true;
    }
  }

  /**
   * Whether a failure is the server refusing a command (an error reply such as {@code ERR},
   * {@code NOSCRIPT}, {@code NOPERM}, {@code OOM}), as opposed to the network or a timeout.
   * {@code LOADING} and {@code BUSY} are passing server states, not refusals.
   *
   * @param failure the failure, possibly wrapped by a future
   * @return {@code true} for an error reply that will come back on every try
   */
  public static boolean isScriptRefusal(final @NotNull Throwable failure) {
    Throwable cause = failure;
    while ((cause instanceof CompletionException || cause instanceof ExecutionException)
        && cause.getCause() != null) {
      cause = cause.getCause();
    }
    return cause instanceof RedisCommandExecutionException
        && !(cause instanceof RedisCommandTimeoutException)
        && !(cause instanceof RedisLoadingException)
        && !(cause instanceof RedisBusyException);
  }

  /**
   * Restarts the underlying Redis Pub/Sub connection and resubscribes to the channel.
   *
   * <p>If an existing publisher connection is present, it is closed before a new
   * connection is created.</p>
   */
  @Override
  public void restart() {
    if (this.publisher != null) {
      this.publisher.getStatefulConnection().close();
    }

    if (this.syncPublisher != null) {
      this.syncPublisher.getStatefulConnection().close();
    }

    final StatefulRedisPubSubConnection<String, String> connection = this.client.connectPubSub();

    // Tracks whether the initial subscribe has completed. The first subscribed() callback is
    // the initial subscribe; every subsequent one is a re-subscribe after a reconnect.
    final AtomicBoolean subscribedOnce = new AtomicBoolean(false);

    connection.addListener(new RedisPubSubAdapter<>() {
      @Override
      public void subscribed(final String channel, final long count) {
        if (CHANNEL.equals(channel) && subscribedOnce.getAndSet(true)) {
          // Re-subscribe after a reconnect, notify listeners so they can reload state.
          messageHandlers.execute(LettuceProvider.this::fireReconnectListeners);
        }
      }

      @Override
      public void message(final String channel, final String message) {
        if (channel.equals(CHANNEL)) {
          messageHandlers.execute(() -> handleMessage(message));
        }
      }
    });

    this.publisher = connection.async();
    this.publisher.subscribe(CHANNEL);

    this.syncPublisher = connection.sync();

    // (Re)create the shared depot connection used by all LettuceDepot instances.
    if (this.depotConnection != null) {
      this.depotConnection.close();
    }
    this.depotConnection = this.client.connect();

    LOGGER.info("Connected to {} Server on channel '{}' (backend: {})",
        config.getBackend().substring(0, 1).toUpperCase() + config.getBackend().substring(1),
        CHANNEL, config.getBackend());
  }

  /**
   * Decodes one pub/sub message and routes it. Runs on {@link #messageHandlers}.
   *
   * @param message the raw message
   */
  private void handleMessage(final String message) {
    final DataPacket dataPacket = packetSerializer.deserialize(message);
    if (dataPacket == null) {
      LOGGER.warn("Received a null packet from channel '{}', ignoring", CHANNEL);
      return;
    }

    if (dataPacket.isOneWay()) {
      handleOneWay(dataPacket);
      return;
    }

    if (!dataPacket.isReply()) {
      handleTransactionRequest(dataPacket);
    } else {
      handleTransactionReply(dataPacket);
    }
  }

  /**
   * Disconnects from the Redis server by closing the Pub/Sub connection and shutting down the client.
   *
   * <p>If no active connection exists, a warning is logged and the call is ignored.</p>
   */
  @Override
  public void disconnect() {
    this.messageHandlers.shutdown(MESSAGE_DRAIN_GRACE);

    if (!this.isConnected()) {
      LOGGER.warn("Disconnecting from Redis while it is unreachable");
    }

    // Closed even when not open: a closed connection stops its reconnect attempts, and the client
    // shutdown releases Lettuce's threads, whatever state Redis is in.
    if (publisher != null) {
      publisher.getStatefulConnection().close();
      publisher = null;
    }

    if (syncPublisher != null) {
      syncPublisher.getStatefulConnection().close();
      syncPublisher = null;
    }

    if (this.depotConnection != null) {
      this.depotConnection.close();
      this.depotConnection = null;
    }

    this.client.shutdown();

    LOGGER.info("Disconnected from {} Server on channel '{}' (backend: {})",
        config.getBackend().substring(0, 1).toUpperCase() + config.getBackend().substring(1),
        CHANNEL, config.getBackend());
  }

  /**
   * Publishes the given {@link DataPacket} to the configured Redis channel.
   *
   * <p>If the publisher has not been initialized yet, a warning is logged and the packet is not sent.</p>
   *
   * @param packet the packet to publish
   */
  @Override
  protected void publishRaw(final @NotNull DataPacket packet) {
    if (this.publisher == null) {
      LOGGER.warn("Attempted to publish a packet to channel '{}' but the publisher is not initialized", CHANNEL);
      return;
    }

    this.publisher.publish(CHANNEL, packetSerializer.serialize(packet)).whenComplete((received, throwable) -> {
      if (throwable != null) {
        LOGGER.warn("Failed to publish packet to '{}' channel", CHANNEL, throwable);
      }
    });
  }

  /**
   * Creates a new {@link Depot} associated with this Redis provider backed by Lettuce.
   *
   * @param valueClass the class of the values stored in the depot
   * @param <K> the key type used by the depot
   * @param <V> the depot entry type
   * @return a new {@link LettuceDepot} instance
   */
  @Override
  @Contract(value = "_ -> new", pure = true)
  public <K, V extends DepotEntry<K, V>> @NotNull Depot<K, V> createDepot(final Class<V> valueClass) {
    Preconditions.checkState(this.depotConnection != null,
        "depot connection is not initialized; call restart() first");
    return new LettuceDepot<>(valueClass, this.depotConnection);
  }

  /**
   * Determines whether this provider is currently connected to Redis.
   *
   * @return {@code true} if the publisher exists and its connection is open, otherwise {@code false}
   */
  @Override
  public boolean isConnected() {
    return this.publisher != null && this.publisher.getStatefulConnection().isOpen();
  }

  /**
   * Handles a one-way {@link DataPacket} by routing it through a registered {@link RouteHandler}, if present.
   *
   * @param dataPacket the one-way packet to handle
   */
  @SuppressWarnings("unchecked")
  private void handleOneWay(final @NotNull DataPacket dataPacket) {
    final RouteHandler<Object> routeHandler = (RouteHandler<Object>) routeHandlers.get(dataPacket.getPayloadType());
    if (routeHandler == null) {
      LOGGER.warn("Received a packet of type '{}' from channel '{}', but no route registration exists, ignoring",
              dataPacket.getPayloadType(), CHANNEL);
      return;
    }

    try {
      routeHandler.getConsumer().accept(dataPacket.getPayload(packetSerializer));
    } catch (Throwable t) {
      LOGGER.warn("Failed to handle one way packet of type '{}'.", dataPacket.getPayloadType(), t);
    }
  }

  /**
   * Handles an incoming transaction request by extracting the payload, delegating to the
   * registered {@link TransactionHandler}, and wrapping the result in a reply {@link DataPacket}.
   *
   * @param dataPacket the incoming transaction request packet
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private void handleTransactionRequest(final @NotNull DataPacket dataPacket) {
    final TransactionHandler transactionHandler = transactionHandlers.get(dataPacket.getPayloadType());
    if (transactionHandler == null) {
      return;
    }

    final CompletableFuture<?> future = transactionHandler.handleData(dataPacket.getPayload(packetSerializer));
    if (future == null) {
      return;
    }

    future.thenAccept(result -> {
      if (result == null) {
        return;
      }

      final DataPacket replyPacket = DataPacket.of(result, packetSerializer);
      replyPacket.setTransactionId(Preconditions.checkNotNull(dataPacket.getTransactionId()));
      replyPacket.setReply(true);

      this.publishRaw(replyPacket);
    }).exceptionally(throwable -> {
      LOGGER.warn("Transaction handler for '{}' completed exceptionally", dataPacket.getPayloadType(), throwable);
      return null;
    });
  }

  /**
   * Handles an incoming transaction reply by extracting the payload and completing
   * the pending {@link Transaction}.
   *
   * @param dataPacket the incoming transaction reply packet
   */
  private void handleTransactionReply(final @NotNull DataPacket dataPacket) {
    final UUID transactionId = Preconditions.checkNotNull(dataPacket.getTransactionId());

    final Transaction<?, ?> transaction = pendingTransactions.remove(transactionId);
    if (transaction == null) {
      return;
    }

    transaction.complete(dataPacket.getPayload(packetSerializer));
  }

  /**
   * Gets the value associated with a key using the regular (non-pub/sub) connection.
   *
   * @param key the key to look up
   * @return the value, or {@code null} if none exists
   * @deprecated use {@link #getAsync(String)} for non-blocking access
   */
  @Deprecated
  @Override
  public @Nullable String get(final @NotNull String key) {
    if (this.syncPublisher == null) {
      LOGGER.warn("Attempted to get key '{}' but the sync connection is not initialized", key);
      return null;
    }

    return this.syncPublisher.get(key);
  }

  /**
   * Sets a key with an expiry time in seconds using the regular (non-pub/sub) connection.
   *
   * @param key        the key to set
   * @param value      the value to store
   * @param ttlSeconds the time-to-live in seconds
   * @deprecated use {@link #setWithExpiryAsync(String, String, long)} for non-blocking access
   */
  @Deprecated
  @Override
  public void setWithExpiry(final @NotNull String key, final @NotNull String value, final long ttlSeconds) {
    if (this.syncPublisher == null) {
      LOGGER.warn("Attempted to set key '{}' with expiry but the sync connection is not initialized", key);
      return;
    }

    this.syncPublisher.setex(key, ttlSeconds, value);
  }

  /**
   * Checks whether a key exists in Redis using the regular (non-pub/sub) connection.
   *
   * @param key the key to check
   * @return {@code true} if the key exists, otherwise {@code false}
   * @deprecated use {@link #existsKeyAsync(String)} for non-blocking access
   */
  @Deprecated
  @Override
  public boolean existsKey(final @NotNull String key) {
    if (this.syncPublisher == null) {
      LOGGER.warn("Attempted to check existence of key '{}' but the sync connection is not initialized", key);
      return false;
    }

    return this.syncPublisher.exists(key) > 0;
  }

  /**
   * Deletes a key from Redis using the regular (non-pub/sub) connection.
   *
   * @param key the key to delete
   * @deprecated use {@link #deleteKeyAsync(String)} for non-blocking access
   */
  @Deprecated
  @Override
  public void deleteKey(final @NotNull String key) {
    if (this.syncPublisher == null) {
      LOGGER.warn("Attempted to delete key '{}' but the sync connection is not initialized", key);
      return;
    }

    this.syncPublisher.del(key);
  }

  @Override
  public boolean acquireLease(final @NotNull String key, final @NotNull String owner,
                              final @NotNull Duration ttl) {
    return this.depotConnection.sync()
        .set(key, owner, SetArgs.Builder.nx().px(ttl.toMillis())) != null;
  }

  @Override
  public boolean renewLease(final @NotNull String key, final @NotNull String owner,
                            final @NotNull Duration ttl) {
    final Long renewed = this.depotConnection.sync().eval(RENEW_LEASE_SCRIPT,
        ScriptOutputType.INTEGER, new String[] {key}, owner, String.valueOf(ttl.toMillis()));
    return renewed != null && renewed > 0;
  }

  @Override
  public boolean releaseLease(final @NotNull String key, final @NotNull String owner) {
    final Long released = this.depotConnection.sync().eval(RELEASE_LEASE_SCRIPT,
        ScriptOutputType.INTEGER, new String[] {key}, owner);
    return released != null && released > 0;
  }

  /**
   * Asynchronously gets the value associated with a key using the async pub/sub API.
   *
   * @param key the key to look up
   * @return a future that completes with the value, or {@code null} if none exists
   */
  @Override
  public CompletableFuture<@Nullable String> getAsync(final @NotNull String key) {
    if (this.publisher == null) {
      LOGGER.warn("Attempted to get key '{}' but the async connection is not initialized", key);
      return CompletableFuture.completedFuture(null);
    }

    return this.publisher.get(key).toCompletableFuture();
  }

  /**
   * Asynchronously sets a key with an expiry time in seconds using the async pub/sub API.
   *
   * @param key        the key to set
   * @param value      the value to store
   * @param ttlSeconds the time-to-live in seconds
   * @return a future that completes when the operation is done
   */
  @Override
  public CompletableFuture<Void> setWithExpiryAsync(final @NotNull String key,
                                                     final @NotNull String value,
                                                     final long ttlSeconds) {
    if (this.publisher == null) {
      LOGGER.warn("Attempted to set key '{}' with expiry but the async connection is not initialized", key);
      return CompletableFuture.completedFuture(null);
    }

    return this.publisher.setex(key, ttlSeconds, value).thenAccept(status -> {}).toCompletableFuture();
  }

  /**
   * Asynchronously checks whether a key exists in Redis using the async pub/sub API.
   *
   * @param key the key to check
   * @return a future that completes with {@code true} if the key exists, otherwise {@code false}
   */
  @Override
  public CompletableFuture<Boolean> existsKeyAsync(final @NotNull String key) {
    if (this.publisher == null) {
      LOGGER.warn("Attempted to check existence of key '{}' but the async connection is not initialized", key);
      return CompletableFuture.completedFuture(false);
    }

    return this.publisher.exists(key).thenApply(count -> count > 0).toCompletableFuture();
  }

  /**
   * Asynchronously deletes a key from Redis using the async pub/sub API.
   *
   * @param key the key to delete
   * @return a future that completes when the operation is done
   */
  @Override
  public CompletableFuture<Void> deleteKeyAsync(final @NotNull String key) {
    if (this.publisher == null) {
      LOGGER.warn("Attempted to delete key '{}' but the async connection is not initialized", key);
      return CompletableFuture.completedFuture(null);
    }

    return this.publisher.del(key).thenAccept(count -> {}).toCompletableFuture();
  }

  /**
   * Represents the Lettuce-provided implementation of the {@link Depot} interface.
   *
   * @param <K> the type of the key in the depot
   * @param <V> the type of the object value in the depot
   */
  public final class LettuceDepot<K, V extends DepotEntry<K, V>> implements Depot<K, V> {

    /**
     * {@link Gson} instance used for serializing and deserializing depot entries.
     */
    private final Gson gson = packetSerializer.gson();

    /**
     * The Redis hash key name used to store entries for this depot.
     */
    private final String name;

    /**
     * The class representing the value type stored in this depot.
     */
    private final Class<V> valueClass;

    /**
     * The synchronous Redis commands used to interact with Redis hashes for this depot.
     * This is a reference to the shared depot connection's sync API, so no extra
     * connection is opened per depot.
     */
    private final RedisCommands<String, String> connection;

    /**
     * The async API of the same connection: commands from both keep their issue order.
     */
    private final RedisAsyncCommands<String, String> async;

    /**
     * Constructs a new {@link LettuceDepot} instance using the shared depot connection.
     *
     * @param valueClass the class of the depot value
     * @param connection the shared Redis connection used for hash operations
     */
    public LettuceDepot(final @NotNull Class<V> valueClass,
                        final @NotNull StatefulRedisConnection<String, String> connection) {
      this.name = valueClass.getSimpleName().toLowerCase();
      this.valueClass = valueClass;
      this.connection = connection.sync();
      this.async = connection.async();
    }

    /**
     * Checks whether a value is present in the depot for the given key.
     *
     * @param key the key to look up
     * @return {@code true} if a value exists for the key, otherwise {@code false}
     */
    @Override
    public boolean contains(final @NotNull K key) {
      return this.connection.hexists(this.name, parseKey(key));
    }

    /**
     * Retrieves a value from the depot by key.
     *
     * @param key the key to retrieve the value for
     * @return the deserialized value, or {@code null} if none exists
     */
    @Override
    public @Nullable V get(final @NotNull K key) {
      final String data = this.connection.hget(this.name, parseKey(key));
      return data == null ? null : deserialize(data);
    }

    /**
     * Inserts or updates the given value in the depot.
     *
     * @param value the value to upsert
     */
    @Override
    public void upsert(final @NotNull V value) {
      this.connection.hset(this.name, parseKey(value.getUniqueId()), serialize(value));
      value.setDepot(this);
    }

    /**
     * Removes a value from the depot by key, returning the removed value if present.
     *
     * @param key the key whose mapping should be removed
     * @return the removed value, or {@code null} if no value existed
     */
    @Override
    public @Nullable V remove(final @NotNull K key) {
      if (this.connection.hexists(this.name, parseKey(key))) {
        final String data = this.connection.hget(this.name, parseKey(key));
        this.connection.hdel(this.name, parseKey(key));

        return data == null ? null : deserialize(data);
      }

      return null;
    }

    /**
     * Returns a collection of all values stored in this depot.
     *
     * @return a collection of all deserialized values
     */
    @Override
    public Collection<V> values() {
      return this.connection.hvals(this.name).stream().map(this::deserialize).toList();
    }

    @Override
    public CompletableFuture<@Nullable V> claim(final @NotNull V value, final @NotNull String proxyId,
                                                final boolean force) {
      final RedisFuture<String> previous = this.async.eval(CLAIM_SCRIPT, ScriptOutputType.VALUE,
          new String[] {this.name}, parseKey(value.getUniqueId()), serialize(value),
          proxyId, force ? "1" : "0");
      value.setDepot(this);
      return previous.toCompletableFuture().thenApply(json -> json == null ? null : deserialize(json));
    }

    @Override
    public boolean removeIfOwner(final @NotNull K key, final @NotNull String proxyId) {
      final Long removed = this.connection.eval(REMOVE_IF_OWNER_SCRIPT, ScriptOutputType.INTEGER,
          new String[] {this.name}, parseKey(key), proxyId);
      return removed != null && removed > 0;
    }

    @Override
    public long removeAllOwnedBy(final @NotNull String proxyId) {
      final Long removed = this.connection.eval(REMOVE_ALL_OWNED_SCRIPT, ScriptOutputType.INTEGER,
          new String[] {this.name}, proxyId);
      return removed == null ? 0 : removed;
    }

    /**
     * Returns a collection of all keys stored in this depot.
     *
     * @return a collection of all keys
     */
    @Override
    public Collection<String> keys() {
      return this.connection.hkeys(this.name);
    }

    /**
     * Serializes the specified entry into a JSON string.
     *
     * @param entry the entry to serialize
     * @return the JSON string representation of the entry
     */
    private @NotNull String serialize(final @NotNull V entry) {
      return gson.toJson(entry, this.valueClass);
    }

    /**
     * Deserializes the specified JSON string into an entry and associates it with this depot.
     *
     * @param data the JSON string to deserialize
     * @return the deserialized entry
     */
    private @NotNull V deserialize(final @NotNull String data) {
      final V entry = gson.fromJson(data, this.valueClass);
      entry.setDepot(this);

      return entry;
    }

    /**
     * Parses the specified key into a string suitable for use in Redis.
     *
     * @param key the key to parse
     * @return the string representation of the key
     */
    private @NotNull String parseKey(final K key) {
      return String.valueOf(key);
    }
  }
}
