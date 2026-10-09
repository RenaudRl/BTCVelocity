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

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Applies the deny list to a session and acts on the verdict: journal, and disconnect only in
 * {@code enforce} mode for an established Java origin.
 *
 * <p>Split from the plugin so that the wiring between the verdict and the disconnect can be
 * tested with plain lambdas, without a proxy.
 */
final class SessionEnforcer {

  private SessionEnforcer() {
  }

  /**
   * Evaluates a session and acts.
   *
   * @param config the loaded configuration
   * @param who the session, for the journal
   * @param origin what the proxy measured of the platform
   * @param session the declarations so far
   * @param hasBypass asked only when a rule matched
   * @param info receives the journal lines that are plain information
   * @param warn receives the journal line of an actual refusal
   * @param disconnect receives the MiniMessage text to show a refused player
   * @return the verdict, whether or not it was reported
   */
  static RefusalPolicy.Verdict apply(ClientPolicyConfig config, ObservationLog.Who who,
                                     ClientOrigin origin, ClientSession session,
                                     BooleanSupplier hasBypass, Consumer<String> info,
                                     Consumer<String> warn, Consumer<String> disconnect) {
    if (config.deny().isEmpty()) {
      return RefusalPolicy.Verdict.NOTHING;
    }
    ClientSession.Snapshot snapshot = session.snapshot();
    RefusalPolicy.Verdict verdict = RefusalPolicy.evaluate(config, origin, hasBypass,
        snapshot.brand(), snapshot.channels());
    if (verdict.action() == RefusalPolicy.Action.NONE
        || !session.markRuleReported(verdict.rule().orElseThrow().label())) {
      return verdict;
    }

    String line = ObservationLog.verdict(who, origin, verdict, config.mode());
    if (verdict.action() == RefusalPolicy.Action.REFUSE) {
      warn.accept(line);
      disconnect.accept(verdict.rule().get().message());
    } else {
      info.accept(line);
    }
    return verdict;
  }
}
