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

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * The {@code asn} section of {@code config.yml}: the IP-to-ASN database and what the module does
 * with the autonomous system an address belongs to.
 *
 * <p>The database comes from iptoasn.com (public domain, PDDL v1.0). Knowing the system names it in
 * the journal and in {@code /admission why} whatever the settings below say; the two classifications
 * are both off until someone fills them in:
 * <ul>
 *   <li>{@code deny-asns}: AS numbers refused like a datacenter list (cause code
 *       {@code ADM-VPN-ASN}), in the mode of {@code mode};</li>
 *   <li>{@code observe-keywords}: words searched in the system's name; a hit is journaled and told
 *       to the staff, never refused.</li>
 * </ul>
 *
 * @param enabled whether the database is downloaded and consulted at all
 * @param mode {@code observe} journals what {@code deny-asns} would refuse, {@code enforce} refuses
 *     it; the module-wide {@code mode: observe} overrides {@code enforce} (see
 *     {@link AdmissionConfig#asnMode()})
 * @param denyAsns the AS numbers refused, unsigned 32-bit, never 0
 * @param observeKeywords lower-case words looked for in the name of the system
 * @param url where the database is downloaded from
 * @param maxDownloadBytes the most the compressed download may weigh
 * @param maxRows the most rows the file may hold; a longer file is not trusted
 */
public record AsnSettings(
    boolean enabled,
    AdmissionConfig.Mode mode,
    Set<Long> denyAsns,
    List<String> observeKeywords,
    URI url,
    long maxDownloadBytes,
    int maxRows) {

  /** The file kept in the list directory, named as iptoasn.com names it. */
  public static final String FILE_NAME = "ip2asn-combined.tsv.gz";

  /** The id the database is known by in the journal and in the quarantine messages. */
  public static final String SOURCE_ID = "iptoasn";

  /** The public download of the combined IPv4 and IPv6 table. */
  public static final URI DEFAULT_URL = URI.create("https://iptoasn.com/data/ip2asn-combined.tsv.gz");

  /** The real file weighs 9 MB (October 2026); 32 MB bounds what a host can send. */
  static final long DEFAULT_MAX_DOWNLOAD_MEGABYTES = 32;

  /** The real file has 723 000 rows (October 2026); this leaves room for years of growth. */
  static final int DEFAULT_MAX_ROWS = 2_000_000;

  private static final int MIN_MAX_ROWS = 1_000;
  private static final long MAX_ASN = 0xFFFF_FFFFL;
  private static final int MAX_ASN_DIGITS = 10;

  /**
   * Reads the {@code asn} section; a value that does not make sense is reported and replaced by
   * its default. The fallback of a mode typo is {@code observe}, not the strict value: it must not
   * start refusing players on an AS number nobody has measured yet.
   *
   * @param section the {@code asn} node, possibly absent
   * @param warnings receives one sentence per value that was replaced or ignored
   * @return the settings
   */
  static AsnSettings parse(final ConfigurationNode section, final Consumer<String> warnings) {
    final long megabytes = Math.max(1, section.node("max-download-megabytes")
        .getLong(DEFAULT_MAX_DOWNLOAD_MEGABYTES));
    final int maxRows = section.node("max-rows").getInt(DEFAULT_MAX_ROWS);
    return new AsnSettings(
        section.node("enabled").getBoolean(true),
        parseMode(section.node("mode").getString("observe"), warnings),
        parseDeny(section.node("deny-asns"), warnings),
        parseKeywords(section.node("observe-keywords")),
        parseUrl(section.node("url").getString(DEFAULT_URL.toString()), warnings),
        megabytes * 1024 * 1024,
        maxRows < MIN_MAX_ROWS ? warnedRows(maxRows, warnings) : maxRows);
  }

  /**
   * The settings used when nothing is configured.
   *
   * @return the defaults
   */
  static AsnSettings defaults() {
    return new AsnSettings(true, AdmissionConfig.Mode.OBSERVE, Set.of(), List.of(), DEFAULT_URL,
        DEFAULT_MAX_DOWNLOAD_MEGABYTES * 1024 * 1024, DEFAULT_MAX_ROWS);
  }

  /**
   * The database as a source for the download and the quarantine messages.
   *
   * @return the source: id {@value #SOURCE_ID}, class {@code asn}, file {@value #FILE_NAME}
   */
  public ListSource source() {
    return new ListSource(SOURCE_ID, IpClass.ASN, this.url, FILE_NAME);
  }

  /**
   * Whether the module refuses (or would, in observe mode) this system.
   *
   * @param number an AS number
   * @return {@code true} if it is in {@code deny-asns}
   */
  public boolean denies(final long number) {
    return this.denyAsns.contains(number);
  }

  /**
   * The first keyword found in a system's name.
   *
   * @param name the name the registry gives the system
   * @return the keyword, or empty when none is in the name
   */
  public Optional<String> keywordIn(final String name) {
    if (this.observeKeywords.isEmpty()) {
      return Optional.empty();
    }
    final String lowered = name.toLowerCase(Locale.ROOT);
    return this.observeKeywords.stream().filter(lowered::contains).findFirst();
  }

  /**
   * Whether the system is one the module takes for hosting: denied, or named by a keyword. It is
   * what the rate bound groups by system when it is told to.
   *
   * @param info the system
   * @return {@code true} if it is denied or its name holds a keyword
   */
  public boolean isHosting(final AsnDatabase.Info info) {
    return denies(info.number()) || keywordIn(info.name()).isPresent();
  }

  private static AdmissionConfig.Mode parseMode(final String value,
      final Consumer<String> warnings) {
    try {
      return AdmissionConfig.Mode.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException unknown) {
      warnings.accept("asn.mode '" + value + "' is not 'enforce' or 'observe'; using 'observe'");
      return AdmissionConfig.Mode.OBSERVE;
    }
  }

  private static Set<Long> parseDeny(final ConfigurationNode node,
      final Consumer<String> warnings) {
    final Set<Long> denied = new LinkedHashSet<>();
    for (ConfigurationNode child : node.childrenList()) {
      final String token = String.valueOf(child.raw()).trim();
      final long number = asNumber(token);
      if (number <= 0) {
        warnings.accept("asn.deny-asns: '" + token + "' is not an AS number (1 to 4294967295,"
            + " optionally written AS64496); ignored");
      } else {
        denied.add(number);
      }
    }
    return Set.copyOf(denied);
  }

  private static long asNumber(final String token) {
    final String digits = token.regionMatches(true, 0, "AS", 0, 2) ? token.substring(2) : token;
    if (digits.isEmpty() || digits.length() > MAX_ASN_DIGITS) {
      return -1;
    }
    long value = 0;
    for (int i = 0; i < digits.length(); i++) {
      final char c = digits.charAt(i);
      if (c < '0' || c > '9') {
        return -1;
      }
      value = value * 10 + (c - '0');
    }
    return value > MAX_ASN ? -1 : value;
  }

  private static List<String> parseKeywords(final ConfigurationNode node) {
    final List<String> keywords = new ArrayList<>();
    for (ConfigurationNode child : node.childrenList()) {
      final String word = String.valueOf(child.raw()).trim().toLowerCase(Locale.ROOT);
      if (!word.isEmpty() && !keywords.contains(word)) {
        keywords.add(word);
      }
    }
    return List.copyOf(keywords);
  }

  private static URI parseUrl(final String value, final Consumer<String> warnings) {
    try {
      final URI uri = URI.create(value.trim());
      if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null) {
        return uri;
      }
    } catch (IllegalArgumentException malformed) {
      // Reported below, with the other way a URL can be unusable.
    }
    warnings.accept("asn.url '" + value + "' is not an https URL; using " + DEFAULT_URL);
    return DEFAULT_URL;
  }

  private static int warnedRows(final int value, final Consumer<String> warnings) {
    warnings.accept("asn.max-rows: " + value + " is below " + MIN_MAX_ROWS + "; using "
        + DEFAULT_MAX_ROWS);
    return DEFAULT_MAX_ROWS;
  }
}
