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

import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * An optional listener that follows the configuration through reloads, remembering the address it
 * is really bound to.
 *
 * <p>The address to close is the one that was bound, never the one a configuration says: by the
 * time a reload compares, the server already holds the new configuration.
 */
public final class TrackedListener {

  private static final Logger LOGGER = LogManager.getLogger(TrackedListener.class);

  private final String name;
  private final Consumer<InetSocketAddress> binder;
  private final Consumer<InetSocketAddress> closer;
  private InetSocketAddress bound;

  /**
   * Creates a listener that is not bound yet.
   *
   * @param name the name of the listener, for the logs
   * @param binder binds an address, and throws if it cannot
   * @param closer closes an address that was bound
   */
  public TrackedListener(final String name, final Consumer<InetSocketAddress> binder,
      final Consumer<InetSocketAddress> closer) {
    this.name = name;
    this.binder = binder;
    this.closer = closer;
  }

  /**
   * Binds the listener at startup. A failure is not caught: the proxy must not start half-way.
   *
   * @param wanted the configured address, if any
   */
  public synchronized void start(final Optional<InetSocketAddress> wanted) {
    if (wanted.isPresent()) {
      this.binder.accept(wanted.get());
      this.bound = wanted.get();
    }
  }

  /**
   * Brings the listener to the configured address: binds the new one first, then closes the old
   * one. If the new address cannot be bound, the error is logged and the old listener stays.
   *
   * @param wanted the address of the new configuration, if any
   */
  public synchronized void reload(final Optional<InetSocketAddress> wanted) {
    final InetSocketAddress old = this.bound;
    if (wanted.equals(Optional.ofNullable(old))) {
      return;
    }
    if (wanted.isPresent()) {
      try {
        this.binder.accept(wanted.get());
      } catch (Exception e) {
        LOGGER.error("Can't bind the {} listener to {}, keeping {}", this.name, wanted.get(),
            old == null ? "no listener" : old, e);
        return;
      }
      this.bound = wanted.get();
    } else {
      this.bound = null;
    }
    if (old != null) {
      this.closer.accept(old);
    }
  }

  /**
   * The address this listener is bound to right now.
   *
   * @return the bound address, or empty
   */
  public synchronized Optional<InetSocketAddress> bound() {
    return Optional.ofNullable(this.bound);
  }
}
