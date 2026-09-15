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

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps the proxy's server list in step with the CloudNet services it may route to.
 *
 * <p>This is the whole authority the registry claims: a service that is eligible is registered,
 * a service that stops being eligible is unregistered, and when the node becomes unreachable
 * every registered service is unregistered (stale is unavailable, never empty). It never
 * chooses a destination for a player; that stays with the proxy and {@code btc:bridge}.
 *
 * <p>Thread-safe: CloudNet delivers its events on its network threads.
 */
public final class ServiceRegistry {

  /**
   * The proxy-side effect of a registration decision.
   */
  public interface Registrar {

    void register(String name, InetSocketAddress address);

    void unregister(String name);
  }

  private final Registrar registrar;
  private final Map<UUID, String> registered = new ConcurrentHashMap<>();

  public ServiceRegistry(Registrar registrar) {
    this.registrar = registrar;
  }

  /**
   * Applies the latest known state of a service.
   *
   * @return {@code true} if the proxy server list changed
   */
  public boolean apply(ServiceView view) {
    if (view.eligible()) {
      String previous = this.registered.putIfAbsent(view.uniqueId(), view.name());
      if (previous != null) {
        return false;
      }
      this.registrar.register(view.name(), view.address());
      return true;
    }

    String removed = this.registered.remove(view.uniqueId());
    if (removed == null) {
      return false;
    }
    this.registrar.unregister(removed);
    return true;
  }

  /**
   * Forgets every registered service. Used when the node channel closes: whatever we knew is
   * stale, and a stale service must not be offered as a destination.
   *
   * @return the names that were unregistered
   */
  public List<String> dropAll() {
    List<String> dropped = new ArrayList<>();
    for (Map.Entry<UUID, String> entry : this.registered.entrySet()) {
      if (this.registered.remove(entry.getKey(), entry.getValue())) {
        this.registrar.unregister(entry.getValue());
        dropped.add(entry.getValue());
      }
    }
    return dropped;
  }

  public Collection<String> registeredNames() {
    return List.copyOf(this.registered.values());
  }
}
