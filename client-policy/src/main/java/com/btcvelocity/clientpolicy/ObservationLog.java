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

import com.google.gson.JsonParser;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Formats the structured, single-line journal entries of the client policy.
 *
 * <p>Every line starts with {@code client-policy} and carries {@code key=value} pairs so the
 * journal can be filtered and counted without a parser. Everything the client sent is untrusted
 * text: it is stripped of control characters and quotes and cut short before it is written, so a
 * hostile brand cannot forge a second line or break the quoting.
 */
final class ObservationLog {

  /** Name of the dedicated logger. */
  static final String LOGGER_NAME = "btc.clientpolicy";

  private static final String PREFIX = "client-policy";
  private static final int MAX_VALUE_LENGTH = 96;
  private static final int MAX_LISTED = 24;
  private static final char SECTION = (char) 0xA7;

  private ObservationLog() {
  }

  /**
   * A brand or channel change.
   *
   * @param who the session
   * @param origin what the proxy measured of the platform
   * @param observation the change
   * @return the journal line
   */
  static String observation(Who who, ClientOrigin origin, ClientSession.Observation observation) {
    String event = switch (observation.kind()) {
      case BRAND_DECLARED -> "brand-declared";
      case BRAND_CHANGED -> "brand-changed";
      case CHANNELS_ADDED -> "channels-added";
      case CHANNELS_REMOVED -> "channels-removed";
    };
    StringBuilder line = head(who, origin, event);
    switch (observation.kind()) {
      case CHANNELS_ADDED -> line.append(" added=").append(list(observation.values()));
      case CHANNELS_REMOVED -> line.append(" removed=").append(list(observation.values()));
      default -> { }
    }
    line.append(" brand=").append(quote(observation.brand()));
    line.append(" channels=").append(observation.channelTotal());
    return line.toString();
  }

  /**
   * The vanilla-with-mod-channels clue.
   *
   * @param who the session
   * @param origin what the proxy measured of the platform
   * @param brand the declared brand
   * @param modChannels the channels outside the {@code minecraft} namespace
   * @return the journal line
   */
  static String inconsistency(Who who, ClientOrigin origin, String brand,
                              List<String> modChannels) {
    return head(who, origin, "inconsistency") + " detail=\"brand vanilla with non-minecraft"
        + " channels\" brand=" + quote(brand) + " modChannels=" + list(modChannels)
        + " action=none";
  }

  /**
   * The one-off notice that a session hit the channel cap.
   *
   * @param who the session
   * @param origin what the proxy measured of the platform
   * @param dropped how many channels were refused storage so far
   * @return the journal line
   */
  static String overflow(Who who, ClientOrigin origin, int dropped) {
    return head(who, origin, "channels-overflow") + " cap=" + ClientSession.MAX_CHANNELS
        + " dropped=" + dropped + " (further drops are counted, not logged)";
  }

  /**
   * The explicit Bedrock notice.
   *
   * @param who the session
   * @return the journal line
   */
  static String bedrock(Who who) {
    return PREFIX + " player=" + safe(who.name()) + " uuid=" + who.uuid()
        + " origin BEDROCK, client policy n/a";
  }

  /**
   * A deny-list verdict other than "nothing matched".
   *
   * @param who the session
   * @param origin what the proxy measured of the platform
   * @param verdict the verdict
   * @param mode the configured mode
   * @return the journal line
   */
  static String verdict(Who who, ClientOrigin origin, RefusalPolicy.Verdict verdict,
                        ClientPolicyConfig.Mode mode) {
    String event = switch (verdict.action()) {
      case WOULD_REFUSE -> "would-refuse";
      case REFUSE -> "refused";
      case EXEMPT_BEDROCK -> "exempt-bedrock";
      case EXEMPT_UNKNOWN -> "exempt-unknown";
      case EXEMPT_BYPASS -> "exempt-bypass";
      case NONE -> "no-match";
    };
    DenyRule rule = verdict.rule().orElseThrow();
    if (verdict.action() == RefusalPolicy.Action.EXEMPT_UNKNOWN) {
      return PREFIX + " player=" + safe(who.name()) + " uuid=" + who.uuid()
          + " origin UNKNOWN, refusal skipped rule=" + quote(rule.label()) + " target="
          + rule.target() + " value=" + quote(verdict.value().orElse("")) + " mode="
          + (mode == ClientPolicyConfig.Mode.ENFORCE ? "enforce" : "dry-run");
    }
    return head(who, origin, event) + " rule=" + quote(rule.label()) + " target="
        + rule.target() + " value=" + quote(verdict.value().orElse("")) + " mode="
        + (mode == ClientPolicyConfig.Mode.ENFORCE ? "enforce" : "dry-run");
  }

  /**
   * A consent sent on entering a backend.
   *
   * @param who the session
   * @param origin what the proxy measured of the platform
   * @param server the backend name
   * @param delta what was sent
   * @return the journal line
   */
  static String consent(Who who, ClientOrigin origin, String server, ConsentPlanner.Delta delta) {
    String xaero = delta.xaeroMessages().isEmpty() ? "none"
        : delta.xaeroMessages().stream().map(code -> code.replace(String.valueOf(SECTION), ""))
            .collect(Collectors.joining("+"));
    return head(who, origin, "consent-sent") + " server=" + quote(server) + " xaero="
        + quote(xaero) + " voxelmap=" + quote(readable(delta.voxelJson()));
  }

  /** Renders the settings JSON we built ourselves as {@code key=value, ...}. */
  private static String readable(String json) {
    if (json == null) {
      return "none";
    }
    return JsonParser.parseString(json).getAsJsonObject().entrySet().stream()
        .map(entry -> entry.getKey() + "=" + entry.getValue().getAsString())
        .collect(Collectors.joining(", "));
  }

  private static StringBuilder head(Who who, ClientOrigin origin, String event) {
    return new StringBuilder(PREFIX).append(" player=").append(safe(who.name()))
        .append(" uuid=").append(who.uuid()).append(" origin=").append(origin)
        .append(" event=").append(event);
  }

  private static String list(List<String> values) {
    String joined = values.stream().limit(MAX_LISTED).map(ObservationLog::quote)
        .collect(Collectors.joining(","));
    String more = values.size() > MAX_LISTED ? ",+" + (values.size() - MAX_LISTED) : "";
    return "[" + joined + more + "]";
  }

  /**
   * Makes untrusted text safe to embed between quotes on one line.
   *
   * @param value text sent by a client, may be {@code null}
   * @return the value in double quotes, control characters and quotes replaced, cut to length
   */
  static String quote(String value) {
    return "\"" + safe(value) + "\"";
  }

  private static String safe(String value) {
    if (value == null) {
      return "?";
    }
    StringBuilder clean = new StringBuilder();
    value.codePoints().limit(MAX_VALUE_LENGTH).forEach(point -> {
      boolean printable = !Character.isISOControl(point) && point != '"' && point != '\\';
      clean.appendCodePoint(printable ? point : '_');
    });
    if (value.codePointCount(0, value.length()) > MAX_VALUE_LENGTH) {
      clean.append("...");
    }
    return clean.toString();
  }

  /**
   * The session a line is about.
   *
   * @param name the account name
   * @param uuid the account id
   */
  record Who(String name, UUID uuid) {
  }
}
