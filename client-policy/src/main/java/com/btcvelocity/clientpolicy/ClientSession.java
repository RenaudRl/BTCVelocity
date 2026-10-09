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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * What one session has declared about its client, and what the policy has already done about it.
 *
 * <p>The events that feed it are asynchronous and may interleave, so every access goes through one
 * lock. The object holds facts and once-only flags, never a decision: those live in
 * {@link RefusalPolicy} and {@link ConsentPlanner}.
 */
final class ClientSession {

  /** Most channels kept per session: a hostile client cannot grow the proxy's memory with them. */
  static final int MAX_CHANNELS = 256;
  /** Longest brand kept, in code points. */
  static final int MAX_BRAND_LENGTH = 128;

  private final Object lock = new Object();
  private final Object owner;
  private final Set<String> channels = new LinkedHashSet<>();
  private final Set<String> reportedRules = new HashSet<>();
  private String brand;
  private int droppedChannels;
  private boolean overflowNoted;
  private boolean bedrockNoted;
  private boolean inconsistencyNoted;
  private ConsentPlanner.Consent lastConsent;

  /** What changed when a declaration arrived. */
  enum Kind {
    BRAND_DECLARED,
    BRAND_CHANGED,
    CHANNELS_ADDED,
    CHANNELS_REMOVED
  }

  /**
   * A change worth one journal line.
   *
   * @param kind what happened
   * @param brand the brand after the change, {@code null} while undeclared
   * @param values the brand (for a brand event) or the channels added or removed
   * @param channelTotal how many channels the session holds after the change
   */
  record Observation(Kind kind, String brand, List<String> values, int channelTotal) {
  }

  /**
   * An immutable copy of the declarations.
   *
   * @param brand the stored (possibly truncated) brand, {@code null} while undeclared
   * @param channels the channels held
   * @param droppedChannels how many channels were refused storage because of the cap
   */
  record Snapshot(String brand, List<String> channels, int droppedChannels) {
  }

  /**
   * Creates the state of one connection.
   *
   * @param owner the connection object (the player) this state belongs to, compared by identity
   */
  ClientSession(Object owner) {
    this.owner = owner;
  }

  /**
   * Whether this state belongs to that connection rather than to an earlier one of the same UUID.
   *
   * @param candidate the connection object
   * @return {@code true} for the same object
   */
  boolean isOwnedBy(Object candidate) {
    return this.owner == candidate;
  }

  /**
   * Records the brand.
   *
   * @param declared the brand string
   * @return the change, empty when the client repeated what it had already said
   */
  Optional<Observation> recordBrand(String declared) {
    String stored = truncate(declared);
    synchronized (this.lock) {
      if (stored.equals(this.brand)) {
        return Optional.empty();
      }
      Kind kind = this.brand == null ? Kind.BRAND_DECLARED : Kind.BRAND_CHANGED;
      this.brand = stored;
      return Optional.of(new Observation(kind, stored, List.of(stored), this.channels.size()));
    }
  }

  private static String truncate(String value) {
    if (value.codePointCount(0, value.length()) <= MAX_BRAND_LENGTH) {
      return value;
    }
    return value.substring(0, value.offsetByCodePoints(0, MAX_BRAND_LENGTH));
  }

  /**
   * Records registered channels.
   *
   * @param declared the channel identifiers
   * @return the change, empty when every channel was already known or the cap was reached
   */
  Optional<Observation> recordChannels(List<String> declared) {
    synchronized (this.lock) {
      List<String> added = new ArrayList<>();
      for (String channel : declared) {
        if (this.channels.contains(channel)) {
          continue;
        }
        if (this.channels.size() >= MAX_CHANNELS) {
          this.droppedChannels++;
          continue;
        }
        this.channels.add(channel);
        added.add(channel);
      }
      if (added.isEmpty()) {
        return Optional.empty();
      }
      return Optional.of(new Observation(Kind.CHANNELS_ADDED, this.brand, added,
          this.channels.size()));
    }
  }

  /**
   * Forgets channels the client unregistered.
   *
   * @param withdrawn the channel identifiers
   * @return the change, empty when none of them was held
   */
  Optional<Observation> removeChannels(List<String> withdrawn) {
    synchronized (this.lock) {
      List<String> removed = withdrawn.stream().filter(this.channels::remove).toList();
      if (removed.isEmpty()) {
        return Optional.empty();
      }
      return Optional.of(new Observation(Kind.CHANNELS_REMOVED, this.brand, removed,
          this.channels.size()));
    }
  }

  /**
   * The number of channels refused storage, once: the first call after the cap was hit answers
   * with the count so far, every later call answers empty. The full count stays in the snapshot.
   *
   * @return the dropped count the first time, else empty
   */
  OptionalInt takeOverflowReport() {
    synchronized (this.lock) {
      if (this.droppedChannels == 0 || this.overflowNoted) {
        return OptionalInt.empty();
      }
      this.overflowNoted = true;
      return OptionalInt.of(this.droppedChannels);
    }
  }

  /**
   * Takes a copy of the declarations.
   *
   * @return the brand and channels so far
   */
  Snapshot snapshot() {
    synchronized (this.lock) {
      return new Snapshot(this.brand, List.copyOf(this.channels), this.droppedChannels);
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
   * Records the consent now in force on the client and returns the one it replaces, in one step:
   * two server switches racing each other each see a different previous value.
   *
   * @param consent what the player is about to be told
   * @return what the player was told before, {@code null} if nothing
   */
  ConsentPlanner.Consent exchangeConsent(ConsentPlanner.Consent consent) {
    synchronized (this.lock) {
      ConsentPlanner.Consent previous = this.lastConsent;
      this.lastConsent = consent;
      return previous;
    }
  }
}
