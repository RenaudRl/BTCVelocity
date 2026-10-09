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

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Writes one structured line per journaled verdict, and remembers the latest ones for
 * {@code /admission why}.
 *
 * <p>The line is {@code key=value} pairs so that it can be grepped and parsed without a schema:
 * {@code ts, user, identity, uuid, ip, origin, class, source, decision, code, basis, phase, mode}.
 * {@code identity} says whether {@code user} is only {@code declared} (pre-login) or
 * {@code authenticated}. It goes to a logger of its own ({@value #LOGGER_NAME}), so the proxy's
 * logging configuration can route it to a file of its own.
 *
 * <p>The memory is a bounded cache in RAM, indexed by address and, for authenticated decisions
 * only, by name. Nothing is written to disk here beyond the log line, and the 180-day retention
 * the compliance note asks for is a property of the log file: the module ships the log4j2
 * fragment that rotates it daily and deletes it after 180 days
 * ({@code log4j2-admission-journal.xml}).
 */
public final class AdmissionJournal {

  /** The name of the dedicated logger. */
  public static final String LOGGER_NAME = "btc.admission.journal";

  /** How long the refusals of one network are counted into one line. */
  static final Duration AGGREGATION_WINDOW = Duration.ofMinutes(1);

  /** The most networks followed at once; beyond it, lines are written in full. */
  private static final int MAX_BURSTS = 10_000;

  private static final long MEMORY_SIZE = 4096;
  private static final int LINE_SEPARATOR = 0x2028;
  private static final int PARAGRAPH_SEPARATOR = 0x2029;

  /**
   * One remembered decision.
   *
   * @param at when it was taken
   * @param username the player's name
   * @param uuid the player's UUID, {@code null} if not yet known
   * @param verdict the verdict
   * @param decision what was done: {@code refused}, {@code observed} or {@code admitted}
   * @param code the cause code shown to a refused player, {@code null} otherwise
   * @param phase where the decision was taken
   * @param mode the mode in force
   */
  public record Entry(
      Instant at,
      String username,
      @Nullable UUID uuid,
      Verdict verdict,
      String decision,
      @Nullable String code,
      Phase phase,
      AdmissionConfig.Mode mode) {

    /**
     * The journal line for this decision.
     *
     * @return the line, without a trailing newline
     */
    public String line() {
      final StringBuilder line = new StringBuilder(200);
      append(line, "ts", this.at.toString());
      append(line, "user", this.username);
      append(line, "identity", identityOf(this.phase));
      append(line, "uuid", this.uuid == null ? "unknown" : this.uuid.toString());
      final Verdict judgement = this.verdict;
      append(line, "ip",
          judgement.judged() == null ? "unknown" : judgement.judged().getHostAddress());
      append(line, "origin", judgement.origin().label());
      append(line, "class", judgement.ipClass() == null ? "none" : judgement.ipClass().key());
      append(line, "source", judgement.sourceId() == null ? "none" : judgement.sourceId());
      append(line, "decision", this.decision);
      append(line, "code", this.code == null ? "none" : this.code);
      append(line, "basis", judgement.basis().label());
      append(line, "phase", this.phase.label());
      append(line, "mode", this.mode.name().toLowerCase(Locale.ROOT));
      return line.toString().trim();
    }

    private static void append(final StringBuilder line, final String key, final String value) {
      line.append(key).append('=').append(value(value)).append(' ');
    }
  }

  /**
   * How a name is to be read: {@code declared} at the pre-login, where anyone can type anything,
   * {@code authenticated} from the login on.
   *
   * @param phase where the decision was taken
   * @return the word written in the journal
   */
  static String identityOf(final Phase phase) {
    return phase == Phase.PRE_LOGIN ? "declared" : "authenticated";
  }

  /**
   * A value as the journal writes it: bare when it is plain, quoted and escaped otherwise.
   *
   * <p>Anything that could forge a field, break the line or reorder what a reader sees is
   * neutralised: quotes and backslashes are escaped, and control characters (C0, C1 including
   * U+0085), the line and paragraph separators U+2028 and U+2029, and every formatting character
   * (bidirectional overrides and isolates among them) are written as a backslash, a {@code u}
   * and four hexadecimal digits per UTF-16 unit.
   *
   * @param raw the value, possibly chosen by a client
   * @return the value, safe to put after {@code key=}
   */
  static String value(final String raw) {
    final boolean plain = !raw.isEmpty() && raw.codePoints().noneMatch(
        c -> c == ',' || c == '"' || c == '=' || c == '\\' || Character.isWhitespace(c)
            || Character.isSpaceChar(c) || isUnsafe(c));
    if (plain) {
      return raw;
    }
    final StringBuilder quoted = new StringBuilder(raw.length() + 8).append('"');
    raw.codePoints().forEach(c -> {
      if (c == '\\') {
        quoted.append("\\\\");
      } else if (c == '"') {
        quoted.append("\\\"");
      } else if (isUnsafe(c)) {
        for (char unit : Character.toChars(c)) {
          quoted.append(String.format(Locale.ROOT, "\\u%04X", (int) unit));
        }
      } else {
        quoted.appendCodePoint(c);
      }
    });
    return quoted.append('"').toString();
  }

  private static boolean isUnsafe(final int codePoint) {
    return Character.isISOControl(codePoint)
        || codePoint == LINE_SEPARATOR || codePoint == PARAGRAPH_SEPARATOR
        || Character.getType(codePoint) == Character.FORMAT;
  }

  private final Logger logger;
  private final Clock clock;
  private final Object burstLock = new Object();
  private final Map<String, Burst> bursts = new HashMap<>();
  private final Cache<String, Entry> memory =
      Caffeine.newBuilder().maximumSize(MEMORY_SIZE).build();

  /**
   * Creates a journal.
   *
   * @param logger the dedicated logger
   * @param clock the source of timestamps
   */
  public AdmissionJournal(final Logger logger, final Clock clock) {
    this.logger = logger;
    this.clock = clock;
  }

  /**
   * Writes a verdict down, if it is one that gets written.
   *
   * @param attempt the connection
   * @param verdict the verdict
   * @param phase where it was taken
   * @param mode the mode in force
   * @return the entry, or empty when this verdict is not journaled (clean, deferred)
   */
  public Optional<Entry> record(final Attempt attempt, final Verdict verdict, final Phase phase,
      final AdmissionConfig.Mode mode) {
    if (!verdict.isJournaled()) {
      return Optional.empty();
    }
    final String decision = switch (verdict.action()) {
      case REFUSE -> "refused";
      case OBSERVE -> "observed";
      case ADMIT, DEFER -> "admitted";
    };
    final String code = verdict.action() == Verdict.Action.REFUSE ? verdict.causeCode() : null;
    final Entry entry = new Entry(this.clock.instant(), attempt.username(), attempt.uuid(),
        verdict, decision, code, phase, mode);
    if (claimFullLine(entry)) {
      this.logger.info("{}", entry.line());
    }
    // A name typed at the pre-login proves nothing: indexing it would let anyone fill the
    // answer to "/admission why <someone else>". Only an authenticated name is an index.
    if (phase == Phase.LOGIN && attempt.uuid() != null) {
      this.memory.put(nameKey(attempt.username()), entry);
    }
    final InetAddress judged = verdict.judged();
    // A Bedrock player of unknown address has no address of their own to be looked up by, and
    // Geyser's must not point at "the last Bedrock player".
    if (judged != null) {
      this.memory.put(addressKey(judged), entry);
    }
    return Optional.of(entry);
  }

  /** One run of refusals or observations from the same network, within one window. */
  private static final class Burst {
    private final Instant start;
    private final String decision;
    private final String basis;
    private final String ipClass;
    private final String source;
    private final String network;
    private int suppressed;

    private Burst(final Instant start, final Entry first, final String network) {
      this.start = start;
      this.decision = first.decision();
      this.basis = first.verdict().basis().label();
      this.ipClass = first.verdict().ipClass() == null ? "none" : first.verdict().ipClass().key();
      this.source = first.verdict().sourceId() == null ? "none" : first.verdict().sourceId();
      this.network = network;
    }

    private String summary() {
      final StringBuilder line = new StringBuilder(160);
      Entry.append(line, "ts", this.start.plus(AGGREGATION_WINDOW).toString());
      Entry.append(line, "event", "aggregate");
      Entry.append(line, "decision", this.decision);
      Entry.append(line, "basis", this.basis);
      Entry.append(line, "class", this.ipClass);
      Entry.append(line, "source", this.source);
      Entry.append(line, "net", this.network);
      Entry.append(line, "suppressed", Integer.toString(this.suppressed));
      Entry.append(line, "since", this.start.toString());
      Entry.append(line, "window_s", Long.toString(AGGREGATION_WINDOW.toSeconds()));
      return line.toString().trim();
    }
  }

  /**
   * Whether this decision gets its own line. Refusals and observations from one network (an IPv4
   * /24, an IPv6 /64), listed or over the rate limit, are written in full the first time in a
   * {@link #AGGREGATION_WINDOW}; the rest of the window is counted and written as one
   * {@code event=aggregate} line when it ends, so that a wave from one datacenter cannot drown
   * the journal (and its retention).
   * Exemptions and the other admissions are rare and always written.
   */
  private boolean claimFullLine(final Entry entry) {
    final InetAddress judged = entry.verdict().judged();
    final Verdict.Basis basis = entry.verdict().basis();
    if ((basis != Verdict.Basis.LISTED && basis != Verdict.Basis.RATE_NETWORK) || judged == null) {
      return true;
    }
    final String network = Networks.of(judged, Networks.AGGREGATE_V4, Networks.AGGREGATE_V6);
    final String key = entry.decision() + '|' + basis.label() + '|' + entry.verdict().sourceId()
        + '|' + network;
    String closed = null;
    boolean full = true;
    synchronized (this.burstLock) {
      final Burst burst = this.bursts.get(key);
      if (burst != null && entry.at().isBefore(burst.start.plus(AGGREGATION_WINDOW))) {
        burst.suppressed++;
        full = false;
      } else {
        if (burst != null && burst.suppressed > 0) {
          closed = burst.summary();
        }
        if (burst != null || this.bursts.size() < MAX_BURSTS) {
          this.bursts.put(key, new Burst(entry.at(), entry, network));
        }
      }
    }
    if (closed != null) {
      this.logger.info("{}", closed);
    }
    return full;
  }

  /**
   * Writes the summary of every window that is over. Called from a timer so that the last window
   * of a wave is not held back until the next refusal from the same network.
   */
  public void flushExpired() {
    final Instant now = this.clock.instant();
    final List<String> closed = new ArrayList<>();
    synchronized (this.burstLock) {
      final Iterator<Burst> bursts = this.bursts.values().iterator();
      while (bursts.hasNext()) {
        final Burst burst = bursts.next();
        if (!now.isBefore(burst.start.plus(AGGREGATION_WINDOW))) {
          bursts.remove();
          if (burst.suppressed > 0) {
            closed.add(burst.summary());
          }
        }
      }
    }
    closed.forEach(line -> this.logger.info("{}", line));
  }

  /**
   * Writes down that a list version still in quarantine would have refused an authenticated
   * player in addition to the versions in use. Nothing happens to the player. Not remembered for
   * {@code /admission why}: it is not a decision.
   *
   * @param attempt the authenticated connection
   * @param verdict what the waiting version would decide
   * @param mode the mode in force
   */
  public void recordWouldRefuse(final Attempt attempt, final Verdict verdict,
      final AdmissionConfig.Mode mode) {
    final Entry entry = new Entry(this.clock.instant(), attempt.username(), attempt.uuid(),
        verdict, "would_refuse", null, Phase.LOGIN, mode);
    this.logger.info("{}", entry.line());
  }

  /**
   * The latest remembered decision about a player.
   *
   * @param username the player's name, any case
   * @return the decision, or empty
   */
  public Optional<Entry> lastForName(final String username) {
    return Optional.ofNullable(this.memory.getIfPresent(nameKey(username)));
  }

  /**
   * The latest remembered decision about an address.
   *
   * @param address the address
   * @return the decision, or empty
   */
  public Optional<Entry> lastForAddress(final InetAddress address) {
    return Optional.ofNullable(this.memory.getIfPresent(addressKey(address)));
  }

  /** How many keys are remembered right now, after pending evictions. */
  long memorySize() {
    this.memory.cleanUp();
    return this.memory.estimatedSize();
  }

  private static String nameKey(final String username) {
    return "name:" + username.toLowerCase(Locale.ROOT);
  }

  private static String addressKey(final InetAddress address) {
    return "ip:" + address.getHostAddress();
  }
}
