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
import java.util.Optional;
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
  private final NetworkRateLimiter rate;
  private final AsnStore asn;

  /**
   * Creates the command.
   *
   * @param journal the remembered decisions
   * @param store the lists in memory
   * @param config the settings in force
   * @param rate the per-network bound on connection attempts
   * @param asn the IP-to-ASN database in memory
   */
  public AdmissionCommand(final AdmissionJournal journal, final ListStore store,
      final AdmissionConfig config, final NetworkRateLimiter rate, final AsnStore asn) {
    this.journal = journal;
    this.store = store;
    this.config = config;
    this.rate = rate;
    this.asn = asn;
  }

  @Override
  public void execute(final Invocation invocation) {
    final String[] arguments = invocation.arguments();
    final String subcommand = arguments.length == 0 ? "" : arguments[0].toLowerCase(Locale.ROOT);
    if (subcommand.equals("why") && arguments.length == 2) {
      final IpClassifier classifier =
          this.store.orElse(new AsnClassifier(this.asn, this.config.asn()));
      for (String line : WhyReport.explain(arguments[1], this.journal, classifier, this.asn,
          this.config.asn())) {
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
    lines.addAll(asnStatus(this.config, this.asn));
    lines.add(rateStatus(this.config, this.rate));
    lines.addAll(exemptionStatus(this.config.exemptions()));
    return lines;
  }

  /**
   * What the module knows of autonomous systems: the database in use and its size, the version
   * waiting in quarantine if any, and what the {@code asn} settings do with it.
   *
   * @param config the settings in force
   * @param asn the database in memory
   * @return the lines to show
   */
  static List<String> asnStatus(final AdmissionConfig config, final AsnStore asn) {
    final AsnSettings settings = config.asn();
    final List<String> lines = new java.util.ArrayList<>();
    if (!settings.enabled()) {
      lines.add("Base IP-vers-ASN : désactivée (asn.enabled: false).");
      return lines;
    }
    final Optional<AsnStore.Loaded> current = asn.current();
    if (current.isEmpty()) {
      lines.add("Base IP-vers-ASN : AUCUNE chargée : les systèmes autonomes ne sont pas nommés"
          + " et asn.deny-asns ne refuse rien.");
    } else {
      final AsnDatabase database = current.get().database();
      lines.add("Base IP-vers-ASN (iptoasn.com, domaine public) : " + current.get().validLines()
          + " lignes, " + database.ipv4RangeCount() + " plages IPv4, " + database.ipv6RangeCount()
          + " plages IPv6, " + database.systemCount() + " systèmes, "
          + (database.approximateBytes() >> 20) + " Mio, chargée " + current.get().loadedAt()
          + ".");
    }
    asn.waitingStatus().ifPresent(waiting -> lines.add(AsnSettings.SOURCE_ID
        + " : nouvelle version (" + waiting.pending().loaded().validLines()
        + " lignes) en quarantaine jusqu'à " + waiting.pending().releaseAt() + ", "
        + waiting.extraPlayers() + " joueur(s) refusé(s) en plus (seuil "
        + config.quarantineMaxExtraPlayers() + ")."));
    lines.add("ASN (" + config.asnMode().name().toLowerCase(Locale.ROOT) + ") : "
        + settings.denyAsns().size() + " AS refusé(s) (code " + IpClass.ASN.causeCode() + "), "
        + settings.observeKeywords().size() + " mot(s)-clé(s) d'observation ; débit regroupé par"
        + " AS d'hébergement : " + (config.networkRate().groupHostingByAsn() ? "oui" : "non")
        + ".");
    return lines;
  }

  /**
   * How many exemptions hold today, and which of them end within
   * {@value Exemptions#NOTICE_DAYS} days.
   *
   * @param exemptions the exemptions in force
   * @return a summary line, then one line per exemption about to end
   */
  static List<String> exemptionStatus(final Exemptions exemptions) {
    final List<Exemptions.Expiring> soon = exemptions.expiringSoon();
    final List<String> lines = new java.util.ArrayList<>();
    lines.add("Exemptions actives : " + exemptions.activeCount() + " ("
        + exemptions.activePlayers() + " joueur(s), " + exemptions.activeNetworks()
        + " réseau(x)), dont " + soon.size() + " expire(nt) sous " + Exemptions.NOTICE_DAYS
        + " jours.");
    for (Exemptions.Expiring ending : soon) {
      lines.add("  expire le " + ending.expires() + " : "
          + (ending.kind() == Exemptions.Kind.PLAYER ? "joueur " : "réseau ")
          + AdmissionJournal.value(ending.label())
          + (ending.reason() == null ? "" : " - " + AdmissionJournal.value(ending.reason())));
    }
    return lines;
  }

  /** One line on the per-network bound; the refusals themselves are read with {@code why <ip>}. */
  static String rateStatus(final AdmissionConfig config, final NetworkRateLimiter rate) {
    final NetworkRateSettings settings = config.networkRate();
    if (!settings.enabled()) {
      return "Débit par réseau : désactivé.";
    }
    return "Débit par réseau (" + config.rateMode().name().toLowerCase(Locale.ROOT)
        + ") : " + settings.attempts() + " tentatives / " + settings.window().toSeconds()
        + " s, rafale " + settings.burst() + ", par /" + settings.ipv4Prefix() + " (IPv4) ou /"
        + settings.ipv6Prefix() + " (IPv6) ; " + rate.trackedNetworks() + " réseau(x) suivi(s)"
        + " sur " + settings.maxNetworks() + " ; " + rate.limitedAttempts()
        + " tentative(s) au-delà de la limite depuis le démarrage (code "
        + Verdict.RATE_NETWORK_CODE + ").";
  }
}
