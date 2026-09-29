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

import java.net.InetAddress;
import org.jspecify.annotations.Nullable;

/**
 * The outcome of judging one connection.
 *
 * @param action what to do with the connection
 * @param basis why
 * @param origin how the connection reached the proxy
 * @param judged the address that was judged, or {@code null} when none was (Geyser's own address
 *     is never judged as a Bedrock player's)
 * @param ipClass the class the address was found in, or {@code null}
 * @param sourceId the list that named that class, or {@code null}
 */
public record Verdict(
    Action action,
    Basis basis,
    Origin origin,
    @Nullable InetAddress judged,
    @Nullable IpClass ipClass,
    @Nullable String sourceId) {

  /** What happens to the connection. */
  public enum Action {
    /** Let it in. */
    ADMIT,
    /** Turn it away. */
    REFUSE,
    /** It would have been turned away, but the module is in observe mode: let it in. */
    OBSERVE,
    /** Not decidable yet; the login will decide. Nothing is written for it. */
    DEFER
  }

  /** How the connection reached the proxy. */
  public enum Origin {
    /** From the address it is judged on. */
    DIRECT("direct"),
    /** From a Geyser address, at the pre-login: Floodgate cannot be asked yet. */
    GEYSER_UNCONFIRMED("geyser-unconfirmed"),
    /** From a Geyser address, by a player Floodgate knows. */
    GEYSER_FLOODGATE("geyser-floodgate"),
    /** From a Geyser address, but not a Floodgate player: an anomaly, judged like anyone. */
    GEYSER_ADDRESS_NOT_FLOODGATE("geyser-address-not-floodgate");

    private final String label;

    Origin(final String label) {
      this.label = label;
    }

    /**
     * The words written in the journal.
     *
     * @return the label
     */
    public String label() {
      return this.label;
    }
  }

  /** The reason behind the action, which is also what the journal states. */
  public enum Basis {
    /** The address is in no list. Nothing is written. */
    CLEAN("clean"),
    /** The address is in a list of a refused class. */
    LISTED("listed"),
    /** The address is listed but sits in an exempted network. */
    EXEMPT_NETWORK("exempt-network"),
    /** The address is listed but the authenticated player is exempted. */
    EXEMPT_PLAYER("exempt-player"),
    /** The connection comes from Geyser: its address is not judged, Floodgate's is awaited. */
    GEYSER_ORIGIN("geyser-origin"),
    /** A Bedrock player whose real address is unknown: admitted, and said so. */
    BEDROCK_IP_UNKNOWN("bedrock, ip réelle inconnue"),
    /** From a Geyser address without being a Floodgate player, and not listed. */
    GEYSER_ADDRESS_NOT_FLOODGATE("geyser-address-not-floodgate"),
    /** Listed, and a nominative exemption might cover the player once authenticated. */
    AWAITING_IDENTITY("awaiting-identity");

    private final String label;

    Basis(final String label) {
      this.label = label;
    }

    /**
     * The words written in the journal.
     *
     * @return the label
     */
    public String label() {
      return this.label;
    }
  }

  /**
   * Whether this verdict is worth a journal line and an entry in the "why" memory.
   *
   * <p>Every refusal and every observation, as required, plus the admissions that a person
   * reading the journal would otherwise wonder about: a listed address let in by an exemption,
   * a Bedrock player nobody could judge, a Geyser address that is not a Floodgate player.
   *
   * @return {@code true} if the verdict is written
   */
  public boolean isJournaled() {
    return switch (this.basis) {
      case LISTED, EXEMPT_NETWORK, EXEMPT_PLAYER, BEDROCK_IP_UNKNOWN,
          GEYSER_ADDRESS_NOT_FLOODGATE -> true;
      case CLEAN, GEYSER_ORIGIN, AWAITING_IDENTITY -> false;
    };
  }
}
