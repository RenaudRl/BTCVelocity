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

import io.lettuce.core.RedisChannelHandler;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisConnectionStateListener;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The proxy's side of the invalidation bus: the channel it listens to, and what a reconnection
 * costs. Lettuce reconnects and resubscribes by itself, but sends SUBSCRIBE asynchronously once
 * connected, so the connection event comes before the subscription is live. A reload started on
 * the connection event could read the tables before the resubscription and miss a change made in
 * between; the reload therefore starts on the server's confirmation ({@code subscribed}).
 *
 * <p>Every callback runs on a Lettuce event loop: each one only hands work to an executor.
 */
final class NativePermissionBus {

  /**
   * How long a reconnected bus may take to confirm its resubscription. Past it, invalidations may
   * be lost for as long as it lasts, so the cache is emptied instead of trusted.
   */
  static final Duration RESUBSCRIBE_GRACE = Duration.ofSeconds(5);
  private static final Logger LOGGER = LogManager.getLogger(NativePermissionBus.class);

  private final String channel;
  private final Consumer<String> onMessage;
  private final Runnable onResubscribed;
  private final Runnable onResubscriptionMissing;
  private final Executor graceDelay;
  private final AtomicLong subscriptions = new AtomicLong();
  private final AtomicLong subscriptionsAtDisconnect = new AtomicLong();
  /** Bumped by each reconnection, so only the latest one keeps retrying its resubscription. */
  private final AtomicLong reconnections = new AtomicLong();
  private volatile StatefulRedisPubSubConnection<String, String> connection;

  /**
   * Builds a bus that is idle until {@link #attach} subscribes it.
   *
   * @param onResubscribed          every confirmed subscription but the first
   * @param onResubscriptionMissing a reconnection not confirmed within {@link #RESUBSCRIBE_GRACE},
   *                                once per reconnection
   * @param graceDelay              runs a task {@link #RESUBSCRIBE_GRACE} later, off any event loop
   */
  NativePermissionBus(
      final String channel,
      final Consumer<String> onMessage,
      final Runnable onResubscribed,
      final Runnable onResubscriptionMissing,
      final Executor graceDelay
  ) {
    this.channel = channel;
    this.onMessage = onMessage;
    this.onResubscribed = onResubscribed;
    this.onResubscriptionMissing = onResubscriptionMissing;
    this.graceDelay = graceDelay;
  }

  /** Subscribes {@code connection} and watches {@code client}. Blocks on SUBSCRIBE: never on an event loop. */
  void attach(final RedisClient client, final StatefulRedisPubSubConnection<String, String> connection) {
    this.connection = connection;
    connection.addListener(new RedisPubSubAdapter<>() {
      @Override
      public void message(final String from, final String message) {
        if (channel.equals(from)) {
          onMessage.accept(message);
        }
      }

      @Override
      public void subscribed(final String to, final long count) {
        if (channel.equals(to) && subscriptions.getAndIncrement() > 0) {
          onResubscribed.run();
        }
      }
    });
    connection.sync().subscribe(channel);
    // After the first connection on purpose: only a reconnection has anything to reconcile.
    client.addListener(new RedisConnectionStateListener() {
      @Override
      public void onRedisDisconnected(final RedisChannelHandler<?, ?> handler) {
        // Counted at the disconnection: a confirmation racing ahead of onRedisConnected still counts.
        subscriptionsAtDisconnect.set(subscriptions.get());
      }

      @Override
      public void onRedisConnected(final RedisChannelHandler<?, ?> handler, final SocketAddress address) {
        expectResubscription();
      }
    });
  }

  private void expectResubscription() {
    final long before = subscriptionsAtDisconnect.get();
    final long reconnection = reconnections.incrementAndGet();
    graceDelay.execute(() -> retryUntilConfirmed(before, reconnection, true));
  }

  /**
   * Lettuce never retries a resubscription that failed, so the bus does, every
   * {@link #RESUBSCRIBE_GRACE}, until the server confirms it ({@code subscribed} then reloads).
   */
  private void retryUntilConfirmed(final long before, final long reconnection, final boolean first) {
    if (subscriptions.get() != before || reconnections.get() != reconnection) {
      return;
    }
    if (first) {
      LOGGER.error("Native BTC permissions bus reconnected without confirming its subscription to {} within"
          + " {} ms; invalidations may be lost, so the permission cache is reloaded until it is.",
          channel, RESUBSCRIBE_GRACE.toMillis());
      onResubscriptionMissing.run();
    }
    try {
      connection.sync().subscribe(channel);
    } catch (RuntimeException error) {
      LOGGER.warn("Native BTC permissions bus resubscription to {} failed; retrying in {} ms: {}",
          channel, RESUBSCRIBE_GRACE.toMillis(), error.toString());
    }
    graceDelay.execute(() -> retryUntilConfirmed(before, reconnection, false));
  }
}
