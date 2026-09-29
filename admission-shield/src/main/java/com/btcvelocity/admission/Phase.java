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

package com.btcvelocity.admission;

/**
 * Where in the connection a decision is taken.
 *
 * <p>The earlier, the cheaper for the proxy: a refusal at the pre-login costs no round trip to
 * Mojang. But the pre-login only knows the address and a username <em>claimed</em> by the client;
 * the login knows the authenticated identity and, for Bedrock, the address Floodgate recorded.
 * Whatever the pre-login cannot settle, it defers to the login.
 */
public enum Phase {

  /** Before authentication: the address and an unproven username. */
  PRE_LOGIN("pre-login"),

  /** After authentication: the account UUID, and the real address of a Bedrock player. */
  LOGIN("login");

  private final String label;

  Phase(final String label) {
    this.label = label;
  }

  /**
   * The name written in the journal.
   *
   * @return the label
   */
  public String label() {
    return this.label;
  }
}
