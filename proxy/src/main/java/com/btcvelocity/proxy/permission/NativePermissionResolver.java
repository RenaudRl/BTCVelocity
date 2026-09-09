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

import com.btcvelocity.api.permission.PermissionResolver;
import com.velocitypowered.api.permission.PermissionFunction;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.Player;
import java.util.Map;
import java.util.UUID;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Read-only Velocity resolver backed by the native TypeWriter snapshot contract.
 *
 * <h2>Who decides, and when (PERM-027)</h2>
 *
 * <p>Three sources could answer a permission question at the proxy: the native snapshot, the
 * Velocity {@link PermissionFunction} installed by other plugins, and nothing at all. This class
 * settles the order once, and {@code NativePermissionResolverPolicyTest} keeps it settled.
 *
 * <ol>
 *   <li><b>Snapshot missing</b> — refuse, and start the asynchronous load. Falling back to Velocity
 *       here would turn a transient I/O delay into an allow, which is the one mistake a permission
 *       system must never make: refusing once in error is recoverable, granting once in error is
 *       not. {@link #getPermissionMap()} returns an empty map for the same reason — announcing
 *       permissions that {@link #getPermissionValue(String)} would refuse makes the two surfaces of
 *       the same resolver disagree.</li>
 *   <li><b>Snapshot loaded, node assigned</b> — the native decision is returned as-is. An explicit
 *       deny is a decision, not the absence of one, so no other source may overturn it.</li>
 *   <li><b>Snapshot loaded, node not covered by any assignment</b> — and only then — the Velocity
 *       source is consulted. Proxy plugins own their own permission nodes; the native backend does
 *       not claim them.</li>
 *   <li><b>No fallback installed</b> — an uncovered node stays {@code UNDEFINED} rather than
 *       becoming {@code FALSE}. "Nobody decided" is not "somebody decided no", and Velocity already
 *       treats undefined as a refusal for its own checks.</li>
 * </ol>
 *
 * <h2>Read-only, and why the proxy is not Paper</h2>
 *
 * <p>This resolver never writes: the proxy reads snapshots produced by the TypeWriter Permissions
 * extension and holds no mutating command. Any grant or revoke goes through a backend, which then
 * publishes an invalidation the proxy observes.
 *
 * <p><b>The one deliberate divergence from Paper.</b> The Paper evaluator grants an operator any node
 * no assignment covers ({@code operator-default}, mirroring {@code PermissionDefault.OP}). The proxy
 * has no notion of an operator — it holds no {@code op.json} and cannot see a backend's — so an
 * administrator who is an operator on one server gains nothing from that fact here. This is a
 * boundary rather than an inconsistency: operator rights are local to the server granting them, and
 * carrying them across the proxy would silently grant them network-wide.
 *
 * <p>The context sent to the evaluator is {@code network} plus the player's current {@code server},
 * so a node scoped to one backend does not leak into decisions taken while the player sits elsewhere.
 */
final class NativePermissionResolver implements PermissionResolver {

  private final UUID subject;
  private final Player player;
  private final PermissionFunction delegate;
  private final NativePermissionService service;

  NativePermissionResolver(
      final Player player,
      final PermissionFunction delegate,
      final NativePermissionService service
  ) {
    this.player = player;
    this.subject = player.getUniqueId();
    this.delegate = delegate;
    this.service = service;
  }

  @Override
  public @NonNull Tristate getPermissionValue(final String permission) {
    final NativePermissionSnapshot snapshot = service.snapshot(subject);
    if (snapshot == null) {
      service.load(subject);
      // Native mode is authoritative: deny while the asynchronous snapshot is loading.
      // Falling back to Velocity here would turn a transient I/O delay into an allow.
      return Tristate.FALSE;
    }
    final Tristate resolved = NativePermissionEvaluator.evaluate(
        snapshot, permission, service.context(player), System.currentTimeMillis());
    return resolved == Tristate.UNDEFINED ? fallback(permission) : resolved;
  }

  @Override
  public @Nullable @Unmodifiable Map<String, Boolean> getPermissionMap() {
    final NativePermissionSnapshot snapshot = service.snapshot(subject);
    if (snapshot == null) {
      service.load(subject);
      // Native mode is authoritative while the asynchronous snapshot is loading.
      return Map.of();
    }
    return NativePermissionEvaluator.permissionMap(
        snapshot, service.context(player), System.currentTimeMillis());
  }

  private Tristate fallback(final String permission) {
    return delegate == null ? Tristate.UNDEFINED : delegate.getPermissionValue(permission);
  }
}
