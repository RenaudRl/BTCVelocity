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

package com.btcvelocity.proxy.permission;

import java.sql.SQLException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory {@link PermissionTables} whose reads can be counted, gated and made to fail, so the
 * service's cache behaviour is tested without PostgreSQL and without timing guesses.
 */
final class FakePermissionTables implements PermissionTables {

  /** Upper bound of a gated read; a test that forgets to release it fails instead of hanging. */
  private static final long GATE_LIMIT_SECONDS = 10;

  final AtomicInteger playerReads = new AtomicInteger();
  final AtomicInteger groupReads = new AtomicInteger();
  /** Every thread that ran a read, to prove no read ever ran on the caller's thread. */
  final Set<Thread> readers = ConcurrentHashMap.newKeySet();

  /** Nodes granted to a player, by player; a player in neither map has no row. */
  final Map<UUID, Set<String>> grants = new ConcurrentHashMap<>();
  /** Groups a player is directly in, by player. */
  final Map<UUID, Set<String>> memberships = new ConcurrentHashMap<>();
  volatile Map<String, NativePermissionSnapshot.Group> groups = Map.of();
  volatile boolean playerReadsFail;
  volatile boolean groupReadsFail;
  /** A player read waits here once the gate is closed. */
  volatile CountDownLatch gate = new CountDownLatch(0);
  /** Counted down each time a player read has started, before it waits on the gate. */
  volatile CountDownLatch readStarted = new CountDownLatch(0);

  @Override
  public NativePermissionSnapshot readPlayer(final UUID subject) throws SQLException {
    playerReads.incrementAndGet();
    readers.add(Thread.currentThread());
    readStarted.countDown();
    try {
      if (!gate.await(GATE_LIMIT_SECONDS, TimeUnit.SECONDS)) {
        throw new SQLException("test gate was never released");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new SQLException("interrupted");
    }
    if (playerReadsFail) {
      throw new SQLException("simulated read failure");
    }
    final Set<String> nodes = grants.get(subject);
    final Set<String> groupNames = memberships.get(subject);
    if (nodes == null && groupNames == null) {
      return null;
    }
    return row(nodes == null ? Set.of() : nodes, groupNames == null ? Set.of() : groupNames);
  }

  @Override
  public Map<String, NativePermissionSnapshot.Group> readGroups() throws SQLException {
    groupReads.incrementAndGet();
    readers.add(Thread.currentThread());
    if (groupReadsFail) {
      throw new SQLException("simulated catalog failure");
    }
    return groups;
  }

  /** A new object per read, as a real read would: the service mutates what it receives. */
  private static NativePermissionSnapshot row(final Set<String> nodes, final Set<String> groupNames) {
    final NativePermissionSnapshot snapshot = new NativePermissionSnapshot();
    snapshot.revision = 1L;
    snapshot.directGroups = new HashSet<>(groupNames);
    for (final String name : new HashSet<>(nodes)) {
      final NativePermissionSnapshot.Node node = new NativePermissionSnapshot.Node();
      node.node = name;
      node.value = true;
      snapshot.permissions.add(node);
    }
    return snapshot;
  }
}
