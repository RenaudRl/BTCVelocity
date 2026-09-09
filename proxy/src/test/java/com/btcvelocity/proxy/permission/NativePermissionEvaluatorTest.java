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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.permission.Tristate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NativePermissionEvaluatorTest {

  @Test
  void resolvesExactWildcardNegativeInheritanceAndContextDeterministically() {
    final NativePermissionSnapshot.Group member = group("member", 10,
        node("btc.chat.*", true, 0, Map.of()),
        node("btc.chat.secret", false, 20, Map.of()));
    final NativePermissionSnapshot.Group admin = group("admin", 100,
        node("btc.chat.secret", true, 0, Map.of("server", "proxy")));
    admin.inherits = Set.of("member");

    final NativePermissionSnapshot snapshot = new NativePermissionSnapshot();
    snapshot.subject = UUID.randomUUID();
    snapshot.permissions = List.of(node("btc.chat.secret", false, 0, Map.of()));
    snapshot.groups = Map.of("member", member, "admin", admin);
    snapshot.directGroups = Set.of("admin");

    assertEquals(Tristate.TRUE, NativePermissionEvaluator.evaluate(
        snapshot, "btc.chat.secret", Map.of("server", "proxy"), 1_000L));
    assertEquals(Tristate.FALSE, NativePermissionEvaluator.evaluate(
        snapshot, "btc.chat.secret", Map.of("server", "backend"), 1_000L));
    assertEquals(Tristate.TRUE, NativePermissionEvaluator.evaluate(
        snapshot, "btc.chat.other", Map.of("server", "backend"), 1_000L));
  }

  @Test
  void expiresTemporaryPermissionAndExposesWildcardMap() {
    final NativePermissionSnapshot snapshot = new NativePermissionSnapshot();
    snapshot.subject = UUID.randomUUID();
    snapshot.permissions = List.of(
        node("btc.test.*", true, 0, Map.of()),
        node("btc.test.expiring", true, 50, Map.of(), 2_000L));

    assertEquals(Tristate.TRUE, NativePermissionEvaluator.evaluate(
        snapshot, "btc.test.expiring", Map.of(), 1_999L));
    assertEquals(Tristate.TRUE, NativePermissionEvaluator.evaluate(
        snapshot, "btc.test.expiring", Map.of(), 2_000L));
    assertTrue(NativePermissionEvaluator.permissionMap(snapshot, Map.of(), 1_999L)
        .containsKey("btc.test.*"));
  }

  /**
   * The permission map is Velocity's view of the subject: once it is present, Velocity stops asking
   * {@code hasPermission}. It must therefore hide what {@code evaluate} would refuse — an expired
   * node — otherwise a temporary permission outlives its own window on the fast path only.
   */
  @Test
  void permissionMapDropsExpiredNodes() {
    final NativePermissionSnapshot snapshot = new NativePermissionSnapshot();
    snapshot.subject = UUID.randomUUID();
    snapshot.permissions = List.of(node("btc.temporary", true, 0, Map.of(), 2_000L));

    assertTrue(NativePermissionEvaluator.permissionMap(snapshot, Map.of(), 1_999L)
        .containsKey("btc.temporary"));
    assertEquals(Map.of(), NativePermissionEvaluator.permissionMap(snapshot, Map.of(), 2_000L));
  }

  /** A node restricted to another server must not enter the current subject's view. */
  @Test
  void permissionMapDropsNodesOutOfContext() {
    final NativePermissionSnapshot snapshot = new NativePermissionSnapshot();
    snapshot.subject = UUID.randomUUID();
    snapshot.permissions = List.of(
        node("btc.survival.only", true, 0, Map.of("server", "survival")),
        node("btc.everywhere", true, 0, Map.of()));

    assertEquals(
        Map.of("btc.everywhere", true),
        NativePermissionEvaluator.permissionMap(snapshot, Map.of("server", "lobby"), 0L));
    assertEquals(
        Map.of("btc.everywhere", true, "btc.survival.only", true),
        NativePermissionEvaluator.permissionMap(snapshot, Map.of("server", "survival"), 0L));
  }

  /**
   * Two sources assigning the same node must be resolved by the business comparator, exactly as
   * {@code evaluate} does. Resolving by source id would make the winner depend on how the groups
   * happen to be named — here `zzz` would beat `aaa` and turn the deny into an allow.
   */
  @Test
  void permissionMapResolvesDuplicatesLikeEvaluate() {
    final NativePermissionSnapshot.Group allowing =
        group("zzz-high", 100, namedNode("btc.rank", true, "zzz-source"));
    final NativePermissionSnapshot.Group denying =
        group("aaa-low", 1, namedNode("btc.rank", false, "aaa-source"));
    final NativePermissionSnapshot snapshot = new NativePermissionSnapshot();
    snapshot.subject = UUID.randomUUID();
    snapshot.groups = Map.of("zzz-high", allowing, "aaa-low", denying);
    snapshot.directGroups = Set.of("zzz-high", "aaa-low");

    assertEquals(
        Tristate.FALSE, NativePermissionEvaluator.evaluate(snapshot, "btc.rank", Map.of(), 0L));
    assertEquals(
        Map.of("btc.rank", false),
        NativePermissionEvaluator.permissionMap(snapshot, Map.of(), 0L),
        "la map doit trancher comme evaluate : le deny gagne");
  }

  private static NativePermissionSnapshot.Node namedNode(
      final String node,
      final boolean value,
      final String sourceId
  ) {
    final NativePermissionSnapshot.Node permission = node(node, value, 0, Map.of());
    permission.sourceId = sourceId;
    return permission;
  }

  @Test
  void breaksEqualConflictsWithDenyBeforeAllow() {
    final NativePermissionSnapshot.Group low = group("low", 1, node("btc.rank", false, 0, Map.of()));
    final NativePermissionSnapshot.Group high = group("high", 20, node("btc.rank", true, 0, Map.of()));
    final NativePermissionSnapshot snapshot = new NativePermissionSnapshot();
    snapshot.subject = UUID.randomUUID();
    snapshot.groups = Map.of("low", low, "high", high);
    snapshot.directGroups = Set.of("low", "high");

    assertEquals(Tristate.FALSE, NativePermissionEvaluator.evaluate(snapshot, "btc.rank", Map.of(), 0L));
  }

  @Test
  void directPlayerAllowPrecedesHigherWeightGroupAllow() {
    final NativePermissionSnapshot.Group admin = group("admin", 100, node("btc.direct", true, 0, Map.of()));
    final NativePermissionSnapshot snapshot = new NativePermissionSnapshot();
    snapshot.subject = UUID.randomUUID();
    snapshot.permissions = List.of(node("btc.direct", true, 0, Map.of()));
    snapshot.groups = Map.of("admin", admin);
    snapshot.directGroups = Set.of("admin");

    assertEquals(Tristate.TRUE, NativePermissionEvaluator.evaluate(snapshot, "btc.direct", Map.of(), 0L));
  }

  private static NativePermissionSnapshot.Group group(
      final String name,
      final int weight,
      final NativePermissionSnapshot.Node... permissions
  ) {
    final NativePermissionSnapshot.Group group = new NativePermissionSnapshot.Group();
    group.name = name;
    group.weight = weight;
    group.permissions = List.of(permissions);
    return group;
  }

  private static NativePermissionSnapshot.Node node(
      final String node,
      final boolean value,
      final int priority,
      final Map<String, String> contexts
  ) {
    return node(node, value, priority, contexts, null);
  }

  private static NativePermissionSnapshot.Node node(
      final String node,
      final boolean value,
      final int priority,
      final Map<String, String> contexts,
      final Long expiresAt
  ) {
    final NativePermissionSnapshot.Node permission = new NativePermissionSnapshot.Node();
    permission.node = node;
    permission.value = value;
    permission.priority = priority;
    permission.contexts = contexts;
    permission.expiresAtEpochMillis = expiresAt;
    permission.sourceId = node;
    return permission;
  }
}
