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

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The staff side of the account links: {@code alts}, {@code household add|remove} and
 * {@code forget}, behind their own permission. Every use is logged with who asked before the
 * database is called, then its outcome; never an address (none is stored).
 */
final class LinkCommand {

  /** The permission these subcommands need, beyond the one that opens {@code /admission}. */
  static final String PERMISSION = "btc.admission.links";

  static final List<String> SUBCOMMANDS = List.of("alts", "household", "forget");
  static final String USAGE = "/admission alts <uuid|pseudo en ligne> | household add <a> <b>"
      + " [raison] | household remove <a> | forget <uuid>";

  private final @Nullable AccountLinks links;
  private final Logger logger;
  private final Clock clock;
  private final Function<String, Optional<UUID>> online;
  private final Consumer<String> staff;

  /**
   * Creates the subcommands.
   *
   * @param links the account links, or {@code null} when they are off
   * @param logger where each use is logged
   * @param clock the time
   * @param online the UUID of a player online by name
   * @param staff told when a household is declared around an account under a network ban
   */
  LinkCommand(final @Nullable AccountLinks links, final Logger logger, final Clock clock,
      final Function<String, Optional<UUID>> online, final Consumer<String> staff) {
    this.links = links;
    this.logger = logger;
    this.clock = clock;
    this.online = online;
    this.staff = staff;
  }

  /**
   * Runs one of the subcommands; the answer comes once the database has answered.
   *
   * @param source who asked
   * @param arguments the arguments, the subcommand first
   * @return the answer, done when it has been sent
   */
  CompletableFuture<Void> execute(final CommandSource source, final String[] arguments) {
    if (!source.hasPermission(PERMISSION)) {
      tell(source, "Permission requise : " + PERMISSION + ".", NamedTextColor.RED);
      return CompletableFuture.completedFuture(null);
    }
    final AccountLinks current = this.links;
    if (current == null) {
      tell(source, "Comptes liés : désactivés (links.enabled: false, ou clé absente au"
          + " démarrage).", NamedTextColor.YELLOW);
      return CompletableFuture.completedFuture(null);
    }
    final String subcommand = arguments[0].toLowerCase(Locale.ROOT);
    final String action = arguments.length > 1 ? arguments[1].toLowerCase(Locale.ROOT) : "";
    if (subcommand.equals("alts") && arguments.length == 2) {
      return withAccount(source, arguments[1], account -> alts(source, current, account));
    }
    if (subcommand.equals("forget") && arguments.length == 2) {
      return withAccount(source, arguments[1], account -> {
        audit(source, "asks to erase what is kept about", account);
        // The erasure is never refused for a ban, nor held up by a sanctions base that fails.
        return current.run(store -> new Erased(store.forget(account), bannedAmong(current,
            Set.of(account)))).thenAccept(erased -> {
              audit(source, "erased " + erased.removed() + " row(s) about", account);
              if (!erased.banned().isEmpty()) {
                // Erasing the links of a banned account leaves no trace of its alts: the staff must know.
                this.logger.warn("links: {} erased the links of {} under a network ban.",
                    who(source), erased.banned());
                this.staff.accept("[Admission] Liens effacés par " + who(source)
                    + " pour un compte sous ban réseau actif : " + erased.banned() + ".");
              }
              tell(source, erased.removed() + " ligne(s) effacée(s) pour " + account + ".",
                  NamedTextColor.GRAY);
            });
      });
    }
    if (subcommand.equals("household") && action.equals("remove") && arguments.length == 3) {
      return withAccount(source, arguments[2], account -> {
        audit(source, "asks to take out of its household", account);
        return current.run(store -> store.leaveHousehold(account)).thenAccept(left -> {
          audit(source, left ? "took out of its household" : "found no household for", account);
          tell(source, left ? account + " ne fait plus partie d'un foyer."
              : account + " n'avait pas de foyer.", NamedTextColor.GRAY);
        });
      });
    }
    if (subcommand.equals("household") && action.equals("add") && arguments.length >= 4) {
      final String reason = arguments.length > 4
          ? String.join(" ", Arrays.copyOfRange(arguments, 4, arguments.length)) : null;
      return withAccount(source, arguments[2], first -> withAccount(source, arguments[3],
          second -> household(source, current, first, second, reason)));
    }
    tell(source, "Usage : " + USAGE, NamedTextColor.YELLOW);
    return CompletableFuture.completedFuture(null);
  }

