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

import java.util.List;
import java.util.Locale;

/**
 * The one consistency check the declarations allow: a client that says it is vanilla yet registers
 * channels that belong to mods (rule taken from AntiSpoof).
 *
 * <p>This is a clue for the staff, never grounds for action: ViaVersion, proxies and custom
 * launchers produce it too.
 */
final class ClientCoherence {

  private static final String VANILLA = "vanilla";
  private static final String VANILLA_NAMESPACE = "minecraft";
  private static final char NAMESPACE_SEPARATOR = ':';

  private ClientCoherence() {
  }

  /**
   * The channels that do not belong to the game itself.
   *
   * @param channels the declared channels
   * @return those outside the {@code minecraft} namespace; a legacy channel with no namespace
   *     counts as foreign
   */
  static List<String> modChannels(List<String> channels) {
    return channels.stream().filter(channel -> !isVanillaChannel(channel)).toList();
  }

  /**
   * Whether the session contradicts itself.
   *
   * @param brand the declared brand, may be {@code null}
   * @param channels the declared channels
   * @return {@code true} for the brand {@code vanilla} together with a foreign channel
   */
  static boolean isVanillaWithModChannels(String brand, List<String> channels) {
    return brand != null
        && VANILLA.equals(brand.trim().toLowerCase(Locale.ROOT))
        && !modChannels(channels).isEmpty();
  }

  private static boolean isVanillaChannel(String channel) {
    int separator = channel.indexOf(NAMESPACE_SEPARATOR);
    return separator > 0
        && VANILLA_NAMESPACE.equals(channel.substring(0, separator).toLowerCase(Locale.ROOT));
  }
}
