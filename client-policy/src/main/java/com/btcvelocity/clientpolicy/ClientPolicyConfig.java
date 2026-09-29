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
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * The validated configuration of the client policy.
 *
 * <p>Loading is all or nothing: a configuration with one error yields no configuration at all, and
 * the plugin then stays off while the proxy starts normally. A deny list half-applied would be
 * worse than none, since the operator believes it protects.
 *
 * @param mode whether a matching rule refuses the player or only says so in the journal
 * @param bypassPermission the permission that exempts a player from refusal
 * @param viewPermission the permission that opens {@code /clientpolicy}
 * @param xaero the Xaero consent
 * @param voxelMap the VoxelMap-Updated consent
 * @param deny the deny list, empty by default
 */
public record ClientPolicyConfig(Mode mode, String bypassPermission, String viewPermission,
                                 XaeroConsent xaero, VoxelMapConsent voxelMap,
                                 List<DenyRule> deny) {

  /** Default bypass permission. */
  public static final String DEFAULT_BYPASS = "btc.clientpolicy.bypass";
  /** Default permission for the command. */
  public static final String DEFAULT_VIEW = "btc.clientpolicy.view";

  public ClientPolicyConfig {
    deny = List.copyOf(deny);
  }

  /** How the deny list acts. */
  public enum Mode {
    /** Journal "would refuse", touch nobody. The default. */
    DRY_RUN,
    /** Disconnect the player with the rule's message. */
    ENFORCE
  }

  /**
   * The outcome of reading a configuration.
   *
   * @param config the configuration, empty when {@code errors} is not
   * @param errors every problem found, in order
   */
  public record Parsed(Optional<ClientPolicyConfig> config, List<String> errors) {
    public Parsed {
      errors = List.copyOf(errors);
    }
  }

  /**
   * The configuration of a proxy that declared nothing: dry-run, fair play, no rule.
   *
   * @return the built-in configuration
   */
  public static ClientPolicyConfig builtIn() {
    return new ClientPolicyConfig(Mode.DRY_RUN, DEFAULT_BYPASS, DEFAULT_VIEW,
        XaeroConsent.fairPlayEverywhere(), VoxelMapConsent.fairPlay(), List.of());
  }

  /**
   * Reads and validates a YAML document.
   *
   * @param text the file content
   * @return the configuration, or every error found (a syntax error included)
   */
  public static Parsed parseYaml(String text) {
    LoaderOptions options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    try {
      return parse(new Yaml(new SafeConstructor(options)).load(text));
    } catch (YAMLException invalid) {
      return new Parsed(Optional.empty(), List.of("invalid YAML: " + invalid.getMessage()));
    }
  }

  /**
   * Validates a parsed YAML tree.
   *
   * @param root the document root, {@code null} for an empty file
   * @return the configuration, or every error found
   */
  public static Parsed parse(Object root) {
    List<String> errors = new ArrayList<>();
    Map<String, Object> map = ConfigNodes.asMap(root, "(root)", errors);

    Mode mode = parseMode(map.get("mode"), errors);
    Map<String, Object> permissions = ConfigNodes.asMap(map.get("permissions"), "permissions", errors);
    Map<String, Object> consent = ConfigNodes.asMap(map.get("consent"), "consent", errors);

    ClientPolicyConfig config = new ClientPolicyConfig(mode,
        ConfigNodes.asText(permissions.get("bypass"), DEFAULT_BYPASS),
        ConfigNodes.asText(permissions.get("view"), DEFAULT_VIEW),
        XaeroConsent.parse(consent.get("xaero"), errors),
        VoxelMapConsent.parse(consent.get("voxelmap"), errors),
        DenyRule.parseAll(map.get("deny"), errors));
    return new Parsed(errors.isEmpty() ? Optional.of(config) : Optional.empty(), errors);
  }

  private static Mode parseMode(Object node, List<String> errors) {
    if (node == null) {
      return Mode.DRY_RUN;
    }
    return switch (node.toString().trim().toLowerCase(Locale.ROOT)) {
      case "dry-run" -> Mode.DRY_RUN;
      case "enforce" -> Mode.ENFORCE;
      default -> {
        errors.add("mode: expected 'dry-run' or 'enforce', found '" + node + "'");
        yield Mode.DRY_RUN;
      }
    };
  }
}
