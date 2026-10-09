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
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Who is let in even from a listed address: named players (staff, partners) and networks.
 *
 * <p>A player entry carries a name <em>and</em> a UUID, and both must match. A name alone can be
 * typed by anyone, and a UUID alone is a weak handle on a person (a renamed account, a copied
 * entry); together, once the login is authenticated (see {@link Phase}), they name one account.
 * An entry with only one of them is refused when the configuration is read.
 *
 * <p>An exemption is a hole in the shield, so it is meant to be justified and to end. Every entry
 * may carry a {@code reason} and an {@code expires} date ({@code AAAA-MM-JJ}, the last valid day,
 * UTC). A <em>new</em> network exemption must carry one, as the documentation says: a fixed
 * address changes hands, a player exemption is bound to an account and may stay. An existing
 * network entry without a date is never refused for it: it is honoured and reported with a
 * warning at every load, and listed by {@code /admission status} as undated. An entry already
 * over when the file is read is ignored with a warning, and one that ends while the proxy runs stops counting that day: the date
 * is judged at each decision, not only at start.
 */
public final class Exemptions {

  /** How many days ahead {@link #expiringSoon} looks. */
  public static final int NOTICE_DAYS = 7;

  /** What an exemption is granted to. */
  public enum Kind {
    /** A named player. */
    PLAYER,
    /** An address or a network. */
    NETWORK
  }

  /**
   * One exempted player.
   *
   * @param name the username
   * @param uuid the account UUID
   * @param reason why the player is exempted, or {@code null}
   * @param expires the last day the exemption holds, or {@code null} for no end
   */
  public record Player(String name, UUID uuid, @Nullable String reason,
      @Nullable LocalDate expires) {

    /** A player entry must say who it is, fully. */
    public Player {
      if (name == null || name.isBlank() || uuid == null) {
        throw new IllegalArgumentException("an exempted player needs a name and a UUID");
      }
    }

    /**
     * An exemption with no reason and no end date.
     *
     * @param name the username
     * @param uuid the account UUID
     */
    public Player(final String name, final UUID uuid) {
      this(name, uuid, null, null);
    }
  }

  /**
   * One exempted network.
   *
   * @param cidr the address or CIDR as written in the file
   * @param set the same, ready to look addresses up in
   * @param reason why the network is exempted, or {@code null}
   * @param expires the last day the exemption holds, or {@code null} for an old entry that was
   *     never dated (still honoured, and reported at every load)
   */
  public record Network(String cidr, CidrSet set, @Nullable String reason,
      @Nullable LocalDate expires) {
  }

  private static final String UNDATED_WARNING = "exemption réseau non datée, à dater: add"
      + " 'expires: AAAA-MM-JJ' (the last valid day, UTC). It is still honoured, and reported at"
      + " every load";

  /**
   * An exemption that ends within {@value #NOTICE_DAYS} days, as the staff reads it.
   *
   * @param kind what it is granted to
   * @param label the player's name or the network
   * @param expires its last valid day
   * @param reason why it was granted, or {@code null}
   */
  public record Expiring(Kind kind, String label, LocalDate expires, @Nullable String reason) {
  }

  private static final Exemptions NONE =
      new Exemptions(List.of(), List.of(), Clock.systemUTC());

  private final List<Player> players;
  private final List<Network> networks;
  private final Clock clock;

