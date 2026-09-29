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
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Writes one structured line per journaled verdict, and remembers the latest ones for
 * {@code /admission why}.
 *
 * <p>The line is {@code key=value} pairs so that it can be grepped and parsed without a schema:
 * {@code ts, user, uuid, ip, class, source, decision, code, basis, phase, mode}. It goes to a
 * logger of its own ({@value #LOGGER_NAME}), so the proxy's logging configuration can route it to
 * a file of its own.
 *
 * <p>The memory is a bounded cache in RAM. Nothing is written to disk here beyond the log line,
 * and the 180-day retention the compliance note asks for is a property of the log file, not of
 * this class: rotation and deletion are configured on the logger, not implemented here.
 */
public final class AdmissionJournal {

  /** The name of the dedicated logger. */
  public static final String LOGGER_NAME = "btc.admission.journal";

  private static final long MEMORY_SIZE = 4096;

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
      final StringBuilder line = new StringBuilder(160);
      append(line, "ts", this.at.toString());
      append(line, "user", this.username);
      append(line, "uuid", this.uuid == null ? "unknown" : this.uuid.toString());
      final Verdict judgement = this.verdict;
      append(line, "ip",
          judgement.judged() == null ? "unknown" : judgement.judged().getHostAddress());
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
      line.append(key).append('=');
      final boolean plain = !value.isEmpty() && value.chars().noneMatch(
          c -> c == ' ' || c == ',' || c == '"' || c == '=' || Character.isISOControl(c));
      if (plain) {
        line.append(value);
      } else {
        line.append('"').append(value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replaceAll("\\p{Cntrl}", " ")).append('"');
      }
      line.append(' ');
    }
  }

  private final Logger logger;
  private final Clock clock;
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
    final String code = verdict.action() == Verdict.Action.REFUSE && verdict.ipClass() != null
        ? verdict.ipClass().causeCode() : null;
    final Entry entry = new Entry(this.clock.instant(), attempt.username(), attempt.uuid(),
        verdict, decision, code, phase, mode);
    this.logger.info("{}", entry.line());
    this.memory.put(nameKey(attempt.username()), entry);
    final InetAddress judged = verdict.judged();
    // A Bedrock player of unknown address has no address of their own to be looked up by, and
    // Geyser's must not point at "the last Bedrock player".
    if (judged != null) {
      this.memory.put(addressKey(judged), entry);
    }
    return Optional.of(entry);
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
