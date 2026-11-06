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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.proxy.ProxyServer;
import eu.cloudnetservice.driver.document.Document;
import eu.cloudnetservice.driver.network.HostAndPort;
import eu.cloudnetservice.driver.service.ProcessSnapshot;
import eu.cloudnetservice.driver.service.ServiceId;
import eu.cloudnetservice.driver.service.ServiceInfoSnapshot;
import eu.cloudnetservice.driver.service.ServiceLifeCycle;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

class CloudNetServiceListenerTest {

  private static final UUID SERVICE = UUID.randomUUID();

  private final List<String> calls = new ArrayList<>();
  private final CloudNetServiceListener listener = new CloudNetServiceListener(
      NOPLogger.NOP_LOGGER,
      mock(ProxyServer.class),
      new ServiceRegistry(new ServiceRegistry.Registrar() {
        @Override
        public void register(String name, InetSocketAddress address) {
          CloudNetServiceListenerTest.this.calls.add("register:" + name);
        }

        @Override
        public void unregister(String name) {
          CloudNetServiceListenerTest.this.calls.add("unregister:" + name);
        }
      }));

  // pid -1 is ProcessSnapshot.empty(): what the node puts in a snapshot it made itself.
  private static ServiceInfoSnapshot snapshot(
      ServiceLifeCycle lifeCycle, boolean connected, boolean online, long pid) {
    var id = mock(ServiceId.class);
    when(id.uniqueId()).thenReturn(SERVICE);
    var properties = mock(Document.class);
    when(properties.getBoolean("Online", false)).thenReturn(online);

    var snapshot = mock(ServiceInfoSnapshot.class);
    when(snapshot.serviceId()).thenReturn(id);
    when(snapshot.name()).thenReturn("Lobby-1");
    when(snapshot.address()).thenReturn(new HostAndPort("127.0.0.1", 30500));
    when(snapshot.connected()).thenReturn(connected);
    when(snapshot.lifeCycle()).thenReturn(lifeCycle);
    when(snapshot.propertyHolder()).thenReturn(properties);
    when(snapshot.processSnapshot()).thenReturn(pid == -1
        ? ProcessSnapshot.empty()
        : new ProcessSnapshot(pid, 0, 0, 0, 0, 0, 0, 0, 0, Set.of()));
    return snapshot;
  }

  private void accept(ServiceInfoSnapshot snapshot) {
    this.listener.accept(CloudNetServiceListener.viewOf(snapshot, true));
  }

  @Test
  void aRestartedServiceIsRegisteredOnlyOnceItsOwnBridgeHasPublished() {
    // Run 1: the wrapper publishes Online, the service is registered
    this.accept(snapshot(ServiceLifeCycle.RUNNING, true, true, 4100));
    assertEquals(List.of("register:Lobby-1"), this.calls);

    // Stop: back to PREPARED, the node keeps the previous run's properties (Online included)
    this.accept(snapshot(ServiceLifeCycle.PREPARED, false, true, -1));
    assertEquals(List.of("register:Lobby-1", "unregister:Lobby-1"), this.calls);

    // Run 2: the wrapper authenticates, the node broadcasts its snapshot as it stands —
    // RUNNING, connected, stale Online, no wrapper publish yet. Witness: the old criterion registered here.
    this.accept(snapshot(ServiceLifeCycle.RUNNING, true, true, -1));
    assertEquals(List.of("register:Lobby-1", "unregister:Lobby-1"), this.calls,
        "a stale Online must not register the service");

    // Run 2: the bridge publishes on the first server tick (new pid, fresh Online)
    this.accept(snapshot(ServiceLifeCycle.RUNNING, true, true, 5200));
    assertEquals(List.of("register:Lobby-1", "unregister:Lobby-1", "register:Lobby-1"), this.calls);
  }

  @Test
  void aFirstRunServiceIsStillRegisteredByItsBridgePublish() {
    // Positive control: a service never started before has no stale Online and is not held back
    this.accept(snapshot(ServiceLifeCycle.RUNNING, true, false, -1));
    this.accept(snapshot(ServiceLifeCycle.RUNNING, true, true, 4100));

    assertEquals(List.of("register:Lobby-1"), this.calls);
  }

  @Test
  void aServiceThatIsNotOnlineIsNeverRegisteredWhateverThePid() {
    this.accept(snapshot(ServiceLifeCycle.RUNNING, true, false, 4100));

    assertEquals(List.of(), this.calls);
  }
}
