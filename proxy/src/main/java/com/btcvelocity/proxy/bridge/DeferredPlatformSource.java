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

package com.btcvelocity.proxy.bridge;

import com.btcvelocity.api.bridge.BridgeMessage;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

/**
 * A platform source that asks the question late, because asking it early gives the wrong answer.
 *
 * <p>Measured on the bench, 17/09: the bridge channel is built while the proxy is still starting,
 * <em>before</em> {@code Loading plugins...}. A source resolved in the constructor therefore found
 * no Floodgate on a proxy that had Floodgate, and the proxy announced it could not resolve
 * platforms — correctly, for what it could see, and wrongly for the machine it was running on.
 *
 * <p>The fix is not merely "resolve later", it is to distinguish two failures that look identical
 * at a single point in time:
 *
 * <ul>
 *   <li><b>the class is absent</b> — a fact about this deployment; it will not change while the
 *       proxy runs, so it is remembered once and never asked again;</li>
 *   <li><b>the class is there but the plugin has not finished starting</b> — a fact about
 *       <em>now</em>; remembering it would freeze a temporary state into a permanent verdict, which
 *       is the very bug this class exists to undo.</li>
 * </ul>
 *
 * <p>So a success is memoised, a definitive absence is memoised, and "not yet" is not.
 */
public final class DeferredPlatformSource implements PlatformSource {

  /** What a resolution attempt found. */
  public record Attempt(@Nullable PlatformSource source, boolean definitive) {
    public static Attempt found(final PlatformSource source) {
      return new Attempt(Objects.requireNonNull(source, "source"), true);
    }

    /** Nothing here, and nothing will be: remember it. */
    public static Attempt absentForGood() {
      return new Attempt(null, true);
    }

    /** Nothing <em>yet</em>: ask again next time. */
    public static Attempt notYet() {
      return new Attempt(null, false);
    }
  }

  private final Supplier<Attempt> resolver;
  private final AtomicReference<@Nullable PlatformSource> settled = new AtomicReference<>();

  public DeferredPlatformSource(final Supplier<Attempt> resolver) {
    this.resolver = Objects.requireNonNull(resolver, "resolver");
  }

  private PlatformSource current() {
    final PlatformSource known = settled.get();
    if (known != null) {
      return known;
    }
    final Attempt attempt = resolver.get();
    if (!attempt.definitive()) {
      // Still starting. Answer honestly for now, and keep the question open.
      return PlatformSource.UNAVAILABLE;
    }
    final PlatformSource resolved =
        attempt.source() == null ? PlatformSource.UNAVAILABLE : attempt.source();
    settled.compareAndSet(null, resolved);
    return settled.get();
  }

  @Override
  public boolean canResolve() {
    return current().canResolve();
  }

  @Override
  public Optional<BridgeMessage.Platform> platformOf(final UUID player) {
    return current().platformOf(player);
  }

  @Override
  public String describe() {
    return current().describe();
  }
}
