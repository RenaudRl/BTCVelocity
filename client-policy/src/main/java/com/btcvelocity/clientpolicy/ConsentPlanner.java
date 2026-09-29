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

import java.util.Objects;

/**
 * Works out what consent a player must be sent on entering a backend, given what they already
 * received.
 *
 * <p>The client keeps the flags it was given: the Xaero codes stay applied when the player moves
 * to another backend, so a backend that declares nothing must actively put the flags back, or a
 * fair-play restriction would follow the player into a server that allows the minimap.
 */
public final class ConsentPlanner {

  private ConsentPlanner() {
  }

  /**
   * What a backend wants a player to be told.
   *
   * @param xaeroCode the Xaero code, empty when the backend declares none
   * @param voxelJson the VoxelMap settings JSON, {@code null} when that packet is switched off
   */
  public record Consent(String xaeroCode, String voxelJson) {
  }

  /**
   * What is actually sent this time.
   *
   * @param xaeroMessage the system message text, {@code null} when nothing is sent
   * @param voxelJson the settings JSON, {@code null} when nothing is sent
   */
  public record Delta(String xaeroMessage, String voxelJson) {

    /**
     * Whether there is nothing to send.
     *
     * @return {@code true} when both parts are absent
     */
    public boolean isEmpty() {
      return this.xaeroMessage == null && this.voxelJson == null;
    }
  }

  /**
   * The consent a backend wants.
   *
   * @param config the loaded configuration
   * @param serverName the Velocity server name
   * @return the consent
   */
  public static Consent desired(ClientPolicyConfig config, String serverName) {
    return new Consent(config.xaero().codeFor(serverName),
        config.voxelMap().jsonFor(serverName).orElse(null));
  }

  /**
   * The part of the desired consent that differs from what the player last received.
   *
   * @param previous what was last sent to this session, {@code null} if nothing was
   * @param wanted what the new backend wants
   * @return what to send, possibly nothing
   */
  public static Delta delta(Consent previous, Consent wanted) {
    String before = previous == null ? "" : previous.xaeroCode();
    String xaero = null;
    if (!wanted.xaeroCode().equals(before)) {
      xaero = wanted.xaeroCode().isEmpty() ? XaeroConsent.RESET : wanted.xaeroCode();
    }

    String voxel = null;
    if (wanted.voxelJson() != null
        && (previous == null || !Objects.equals(previous.voxelJson(), wanted.voxelJson()))) {
      voxel = wanted.voxelJson();
    }
    return new Delta(xaero, voxel);
  }
}
