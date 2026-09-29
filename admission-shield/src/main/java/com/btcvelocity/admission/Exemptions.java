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
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Who is let in even from a listed address: named players (staff, partners) and networks.
 *
 * <p>A player entry carries a name <em>and</em> a UUID, and both must match. A name alone can be
 * typed by anyone, and a UUID alone is a weak handle on a person (a renamed account, a copied
 * entry); together, once the login is authenticated (see {@link Phase}), they name one account.
 * An entry with only one of them is refused when the configuration is read.
 */
public final class Exemptions {

  /**
   * One exempted player.
   *
   * @param name the username
   * @param uuid the account UUID
   */
  public record Player(String name, UUID uuid) {

    /** A player entry must say who it is, fully. */
    public Player {
      if (name == null || name.isBlank() || uuid == null) {
        throw new IllegalArgumentException("an exempted player needs a name and a UUID");
      }
    }
  }

  private static final Exemptions NONE = new Exemptions(List.of(), CidrSet.empty());

  private final List<Player> players;
  private final CidrSet networks;

  /**
   * Creates the exemptions.
   *
   * @param players the exempted players
   * @param networks the exempted networks
   */
  public Exemptions(final List<Player> players, final CidrSet networks) {
    this.players = List.copyOf(players);
    this.networks = networks;
  }

  /**
   * No exemption at all.
   *
   * @return the empty exemptions
   */
  public static Exemptions none() {
    return NONE;
  }

  /**
   * The exempted players.
   *
   * @return the entries, each with a name and a UUID
   */
  public List<Player> players() {
    return this.players;
  }

  /**
   * Whether the address sits in an exempted network.
   *
   * @param address the address being judged
   * @return {@code true} if it is exempted
   */
  public boolean coversAddress(final InetAddress address) {
    return this.networks.contains(address);
  }

  /**
   * Whether an authenticated player is exempted.
   *
   * @param username the username, authenticated
   * @param uuid the account UUID, authenticated
   * @return {@code true} if some entry names this player
   */
  public boolean coversPlayer(final String username, final UUID uuid) {
    for (Player player : this.players) {
      if (sameName(player.name(), username) && player.uuid().equals(uuid)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether some entry could cover this player once authenticated, so that a refusal must wait
   * for the login rather than fall at the pre-login.
   *
   * @param username the username claimed at the pre-login, not yet authenticated
   * @return {@code true} if an entry carries this name, so the decision waits for the login
   */
  public boolean mayCoverLater(final String username) {
    for (Player player : this.players) {
      if (sameName(player.name(), username)) {
        return true;
      }
    }
    return false;
  }

  private static boolean sameName(final String left, final String right) {
    return left.toLowerCase(Locale.ROOT).equals(right.toLowerCase(Locale.ROOT));
  }
}
