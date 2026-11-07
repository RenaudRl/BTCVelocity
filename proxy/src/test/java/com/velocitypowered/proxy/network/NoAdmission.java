/*
 * Copyright (C) 2018-2026 Velocity Contributors
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

package com.velocitypowered.proxy.network;

import com.btcvelocity.proxy.security.AdmissionStats;
import com.btcvelocity.proxy.security.ConnectionAdmission;
import com.btcvelocity.proxy.security.HandshakeHosts;
import java.util.List;

/** The pre-handshake checks as shipped: everything off. For tests that mock the server. */
final class NoAdmission {

  private NoAdmission() {
  }

  static ConnectionAdmission checks() {
    return new ConnectionAdmission(
        HandshakeHosts.of(HandshakeHosts.Mode.OFF, List.of(), List.of()), new AdmissionStats());
  }
}
