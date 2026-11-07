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

package com.btcvelocity.proxy.network;

import com.btcvelocity.api.connection.ClientChannelHook;
import com.btcvelocity.proxy.redis.LogThrottle;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The {@link ClientChannelHook}s plugins registered, run for each new client channel.
 *
 * <p>A hook that throws is logged and skipped, and the handlers it added are removed: a plugin
 * must not be able to refuse a connection, stop the hooks after it, or leave half a setup behind.
 */
public final class ClientChannelHooks {

  /**
   * A hook only adds handlers, which takes microseconds: above this it is doing I/O or waiting on a
   * lock on the event loop (AD-092). The first call of a hook may include its class loading.
   */
  static final long SLOW_HOOK_NANOS = TimeUnit.MILLISECONDS.toNanos(10);

  private static final Logger LOGGER = LogManager.getLogger(ClientChannelHooks.class);

  // copy-on-write: hooks are added once at startup, then read on every connection
  private final List<Registration> hooks = new CopyOnWriteArrayList<>();

  /**
   * Registers a hook.
   *
   * @param owner the id of the plugin that owns it, for the logs
   * @param hook the hook
   */
  public void add(final String owner, final ClientChannelHook hook) {
    this.hooks.add(new Registration(Objects.requireNonNull(owner, "owner"),
        Objects.requireNonNull(hook, "hook")));
  }

  /**
   * Unregisters one registration of a hook by the same owner, if there is one.
   *
   * @param owner the id of the plugin that registered it
   * @param hook the hook
   */
  public void remove(final String owner, final ClientChannelHook hook) {
    for (final Registration registration : this.hooks) {
      if (registration.owner.equals(owner) && registration.hook.equals(hook)) {
        this.hooks.remove(registration);
        return;
      }
    }
  }

  /**
   * Runs every hook, in registration order, on a channel whose proxy pipeline is built.
   *
   * @param channel the new client channel
   */
  public void run(final Channel channel) {
    final ChannelPipeline pipeline = channel.pipeline();
    for (final Registration registration : this.hooks) {
      final List<String> before = pipeline.names();
      final long start = System.nanoTime();
      try {
        registration.hook.initChannel(channel);
      } catch (final Exception | LinkageError e) {
        // a plugin built for another API (NoSuchMethodError) must not close the channel either
        removeAddedHandlers(pipeline, before);
        registration.failed(channel, e);
        continue;
      }
      registration.checkDuration(System.nanoTime() - start);
    }
  }

  private static void removeAddedHandlers(final ChannelPipeline pipeline, final List<String> before) {
    final Set<String> kept = new HashSet<>(before);
    for (final String name : pipeline.names()) {
      if (!kept.contains(name)) {
        try {
          pipeline.remove(name);
        } catch (final NoSuchElementException expected) {
          // removing an earlier handler of the hook took this one with it
        }
      }
    }
  }

  private static final class Registration {

    private final String owner;
    private final ClientChannelHook hook;
    private final AtomicBoolean failedOnce = new AtomicBoolean();
    private final LogThrottle failures = new LogThrottle(LogThrottle.DEFAULT_INTERVAL);
    private final LogThrottle slowCalls = new LogThrottle(LogThrottle.DEFAULT_INTERVAL);

    private Registration(final String owner, final ClientChannelHook hook) {
      this.owner = owner;
      this.hook = hook;
    }

    // the stack once per hook, then a counted line per interval: a broken hook fails on every join
    private void failed(final Channel channel, final Throwable error) {
      if (!this.failedOnce.getAndSet(true)) {
        this.failures.acquire(); // takes the throttle's first slot: the next line waits an interval
        LOGGER.warn("Client channel hook {} of plugin {} failed on {}; the handlers it added were "
            + "removed and the connection goes on", this.hook, this.owner, channel, error);
        return;
      }
      final long quiet = this.failures.acquire();
      if (quiet >= 0) {
        LOGGER.warn("Client channel hook {} of plugin {} failed again on {} ({} more failures not "
            + "logged; stack trace at its first failure): {}", this.hook, this.owner, channel,
            quiet, error.toString());
      }
    }

    private void checkDuration(final long nanos) {
      if (nanos <= SLOW_HOOK_NANOS) {
        return;
      }
      final long quiet = this.slowCalls.acquire();
      if (quiet >= 0) {
        LOGGER.warn("Client channel hook {} of plugin {} took {} ms on the event loop, over {} ms "
            + "({} more slow calls not logged): a hook must not block nor do I/O", this.hook,
            this.owner, TimeUnit.NANOSECONDS.toMillis(nanos),
            TimeUnit.NANOSECONDS.toMillis(SLOW_HOOK_NANOS), quiet);
      }
    }
  }
}