  /**
   * Creates the exemptions.
   *
   * @param players the exempted players
   * @param networks the exempted networks
   * @param clock the source of "today", read at each decision
   */
  public Exemptions(final List<Player> players, final List<Network> networks, final Clock clock) {
    this.players = List.copyOf(players);
    this.networks = List.copyOf(networks);
    this.clock = clock;
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
   * Reads the exempted players and networks of a configuration.
   *
   * <p>A player needs a name and a UUID; a network needs {@code network}, and should carry
   * {@code expires}. An entry that is unsafe as written (incomplete, or with an end date that
   * cannot be read) is reported to {@code errors} and left out, so that it exempts nobody; an
   * entry that is merely over is reported to {@code warnings} and left out. A network without an
   * end date is kept and reported to {@code warnings}. The other entries are unaffected.
   *
   * @param playerNodes the {@code exemptions.players} node
   * @param networkNodes the {@code exemptions.networks} node
   * @param clock the source of "today"
   * @param warnings receives one sentence per entry ignored
   * @param errors receives one sentence per entry refused
   * @return the exemptions
   */
  public static Exemptions parse(final ConfigurationNode playerNodes,
      final ConfigurationNode networkNodes, final Clock clock, final Consumer<String> warnings,
      final Consumer<String> errors) {
    final LocalDate today = today(clock);
    final List<Player> players = new ArrayList<>();
    int index = 0;
    for (ConfigurationNode entry : playerNodes.childrenList()) {
      final String where = "exemptions.players[" + index++ + "]";
      final Player player = parsePlayer(entry, where, today, warnings, errors);
      if (player != null) {
        players.add(player);
      }
    }
    final List<Network> networks = new ArrayList<>();
    index = 0;
    for (ConfigurationNode entry : networkNodes.childrenList()) {
      final String where = "exemptions.networks[" + index++ + "]";
      final Network network = parseNetwork(entry, where, today, warnings, errors);
      if (network != null) {
        networks.add(network);
      }
    }
    return new Exemptions(players, networks, clock);
  }

  /**
   * The exempted players read from the file. One that ends while the proxy runs stays in this list
   * but no longer exempts anybody; {@link #activePlayers()} counts those that still do.
   *
   * @return the entries, each with a name and a UUID
   */
  public List<Player> players() {
    return this.players;
  }

  /**
   * The exempted networks read from the file, each with the date it ends; see {@link #players()}
   * for the ones that have ended since.
   *
   * @return the entries
   */
  public List<Network> networks() {
    return this.networks;
  }

  /**
   * Whether the address sits in an exempted network that has not ended.
   *
   * @param address the address being judged
   * @return {@code true} if it is exempted
   */
  public boolean coversAddress(final InetAddress address) {
    final LocalDate today = today(this.clock);
    for (Network network : this.networks) {
      if (isActive(network.expires(), today) && network.set().contains(address)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether an authenticated player is exempted.
   *
   * @param username the username, authenticated
   * @param uuid the account UUID, authenticated
   * @return {@code true} if some entry that has not ended names this player
   */
  public boolean coversPlayer(final String username, final UUID uuid) {
    final LocalDate today = today(this.clock);
    for (Player player : this.players) {
      if (isActive(player.expires(), today) && sameName(player.name(), username)
          && player.uuid().equals(uuid)) {
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
   * @return {@code true} if an entry that has not ended carries this name, so the decision waits
   *     for the login
   */
  public boolean mayCoverLater(final String username) {
    final LocalDate today = today(this.clock);
    for (Player player : this.players) {
      if (isActive(player.expires(), today) && sameName(player.name(), username)) {
        return true;
      }
    }
    return false;
  }

  /**
   * How many exemptions hold today: players and networks that have not ended.
   *
   * @return the number of active entries
   */
  public int activeCount() {
    return activePlayers() + activeNetworks();
  }

  /**
   * How many players are exempted today.
   *
   * @return the number of active player entries
   */
  public int activePlayers() {
    final LocalDate today = today(this.clock);
    return (int) this.players.stream().filter(p -> isActive(p.expires(), today)).count();
  }

  /**
   * How many networks are exempted today.
   *
   * @return the number of active network entries
   */
  public int activeNetworks() {
    final LocalDate today = today(this.clock);
    return (int) this.networks.stream().filter(n -> isActive(n.expires(), today)).count();
  }

  /**
   * The active exemptions whose last valid day falls within the next {@value #NOTICE_DAYS} days,
   * the day itself included.
   *
   * @return the entries, the soonest first
   */
  public List<Expiring> expiringSoon() {
    final LocalDate today = today(this.clock);
    final LocalDate horizon = today.plusDays(NOTICE_DAYS);
    final List<Expiring> soon = new ArrayList<>();
    for (Player player : this.players) {
      addIfSoon(soon, Kind.PLAYER, player.name(), player.expires(), player.reason(), today,
          horizon);
    }
    for (Network network : this.networks) {
      addIfSoon(soon, Kind.NETWORK, network.cidr(), network.expires(), network.reason(), today,
          horizon);
    }
    soon.sort(Comparator.comparing(Expiring::expires).thenComparing(Expiring::label));
    return soon;
  }

  /**
   * The networks exempted with no end date: honoured, but waiting to be dated.
   *
   * @return the entries, in file order
   */
  public List<Network> undatedNetworks() {
    return this.networks.stream().filter(n -> n.expires() == null).toList();
  }

  private static void addIfSoon(final List<Expiring> soon, final Kind kind, final String label,
      final @Nullable LocalDate expires, final @Nullable String reason, final LocalDate today,
      final LocalDate horizon) {
    if (expires != null && isActive(expires, today) && !expires.isAfter(horizon)) {
      soon.add(new Expiring(kind, label, expires, reason));
    }
  }

  private static @Nullable Player parsePlayer(final ConfigurationNode entry, final String where,
      final LocalDate today, final Consumer<String> warnings, final Consumer<String> errors) {
    if (!entry.isMap()) {
      errors.accept(where + ": '" + entry.getString("") + "' is a bare value; an exemption"
          + " needs both 'name' and 'uuid'. Entry REFUSED, it exempts nobody");
      return null;
    }
    final String name = entry.node("name").getString("").trim();
    final String rawUuid = entry.node("uuid").getString("").trim();
    final UUID uuid = rawUuid.isEmpty() ? null : parseUuid(rawUuid);
    if (name.isEmpty() && uuid != null) {
      errors.accept(where + ": a UUID alone is not an exemption; add the player's 'name'."
          + " Entry REFUSED, it exempts nobody");
      return null;
    }
    if (name.isEmpty() || uuid == null) {
      errors.accept(where + ": an exemption needs both 'name' and a valid 'uuid'. Entry"
          + " REFUSED, it exempts nobody");
      return null;
    }
    final String reason = reasonOf(entry);
    final LocalDate expires;
    try {
      expires = expiryOf(entry);
    } catch (DateTimeParseException unreadable) {
      errors.accept(where + " (" + name + "): " + badExpiry(entry));
      return null;
    }
    if (expires != null && !isActive(expires, today)) {
      warnings.accept(where + " (" + name + "): ended on " + expires + describe(reason)
          + "; ignored. Remove it from the file, or give it a later 'expires'");
      return null;
    }
    return new Player(name, uuid, reason, expires);
  }

  private static @Nullable Network parseNetwork(final ConfigurationNode entry, final String where,
      final LocalDate today, final Consumer<String> warnings, final Consumer<String> errors) {
    // The bare form ('203.0.113.0/28') is the old way of writing it, and it is still honoured: a
    // file that exempted an office yesterday keeps exempting it. The date is required of NEW
    // entries by the documentation, never enforced by refusing one that exists.
    final String cidr = (entry.isMap() ? entry.node("network") : entry).getString("").trim();
    if (cidr.isEmpty()) {
      errors.accept(where + ": a network exemption needs 'network' (an address or CIDR) and"
          + " 'expires' (AAAA-MM-JJ). Entry REFUSED, it exempts nobody");
      return null;
    }
    final LocalDate expires;
    try {
      expires = entry.isMap() ? expiryOf(entry) : null;
    } catch (DateTimeParseException unreadable) {
      errors.accept(where + " (" + cidr + "): " + badExpiry(entry));
      return null;
    }
    final CidrSet.Builder builder = CidrSet.builder();
    if (!builder.add(cidr)) {
      warnings.accept(where + ": '" + AdmissionJournal.value(cidr)
          + "' is not an address or CIDR; ignored");
      return null;
    }
    final String reason = entry.isMap() ? reasonOf(entry) : null;
    if (expires == null) {
      warnings.accept(where + " (" + cidr + "): " + UNDATED_WARNING);
    } else if (!isActive(expires, today)) {
      warnings.accept(where + " (" + cidr + "): ended on " + expires + describe(reason)
          + "; ignored. Remove it from the file, or give it a later 'expires'");
      return null;
    }
    return new Network(cidr, builder.build(), reason, expires);
  }

  private static @Nullable String reasonOf(final ConfigurationNode entry) {
    final String reason = entry.node("reason").getString("").trim();
    return reason.isEmpty() ? null : reason;
  }

  /**
   * The date of an {@code expires} key. YAML may hand an unquoted {@code 2026-12-31} over as a
   * timestamp object, so both forms are read.
   */
  private static @Nullable LocalDate expiryOf(final ConfigurationNode entry)
      throws DateTimeParseException {
    final ConfigurationNode node = entry.node("expires");
    if (node.virtual() || node.raw() == null) {
      return null;
    }
    if (node.raw() instanceof Date timestamp) {
      return timestamp.toInstant().atOffset(ZoneOffset.UTC).toLocalDate();
    }
    if (node.raw() instanceof LocalDate date) {
      return date;
    }
    return LocalDate.parse(node.getString("").trim());
  }

  private static String badExpiry(final ConfigurationNode entry) {
    return "'expires' must be a date written AAAA-MM-JJ (for example 2026-12-31), got '"
        + AdmissionJournal.value(String.valueOf(entry.node("expires").raw()))
        + "'. Entry REFUSED, it exempts nobody";
  }

  private static String describe(final @Nullable String reason) {
    return reason == null ? "" : " (reason: " + AdmissionJournal.value(reason) + ")";
  }

  private static @Nullable UUID parseUuid(final String text) {
    try {
      return UUID.fromString(text.trim());
    } catch (IllegalArgumentException malformed) {
      return null;
    }
  }

  /** The day an exemption is judged on: UTC, whatever zone the clock carries. */
  private static LocalDate today(final Clock clock) {
    return LocalDate.now(clock.withZone(ZoneOffset.UTC));
  }

  private static boolean isActive(final @Nullable LocalDate expires, final LocalDate today) {
    return expires == null || !today.isAfter(expires);
  }

  private static boolean sameName(final String left, final String right) {
    return left.toLowerCase(Locale.ROOT).equals(right.toLowerCase(Locale.ROOT));
  }
}
