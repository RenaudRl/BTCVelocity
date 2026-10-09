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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The answer to {@code /admission why <pseudo|ip>}, as plain lines for the staff.
 *
 * <p>Kept apart from the command so that it is testable and so that the wording lives in one place.
 */
public final class WhyReport {

  private WhyReport() {
  }

  /**
   * Explains the latest known decision about a player or an address.
   *
   * @param query a username or an IP address literal
   * @param journal the remembered decisions
   * @param classifier the lists, to say what an address is classed as <em>now</em>
   * @return the lines to show
   */
  public static List<String> explain(
      final String query, final AdmissionJournal journal, final IpClassifier classifier) {
    return explainWith(query, journal, classifier, null, AsnSettings.defaults(),
        AdmissionConfig.Mode.OBSERVE);
  }

  /**
   * Explains the latest known decision about a player or an address, and names the autonomous
   * system of an address as the database knows it now, with what the {@code asn} settings do
   * about it.
   *
   * @param query a username or an IP address literal
   * @param journal the remembered decisions
   * @param classifier the lists and the denied systems, to say what an address is classed as
   *     <em>now</em>
   * @param asn the IP-to-ASN database
   * @param settings the {@code asn} section
   * @param asnMode the mode the denied systems really run in ({@link AdmissionConfig#asnMode()}:
   *     the module's {@code observe} wins over {@code asn.mode})
   * @return the lines to show
   */
  public static List<String> explain(final String query, final AdmissionJournal journal,
      final IpClassifier classifier, final AsnLookup asn, final AsnSettings settings,
      final AdmissionConfig.Mode asnMode) {
    return explainWith(query, journal, classifier, asn, settings, asnMode);
  }

  private static List<String> explainWith(final String query, final AdmissionJournal journal,
      final IpClassifier classifier, final @Nullable AsnLookup asn, final AsnSettings settings,
      final AdmissionConfig.Mode asnMode) {
    final List<String> lines = new ArrayList<>();
    final InetAddress address = asAddress(query);
    final Optional<AdmissionJournal.Entry> last = address != null
        ? journal.lastForAddress(address) : journal.lastForName(query);

    if (last.isPresent()) {
      lines.add("Dernière décision connue pour " + query + " :");
      lines.add(last.get().line());
    } else {
      lines.add("Aucune décision mémorisée pour " + query + ". Seuls les refus, les"
          + " observations et les exemptions sont gardés ; un pseudo n'est retrouvable"
          + " qu'une fois authentifié (au login) : un refus de pré-login se cherche par IP."
          + " La mémoire est bornée et vidée au redémarrage.");
    }
    if (address != null) {
      final Optional<IpClassifier.Match> now = classifier.classify(address);
      lines.add(now.map(match -> "Classement actuel de l'adresse : " + match.ipClass().key()
              + " (liste " + match.sourceId() + ").")
          .orElse("Classement actuel de l'adresse : aucune liste ne la connaît."));
      if (asn != null) {
        lines.add(describeAsn(asn.lookup(address).orElse(null), settings, asnMode));
      }
    }
    return lines;
  }

  /**
   * The autonomous system of an address and what the settings do about it, as one line.
   *
   * @param info the system, or {@code null} when unknown
   * @param settings the {@code asn} section
   * @param asnMode the mode the denied systems really run in
   * @return the line; the registry's name is escaped like any journal value
   */
  static String describeAsn(final AsnDatabase.@Nullable Info info, final AsnSettings settings,
      final AdmissionConfig.Mode asnMode) {
    if (info == null) {
      return "Système autonome : inconnu (adresse non publique, non routée, ou base IP-vers-ASN"
          + " non chargée).";
    }
    final String policy;
    if (settings.denies(info.number())) {
      policy = "AS dans asn.deny-asns (mode " + asnMode.name().toLowerCase(Locale.ROOT) + ")";
    } else {
      policy = settings.keywordIn(info.name())
          .map(word -> "mot-clé d'observation '" + AdmissionJournal.value(word)
              + "' dans le nom (signal staff, jamais un refus)")
          .orElse("aucune règle asn ne s'applique");
    }
    return "Système autonome : AS" + info.number() + " " + AdmissionJournal.value(info.name())
        + (info.country().isEmpty() ? "" : " (" + info.country() + ")") + " ; " + policy + ".";
  }

  private static InetAddress asAddress(final String query) {
    if (query.indexOf('.') < 0 && query.indexOf(':') < 0) {
      return null;
    }
    try {
      return InetAddress.ofLiteral(query);
    } catch (IllegalArgumentException malformed) {
      return null;
    }
  }
}
