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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * The module's settings, read from {@code config.yml} in its data directory.
 *
 * <p>Reading is forgiving on purpose: a value that does not parse is reported and replaced by its
 * default, and a file that cannot be read at all yields the defaults. A typo in this file must
 * never stop the proxy from starting, nor open the door: the defaults are the strict ones
 * ({@code enforce}, datacenter and Tor refused).
 *
 * @param mode whether listed connections are refused or only journaled
 * @param geyserAddresses the addresses Geyser connects from: a Floodgate player coming from one
 *     is not judged on it. Empty by default, until the bench has measured what the proxy sees
 * @param exemptions the players and networks let in even when listed
 * @param messages the text shown to a refused player, per class, with a {@code {code}} placeholder
 * @param sources the lists to load and refresh
 * @param refreshInterval how often the lists are downloaded again
 * @param downloadTimeout how long one download may take, all included
 * @param maxDownloadBytes the most one downloaded list may weigh
 * @param maxEntries the most entries one list may hold; a longer list is not trusted
 * @param embeddedBaseline whether a default list with no previous version (first start, or a
 *     list directory that is not kept) is judged against the bounds shipped with the module
 * @param quarantine how long a changed version of a list in use is held aside before it may
 *     refuse anybody (see {@link ListQuarantine}); zero puts it in use at once
 * @param quarantineMaxExtraPlayers the most authenticated players a version may have refused in
 *     addition during its quarantine and still be promoted
 * @param networkRate the bound on connection attempts per network
 */
