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
import org.jspecify.annotations.Nullable;

/**
 * Who is let in even from a listed address: named players (staff, partners) and networks.
 *
 * <p>A player entry carries a name, a UUID, or both. An entry with both requires both, which is
 * the strongest form: a name alone can be typed by anyone before the login is authenticated,
 * so the name is only ever trusted after authentication (see {@link Phase}).
 */
public final class Exemptions {

  /**
   * One exempted player.
   *
   * @param name the username, or {@code null} to match any name
   * @param uuid the account UUID, or {@code null} to match any account
   */
  public record Player(@Nullable String name, @Nullable UUID uuid) {

    /** A player entry must say who it is: an entry with neither would exempt everybody. */
    public Player {
      if (name == null && uuid == null) {
        throw new IllegalArgumentException("an exempted player needs a name or a UUID");
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
      final boolean nameMatches = player.name() == null || sameName(player.name(), username);
      final boolean uuidMatches = player.uuid() == null || player.uuid().equals(uuid);
      if (nameMatches && uuidMatches) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether some entry could cover this player once authenticated, so that a refusal must wait
   * for the login rather than fall at the pre-login.
   *
   * <p>True when an entry names this username, or when an entry names a UUID only: a UUID is not
   * known before authentication, so every login is a candidate for it.
   *
   * @param username the username claimed at the pre-login, not yet authenticated
   * @return {@code true} if the decision must be deferred to the login
   */
  public boolean mayCoverLater(final String username) {
    for (Player player : this.players) {
      if (player.name() == null || sameName(player.name(), username)) {
        return true;
      }
    }
    return false;
  }

  private static boolean sameName(final String left, final String right) {
    return left.toLowerCase(Locale.ROOT).equals(right.toLowerCase(Locale.ROOT));
  }
}
