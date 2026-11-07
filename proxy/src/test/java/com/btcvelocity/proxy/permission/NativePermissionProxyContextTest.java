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

package com.btcvelocity.proxy.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.ServerInfo;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The proxy evaluates every permission in one constant context ({@code server=proxy}), whichever
 * backend the player sits on: a grade scoped to {@code lobby} must not unlock a proxy command.
 */
class NativePermissionProxyContextTest {

  private static final UUID SUBJECT = UUID.fromString("3c4d5e6f-7081-4192-a3b4-c5d6e7f80910");
  private static final String NETWORK = "test-network";

  @Test
  void contextIsTheConfiguredNetworkAndTheProxyServerOnly() {
    assertEquals(
        Map.of("network", NETWORK, "server", "proxy"),
        NativePermissionService.proxyContext(NETWORK));
  }

  @Test
  void aNodeScopedToTheProxyIsGrantedWhileThePlayerIsOnLobby() {
    final NativePermissionResolver resolver = resolverFor(
        scopedNode("velocity.command.info", "proxy"));

    assertEquals(Tristate.TRUE, resolver.getPermissionValue("velocity.command.info"));
  }

  @Test
  void aNodeScopedToLobbyIsRefusedEvenWhileThePlayerIsOnLobby() {
    final NativePermissionResolver resolver = resolverFor(
        scopedNode("velocity.command.server", "lobby"));

    assertEquals(Tristate.UNDEFINED, resolver.getPermissionValue("velocity.command.server"),
        "the node is out of the proxy context: nobody decided, and the proxy does not grant it");
  }

  private static NativePermissionSnapshot.Node scopedNode(final String node, final String server) {
    final NativePermissionSnapshot.Node permission = new NativePermissionSnapshot.Node();
    permission.node = node;
    permission.value = true;
    permission.contexts = Map.of("server", server);
    permission.sourceId = "test";
    return permission;
  }

  /** A resolver for a player currently connected to the backend {@code lobby}. */
  private static NativePermissionResolver resolverFor(final NativePermissionSnapshot.Node node) {
    final NativePermissionSnapshot snapshot = new NativePermissionSnapshot();
    snapshot.subject = SUBJECT;
    snapshot.permissions = List.of(node);

    final ServerConnection connection = mock(ServerConnection.class);
    when(connection.getServerInfo())
        .thenReturn(new ServerInfo("lobby", new InetSocketAddress("127.0.0.1", 25566)));
    final Player player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(SUBJECT);
    doReturn(Optional.of(connection)).when(player).getCurrentServer();

    final NativePermissionService service = mock(NativePermissionService.class);
    when(service.snapshot(SUBJECT)).thenReturn(snapshot);
    when(service.context()).thenReturn(NativePermissionService.proxyContext(NETWORK));
    return new NativePermissionResolver(player, null, service);
  }
}
