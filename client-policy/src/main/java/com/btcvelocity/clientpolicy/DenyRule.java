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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * One line of the deny list: a client that declares itself a cheat client.
 *
 * <p>The pattern is a Java <strong>regular expression</strong>, matched case-insensitively against
 * the <em>whole</em> value (not a substring: write {@code .*wurst.*} to match a substring). A
 * regex was chosen over a glob because the value to catch is a free-form brand such as
 * {@code lunarclient:v1.2.3-4567}, and because the guard below needs to test an arbitrary pattern
 * against the protected brands.
 *
 * <p><strong>The channel guard:</strong> a channel rule may not match any of
 * {@link #PROTECTED_CHANNELS} either (loader, Xaero, VoxelMap, JourneyMap channels).
 *
 * <p><strong>The guard:</strong> a rule whose pattern matches {@code fabric}, {@code forge},
 * {@code neoforge}, {@code vanilla} or {@code quilt} is rejected when the configuration loads.
 * Those are the brands of every honest player, comfort mods included; a cheat client such as
 * Wurst or Meteor announces itself with the same word, so refusing it would refuse everybody and
 * catch nobody (research of 29/09/2026).
 *
 * @param label the name the staff sees in the journal and in {@code /clientpolicy}
 * @param target what the pattern is matched against
 * @param pattern the compiled pattern
 * @param message the disconnect message, MiniMessage syntax
 */
public record DenyRule(String label, Target target, Pattern pattern, String message) {

  /** Brands no rule may match, whatever the pattern. */
  static final List<String> PROTECTED_BRANDS =
      List.of("fabric", "forge", "neoforge", "vanilla", "quilt");

  /**
   * Channels of honest mods and of the game itself; no rule may match any of them. Xaero,
   * VoxelMap, JourneyMap and the loaders register these on every comfort-mod install, so a
   * pattern that covers one would refuse the players the policy exists to leave alone.
   */
  static final List<String> PROTECTED_CHANNELS = List.of(
      "fabric:register", "c:register", "c:version", "fml:handshake", "forge:handshake",
      "neoforge:register", "xaerominimap:main", "xaeroworldmap:main", "voxelmap:settings",
      "journeymap:version", "minecraft:brand", "minecraft:register");

  /** Shown when a rule has no message of its own. */
  static final String DEFAULT_MESSAGE = "<red>This client is not allowed on this server.</red>";

  /** What a rule looks at. */
  public enum Target {
    /** The {@code minecraft:brand} string. */
    BRAND,
    /** The plugin channels the client registered. */
    CHANNEL
  }

  /**
   * The value that made this rule match, if any.
   *
   * @param brand the client brand, may be {@code null} while undeclared
   * @param channels the channels declared so far
   * @return the brand or the first channel matched, empty when the rule does not match
   */
  public Optional<String> matches(String brand, List<String> channels) {
    if (this.target == Target.BRAND) {
      return brand != null && this.pattern.matcher(brand).matches()
          ? Optional.of(brand) : Optional.empty();
    }
    return channels.stream().filter(channel -> this.pattern.matcher(channel).matches()).findFirst();
  }

  /**
   * Reads the {@code deny} list.
   *
   * @param node the parsed list, may be {@code null}
   * @param errors where problems are reported
   * @return the valid rules; a problem with any rule is reported and that rule is left out, and
   *     the caller refuses the whole configuration as soon as {@code errors} is not empty
   */
  static List<DenyRule> parseAll(Object node, List<String> errors) {
    List<DenyRule> rules = new ArrayList<>();
    List<Object> entries = ConfigNodes.asList(node, "deny", errors);
    for (int index = 0; index < entries.size(); index++) {
      parse(entries.get(index), "deny[" + index + "]", errors).ifPresent(rules::add);
    }
    return List.copyOf(rules);
  }

  private static Optional<DenyRule> parse(Object entry, String path, List<String> errors) {
    if (!(entry instanceof Map<?, ?>)) {
      errors.add(path + ": expected a mapping with label, brand or channel, message");
      return Optional.empty();
    }
    Map<String, Object> map = ConfigNodes.asMap(entry, path, errors);
    String label = ConfigNodes.asText(map.get("label"), null);
    if (label == null) {
      errors.add(path + ": missing label");
      return Optional.empty();
    }

    boolean hasBrand = map.get("brand") != null;
    boolean hasChannel = map.get("channel") != null;
    if (hasBrand == hasChannel) {
      errors.add(path + " (" + label + "): give exactly one of brand or channel");
      return Optional.empty();
    }
    Target target = hasBrand ? Target.BRAND : Target.CHANNEL;
    String raw = ConfigNodes.asText(map.get(hasBrand ? "brand" : "channel"), "");

    Pattern pattern;
    try {
      pattern = Pattern.compile(raw, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    } catch (PatternSyntaxException invalid) {
      errors.add(path + " (" + label + "): invalid regular expression: " + invalid.getDescription());
      return Optional.empty();
    }
    if (raw.isEmpty()) {
      errors.add(path + " (" + label + "): empty pattern");
      return Optional.empty();
    }
    if (target == Target.CHANNEL) {
      for (String protectedChannel : PROTECTED_CHANNELS) {
        if (pattern.matcher(protectedChannel).matches()) {
          errors.add(path + " (" + label + "): pattern '" + raw + "' matches the channel '"
              + protectedChannel + "', which honest mods register; a channel rule must name"
              + " a cheat client's own channel");
          return Optional.empty();
        }
      }
    }
    for (String protectedBrand : PROTECTED_BRANDS) {
      if (pattern.matcher(protectedBrand).matches()) {
        errors.add(path + " (" + label + "): pattern '" + raw + "' matches the brand '"
            + protectedBrand.toLowerCase(Locale.ROOT) + "', which every honest player declares;"
            + " a rule may never cover fabric, forge, neoforge, vanilla or quilt");
        return Optional.empty();
      }
    }
    return Optional.of(new DenyRule(label, target, pattern,
        ConfigNodes.asText(map.get("message"), DEFAULT_MESSAGE)));
  }
}
