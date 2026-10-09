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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Tells the staff online about refusals, at most one message per window.
 *
 * <p>The first refusal after a quiet window is said at once. Those that follow within the window
 * are counted, and said together, with the latest one as an example, when the window is over:
 * a wave of datacenter connections produces one line every {@link #WINDOW}, not a flood.
 * {@link #flushIfDue()} is meant to be called on a timer so that the last batch is not held until
 * the next refusal.
 *
 * <p>Safe to call from several threads.
 */
public final class StaffAlerts {

  /** The shortest time between two messages. */
  public static final Duration WINDOW = Duration.ofSeconds(10);

  private final Clock clock;
  private final Consumer<String> sink;
  private final String single;
  private final String plural;
  private @Nullable Instant lastSent;
  private int pending;
  private AdmissionJournal.@Nullable Entry latest;

  /**
   * Creates the alerts for refusals.
   *
   * @param clock the source of "now"
   * @param sink receives each message, to be shown to the staff online
   */
  public StaffAlerts(final Clock clock, final Consumer<String> sink) {
    this(clock, sink, "[Admission] Refus : ", "refus");
  }

  private StaffAlerts(final Clock clock, final Consumer<String> sink, final String single,
      final String plural) {
    this.clock = clock;
    this.sink = sink;
    this.single = single;
    this.plural = plural;
  }

  /**
   * Creates the alerts for the autonomous systems whose name holds an observation keyword. They
   * are a signal and not a refusal, so they get their own instance (their own window: a refusal
   * is never held back by a signal) and their own words.
   *
   * @param clock the source of "now"
   * @param sink receives each message, to be shown to the staff online
   * @return the alerts
   */
  public static StaffAlerts forAsnKeywords(final Clock clock, final Consumer<String> sink) {
    return new StaffAlerts(clock, sink, "[Admission] Signal ASN (observation, non refusé) : ",
        "signaux ASN (observation, non refusés)");
  }

  /**
   * Counts a refusal, and says it now if the window allows.
   *
   * @param refusal the journal entry of the refusal
   */
  public void onRefusal(final AdmissionJournal.Entry refusal) {
    final String message;
    synchronized (this) {
      this.pending++;
      this.latest = refusal;
      message = takeIfDue();
    }
    deliver(message);
  }

  /** Says what was counted since the last message, if the window is over. */
  public void flushIfDue() {
    final String message;
    synchronized (this) {
      message = takeIfDue();
    }
    deliver(message);
  }

  private @Nullable String takeIfDue() {
    final Instant now = this.clock.instant();
    if (this.pending == 0 || this.latest == null
        || (this.lastSent != null && now.isBefore(this.lastSent.plus(WINDOW)))) {
      return null;
    }
    final String message = this.pending == 1
        ? this.single + describe(this.latest)
        : "[Admission] " + this.pending + " " + this.plural + " depuis " + WINDOW.toSeconds()
            + " s, dernier : " + describe(this.latest);
    this.pending = 0;
    this.latest = null;
    this.lastSent = now;
    return message;
  }

  private void deliver(final @Nullable String message) {
    if (message != null) {
      this.sink.accept(message);
    }
  }

  private static String describe(final AdmissionJournal.Entry entry) {
    final Verdict verdict = entry.verdict();
    // The name is typed by a client and shown in a chat line, where the client reads a section
    // sign as a formatting code: written out, not interpreted. The address is shown as its
    // network (a /24 or a /48), not the player's own: /admission why gives the full one.
    return AdmissionJournal.value(entry.username()).replace("§", "\\u00A7")
        + " (" + AdmissionJournal.identityOf(entry.phase())
        + ", " + (verdict.judged() == null ? "ip inconnue"
            : Networks.of(verdict.judged(), Networks.STAFF_V4, Networks.STAFF_V6))
        + ", " + (verdict.ipClass() == null ? "aucune classe" : verdict.ipClass().key())
        + ", " + (entry.code() == null ? "sans code" : entry.code())
        + asnSuffix(verdict.asn()) + ")."
        + " /admission why pour le détail.";
  }

  /** The system, when known; its name is the registry's, written out like the player's. */
  private static String asnSuffix(final AsnDatabase.@Nullable Info asn) {
    return asn == null ? "" : ", AS" + asn.number() + " "
        + AdmissionJournal.value(asn.name()).replace("§", "\\u00A7");
  }
}
