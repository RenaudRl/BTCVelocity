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
import java.util.Optional;
import java.util.UUID;

/**
 * Where the proxy learns which client platform a live session actually uses.
 *
 * <p>Two questions, not one, and the difference is the whole point. {@link #canResolve()} asks
 * whether this proxy is able to answer at all; {@link #platformOf(UUID)} asks about one player.
 * Collapsing them would make "no source installed" indistinguishable from "that player is not
 * online", and those call for opposite conduct: the first means the proxy must stop pretending to
 * verify anything, the second means a specific claim cannot be checked and must be refused.
 *
 * <p>No implementation may derive a platform from a UUID shape, a username prefix, or anything the
 * client declares. Those are guesses wearing the clothes of a measurement.
 */
public interface PlatformSource {

  /** A proxy with nothing able to answer the question. It says so rather than guessing. */
  PlatformSource UNAVAILABLE = new PlatformSource() {
    @Override
    public boolean canResolve() {
      return false;
    }

    @Override
    public Optional<BridgeMessage.Platform> platformOf(final UUID player) {
      return Optional.empty();
    }

    @Override
    public String describe() {
      return "unavailable (no platform source installed)";
    }
  };

  /**
   * Whether this proxy can resolve platforms at all.
   *
   * <p>Stable for the life of the proxy: it describes what is installed, not what is known about
   * any given player.
   */
  boolean canResolve();

  /**
   * The platform of a live session, or empty when this player has no live session here — or when
   * nothing can resolve platforms at all.
   */
  Optional<BridgeMessage.Platform> platformOf(UUID player);

  /** A short, secret-free description for the startup announcement. */
  String describe();
}
