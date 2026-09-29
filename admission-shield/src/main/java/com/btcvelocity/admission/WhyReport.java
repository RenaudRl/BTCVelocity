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
import java.util.Optional;

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
    final List<String> lines = new ArrayList<>();
    final InetAddress address = asAddress(query);
    final Optional<AdmissionJournal.Entry> last = address != null
        ? journal.lastForAddress(address) : journal.lastForName(query);

    if (last.isPresent()) {
      lines.add("Dernière décision connue pour " + query + " :");
      lines.add(last.get().line());
    } else {
      lines.add("Aucune décision mémorisée pour " + query + ". Seuls les refus, les"
          + " observations et les exemptions sont gardés ; la mémoire est bornée et"
          + " vidée au redémarrage.");
    }
    if (address != null) {
      final Optional<IpClassifier.Match> now = classifier.classify(address);
      lines.add(now.map(match -> "Classement actuel de l'adresse : " + match.ipClass().key()
              + " (liste " + match.sourceId() + ").")
          .orElse("Classement actuel de l'adresse : aucune liste ne la connaît."));
    }
    return lines;
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
