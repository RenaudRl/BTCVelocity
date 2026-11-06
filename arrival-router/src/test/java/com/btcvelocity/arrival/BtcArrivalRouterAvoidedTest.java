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

package com.btcvelocity.arrival;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

/** A backend the proxy avoids is advice, never a refusal: nothing else left means it is used. */
class BtcArrivalRouterAvoidedTest {

  private final ProxyServer proxy = mock(ProxyServer.class);
  private final List<RegisteredServer> registered = new ArrayList<>();

  BtcArrivalRouterAvoidedTest() {
    doReturn(this.registered).when(this.proxy).getAllServers();
  }

  private BtcArrivalRouterPlugin router(String limbo) {
    return new BtcArrivalRouterPlugin(
        this.proxy, NOPLogger.NOP_LOGGER, ArrivalPolicy.parse("Lobby-*"), limbo, null);
  }

  private RegisteredServer server(String name, int local, boolean avoided) {
    var server = mock(RegisteredServer.class);
    when(server.getServerInfo()).thenReturn(new ServerInfo(name, new InetSocketAddress(25599)));
    doReturn(java.util.Collections.nCopies(local, mock(Player.class))).when(server).getPlayersConnected();
    doReturn(Optional.of(server)).when(this.proxy).getServer(name);
    when(this.proxy.isBackendAvoided(name)).thenReturn(avoided);
    this.registered.add(server);
    return server;
  }

  private static Player player() {
    var player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(UUID.randomUUID());
    when(player.getUsername()).thenReturn("tester");
    return player;
  }

  @Test
  void withoutALimboAnArrivalGoesToTheLeastPopulatedAvoidedBackend() {
    // Witness: before this, "every backend avoided" meant "no destination": a disconnect.
    this.server("Lobby-1", 5, true);
    var quieter = this.server("Lobby-2", 2, true);
    var event = new PlayerChooseInitialServerEvent(player(), null);

    this.router(null).onChooseInitialServer(event);

    assertSame(quieter, event.getInitialServer().orElseThrow());
  }

  @Test
  void aBackendNotAvoidedStillBeatsAnAvoidedOneWithoutALimbo() {
    this.server("Lobby-1", 0, true);
    var healthy = this.server("Lobby-2", 9, false);
    var event = new PlayerChooseInitialServerEvent(player(), null);

    this.router(null).onChooseInitialServer(event);

    assertSame(healthy, event.getInitialServer().orElseThrow());
  }

  @Test
  void withALimboAnArrivalWaitsThereRatherThanGoingToAnAvoidedBackend() {
    this.server("Lobby-1", 0, true);
    var limbo = this.server("limbo", 0, false);
    var event = new PlayerChooseInitialServerEvent(player(), null);

    this.router("limbo").onChooseInitialServer(event);

    assertSame(limbo, event.getInitialServer().orElseThrow());
  }

  @Test
  void withALimboThatIsDownAnArrivalStillFindsTheAvoidedBackend() {
    var avoided = this.server("Lobby-1", 0, true);
    var event = new PlayerChooseInitialServerEvent(player(), null);

    this.router("limbo").onChooseInitialServer(event);

    assertSame(avoided, event.getInitialServer().orElseThrow());
  }

  @Test
  void aKickedPlayerWithOnlyAvoidedBackendsLeftIsRedirectedNotDisconnected() {
    var kickedFrom = this.server("Lobby-1", 3, false);
    var avoided = this.server("Lobby-2", 1, true);
    var event = new KickedFromServerEvent(player(), kickedFrom, Component.empty(), true,
        KickedFromServerEvent.DisconnectPlayer.create(Component.empty()));

    this.router(null).onKickedFromServer(event);

    var redirect = assertInstanceOf(KickedFromServerEvent.RedirectPlayer.class, event.getResult());
    assertSame(avoided, redirect.getServer());
  }

  @Test
  void aKickedPlayerWithALimboIsHeldThereNotSentToAnAvoidedBackend() {
    var kickedFrom = this.server("Lobby-1", 3, false);
    this.server("Lobby-2", 1, true);
    var limbo = this.server("limbo", 0, false);
    var event = new KickedFromServerEvent(player(), kickedFrom, Component.empty(), true,
        KickedFromServerEvent.DisconnectPlayer.create(Component.empty()));

    this.router("limbo").onKickedFromServer(event);

    var redirect = assertInstanceOf(KickedFromServerEvent.RedirectPlayer.class, event.getResult());
    assertSame(limbo, redirect.getServer());
  }

  @Test
  void maintenanceStillHoldsPlayersWhenEveryBackendIsAvoided() {
    // The last resort must not become a way around maintenance.
    this.server("Lobby-1", 0, true);
    var router = this.router(null);
    router.setMaintenance(true);
    var event = new PlayerChooseInitialServerEvent(player(), null);

    router.onChooseInitialServer(event);

    assertTrue(event.getInitialServer().isEmpty());
  }
}
