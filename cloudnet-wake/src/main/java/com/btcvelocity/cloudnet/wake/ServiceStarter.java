/*
 * Copyright (C) 2026 Velocity Contributors
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

package com.btcvelocity.cloudnet.wake;

import java.util.concurrent.CompletableFuture;

/**
 * Makes sure a service of a task is starting. The only place the wake logic touches the
 * orchestrator, so that the rest can be tested without it.
 */
interface ServiceStarter {

  /**
   * What the orchestrator did for a task.
   */
  enum Outcome {
    /** An instance was already RUNNING (starting, or up but not registered yet): nothing sent. */
    ALREADY_STARTING(true),
    /** A PREPARED or newly created instance was started. */
    STARTED(true),
    /** The orchestrator refused or did not answer. */
    FAILED(false),
    /** The creation was postponed by the node, and there is no instance to wait for. */
    DEFERRED(false),
    /** No task of that name exists in the orchestrator. */
    UNKNOWN_TASK(false);

    private final boolean waitable;

    Outcome(boolean waitable) {
      this.waitable = waitable;
    }

    /**
     * Whether an instance is on its way, so that waiting for its registration makes sense.
     *
     * @return {@code true} when the player should keep waiting
     */
    boolean waitable() {
      return this.waitable;
    }
  }

  /**
   * Starts the task if nothing of it is starting yet. Never blocks and never throws: every
   * failure is an {@link Outcome}.
   *
   * @param task the CloudNet task name
   * @return the outcome, completed on the orchestrator's answer
   */
  CompletableFuture<Outcome> ensureStarted(String task);
}
