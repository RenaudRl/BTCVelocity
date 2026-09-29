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

package com.btcvelocity.clientpolicy;

import java.util.ArrayList;
import java.util.List;

/** The text {@code /clientpolicy <player>} answers with, as plain lines. */
final class ProfileView {

  private ProfileView() {
  }

  /**
   * Renders a session's declarations for the staff.
   *
   * @param name the account name
   * @param origin what the proxy measured of the platform
   * @param snapshot the declarations, {@code null} when the session declared nothing yet
   * @param verdict what the deny list says of these declarations, ignoring any bypass
   * @return the lines to print, sanitised
   */
  static List<String> render(String name, ClientOrigin origin, ClientSession.Snapshot snapshot,
                             RefusalPolicy.Verdict verdict) {
    List<String> lines = new ArrayList<>();
    lines.add("Client of " + name + " (origin " + origin + ")");
    if (!origin.isPolicyApplicable()) {
      lines.add("origin BEDROCK, client policy n/a");
      return lines;
    }
    if (snapshot == null || snapshot.brand() == null && snapshot.channels().isEmpty()) {
      lines.add("Nothing declared yet.");
      return lines;
    }

    lines.add("Brand: " + ObservationLog.quote(snapshot.brand()));
    lines.add("Channels (" + snapshot.channels().size() + "): " + (snapshot.channels().isEmpty()
        ? "none" : snapshot.channels().stream().limit(40).map(ObservationLog::quote)
            .reduce((a, b) -> a + ", " + b).orElse("")));
    if (snapshot.droppedChannels() > 0) {
      lines.add("Channel cap reached: " + snapshot.droppedChannels() + " further channel(s) not kept.");
    }
    if (ClientCoherence.isVanillaWithModChannels(snapshot.brand(), snapshot.channels())) {
      lines.add("Inconsistent: brand vanilla but non-minecraft channels registered"
          + " (a clue, not proof; no action is taken).");
    }
    lines.add(verdict.rule().map(rule -> "Deny list: rule " + ObservationLog.quote(rule.label())
        + " matches " + ObservationLog.quote(verdict.value().orElse("")) + " (" + verdict.action()
        + ")").orElse("Deny list: no rule matches."));
    return lines;
  }
}
