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

package com.btcvelocity.proxy.redis;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs blocking Redis work on one dedicated thread, in submission order, behind a bounded queue.
 *
 * <p>Netty loops and Lettuce I/O threads hand their Redis work to this executor instead of
 * blocking on it. A single worker keeps writes in issue order, so the writes for one player
 * (connect, then switch, then disconnect) can never be reordered. A full queue rejects the task
 * rather than growing without bound; callers rely on the periodic repair to catch up.</p>
 */
public final class OrderedRedisExecutor {

  private static final Logger LOGGER = LoggerFactory.getLogger(OrderedRedisExecutor.class);

  private final String name;
  private final ThreadPoolExecutor executor;

  /**
   * One warning per interval for rejections and failures, so an outage (or a mix of failing and
   * succeeding tasks) does not log one line per task.
   */
  private final LogThrottle warnings = new LogThrottle(LogThrottle.DEFAULT_INTERVAL);

  /**
   * Creates an executor with one daemon worker thread.
   *
   * @param name the worker thread name, also used in log lines
   * @param capacity the maximum number of tasks waiting behind the running one
   */
  public OrderedRedisExecutor(final @NotNull String name, final int capacity) {
    this.name = name;
    this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(capacity), Thread.ofPlatform().name(name).daemon(true).factory(),
        new ThreadPoolExecutor.AbortPolicy());
  }

  /**
   * Queues a task. Never runs it on the calling thread.
   *
   * @param task the task to run on the worker
   * @return {@code false} if the queue was full or the executor is shut down
   */
  public boolean execute(final @NotNull Runnable task) {
    try {
      this.executor.execute(() -> runLogged(task));
      return true;
    } catch (RejectedExecutionException e) {
      final long quiet = this.warnings.acquire();
      if (quiet >= 0) {
        LOGGER.warn("Redis worker '{}' rejected a task (queue full or shut down); the periodic "
            + "repair will catch up ({} similar events not logged)", this.name, quiet);
      }
      return false;
    }
  }

  /**
   * Queues a task that produces a value.
   *
   * @param task the task to run on the worker
   * @param <T> the value type
   * @return a future completed with the value, or exceptionally if the task failed or was rejected
   */
  public <T> @NotNull CompletableFuture<T> supply(final @NotNull Supplier<T> task) {
    final CompletableFuture<T> future = new CompletableFuture<>();
    final boolean queued = execute(() -> {
      try {
        future.complete(task.get());
      } catch (RuntimeException e) {
        future.completeExceptionally(e);
        throw e;
      }
    });
    if (!queued) {
      future.completeExceptionally(new RejectedExecutionException(this.name + " rejected the task"));
    }
    return future;
  }

  /**
   * Stops accepting tasks and waits for the queued ones, then drops whatever is left.
   *
   * @param grace how long to wait for the queue to drain
   */
  public void shutdown(final @NotNull Duration grace) {
    this.executor.shutdown();
    try {
      if (!this.executor.awaitTermination(grace.toMillis(), TimeUnit.MILLISECONDS)) {
        LOGGER.warn("Redis worker '{}' did not drain within {} ms, dropping {} queued task(s)",
            this.name, grace.toMillis(), this.executor.shutdownNow().size());
      }
    } catch (InterruptedException e) {
      this.executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  private void runLogged(final Runnable task) {
    try {
      task.run();
    } catch (RuntimeException e) {
      final long quiet = this.warnings.acquire();
      if (quiet >= 0) {
        LOGGER.warn("Redis task failed on '{}' ({} similar events not logged)", this.name, quiet, e);
      } else {
        LOGGER.debug("Redis task failed on '{}'", this.name, e);
      }
    }
  }
}
