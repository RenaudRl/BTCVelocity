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

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * The account links (the {@code links} section of {@code config.yml}, Sentinel 16.2).
 *
 * <p>Off by default. Switched on, it starts in {@code observe}. Only a STRONG link to an account
 * under a NETWORK ban in force refuses, and only in {@code enforce}; everything else is a journal
 * line and a staff message. A declared household is never refused for it.
 *
 * @param enabled whether anything is read or recorded; {@code false} also when the section is
 *     unusable as written (the reason is reported as an error)
 * @param mode {@code observe} or {@code enforce}; the module-wide {@code observe} holds it back
 * @param keyEnv the environment variable holding the HMAC key (base64, at least 32 bytes)
 * @param jdbcUrl the Sentinel database, PostgreSQL, without a password
 * @param username the database role (SELECT on the sanctions, owner of its two tables)
 * @param passwordEnv the environment variable holding the role's password
 * @param tablePrefix the table prefix shared with Sentinel, {@code ^[a-z][a-z0-9_]{0,31}$}
 * @param networkId the network the bans must belong to (Sentinel's {@code identity.network-id})
 * @param lookupTimeout how long a login waits for the database before it is admitted anyway
 * @param maxPoolSize the most connections held to the database
 * @param statementTimeout how long PostgreSQL lets one statement run before cancelling it
 * @param socketTimeout how long the driver waits on a silent connection before closing it
 * @param strongWindow how recent a sighting on the same address must be for a STRONG link
 * @param weakWindow how recent a sighting on the same network must be for a WEAK link
 * @param sharedAddressAccounts above this many distinct accounts over {@link #ADDRESS_HISTORY},
 *     an address is shared and never makes a STRONG link
 * @param maxAccountsPerAddress above this many distinct accounts over {@link #ADDRESS_HISTORY}
 *     (the entrant's declared household counted once), a login is signalled; never refused
 * @param retention how long a sighting is kept after it was last seen
 * @param message the MiniMessage text shown to a refused player, with {@code {code}}
 */
public record LinkSettings(
    boolean enabled,
    AdmissionConfig.Mode mode,
    String keyEnv,
    String jdbcUrl,
    String username,
    String passwordEnv,
    String tablePrefix,
    String networkId,
    Duration lookupTimeout,
    int maxPoolSize,
    Duration statementTimeout,
    Duration socketTimeout,
    Duration strongWindow,
    Duration weakWindow,
    int sharedAddressAccounts,
    int maxAccountsPerAddress,
    Duration retention,
    String message) {

  /** How far back the sightings of an address are read. */
  public static final Duration ADDRESS_HISTORY = Duration.ofDays(30);

  /** Same rule as Sentinel's own prefix: it is pasted into SQL, so nothing else gets through. */
  static final Pattern PREFIX = Pattern.compile("^[a-z][a-z0-9_]{0,31}$");

  static final String DEFAULT_KEY_ENV = "BTC_ADMISSION_LINK_KEY";
  static final String DEFAULT_PASSWORD_ENV = "BTC_ADMISSION_DB_PASSWORD";
  static final int DEFAULT_TIMEOUT_MS = 1500;
  static final int DEFAULT_POOL = 2;
  static final int DEFAULT_STATEMENT_TIMEOUT_MS = 2000;
  static final int DEFAULT_SOCKET_TIMEOUT_S = 5;
  private static final int MAX_STATEMENT_TIMEOUT_MS = 60_000;
  private static final int MAX_SOCKET_TIMEOUT_S = 300;
  static final int DEFAULT_WINDOW_DAYS = 7;
  static final int DEFAULT_SHARED_ACCOUNTS = 4;
  static final int DEFAULT_MAX_ACCOUNTS = 5;
  static final int DEFAULT_RETENTION_DAYS = 180;
  private static final int MAX_TIMEOUT_MS = 10_000;
  private static final int MIN_TIMEOUT_MS = 100;
  private static final int MAX_POOL = 16;

  static final String DEFAULT_MESSAGE =
      "<red>Connexion refusée / Connection refused.</red>\n<gray>Contactez le staff avec le code"
          + " / Contact the staff with the code <white>{code}</white>.</gray>";

  /**
   * Reads the {@code links} section. A value that does not make sense is reported and replaced by
   * its default; a database setting that would be unsafe disables the links with an error.
   *
   * @param section the {@code links} node, possibly absent
   * @param message the text shown to a refused player
   * @param warnings receives one sentence per value that was replaced
   * @param errors receives one sentence per setting that disables the links
   * @return the settings
   */
  static LinkSettings parse(final ConfigurationNode section, final String message,
      final Consumer<String> warnings, final Consumer<String> errors) {
    final ConfigurationNode database = section.node("database");
    // A bare `off` is the YAML boolean false: both spellings switch the links off.
    final String modeText = section.node("mode").getString("observe").trim()
        .toLowerCase(Locale.ROOT);
    final boolean off = modeText.equals("off") || modeText.equals("false");
    boolean enabled = section.node("enabled").getBoolean(false) && !off;
    final AdmissionConfig.Mode mode = switch (modeText) {
      case "enforce" -> AdmissionConfig.Mode.ENFORCE;
      case "observe", "off", "false" -> AdmissionConfig.Mode.OBSERVE;
      default -> {
        warnings.accept("links.mode '" + modeText + "' is not off, observe or enforce; using"
            + " 'observe'");
        yield AdmissionConfig.Mode.OBSERVE;
      }
    };
    final String prefix = database.node("table-prefix").getString("btc_");
    final String url = database.node("jdbc-url").getString("").trim();
    if (!PREFIX.matcher(prefix).matches()) {
      errors.accept("links.database.table-prefix '" + AdmissionJournal.value(prefix)
          + "' does not match " + PREFIX.pattern() + "; account links are DISABLED");
      enabled = false;
    }
    if (enabled && !url.startsWith("jdbc:postgresql://")) {
      errors.accept("links.database.jdbc-url must be a jdbc:postgresql:// URL; account links are"
          + " DISABLED");
      enabled = false;
    }
    if (holdsPassword(url)) {
      // Not echoed: the URL is the thing holding a secret.
      errors.accept("links.database.jdbc-url holds a password; put it in the environment variable"
          + " named by password-env. Account links are DISABLED");
      enabled = false;
    }
    return new LinkSettings(
        enabled,
        mode,
        section.node("key-env").getString(DEFAULT_KEY_ENV),
        url,
        database.node("username").getString("btc_admission"),
        database.node("password-env").getString(DEFAULT_PASSWORD_ENV),
        PREFIX.matcher(prefix).matches() ? prefix : "btc_",
        database.node("network-id").getString("borntocraft"),
        Duration.ofMillis(within(database, "lookup-timeout-ms", DEFAULT_TIMEOUT_MS, MIN_TIMEOUT_MS,
            MAX_TIMEOUT_MS, warnings)),
        within(database, "max-pool-size", DEFAULT_POOL, 1, MAX_POOL, warnings),
        Duration.ofMillis(within(database, "statement-timeout-ms", DEFAULT_STATEMENT_TIMEOUT_MS,
            MIN_TIMEOUT_MS, MAX_STATEMENT_TIMEOUT_MS, warnings)),
        Duration.ofSeconds(within(database, "socket-timeout-seconds", DEFAULT_SOCKET_TIMEOUT_S, 1,
            MAX_SOCKET_TIMEOUT_S, warnings)),
        Duration.ofDays(within(section, "strong-window-days", DEFAULT_WINDOW_DAYS, 1,
            (int) ADDRESS_HISTORY.toDays(), warnings)),
        Duration.ofDays(within(section, "weak-window-days", DEFAULT_WINDOW_DAYS, 1,
            (int) ADDRESS_HISTORY.toDays(), warnings)),
        within(section, "shared-address-accounts", DEFAULT_SHARED_ACCOUNTS, 2,
            LinkStore.MAX_ACCOUNTS_PER_LOOKUP - 1, warnings),
        within(section, "max-accounts-per-household", DEFAULT_MAX_ACCOUNTS, 2,
            LinkStore.MAX_ACCOUNTS_PER_LOOKUP - 1, warnings),
        Duration.ofDays(within(section, "retention-days", DEFAULT_RETENTION_DAYS,
            (int) ADDRESS_HISTORY.toDays(), Integer.MAX_VALUE, warnings)),
        message);
  }

  /**
   * What the PostgreSQL driver is told beyond the URL: no statement and no silent socket may hold a
   * thread of this module for long.
   *
   * @return the driver properties
   */
  public Map<String, String> driverProperties() {
    return Map.of(
        "socketTimeout", Long.toString(this.socketTimeout.toSeconds()),
        "options", "-c statement_timeout=" + this.statementTimeout.toMillis());
  }

  private static boolean holdsPassword(final String url) {
    final String lower = url.toLowerCase(Locale.ROOT);
    final int scheme = lower.indexOf("//");
    final int path = scheme < 0 ? -1 : lower.indexOf('/', scheme + 2);
    final String authority = scheme < 0 ? "" : lower.substring(scheme + 2,
        path < 0 ? lower.length() : path);
    return lower.contains("password=") || authority.contains("@");
  }

  private static int within(final ConfigurationNode section, final String key,
      final int fallback, final int minimum, final int maximum,
      final Consumer<String> warnings) {
    final int value = section.node(key).getInt(fallback);
    if (value >= minimum && value <= maximum) {
      return value;
    }
    warnings.accept("links." + key + ": " + value + " is outside " + minimum + ".." + maximum
        + "; using " + fallback);
    return fallback;
  }
}
