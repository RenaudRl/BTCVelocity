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
import java.util.function.Consumer;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * The bound on connection attempts per network (the {@code network-rate} section of
 * {@code config.yml}, Sentinel 13.4).
 *
 * <p>A network is an IPv4 /24 or an IPv6 /48. It owns a bucket of {@code burst} tokens that
 * refills at {@code attempts} per {@code window}; every pre-login spends one token and an attempt
 * that finds the bucket empty is over the limit.
 *
 * <p>The defaults are chosen for a French mobile carrier behind carrier-grade NAT, where a /24
 * stands for a few hundred subscribers: 10 attempts per 10 s sustained (one per second, far above
 * what a /24 of players produces in normal play) and a burst of 20 (a group joining together, or a
 * wave of reconnections after a restart, drains it without a refusal). A bot wave from one
 * network passes its 20 first attempts, then crawls at one per second.
 *
 * @param enabled whether attempts are counted at all
 * @param mode {@code observe} journals the attempts over the limit, {@code enforce} refuses them;
 *     the module-wide {@code mode: observe} overrides {@code enforce} (see
 *     {@link AdmissionConfig#rateMode()})
 * @param attempts the sustained attempts allowed per {@code window}
 * @param window the span {@code attempts} is counted over
 * @param burst the most attempts a quiet network may make at once
 * @param ipv4Prefix the IPv4 prefix length that makes a network
 * @param ipv6Prefix the IPv6 prefix length that makes a network
 * @param maxNetworks the most networks followed at once; the least recently seen is forgotten
 *     first, and a forgotten network starts again with a full bucket
 * @param message the MiniMessage text shown to a refused player, with {@code {code}} to replace
 * @param groupHostingByAsn whether the addresses of a hosting autonomous system (one of
 *     {@code asn.deny-asns}, or named by one of {@code asn.observe-keywords}) share one bucket per
 *     system instead of one per /24: a wave spread over a provider's many networks is then
 *     counted as the one wave it is. Off by default; it needs the ASN database, and an address the
 *     database does not know, or a system that is not hosting, keeps its prefix
 */
public record NetworkRateSettings(
    boolean enabled,
    AdmissionConfig.Mode mode,
    int attempts,
    Duration window,
    int burst,
    int ipv4Prefix,
    int ipv6Prefix,
    int maxNetworks,
    String message,
    boolean groupHostingByAsn) {

  /**
   * Creates settings that group by prefix only.
   *
   * @param enabled whether attempts are counted at all
   * @param mode {@code observe} or {@code enforce}
   * @param attempts the sustained attempts allowed per {@code window}
   * @param window the span {@code attempts} is counted over
   * @param burst the most attempts a quiet network may make at once
   * @param ipv4Prefix the IPv4 prefix length that makes a network
   * @param ipv6Prefix the IPv6 prefix length that makes a network
   * @param maxNetworks the most networks followed at once
   * @param message the MiniMessage text shown to a refused player
   */
  public NetworkRateSettings(final boolean enabled, final AdmissionConfig.Mode mode,
      final int attempts, final Duration window, final int burst, final int ipv4Prefix,
      final int ipv6Prefix, final int maxNetworks, final String message) {
    this(enabled, mode, attempts, window, burst, ipv4Prefix, ipv6Prefix, maxNetworks, message,
        false);
  }

  static final int DEFAULT_ATTEMPTS = 10;
  static final int DEFAULT_WINDOW_SECONDS = 10;
  static final int DEFAULT_IPV4_PREFIX = 24;
  static final int DEFAULT_IPV6_PREFIX = 48;
  static final int DEFAULT_MAX_NETWORKS = 20_000;

  private static final int MIN_IPV4_PREFIX = 16;
  private static final int MAX_IPV4_PREFIX = 32;
  private static final int MIN_IPV6_PREFIX = 32;
  private static final int MAX_IPV6_PREFIX = 64;
  private static final int MIN_MAX_NETWORKS = 1_000;
  private static final int MAX_MAX_NETWORKS = 1_000_000;

  static final String DEFAULT_MESSAGE =
      "<red>Connexion refusée.</red>\n<gray>Trop de tentatives de connexion depuis votre réseau."
          + " Patientez quelques secondes puis réessayez. Si cela persiste, contactez le staff"
          + " avec le code <white>{code}</white>.</gray>";

  /**
   * Reads the {@code network-rate} section; a value that does not make sense is reported and
   * replaced by its default.
   *
   * @param section the {@code network-rate} node, possibly absent
   * @param message the text shown to a refused player
   * @param warnings receives one sentence per value that was replaced
   * @return the settings
   */
  static NetworkRateSettings parse(final ConfigurationNode section, final String message,
      final Consumer<String> warnings) {
    final int attempts = atLeast(section, "attempts", DEFAULT_ATTEMPTS, 1, warnings);
    final int windowSeconds =
        atLeast(section, "window-seconds", DEFAULT_WINDOW_SECONDS, 1, warnings);
    int burst = section.node("burst").virtual()
        ? attempts * 2 : section.node("burst").getInt(attempts * 2);
    if (burst < attempts) {
      warnings.accept("network-rate.burst: " + burst + " is below attempts (" + attempts
          + "), so the sustained rate could never be reached; using " + attempts);
      burst = attempts;
    }
    return new NetworkRateSettings(
        section.node("enabled").getBoolean(true),
        parseMode(section.node("mode").getString("observe"), warnings),
        attempts,
        Duration.ofSeconds(windowSeconds),
        burst,
        within(section, "ipv4-prefix", DEFAULT_IPV4_PREFIX, MIN_IPV4_PREFIX, MAX_IPV4_PREFIX,
            warnings),
        within(section, "ipv6-prefix", DEFAULT_IPV6_PREFIX, MIN_IPV6_PREFIX, MAX_IPV6_PREFIX,
            warnings),
        within(section, "max-networks", DEFAULT_MAX_NETWORKS, MIN_MAX_NETWORKS, MAX_MAX_NETWORKS,
            warnings),
        message,
        section.node("group-hosting-by-asn").getBoolean(false));
  }

  private static AdmissionConfig.Mode parseMode(final String value,
      final Consumer<String> warnings) {
    try {
      return AdmissionConfig.Mode.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException unknown) {
      // The fallback is the default, not the strict value: a typo must not start refusing
      // players on a limit nobody has measured yet.
      warnings.accept("network-rate.mode '" + value
          + "' is not 'enforce' or 'observe'; using 'observe'");
      return AdmissionConfig.Mode.OBSERVE;
    }
  }

  private static int atLeast(final ConfigurationNode section, final String key,
      final int fallback, final int minimum, final Consumer<String> warnings) {
    final int value = section.node(key).getInt(fallback);
    if (value >= minimum) {
      return value;
    }
    warnings.accept("network-rate." + key + ": " + value + " is below " + minimum + "; using "
        + fallback);
    return fallback;
  }

  private static int within(final ConfigurationNode section, final String key,
      final int fallback, final int minimum, final int maximum,
      final Consumer<String> warnings) {
    final int value = section.node(key).getInt(fallback);
    if (value >= minimum && value <= maximum) {
      return value;
    }
    warnings.accept("network-rate." + key + ": " + value + " is outside " + minimum + ".."
        + maximum + "; using " + fallback);
    return fallback;
  }
}
