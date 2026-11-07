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
import com.btcvelocity.api.permission.PermissionResolverProvider;
import com.velocitypowered.api.permission.PermissionFunction;
import com.velocitypowered.api.permission.PermissionSubject;
import com.velocitypowered.api.proxy.Player;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Optional native BTC provider; disabled by default for public standalone proxies. */
final class NativePermissionResolverProvider implements PermissionResolverProvider, AutoCloseable {

  private static final Logger LOGGER = LogManager.getLogger(NativePermissionResolverProvider.class);

  private final NativePermissionService service = new NativePermissionService();

  /**
   * The provider stays off, leaving Velocity's default permissions, when the flag is absent or when
   * the configuration is invalid (for instance a {@code ${env:NAME}} whose variable is unset). An
   * unreadable file is not a verdict: the service retries it on demand.
   */
  @Override
  public boolean isAvailable() {
    if (!NativePermissionConfig.isEnabled()) {
      return false;
    }
    try {
      NativePermissionConfig.load(NativePermissionConfig.configPath());
    } catch (IOException unreadable) {
      // Retried on demand by the service.
    } catch (IllegalArgumentException invalid) {
      // The message names settings and variables, never values.
      LOGGER.error("Native BTC permissions disabled, default permissions apply: {}", invalid.getMessage());
      return false;
    }
    return true;
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

  /** See {@link NativePermissionService#awaitLoaded(UUID)}. */
  CompletableFuture<Void> awaitLoaded(final UUID subject) {
    return service.awaitLoaded(subject);
  }

  @Override
  public void close() {
    service.close();
  }
}