  private CompletableFuture<Void> alts(final CommandSource source, final AccountLinks current,
      final UUID account) {
    audit(source, "asks for the accounts sharing an address with", account);
    return current.run(store -> store.sharingAddressWith(account,
        this.clock.instant().minus(LinkSettings.ADDRESS_HISTORY))).thenAccept(others -> {
          audit(source, "was shown " + others.size() + " account(s) sharing an address with",
              account);
          tell(source, others.size() + " compte(s) vu(s) sur une adresse de " + account + " en "
              + LinkSettings.ADDRESS_HISTORY.toDays() + " jours :", NamedTextColor.GRAY);
          others.forEach((other, seen) -> tell(source, "  " + other + " (vu le " + seen + ")",
              NamedTextColor.GRAY));
        });
  }

  private CompletableFuture<Void> household(final CommandSource source,
      final AccountLinks current, final UUID first, final UUID second,
      final @Nullable String reason) {
    if (first.equals(second)) {
      tell(source, "Un foyer réunit deux comptes différents.", NamedTextColor.YELLOW);
      return CompletableFuture.completedFuture(null);
    }
    audit(source, "asks to declare one household: " + second + " and", first);
    return current.run(store -> {
      if (!store.declareHousehold(first, second, who(source), reason, this.clock.instant())) {
        return new Declaration(false, null);
      }
      // Once the household exists, a failing ban check must not read as a failed declaration.
      return new Declaration(true, bannedAmongOrNull(current, Set.of(first, second)));
    }).thenAccept(declaration -> {
      final Set<UUID> banned = declaration.banned();
      audit(source, !declaration.declared()
          ? "was refused one household (two households already): " + second + " and"
          : banned == null ? "declared one household, ban check failed: " + second + " and"
              : "declared one household: " + second + " and", first);
      if (declaration.declared() && banned == null) {
        tell(source, first + " et " + second + " forment un foyer, mais la vérification des bans"
            + " réseau a échoué : vérifiez à la main qu'aucun des deux n'est sous ban réseau actif.",
            NamedTextColor.YELLOW);
        return;
      }
      if (banned != null && !banned.isEmpty()) {
        // A household is never refused for its banned member: the staff must know.
        this.logger.warn("links: {} declared a household with {} under a network ban.",
            who(source), banned);
        this.staff.accept("[Admission] Foyer déclaré par " + who(source)
            + " avec un compte sous ban réseau actif : " + banned + ".");
      }
      tell(source, declaration.declared() ? first + " et " + second + " forment un foyer :"
          + " jamais refusés l'un pour l'autre." : "Ces deux comptes sont déjà dans deux"
          + " foyers différents : retirez d'abord l'un des deux (household remove).",
          NamedTextColor.GRAY);
    });
  }

  /** What a household declaration did; {@code banned} is null when the ban check failed. */
  private record Declaration(boolean declared, @Nullable Set<UUID> banned) {
  }

  /** What an erasure did, and which of its accounts are under a network ban in force. */
  private record Erased(int removed, Set<UUID> banned) {
  }

  /** The accounts under a network ban in force, or {@code null} when the sanctions cannot be read. */
  private @Nullable Set<UUID> bannedAmongOrNull(final AccountLinks current,
      final Set<UUID> accounts) {
    try {
      return current.bannedAmong(accounts);
    } catch (SQLException | RuntimeException failed) {
      this.logger.warn("links: the ban check of a staff request failed ({}).",
          failed.getClass().getSimpleName());
      return null;
    }
  }

  /** As {@link #bannedAmongOrNull}, an unreadable base counting as no ban to tell. */
  private Set<UUID> bannedAmong(final AccountLinks current, final Set<UUID> accounts) {
    final Set<UUID> banned = bannedAmongOrNull(current, accounts);
    return banned == null ? Set.of() : banned;
  }

  private CompletableFuture<Void> withAccount(final CommandSource source, final String given,
      final Function<UUID, CompletableFuture<Void>> then) {
    final Optional<UUID> account = account(given);
    if (account.isEmpty()) {
      tell(source, "Compte inconnu : " + AdmissionJournal.value(given) + " (un UUID, ou le pseudo"
          + " d'un joueur en ligne).", NamedTextColor.YELLOW);
      return CompletableFuture.completedFuture(null);
    }
    return then.apply(account.get()).exceptionally(failure -> {
      this.logger.warn("links: a staff request failed: {}", failure.toString());
      tell(source, "La base des comptes liés ne répond pas ; réessayez plus tard.",
          NamedTextColor.RED);
      return null;
    });
  }

  private Optional<UUID> account(final String given) {
    try {
      return Optional.of(UUID.fromString(given));
    } catch (IllegalArgumentException notUuid) {
      return this.online.apply(given);
    }
  }

  private void audit(final CommandSource source, final String what, final UUID account) {
    this.logger.info("links: {} {} {}.", who(source), what, account);
  }

  private static String who(final CommandSource source) {
    return source instanceof Player player ? player.getUniqueId().toString() : "console";
  }

  private static void tell(final CommandSource source, final String text,
      final NamedTextColor color) {
    source.sendMessage(Component.text(text, color));
  }
}
