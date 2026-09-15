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

package com.btcvelocity.cloudnet.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ServiceRegistryTest {

  private final List<String> calls = new ArrayList<>();
  private final ServiceRegistry registry = new ServiceRegistry(new ServiceRegistry.Registrar() {
    @Override
    public void register(String name, InetSocketAddress address) {
      ServiceRegistryTest.this.calls.add("register:" + name);
    }

    @Override
    public void unregister(String name) {
      ServiceRegistryTest.this.calls.add("unregister:" + name);
    }
  });

  private static ServiceView view(UUID id, String name, boolean eligible) {
    return new ServiceView(id, name, new InetSocketAddress("127.0.0.1", 30200), eligible);
  }

  @Test
  void registersAnEligibleServiceOnce() {
    // Arrange
    var id = UUID.randomUUID();

    // Act
    var first = this.registry.apply(view(id, "BtcCore0-1", true));
    var second = this.registry.apply(view(id, "BtcCore0-1", true));

    // Assert
    assertTrue(first);
    assertFalse(second, "a repeated update must not register the service twice");
    assertEquals(List.of("register:BtcCore0-1"), this.calls);
  }

  @Test
  void unregistersWhenTheServiceStopsBeingEligible() {
    // Arrange
    var id = UUID.randomUUID();
    this.registry.apply(view(id, "BtcCore0-1", true));

    // Act
    var changed = this.registry.apply(view(id, "BtcCore0-1", false));

    // Assert
    assertTrue(changed);
    assertEquals(List.of("register:BtcCore0-1", "unregister:BtcCore0-1"), this.calls);
    assertTrue(this.registry.registeredNames().isEmpty());
  }

  @Test
  void ignoresAnUnknownIneligibleService() {
    // Act
    var changed = this.registry.apply(view(UUID.randomUUID(), "BtcCore0-2", false));

    // Assert
    assertFalse(changed);
    assertTrue(this.calls.isEmpty(), "an ineligible unknown service must not touch the proxy");
  }

  @Test
  void dropsEverythingWhenTheNodeGoesAway() {
    // Arrange
    this.registry.apply(view(UUID.randomUUID(), "BtcCore0-1", true));
    this.registry.apply(view(UUID.randomUUID(), "BtcCore0-2", true));
    this.calls.clear();

    // Act
    var dropped = this.registry.dropAll();

    // Assert — stale is unavailable, never empty
    assertEquals(2, dropped.size());
    assertEquals(2, this.calls.size());
    assertTrue(this.calls.stream().allMatch(call -> call.startsWith("unregister:")));
    assertTrue(this.registry.registeredNames().isEmpty());
    assertTrue(this.registry.dropAll().isEmpty(), "dropping twice must be a no-op");
  }
}
