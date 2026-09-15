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

package com.btcvelocity.arrival;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Which servers may receive an arriving player, and which one of them wins.
 *
 * <p>The policy is the proxy's own, declared next to the proxy and never asked of the node: the
 * orchestrator declares what runs, this decides where a player goes. It is a pure value — no
 * Velocity type, no CloudNet type — so the rule can be tested without a runtime.
 *
 * <p>A pattern is either an exact server name or a prefix ending in {@code *}. CloudNet names a
 * service {@code <task>-<n>}, so {@code BtcCore0-*} means "any service of that task", expressed
 * without depending on CloudNet at all.
 */
public final class ArrivalPolicy {

  private static final String WILDCARD = "*";

  private final List<String> patterns;

  private ArrivalPolicy(List<String> patterns) {
    this.patterns = List.copyOf(patterns);
  }

  /**
   * Reads a policy from its declared form: patterns separated by commas.
   *
   * <p>A blank declaration yields an empty policy, which selects nothing. That is deliberate: an
   * absent policy must leave the proxy exactly as it was, never silently route to whatever
   * happens to be registered.
   *
   * @param declaration the raw declaration, may be {@code null}
   * @return the policy it describes
   */
  public static ArrivalPolicy parse(String declaration) {
    if (declaration == null || declaration.isBlank()) {
      return new ArrivalPolicy(List.of());
    }

    List<String> parsed = new ArrayList<>();
    for (String piece : declaration.split(",")) {
      String trimmed = piece.trim();
      if (!trimmed.isEmpty()) {
        parsed.add(trimmed.toLowerCase(Locale.ROOT));
      }
    }
    return new ArrivalPolicy(parsed);
  }

  /**
   * Whether this policy declares no destination at all.
   *
   * @return {@code true} when nothing was declared
   */
  public boolean isEmpty() {
    return this.patterns.isEmpty();
  }

  /**
   * Whether a server name is a destination this policy accepts.
   *
   * @param serverName the Velocity server name
   * @return {@code true} if some declared pattern covers it
   */
  public boolean accepts(String serverName) {
    String candidate = serverName.toLowerCase(Locale.ROOT);
    for (String pattern : this.patterns) {
      if (pattern.endsWith(WILDCARD)) {
        if (candidate.startsWith(pattern.substring(0, pattern.length() - 1))) {
          return true;
        }
      } else if (pattern.equals(candidate)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Picks the destination for a player among what the proxy currently knows.
   *
   * <p>Least populated first, ties broken by name so the outcome is reproducible rather than
   * merely plausible. Anything already attempted by this player is out of the running: that is
   * the whole loop guard, and it holds without a timer.
   *
   * @param candidates every server the proxy has registered right now
   * @param alreadyAttempted the servers this player has already been sent to
   * @return the chosen server, or empty when the policy covers none of them
   */
  public Optional<ArrivalCandidate> select(
      Collection<ArrivalCandidate> candidates,
      Set<String> alreadyAttempted) {
    return candidates.stream()
        .filter(candidate -> this.accepts(candidate.name()))
        .filter(candidate -> !alreadyAttempted.contains(candidate.name()))
        .min(Comparator.comparingInt(ArrivalCandidate::playerCount)
            .thenComparing(ArrivalCandidate::name));
  }

  /**
   * The patterns this policy was built from, in declaration order.
   *
   * @return the declared patterns
   */
  public List<String> patterns() {
    return this.patterns;
  }
}
