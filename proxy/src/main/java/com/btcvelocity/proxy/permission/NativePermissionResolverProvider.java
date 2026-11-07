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
   * On exactly when the flag is set: only an absent flag leaves Velocity's default permissions.
   *
   * <p>An invalid configuration (an unset {@code ${env:NAME}}, a missing or invalid setting, an
   * empty {@code valkey-uri}) keeps the provider, which then refuses every player permission, and
   * says so in an ERROR (design decision 6). Velocity's defaults answer UNDEFINED, which
   * {@code /server} and the queue commands treat as granted: a typo must not open every backend.
   * The service re-reads the file on its next attempt, so fixing it needs no restart.
   */
  @Override
  public boolean isAvailable() {
    if (!NativePermissionConfig.isEnabled()) {
      return false;
    }
    try {
      NativePermissionConfig.load(NativePermissionConfig.configPath());
    } catch (IOException unreadable) {
      LOGGER.warn("Native BTC permissions configuration is not readable yet ({}); player permissions are"
          + " refused until the service can read it.", unreadable.toString());
    } catch (IllegalArgumentException invalid) {
      // The message names settings and variables, never values.
      LOGGER.error("Native BTC permissions configuration is invalid; every player permission is refused"
          + " until it is fixed: {}", invalid.getMessage());
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

  /** See {@link NativePermissionService#evict(UUID)}. */
  void evict(final UUID subject) {
    service.evict(subject);
  }

  @Override
  public void close() {
    service.close();
  }
}
