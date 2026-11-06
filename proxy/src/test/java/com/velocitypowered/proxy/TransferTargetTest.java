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

package com.velocitypowered.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.velocitypowered.proxy.config.DynamicProxyFilterMode;
import com.velocitypowered.proxy.config.ProxyAddress;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * On shutdown, players are transferred to another proxy. A proxy listed in the config but
 * whose heartbeat expired is dead: sending players there disconnects them (CTD {@code 12f1a9de7}).
 */
class TransferTargetTest {

  private static final ProxyAddress SELF = new ProxyAddress("a", "10.0.0.1", 25577);
  private static final ProxyAddress DEAD = new ProxyAddress("b", "10.0.0.2", 25577);
  private static final ProxyAddress LIVE = new ProxyAddress("c", "10.0.0.3", 25577);
  private static final List<ProxyAddress> CONFIGURED = List.of(SELF, DEAD, LIVE);
  private static final Map<String, Integer> PLAYERS = Map.of("a", 5, "b", 0, "c", 9);

  @Test
  void aDeadProxyIsNeverChosen() {
    for (DynamicProxyFilterMode mode : List.of(DynamicProxyFilterMode.FIRST_FOUND,
        DynamicProxyFilterMode.MOST_EMPTY)) {
      assertEquals(LIVE, choose(mode, Set.of("a", "c")), mode.name());
    }
  }

  @Test
  void noLiveProxyMeansNoTransfer() {
    assertNull(choose(DynamicProxyFilterMode.FIRST_FOUND, Set.of("a")));
  }

  @Test
  void rankingStillAppliesAmongLiveProxies() {
    final Set<String> all = Set.of("a", "b", "c");
    assertEquals(DEAD, choose(DynamicProxyFilterMode.MOST_EMPTY, all));
    assertEquals(LIVE, choose(DynamicProxyFilterMode.LEAST_EMPTY, all));
    assertNull(choose(DynamicProxyFilterMode.NONE, all));
  }

  @Test
  void noneNeverReadsRedis() {
    assertNull(VelocityServer.chooseTransferTarget(CONFIGURED, SELF.proxyId(),
        DynamicProxyFilterMode.NONE, id -> {
          throw new AssertionError("NONE must not query liveness");
        }, PLAYERS::get));
  }

  @Test
  void anUnreachableRedisMeansNoTransferInsteadOfAnAbortedShutdown() {
    assertNull(VelocityServer.chooseTransferTarget(CONFIGURED, SELF.proxyId(),
        DynamicProxyFilterMode.FIRST_FOUND, id -> {
          throw new IllegalStateException("Command timed out after 2 second(s)");
        }, PLAYERS::get));
  }

  private static ProxyAddress choose(final DynamicProxyFilterMode mode, final Set<String> alive) {
    return VelocityServer.chooseTransferTarget(CONFIGURED, SELF.proxyId(), mode, alive::contains,
        PLAYERS::get);
  }
}
