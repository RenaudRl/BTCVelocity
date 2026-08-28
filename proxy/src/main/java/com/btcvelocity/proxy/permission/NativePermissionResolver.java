/*
 * Copyright (C) 2018-2026 Velocity Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
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

/** Read-only Velocity resolver backed by the native TypeWriter snapshot contract. */
final class NativePermissionResolver implements PermissionResolver {

  private final UUID subject;
  private final PermissionFunction delegate;
  private final NativePermissionService service;

  NativePermissionResolver(
      final Player player,
      final PermissionFunction delegate,
      final NativePermissionService service
  ) {
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
        snapshot, permission, service.context(), System.currentTimeMillis());
    return resolved == Tristate.UNDEFINED ? fallback(permission) : resolved;
  }

  @Override
  public @Nullable @Unmodifiable Map<String, Boolean> getPermissionMap() {
    final NativePermissionSnapshot snapshot = service.snapshot(subject);
    if (snapshot == null) {
      service.load(subject);
      if (delegate instanceof PermissionResolver resolver) {
        return resolver.getPermissionMap();
      }
      return null;
    }
    return NativePermissionEvaluator.permissionMap(snapshot);
  }

  private Tristate fallback(final String permission) {
    return delegate == null ? Tristate.UNDEFINED : delegate.getPermissionValue(permission);
  }
}
