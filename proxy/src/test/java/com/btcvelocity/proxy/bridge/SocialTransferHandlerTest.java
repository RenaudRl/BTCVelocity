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

package com.btcvelocity.proxy.bridge;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.btcvelocity.api.bridge.BridgeMessage;
import com.btcvelocity.proxy.cluster.VelocityClusterPlayer;
import com.btcvelocity.proxy.cluster.VelocityClusterPlayerService;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.server.VelocityRegisteredServer;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The dispatch half of 0.6b: a party warp is judged once, before anybody moves.
 *
 * <p>Validating the rule in isolation is not enough — the defect being closed lived in the dispatch
 * loop, which validated per member and could therefore move part of a party it should have refused.
 */
class SocialTransferHandlerTest {

  private static final String TARGET = "creatif";
  private static final long NOW = 1_700_000_000_000L;

  private final VelocityClusterPlayerService cluster = mock(VelocityClusterPlayerService.class);
  private final VelocityServer server = mock(VelocityServer.class);
  private final SocialTransferHandler handler = new SocialTransferHandler(cluster, server);

  private static BridgeMessage.PartyWarp warp(final List<UUID> members, final String target) {
    final BridgeMessage.Envelope envelope = new BridgeMessage.Envelope(BridgeMessage.VERSION,
        UUID.randomUUID(), "party_warp", "survie", "proxy", NOW, NOW + 10_000L);
    return new BridgeMessage.PartyWarp(envelope, members, target, null);
  }

  private void targetIsRegistered() {
    when(server.getServer(TARGET)).thenReturn(Optional.of(mock(VelocityRegisteredServer.class)));
  }

  private VelocityClusterPlayer knownPlayer(final UUID uuid) {
    final VelocityClusterPlayer player = mock(VelocityClusterPlayer.class);
    when(cluster.getPlayer(uuid)).thenReturn(Optional.of(player));
    return player;
  }

  @Test
  void aWellFormedPartyMovesEveryMemberExactlyOnce() {
    // The positive control. Without it, every "moves nobody" test below would also pass on a
    // handler that moves nobody, ever.
    targetIsRegistered();
    final UUID alice = UUID.randomUUID();
    final UUID bob = UUID.randomUUID();
    final VelocityClusterPlayer alicePlayer = knownPlayer(alice);
    final VelocityClusterPlayer bobPlayer = knownPlayer(bob);

    handler.onMessage("survie", warp(List.of(alice, bob), TARGET));

    verify(alicePlayer, times(1)).move(TARGET);
    verify(bobPlayer, times(1)).move(TARGET);
  }

  @Test
  void aPartyHoldingTheSamePlayerTwiceMovesNobodyAtAll() {
    // The witness this task exists for. Everyone here is online and the destination is real: the
    // only thing wrong is the party itself. The old loop would have moved Bob — and Alice twice —
    // splitting a group on a message it should have refused whole.
    targetIsRegistered();
    final UUID alice = UUID.randomUUID();
    final UUID bob = UUID.randomUUID();
    final VelocityClusterPlayer alicePlayer = knownPlayer(alice);
    final VelocityClusterPlayer bobPlayer = knownPlayer(bob);

    handler.onMessage("survie", warp(List.of(alice, bob, alice), TARGET));

    verify(alicePlayer, never()).move(anyString());
    verify(bobPlayer, never()).move(anyString());
  }

  @Test
  void aPartyBoundForAnUnregisteredServerMovesNobody() {
    when(server.getServer(TARGET)).thenReturn(Optional.empty());
    final UUID alice = UUID.randomUUID();
    final VelocityClusterPlayer alicePlayer = knownPlayer(alice);

    handler.onMessage("survie", warp(List.of(alice, UUID.randomUUID()), TARGET));

    verify(alicePlayer, never()).move(anyString());
  }

  @Test
  void membersTheClusterDoesNotKnowDoNotHoldBackTheRestOfTheParty() {
    // Not a defect of the message: a player who logged off a second ago is simply not movable.
    // Refusing the whole warp here would punish the party for somebody else's disconnection.
    targetIsRegistered();
    final UUID alice = UUID.randomUUID();
    final UUID ghost = UUID.randomUUID();
    final VelocityClusterPlayer alicePlayer = knownPlayer(alice);
    when(cluster.getPlayer(ghost)).thenReturn(Optional.empty());

    handler.onMessage("survie", warp(List.of(alice, ghost), TARGET));

    verify(alicePlayer, times(1)).move(TARGET);
  }

  @Test
  void anEmptyPartyIsRefusedAndTheClusterIsNeverEvenAsked() {
    targetIsRegistered();

    handler.onMessage("survie", warp(List.of(), TARGET));

    verify(cluster, never()).getPlayer(any(UUID.class));
  }

  @Test
  void aSingleConnectRequestStillMovesItsPlayer() {
    // Party validation must not have swallowed the one-player path that shares the handler.
    targetIsRegistered();
    final UUID alice = UUID.randomUUID();
    final VelocityClusterPlayer alicePlayer = knownPlayer(alice);
    final BridgeMessage.Envelope envelope = new BridgeMessage.Envelope(BridgeMessage.VERSION,
        UUID.randomUUID(), "connect_request", "survie", "proxy", NOW, NOW + 10_000L);

    handler.onMessage("survie", new BridgeMessage.ConnectRequest(envelope, alice, TARGET, null));

    verify(alicePlayer, times(1)).move(TARGET);
  }
}
