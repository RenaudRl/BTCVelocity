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

package com.btcvelocity.clientpolicy;

/**
 * Where a session comes from, as far as the proxy can measure it.
 *
 * <p>{@link #UNKNOWN} is a real answer, not a default: a proxy without a platform source cannot
 * tell a Bedrock player from a Java one, and the journal must say so rather than conclude
 * "no mod".
 */
public enum ClientOrigin {
  JAVA,
  BEDROCK,
  UNKNOWN;

  /**
   * Whether the client policy applies: only a session measured as Bedrock is out of scope, since
   * none of the mods concerned exist there.
   *
   * @return {@code false} for Bedrock
   */
  public boolean isPolicyApplicable() {
    return this != BEDROCK;
  }

  /**
   * Whether a session may be refused: only when it is <em>established</em> as Java. An origin the
   * proxy could not measure might be a Bedrock player, and refusing one of those for a mod they
   * cannot have is worse than letting a cheat client through this one door.
   *
   * @return {@code true} for Java only
   */
  public boolean isRefusable() {
    return this == JAVA;
  }
}
