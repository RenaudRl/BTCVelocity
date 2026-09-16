/*
 * Copyright (C) 2018-2026 Velocity Contributors
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

package com.btcvelocity.proxy.bridge;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * What the bridge did, counted — never what it carried.
 *
 * <p>Counters only: no payload, no player UUID, no server-supplied string ever enters this class.
 * That is not prudishness, it is what makes the numbers safe to expose to an operator surface. A
 * metric keyed by a value the other end chose would also let a backend grow the proxy's memory by
 * inventing keys.</p>
 *
 * <p>{@link LongAdder} rather than {@code AtomicLong}: plugin messages arrive on several Netty
 * event loops at once, and an adder trades a little read cost for writes that do not contend.
 * Nothing here blocks, allocates per message, or synchronizes.</p>
 */
public final class BridgeMetrics {

  /** What can be counted about one message. */
  public enum Event {
    /** A message arrived on the channel, whatever became of it. */
    RECEIVED,
    /** The source was not an authenticated backend connection. */
    REJECTED_SOURCE,
    /** The payload was malformed, oversized, expired or of an unknown version. */
    REJECTED_PAYLOAD,
    /** The message spoke in another backend's name. */
    REJECTED_IDENTITY,
    /** The frame was unsigned or its signature did not verify: origin not proven. */
    REJECTED_SIGNATURE,
    /** The sender or the destination was not allowed by the declared policy. */
    REJECTED_AUTHORIZATION,
    /**
     * The command was allowed but cannot be carried out (unknown destination, malformed party).
     * Kept apart from authorization: "you may not" and "it cannot be done" call for different fixes.
     */
    REJECTED_UNEXECUTABLE,
    /** The message was handed to the listeners. */
    DISPATCHED,
    /** A command already executed inside the deduplication window arrived again. */
    DUPLICATE,
    /** An acknowledgement was written back to the sender. */
    ACKNOWLEDGED,
    /** A categorized refusal was written back to the sender. */
    REFUSED,
    /** A response could not be written back. */
    RESPONSE_FAILED,
    /** A listener threw while handling a message. */
    LISTENER_FAILED
  }

  private final Map<Event, LongAdder> counters = new EnumMap<>(Event.class);

  /** Creates a metrics set with every counter at zero. */
  public BridgeMetrics() {
    for (final Event event : Event.values()) {
      counters.put(event, new LongAdder());
    }
  }

  /**
   * Counts one occurrence.
   *
   * @param event what happened
   */
  public void record(final Event event) {
    counters.get(event).increment();
  }

  /**
   * Reads one counter.
   *
   * @param event the counter to read
   * @return how many times it occurred since startup
   */
  public long count(final Event event) {
    return counters.get(event).sum();
  }

  /**
   * An immutable reading of every counter, taken one by one.
   *
   * <p>The snapshot is not atomic across counters, and it does not pretend to be: the numbers are
   * for observing a running proxy, not for proving an invariant between two of them.</p>
   *
   * @return the counters, by event
   */
  public Map<Event, Long> snapshot() {
    final Map<Event, Long> reading = new EnumMap<>(Event.class);
    counters.forEach((event, adder) -> reading.put(event, adder.sum()));
    return Map.copyOf(reading);
  }
}
