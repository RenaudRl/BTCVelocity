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
import com.btcvelocity.api.permission.PermissionResolverProvider;
import com.velocitypowered.api.permission.PermissionFunction;
import com.velocitypowered.api.permission.PermissionSubject;
import com.velocitypowered.api.proxy.Player;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Optional native BTC provider; disabled by default for public standalone proxies. */
final class NativePermissionResolverProvider implements PermissionResolverProvider, AutoCloseable {

  private final NativePermissionService service = new NativePermissionService();

  @Override
  public boolean isAvailable() {
    return NativePermissionConfig.isEnabled();
  }

  @Override
  public @Nullable PermissionResolver createResolver(
      final PermissionSubject subject,
      final @Nullable PermissionFunction delegate
  ) {
    if (!(subject instanceof Player player)) {
      return null;
    }
    return new NativePermissionResolver(player, delegate, service);
  }

  @Override
  public void close() {
    service.close();
  }
}
