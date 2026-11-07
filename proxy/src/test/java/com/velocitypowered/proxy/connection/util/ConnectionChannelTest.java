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

package com.velocitypowered.proxy.connection.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.btcvelocity.api.connection.ConnectionChannel;
import com.velocitypowered.api.proxy.InboundConnection;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** A plugin finds the Netty channel of a player or inbound connection through the API. */
class ConnectionChannelTest {

  @Test
  void anInboundConnectionGivesTheChannelOfItsMinecraftConnection() {
    final Channel channel = new EmbeddedChannel();
    final MinecraftConnection connection = mock(MinecraftConnection.class);
    when(connection.getChannel()).thenReturn(channel);
    final VelocityInboundConnection inbound = mock(VelocityInboundConnection.class, CALLS_REAL_METHODS);
    doReturn(connection).when(inbound).getConnection();

    final Optional<Channel> found = ConnectionChannel.of(inbound);

    assertSame(channel, found.orElseThrow());
  }

  @Test
  void aConnectionOfAnotherOriginHasNoChannel() {
    assertEquals(Optional.empty(), ConnectionChannel.of(mock(InboundConnection.class)));
  }
}
