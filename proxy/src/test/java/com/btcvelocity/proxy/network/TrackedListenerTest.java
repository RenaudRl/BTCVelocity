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

package com.btcvelocity.proxy.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** A reload adds, moves or removes the listener by what is bound, not by what a config says. */
class TrackedListenerTest {

  private static final InetSocketAddress A = at(25566);
  private static final InetSocketAddress B = at(25567);

  private final List<String> calls = new ArrayList<>();
  private boolean failBinds;

  private final TrackedListener listener = new TrackedListener("geyser",
      address -> {
        if (this.failBinds) {
          throw new IllegalStateException("address in use");
        }
        this.calls.add("bind " + address.getPort());
      },
      address -> this.calls.add("close " + address.getPort()));

  private static InetSocketAddress at(final int port) {
    return new InetSocketAddress(InetAddress.ofLiteral("127.0.0.1"), port);
  }

  @Test
  void reloadAddsTheListenerWhenItWasOff() {
    this.listener.start(Optional.empty());

    this.listener.reload(Optional.of(A));

    assertEquals(List.of("bind 25566"), this.calls);
    assertEquals(Optional.of(A), this.listener.bound());
  }

  @Test
  void reloadMovesItByBindingTheNewAddressBeforeClosingTheOld() {
    this.listener.start(Optional.of(A));
    this.calls.clear();

    this.listener.reload(Optional.of(B));

    assertEquals(List.of("bind 25567", "close 25566"), this.calls);
    assertEquals(Optional.of(B), this.listener.bound());
  }

  @Test
  void reloadRemovesItWhenTheConfigurationDropsIt() {
    this.listener.start(Optional.of(A));
    this.calls.clear();

    this.listener.reload(Optional.empty());

    assertEquals(List.of("close 25566"), this.calls);
    assertTrue(this.listener.bound().isEmpty());
  }

  @Test
  void reloadWithTheSameAddressDoesNothing() {
    this.listener.start(Optional.of(A));
    this.calls.clear();

    this.listener.reload(Optional.of(at(25566)));

    assertTrue(this.calls.isEmpty());
  }

  @Test
  void aFailedBindOnReloadKeepsTheOldListenerAndTheRightAddress() {
    this.listener.start(Optional.of(A));
    this.calls.clear();
    this.failBinds = true;

    this.listener.reload(Optional.of(B));

    assertTrue(this.calls.isEmpty(), "the old listener must not be closed");
    assertEquals(Optional.of(A), this.listener.bound());

    this.failBinds = false;
    this.listener.reload(Optional.of(B));
    assertEquals(List.of("bind 25567", "close 25566"), this.calls, "a later reload retries");
  }

  @Test
  void aFailedBindAtStartupIsNotSwallowed() {
    this.failBinds = true;

    assertThrows(IllegalStateException.class, () -> this.listener.start(Optional.of(A)));
    assertTrue(this.listener.bound().isEmpty());
  }
}