public record AdmissionConfig(
    Mode mode,
    CidrSet geyserAddresses,
    Exemptions exemptions,
    Map<IpClass, String> messages,
    List<ListSource> sources,
    Duration refreshInterval,
    Duration downloadTimeout,
    long maxDownloadBytes,
    int maxEntries,
    boolean embeddedBaseline,
    Duration quarantine,
    int quarantineMaxExtraPlayers,
    NetworkRateSettings networkRate) {

  /** The default ceiling on the entries of one list, well above the largest default list. */
  public static final int DEFAULT_MAX_ENTRIES = 500_000;

  /** Whether a listed connection is turned away or only written down. */
  public enum Mode {
    /** Listed connections are refused. The default. */
    ENFORCE,
    /** Listed connections are journaled as "observed" and let in. */
    OBSERVE
  }

  private static final String RESOURCE = "/config.yml";
  private static final Pattern SAFE_FILE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
  private static final Duration DEFAULT_REFRESH = Duration.ofHours(24);
  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
  /** The largest default list is 0.7 MB (October 2026); 8 MB bounds what a host can send. */
  private static final long DEFAULT_MAX_BYTES = 8L * 1024 * 1024;
  private static final Duration DEFAULT_QUARANTINE = Duration.ofHours(6);
  private static final int DEFAULT_QUARANTINE_MAX_EXTRA_PLAYERS = 0;

  private static final String DEFAULT_DATACENTER_MESSAGE =
      "<red>Connexion refusée.</red>\n<gray>Votre adresse appartient à un réseau"
          + " d'hébergement ou de VPN. Si vous n'utilisez pas de VPN, contactez le staff"
          + " avec le code <white>{code}</white>.</gray>";
  private static final String DEFAULT_TOR_MESSAGE =
      "<red>Connexion refusée.</red>\n<gray>Les connexions depuis le réseau Tor ne"
          + " sont pas acceptées. Contactez le staff avec le code"
          + " <white>{code}</white>.</gray>";

  /**
   * The default lists. X4BNet {@code lists_vpn} (MIT) for datacenters in both families; the
   * FireHOL {@code tor_exits} ipset, built from the Tor Project's own exit list, for Tor. That
   * ipset is IPv4 only; IPv6 Tor exits are not covered.
   *
   * @return the default sources
   */
  public static List<ListSource> defaultSources() {
    return List.of(
        new ListSource("x4bnet-datacenter-ipv4", IpClass.DATACENTER, URI.create(
            "https://raw.githubusercontent.com/X4BNet/lists_vpn/main/output/datacenter/ipv4.txt"),
            "x4bnet-datacenter-ipv4.txt"),
        new ListSource("x4bnet-datacenter-ipv6", IpClass.DATACENTER, URI.create(
            "https://raw.githubusercontent.com/X4BNet/lists_vpn/main/output/datacenter/ipv6.txt"),
            "x4bnet-datacenter-ipv6.txt"),
        new ListSource("firehol-tor-exits", IpClass.TOR, URI.create(
            "https://raw.githubusercontent.com/firehol/blocklist-ipsets/master/tor_exits.ipset"),
            "firehol-tor-exits.ipset"));
  }

  /**
   * The settings used when nothing is configured.
   *
   * @return the defaults
   */
  public static AdmissionConfig defaults() {
    return parse(YamlConfigurationLoader.builder().build().createNode(), warning -> { });
  }

  /**
   * Reads {@code config.yml}, creating it from the bundled template on first start.
   *
   * @param file the configuration file
   * @param logger where problems are reported
   * @return the settings; the defaults if the file cannot be read
   */
  public static AdmissionConfig loadOrCreate(final Path file, final Logger logger) {
    try {
      if (Files.notExists(file)) {
        writeTemplate(file);
        logger.info("Wrote the default configuration to {}.", file);
      }
      try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
        return parseYaml(reader, warning -> logger.warn("config.yml: {}", warning),
            error -> logger.error("config.yml: {}", error));
      }
    } catch (IOException | RuntimeException unreadable) {
      logger.error("Could not read {}: {}. Using the strict defaults.", file,
          unreadable.toString());
      return defaults();
    }
  }

  /**
   * Parses a YAML document.
   *
   * @param source the YAML text
   * @param warnings receives one sentence per value that was ignored
   * @return the settings
   * @throws IOException if the text is not valid YAML
   */
  public static AdmissionConfig parseYaml(final Reader source, final Consumer<String> warnings)
      throws IOException {
    return parseYaml(source, warnings, warnings);
  }

  /**
   * Parses a YAML document, keeping errors apart from warnings.
   *
   * @param source the YAML text
   * @param warnings receives one sentence per value that was ignored
   * @param errors receives one sentence per entry refused because it would be unsafe as written
   * @return the settings
   * @throws IOException if the text is not valid YAML
   */
  public static AdmissionConfig parseYaml(final Reader source, final Consumer<String> warnings,
      final Consumer<String> errors) throws IOException {
    return parseYaml(source, warnings, errors, Clock.systemUTC());
  }

  /**
   * Parses a YAML document, judging the end dates of the exemptions against a given clock.
   *
   * @param source the YAML text
   * @param warnings receives one sentence per value that was ignored
   * @param errors receives one sentence per entry refused because it would be unsafe as written
   * @param clock the source of "today" for the {@code expires} dates, in UTC
   * @return the settings
   * @throws IOException if the text is not valid YAML
   */
  public static AdmissionConfig parseYaml(final Reader source, final Consumer<String> warnings,
      final Consumer<String> errors, final Clock clock) throws IOException {
    final BufferedReader reader =
        source instanceof BufferedReader buffered ? buffered : new BufferedReader(source);
    try {
      return parse(YamlConfigurationLoader.builder().source(() -> reader).build().load(),
          warnings, errors, clock);
    } catch (ConfigurateException malformed) {
      throw new IOException(malformed.getMessage(), malformed);
    }
  }

  /**
   * Reads the settings from a parsed document.
   *
   * @param root the document root
   * @param warnings receives one sentence per value that was ignored
   * @return the settings
   */
  public static AdmissionConfig parse(final ConfigurationNode root,
      final Consumer<String> warnings) {
    return parse(root, warnings, warnings);
  }

  /**
   * Reads the settings from a parsed document, keeping errors apart from warnings.
   *
   * @param root the document root
   * @param warnings receives one sentence per value that was ignored
   * @param errors receives one sentence per entry refused because it would be unsafe as written
   * @return the settings
   */
  public static AdmissionConfig parse(final ConfigurationNode root,
      final Consumer<String> warnings, final Consumer<String> errors) {
    return parse(root, warnings, errors, Clock.systemUTC());
  }

  /**
   * Reads the settings from a parsed document, judging the end dates of the exemptions against a
   * given clock.
   *
   * @param root the document root
   * @param warnings receives one sentence per value that was ignored
   * @param errors receives one sentence per entry refused because it would be unsafe as written
   * @param clock the source of "today" for the {@code expires} dates, in UTC
   * @return the settings
   */
  public static AdmissionConfig parse(final ConfigurationNode root,
      final Consumer<String> warnings, final Consumer<String> errors, final Clock clock) {
    final Mode mode = parseMode(root.node("mode").getString("enforce"), warnings);
    // Empty by default: until the bench has measured the address Geyser connects from (13.1),
    // no address is spared its judgement.
    final CidrSet geyser = parseNetworks(
        root.node("geyser-addresses"), List.of(), "geyser-addresses", warnings);
    final Exemptions exemptions = Exemptions.parse(root.node("exemptions", "players"),
        root.node("exemptions", "networks"), clock, warnings, errors);

    final ConfigurationNode lists = root.node("lists");
    final Duration refresh = Duration.ofMinutes(Math.max(1, Math.round(
        lists.node("refresh-hours").getDouble(DEFAULT_REFRESH.toHours()) * 60)));
    final Duration timeout = Duration.ofSeconds(Math.max(1,
        lists.node("download-timeout-seconds").getLong(DEFAULT_TIMEOUT.toSeconds())));
    final long maxBytes = Math.max(1, lists.node("max-download-megabytes")
        .getLong(DEFAULT_MAX_BYTES / (1024 * 1024))) * 1024 * 1024;
    final int maxEntries = Math.max(1, lists.node("max-entries").getInt(DEFAULT_MAX_ENTRIES));
    final boolean embeddedBaseline = lists.node("embedded-baseline").getBoolean(true);
    final Duration quarantine = Duration.ofMinutes(Math.max(0, Math.round(
        lists.node("quarantine-hours").getDouble(DEFAULT_QUARANTINE.toHours()) * 60)));
    final List<ListSource> sources = lists.node("sources").empty()
        ? defaultSources() : parseSources(lists.node("sources"), warnings);

    return new AdmissionConfig(mode, geyser, exemptions, parseMessages(root.node("messages")),
        sources, refresh, timeout, maxBytes, maxEntries, embeddedBaseline, quarantine,
        Math.max(0, lists.node("quarantine-max-extra-players")
            .getInt(DEFAULT_QUARANTINE_MAX_EXTRA_PLAYERS)),
        NetworkRateSettings.parse(root.node("network-rate"),
            root.node("messages", "network-rate").getString(NetworkRateSettings.DEFAULT_MESSAGE),
            warnings));
  }

  /**
   * The text to show a refused player.
   *
   * @param ipClass the class that caused the refusal
   * @return the MiniMessage text, with {@code {code}} still to be replaced
   */
  public String messageFor(final IpClass ipClass) {
    return this.messages.get(ipClass);
  }

  /**
   * The text to show a player this verdict refuses, its cause code already written in.
   *
   * @param refusal a verdict that refuses a connection
   * @return the MiniMessage text
   */
  public String refusalText(final Verdict refusal) {
    final String code = refusal.causeCode();
    final String template = refusal.basis() == Verdict.Basis.RATE_NETWORK
        ? this.networkRate.message() : messageFor(refusal.ipClass());
    return template.replace("{code}", code == null ? "" : code);
  }

  /**
   * The mode the network bound runs in. The module-wide {@code observe} is a dry run for
   * everything the module does, so it also holds the bound back.
   *
   * @return {@code observe} if either the module or the bound is in {@code observe}
   */
  public Mode rateMode() {
    return this.mode == Mode.OBSERVE ? Mode.OBSERVE : this.networkRate.mode();
  }

  /**
   * The mode in force for the rule behind a verdict, as the journal states it.
   *
   * @param verdict the verdict
   * @return the network bound's mode for a rate verdict, the module's otherwise
   */
  public Mode modeFor(final Verdict verdict) {
    return verdict.basis() == Verdict.Basis.RATE_NETWORK ? rateMode() : this.mode;
  }

  private static Mode parseMode(final String value, final Consumer<String> warnings) {
    try {
      return Mode.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException unknown) {
      warnings.accept("mode '" + value + "' is not 'enforce' or 'observe'; using 'enforce'");
      return Mode.ENFORCE;
    }
  }

  private static CidrSet parseNetworks(final ConfigurationNode node, final List<String> fallback,
      final String path, final Consumer<String> warnings) {
    final List<String> tokens = new ArrayList<>();
    if (node.virtual()) {
      tokens.addAll(fallback);
    } else {
      node.childrenList().forEach(child -> tokens.add(String.valueOf(child.raw())));
    }
    final CidrSet.Builder builder = CidrSet.builder();
    for (String token : tokens) {
      if (!builder.add(token)) {
        warnings.accept(path + ": '" + token + "' is not an address or CIDR; ignored");
      }
    }
    return builder.build();
  }

  private static Map<IpClass, String> parseMessages(final ConfigurationNode node) {
    final Map<IpClass, String> messages = new EnumMap<>(IpClass.class);
    messages.put(IpClass.DATACENTER,
        node.node("datacenter").getString(DEFAULT_DATACENTER_MESSAGE));
    messages.put(IpClass.TOR, node.node("tor").getString(DEFAULT_TOR_MESSAGE));
    return Map.copyOf(messages);
  }

  private static List<ListSource> parseSources(final ConfigurationNode node,
      final Consumer<String> warnings) {
    final List<ListSource> sources = new ArrayList<>();
    for (Map.Entry<Object, ? extends ConfigurationNode> entry : node.childrenMap().entrySet()) {
      final String id = String.valueOf(entry.getKey());
      final ConfigurationNode item = entry.getValue();
      final IpClass ipClass = IpClass.fromKey(item.node("class").getString("")).orElse(null);
      final String url = item.node("url").getString("");
      final String file = item.node("file").getString("");
      final URI uri = parseHttps(url);
      if (ipClass == null || uri == null || !SAFE_FILE_NAME.matcher(file).matches()
          || file.contains("..")) {
        warnings.accept("lists.sources." + id + ": needs class (datacenter|tor), an https url"
            + " and a plain file name; ignored");
        continue;
      }
      sources.add(new ListSource(id, ipClass, uri, file));
    }
    return List.copyOf(sources);
  }

  private static URI parseHttps(final String url) {
    try {
      final URI uri = URI.create(url);
      return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null ? uri : null;
    } catch (IllegalArgumentException malformed) {
      return null;
    }
  }

  private static void writeTemplate(final Path file) throws IOException {
    try (InputStream template = AdmissionConfig.class.getResourceAsStream(RESOURCE)) {
      if (template == null) {
        throw new IOException("bundled " + RESOURCE + " is missing from the jar");
      }
      Files.createDirectories(file.toAbsolutePath().getParent());
      Files.copy(template, file);
    }
  }
}
