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

package com.btcvelocity.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.btcvelocity.api.bridge.BridgeMessage;
import com.btcvelocity.api.bridge.BridgePlatformSources;
import com.btcvelocity.api.bridge.PlatformSource;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.Optional;
import java.util.UUID;
import org.geysermc.floodgate.api.FloodgateApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.slf4j.helpers.NOPLogger;

class FloodgatePlatformSourceTest {

  /** Shape of a Floodgate-issued UUID: must never be what makes a player Bedrock. */
  private static final UUID FLOODGATE_SHAPED_UUID =
      UUID.fromString("00000000-0000-0000-0009-000000000001");

  private final ProxyServer proxy = mock(ProxyServer.class);
  private final FloodgateApi floodgate = mock(FloodgateApi.class);
  private MockedStatic<FloodgateApi> floodgateStatic;

  @BeforeEach
  void installFloodgate() {
    BridgePlatformSources.reset();
    this.floodgateStatic = Mockito.mockStatic(FloodgateApi.class);
    this.floodgateStatic.when(FloodgateApi::getInstance).thenReturn(this.floodgate);
  }

  @AfterEach
  void cleanUp() {
    this.floodgateStatic.close();
    BridgePlatformSources.reset();
  }

  private void online(UUID id, String name) {
    var player = mock(Player.class);
    doReturn(id).when(player).getUniqueId();
    doReturn(name).when(player).getUsername();
    doReturn(Optional.of(player)).when(this.proxy).getPlayer(id);
  }

  private void floodgateKnows(UUID id) {
    doReturn(true).when(this.floodgate).isFloodgatePlayer(id);
  }

  private PlatformSource source() {
    return FloodgatePlatformSource.createIfPresent(this.proxy);
  }

  @Test
  void bedrockPlayerKnownToFloodgateResolvesBedrock() {
    var bedrock = UUID.randomUUID();
    var java = UUID.randomUUID();
    this.online(bedrock, ".Steve");
    this.online(java, "Alex");
    this.floodgateKnows(bedrock);

    var source = this.source();

    assertEquals(Optional.of(BridgeMessage.Platform.BEDROCK), source.platformOf(bedrock));
    assertEquals(Optional.of(BridgeMessage.Platform.JAVA), source.platformOf(java));
  }

  @Test
  void javaPlayerUnknownToFloodgateResolvesJava() {
    var java = UUID.randomUUID();
    this.online(java, "Alex");

    assertEquals(Optional.of(BridgeMessage.Platform.JAVA), this.source().platformOf(java));
  }

  @Test
  void presentFloodgateYieldsASourceThatCanResolveAndDescribesIt() {
    var source = this.source();

    assertTrue(source.canResolve());
    assertEquals("Floodgate", source.describe());
  }

  @Test
  void uuidShapedLikeFloodgateIsNotBedrockWhenFloodgateDoesNotKnowIt() {
    var lookalike = FLOODGATE_SHAPED_UUID;
    var real = UUID.fromString("00000000-0000-0000-0009-000000000002");
    this.online(lookalike, "Alex");
    this.online(real, "Bob");
    this.floodgateKnows(real);

    var source = this.source();

    assertEquals(Optional.of(BridgeMessage.Platform.JAVA), source.platformOf(lookalike));
    assertEquals(Optional.of(BridgeMessage.Platform.BEDROCK), source.platformOf(real));
    verify(this.floodgate, never()).isFloodgateId(any(UUID.class));
  }

  @Test
  void dotPrefixedNameIsNotBedrockWhenFloodgateDoesNotKnowIt() {
    var dotted = UUID.randomUUID();
    var real = UUID.randomUUID();
    this.online(dotted, ".NotBedrock");
    this.online(real, ".RealBedrock");
    this.floodgateKnows(real);

    var source = this.source();

    assertEquals(Optional.of(BridgeMessage.Platform.JAVA), source.platformOf(dotted));
    assertEquals(Optional.of(BridgeMessage.Platform.BEDROCK), source.platformOf(real));
  }

  @Test
  void offlinePlayerResolvesEmptyEvenIfFloodgateKnowsTheUuid() {
    var offline = UUID.randomUUID();
    var online = UUID.randomUUID();
    this.floodgateKnows(offline);
    this.floodgateKnows(online);
    this.online(online, ".Steve");

    var source = this.source();

    assertEquals(Optional.empty(), source.platformOf(offline));
    assertEquals(Optional.of(BridgeMessage.Platform.BEDROCK), source.platformOf(online));
    verify(this.floodgate, never()).isFloodgatePlayer(offline);
  }

  @Test
  void floodgateInstalledButNotStartedYieldsUnavailableWithoutThrowing() {
    this.floodgateStatic.when(FloodgateApi::getInstance).thenReturn(null);
    var java = UUID.randomUUID();
    this.online(java, "Alex");

    var source = this.source();

    assertSame(PlatformSource.UNAVAILABLE, source);
    assertFalse(source.canResolve());
    assertEquals(Optional.empty(), source.platformOf(java));
  }

  @Test
  void nullArgumentsAreRejected() {
    assertThrows(NullPointerException.class, () -> FloodgatePlatformSource.createIfPresent(null));
    assertThrows(NullPointerException.class, () -> this.source().platformOf(null));
  }

  @Test
  void initializeInstallsFloodgateSourceInTheBridgeRegistry() {
    var plugin = new BtcPlatformResolverPlugin(this.proxy, NOPLogger.NOP_LOGGER);
    var bedrock = UUID.randomUUID();
    this.online(bedrock, ".Steve");
    this.floodgateKnows(bedrock);

    plugin.onProxyInitialize(new ProxyInitializeEvent());

    var installed = BridgePlatformSources.current();
    assertNotSame(PlatformSource.UNAVAILABLE, installed);
    assertTrue(installed.canResolve());
    assertEquals(Optional.of(BridgeMessage.Platform.BEDROCK), installed.platformOf(bedrock));
  }

  @Test
  void initializeWithoutStartedFloodgateLeavesBridgeUnavailableWithoutThrowing() {
    this.floodgateStatic.when(FloodgateApi::getInstance).thenReturn(null);
    var plugin = new BtcPlatformResolverPlugin(this.proxy, NOPLogger.NOP_LOGGER);

    plugin.onProxyInitialize(new ProxyInitializeEvent());

    assertSame(PlatformSource.UNAVAILABLE, BridgePlatformSources.current());
    assertFalse(BridgePlatformSources.current().canResolve());
  }

  @Test
  void shutdownRemovesTheInstalledSource() {
    var plugin = new BtcPlatformResolverPlugin(this.proxy, NOPLogger.NOP_LOGGER);
    plugin.onProxyInitialize(new ProxyInitializeEvent());
    assertTrue(BridgePlatformSources.current().canResolve());

    plugin.onProxyShutdown(new ProxyShutdownEvent());

    assertSame(PlatformSource.UNAVAILABLE, BridgePlatformSources.current());
  }
}