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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * An allow-list entry covers a name exactly or by task, and never more generously than that.
 *
 * <p>The same cases as {@code ServiceIdentityCheck} in BTC-CORE: both sides of the bridge must
 * read a declaration the same way.</p>
 */
class ServiceNamesTest {

  @Test
  void aServiceBelongsToItsTaskWhateverItsNumber() {
    assertEquals("Lobby", ServiceNames.taskOf("Lobby-1"));
    assertEquals("Lobby", ServiceNames.taskOf("Lobby-42"));
  }

  @Test
  void aTaskNameMayItselfContainTheSeparator() {
    // Split on the first separator, and an allow-list naming skyadventure-1 would also admit
    // skyadventure-2.
    assertEquals("skyadventure-1", ServiceNames.taskOf("skyadventure-1-2"));
  }

  @Test
  void aNameWhoseSuffixIsNotANumberIsNotAServiceName() {
    // Otherwise btc-proxy reads as service "proxy" of a task btc.
    assertNull(ServiceNames.taskOf("btc-proxy"));
    assertNull(ServiceNames.taskOf("Lobby"));
    assertNull(ServiceNames.taskOf("-1"));
    assertNull(ServiceNames.taskOf("Lobby-"));
  }

  @Test
  void anAllowListNamingATaskCoversItsServicesOnly() {
    final Set<String> allowed = Set.of("skyadventure-1", "Lobby");

    assertTrue(ServiceNames.covers(allowed, "skyadventure-1-2"));
    assertTrue(ServiceNames.covers(allowed, "Lobby-1"));
    assertTrue(ServiceNames.covers(allowed, "Lobby"), "un nom exact reste couvert");
    assertFalse(ServiceNames.covers(allowed, "skyadventure-2-1"), "tâche voisine");
    assertFalse(ServiceNames.covers(allowed, "Creatif-1"));
    assertFalse(ServiceNames.covers(allowed, null));
  }

  @Test
  void grantingATaskBtcDoesNotGrantBtcProxy() {
    assertFalse(ServiceNames.covers(Set.of("btc"), "btc-proxy"));
    assertTrue(ServiceNames.covers(Set.of("btc-proxy"), "btc-proxy"));
  }
}
