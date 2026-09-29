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

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What one session has declared about its client, and what the policy has already done about it.
 *
 * <p>The events that feed it are asynchronous and may interleave, so every access goes through one
 * lock. The object holds facts and once-only flags, never a decision: those live in
 * {@link RefusalPolicy} and {@link ConsentPlanner}.
 */
final class ClientSession {

  private final Object lock = new Object();
  private final Set<String> channels = new LinkedHashSet<>();
  private final Set<String> reportedRules = new HashSet<>();
  private String brand;
  private boolean bedrockNoted;
  private boolean inconsistencyNoted;
  private ConsentPlanner.Consent lastConsent;

  /** What changed when a declaration arrived. */
  enum Kind {
    BRAND_DECLARED,
    BRAND_CHANGED,
    CHANNELS_ADDED
  }

  /**
   * A change worth one journal line.
   *
   * @param kind what happened
   * @param brand the brand after the change, {@code null} while undeclared
   * @param added the brand (for a brand event) or the new channels
   * @param channelTotal how many channels the session holds after the change
   */
  record Observation(Kind kind, String brand, List<String> added, int channelTotal) {
  }

  /** An immutable copy of the declarations. */
  record Snapshot(String brand, List<String> channels) {
  }

  /**
   * Records the brand.
   *
   * @param declared the brand string
   * @return the change, empty when the client repeated what it had already said
   */
  Optional<Observation> recordBrand(String declared) {
    synchronized (this.lock) {
      if (declared.equals(this.brand)) {
        return Optional.empty();
      }
      Kind kind = this.brand == null ? Kind.BRAND_DECLARED : Kind.BRAND_CHANGED;
      this.brand = declared;
      return Optional.of(new Observation(kind, declared, List.of(declared), this.channels.size()));
    }
  }

  /**
   * Records registered channels.
   *
   * @param declared the channel identifiers
   * @return the change, empty when every channel was already known
   */
  Optional<Observation> recordChannels(List<String> declared) {
    synchronized (this.lock) {
      List<String> added = declared.stream().filter(this.channels::add).toList();
      if (added.isEmpty()) {
        return Optional.empty();
      }
      return Optional.of(new Observation(Kind.CHANNELS_ADDED, this.brand, added,
          this.channels.size()));
    }
  }

  /**
   * Takes a copy of the declarations.
   *
   * @return the brand and channels so far
   */
  Snapshot snapshot() {
    synchronized (this.lock) {
      return new Snapshot(this.brand, List.copyOf(this.channels));
    }
  }

  /**
   * Marks the Bedrock notice as written.
   *
   * @return {@code true} the first time only
   */
  boolean markBedrockNoted() {
    synchronized (this.lock) {
      boolean first = !this.bedrockNoted;
      this.bedrockNoted = true;
      return first;
    }
  }

  /**
   * Marks the vanilla-with-mod-channels inconsistency as written.
   *
   * @return {@code true} the first time only
   */
  boolean markInconsistencyNoted() {
    synchronized (this.lock) {
      boolean first = !this.inconsistencyNoted;
      this.inconsistencyNoted = true;
      return first;
    }
  }

  /**
   * Marks a rule as reported for this session, so a brand event then a channel event do not write
   * the same refusal twice.
   *
   * @param label the rule's label
   * @return {@code true} the first time only
   */
  boolean markRuleReported(String label) {
    synchronized (this.lock) {
      return this.reportedRules.add(label);
    }
  }

  /**
   * The consent last sent.
   *
   * @return the consent, {@code null} if none was sent yet
   */
  ConsentPlanner.Consent lastConsent() {
    synchronized (this.lock) {
      return this.lastConsent;
    }
  }

  /**
   * Remembers the consent now in force on the client.
   *
   * @param consent what the player was last told
   */
  void rememberConsent(ConsentPlanner.Consent consent) {
    synchronized (this.lock) {
      this.lastConsent = consent;
    }
  }
}
