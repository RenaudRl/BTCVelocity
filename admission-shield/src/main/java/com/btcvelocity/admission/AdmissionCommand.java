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

import com.velocitypowered.api.command.SimpleCommand;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * {@code /admission why <pseudo|ip>} and {@code /admission status}, for the staff.
 *
 * <p>Read-only: the command explains decisions, it cannot change one.
 */
public final class AdmissionCommand implements SimpleCommand {

  /** The permission that opens the command. The console always passes. */
  public static final String PERMISSION = "btc.admission.staff";

  private static final List<String> SUBCOMMANDS = List.of("why", "status");

  private final AdmissionJournal journal;
  private final ListStore store;
  private final AdmissionConfig config;

  /**
   * Creates the command.
   *
   * @param journal the remembered decisions
   * @param store the lists in memory
   * @param config the settings in force
   */
  public AdmissionCommand(
      final AdmissionJournal journal, final ListStore store, final AdmissionConfig config) {
    this.journal = journal;
    this.store = store;
    this.config = config;
  }

  @Override
  public void execute(final Invocation invocation) {
    final String[] arguments = invocation.arguments();
    final String subcommand = arguments.length == 0 ? "" : arguments[0].toLowerCase(Locale.ROOT);
    if (subcommand.equals("why") && arguments.length == 2) {
      for (String line : WhyReport.explain(arguments[1], this.journal, this.store)) {
        invocation.source().sendMessage(Component.text(line, NamedTextColor.GRAY));
      }
    } else if (subcommand.equals("status") && arguments.length == 1) {
      for (String line : status()) {
        invocation.source().sendMessage(Component.text(line, NamedTextColor.GRAY));
      }
    } else {
      invocation.source().sendMessage(Component.text(
          "Usage : /admission why <pseudo|ip> | /admission status", NamedTextColor.YELLOW));
    }
  }

  @Override
  public List<String> suggest(final Invocation invocation) {
    return invocation.arguments().length <= 1 ? SUBCOMMANDS : List.of();
  }

  @Override
  public boolean hasPermission(final Invocation invocation) {
    return invocation.source().hasPermission(PERMISSION);
  }

  private List<String> status() {
    final List<String> lines = new java.util.ArrayList<>();
    lines.add("Mode : " + this.config.mode().name().toLowerCase(Locale.ROOT)
        + " ; rafraîchissement : " + this.config.refreshInterval().toHours() + " h.");
    if (!this.store.hasAnyList()) {
      lines.add("AUCUNE liste chargée : le module est ouvert, rien n'est filtré.");
    }
    for (ListStore.Loaded list : this.store.snapshot()) {
      lines.add(list.source().id() + " (" + list.source().ipClass().key() + ") : "
          + list.validLines() + " entrées, " + list.invalidLines()
          + " lignes invalides, chargée " + list.loadedAt() + ".");
    }
    for (ListQuarantine.Status waiting : this.store.quarantine().waiting()) {
      lines.add(waiting.pending().list().source().id() + " : nouvelle version ("
          + waiting.pending().list().validLines() + " entrées) en quarantaine jusqu'à "
          + waiting.pending().releaseAt() + ", " + waiting.extraPlayers()
          + " joueur(s) refusé(s) en plus (seuil " + this.config.quarantineMaxExtraPlayers()
          + ").");
    }
    return lines;
  }
}
