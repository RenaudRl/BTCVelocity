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

package com.btcvelocity.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LoginRefusalOrderTest {

  /**
   * Velocity runs the HIGHEST priority first: PostOrder.LAST is Short.MIN_VALUE + 1, so only
   * Short.MIN_VALUE runs after every other listener (VelocityEventManager POST_ORDER_MAP).
   */
  @Test
  void theLoginRefusalRunsAfterEveryOtherListenerAndTheNetAfterIt() throws NoSuchMethodException {
    final short login = BtcAdmissionShieldPlugin.class
        .getMethod("onLogin", LoginEvent.class).getAnnotation(Subscribe.class).priority();
    final short postLogin = BtcAdmissionShieldPlugin.class
        .getMethod("onPostLogin", PostLoginEvent.class).getAnnotation(Subscribe.class).priority();

    assertEquals(Short.MIN_VALUE, login);
    assertEquals(Short.MIN_VALUE, postLogin);
  }

  @Test
  void aRefusalNotedForAConnectionIsTakenOnceAndOnlyForThatConnection() {
    final RefusalLedger ledger = new RefusalLedger();
    final InetSocketAddress refused = new InetSocketAddress(InetAddress.ofLiteral("203.0.113.9"),
        50_000);
    final InetSocketAddress other = new InetSocketAddress(InetAddress.ofLiteral("203.0.113.9"),
        50_001);
    final Verdict verdict = new Verdict(Verdict.Action.REFUSE, Verdict.Basis.LISTED,
        Verdict.Origin.DIRECT, refused.getAddress(), IpClass.DATACENTER, "dc");

    ledger.note(refused, verdict);

    assertEquals(Optional.empty(), ledger.take(other));
    assertEquals(Optional.of(verdict), ledger.take(refused));
    assertTrue(ledger.take(refused).isEmpty(), "taken once");
  }
}
