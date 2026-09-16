/*
 * Copyright (C) 2018-2026 Velocity Contributors
 *
 * The Velocity API is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package com.btcvelocity.api.bridge;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The one place a plugin can tell the bridge how to resolve a player's client platform.
 *
 * <p>It exists because of a wall measured on the bench, 17/09: the bridge lives in the proxy
 * <em>core</em>, and a plugin's classes are loaded by a child classloader the core cannot see. The
 * core therefore cannot reach Floodgate at all — not as a matter of style, but by construction. The
 * dependency has to run the other way: a plugin, which does see both the API and Floodgate,
 * installs a source here and the core reads it.
 *
 * <p>Static on purpose, and this is the one place it is warranted: there is a single proxy per JVM,
 * the core cannot be handed a reference by a plugin it does not know about, and Velocity has no
 * service registry to pass one through. Everything else about the platform — the decision, the
 * refusals, the wire format — stays an ordinary object that tests construct directly.
 *
 * <p>Reads are cheap and lock-free: the bridge asks on every player-scoped message.
 */
public final class BridgePlatformSources {

  private static final AtomicReference<PlatformSource> INSTALLED =
      new AtomicReference<>(PlatformSource.UNAVAILABLE);

  private BridgePlatformSources() {
  }

  /**
   * Installs the source the bridge will use from now on.
   *
   * <p>Called by a plugin once it knows Floodgate is there. Installing twice replaces the previous
   * source rather than failing: a proxy that reloads a plugin must end up with the live one, not
   * with whichever arrived first.
   *
   * @param source how a live session's platform is measured
   */
  public static void install(final PlatformSource source) {
    INSTALLED.set(Objects.requireNonNull(source, "source"));
  }

  /**
   * The source in use, never {@code null}.
   *
   * <p>Defaults to {@link PlatformSource#UNAVAILABLE}: a proxy where no plugin installed anything
   * states that it cannot resolve, rather than quietly assuming everyone is Java.
   */
  public static PlatformSource current() {
    return INSTALLED.get();
  }

  /** Puts the registry back to "nothing installed". For tests, and for a plugin shutting down. */
  public static void reset() {
    INSTALLED.set(PlatformSource.UNAVAILABLE);
  }
}
