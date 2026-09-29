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

import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * Decides what a client's declarations earn it against the deny list.
 *
 * <p>A pure function of its inputs so that every branch is testable without a proxy. The order is
 * deliberate: a rule must match first, and only then are the exemptions and the mode consulted, so
 * the journal can tell "nothing matched" from "matched, but exempt".
 */
public final class RefusalPolicy {

  private RefusalPolicy() {
  }

  /** What to do with a session. */
  public enum Action {
    /** No rule matched. */
    NONE,
    /** A rule matched a Bedrock session: out of scope, nothing is done. */
    EXEMPT_BEDROCK,
    /** A rule matched a session whose origin could not be measured: refusal is skipped. */
    EXEMPT_UNKNOWN,
    /** A rule matched a session holding the bypass permission. */
    EXEMPT_BYPASS,
    /** A rule matched in dry-run mode: journal it, do nothing. */
    WOULD_REFUSE,
    /** A rule matched in enforce mode: disconnect. */
    REFUSE
  }

  /**
   * The outcome for one evaluation.
   *
   * @param action what to do
   * @param rule the rule that matched, empty for {@link Action#NONE}
   * @param value the brand or channel that matched, empty for {@link Action#NONE}
   */
  public record Verdict(Action action, Optional<DenyRule> rule, Optional<String> value) {

    static final Verdict NOTHING = new Verdict(Action.NONE, Optional.empty(), Optional.empty());
  }

  /**
   * Evaluates a session against the deny list.
   *
   * @param config the loaded configuration
   * @param origin what the proxy measured of the session's platform
   * @param hasBypass asked only when a rule matched, since a permission lookup has a cost
   * @param brand the declared brand, {@code null} while undeclared
   * @param channels the channels declared so far
   * @return the verdict; the first matching rule in configuration order wins
   */
  public static Verdict evaluate(ClientPolicyConfig config, ClientOrigin origin,
                                 BooleanSupplier hasBypass, String brand, List<String> channels) {
    for (DenyRule rule : config.deny()) {
      Optional<String> value = rule.matches(brand, channels);
      if (value.isEmpty()) {
        continue;
      }
      return new Verdict(actionFor(config.mode(), origin, hasBypass), Optional.of(rule), value);
    }
    return Verdict.NOTHING;
  }

  private static Action actionFor(ClientPolicyConfig.Mode mode, ClientOrigin origin,
                                  BooleanSupplier hasBypass) {
    if (!origin.isPolicyApplicable()) {
      return Action.EXEMPT_BEDROCK;
    }
    if (!origin.isRefusable()) {
      return Action.EXEMPT_UNKNOWN;
    }
    if (hasBypass.getAsBoolean()) {
      return Action.EXEMPT_BYPASS;
    }
    return mode == ClientPolicyConfig.Mode.ENFORCE ? Action.REFUSE : Action.WOULD_REFUSE;
  }
}
