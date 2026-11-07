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

package com.btcvelocity.proxy.security;

import com.velocitypowered.proxy.util.except.QuietDecoderException;

/**
 * Thrown by a decoder when the client went past the {@code [packet-limiter]} limits, so the
 * connection can tell this apart from a malformed packet and say why it closes.
 */
public final class PacketLimitExceededException extends QuietDecoderException {

  /** Creates the exception; it carries no address, the connection logs who it was. */
  public PacketLimitExceededException() {
    super("Packet limit exceeded");
  }
}
