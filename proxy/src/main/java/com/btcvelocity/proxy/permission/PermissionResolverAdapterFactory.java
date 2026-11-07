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
import com.btcvelocity.api.permission.PermissionResolverFunctionAdapter;
import com.btcvelocity.api.permission.PermissionResolverProvider;
import com.velocitypowered.api.permission.PermissionFunction;
import com.velocitypowered.api.permission.PermissionSubject;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Factory for producing an {@link PermissionResolver} for a given {@link PermissionSubject}.
 *
 * <p>This factory supports an optional native BTC permissions provider. The provider is enabled
 * explicitly through the proxy's native permissions configuration and uses the same PostgreSQL/Valkey
 * protocol as the TypeWriter Permissions extension.
 *
 * <p>If no provider can be loaded (e.g., the embedded jar is missing, cannot be extracted, or the
 * provider reports it is unavailable), this factory falls back to {@link PermissionResolverFunctionAdapter}.
 *
 * <p>The provider lookup is performed at most once per JVM. The result is cached (including the
 * "no provider available" outcome) to avoid repeated setup overhead.
 */
public final class PermissionResolverAdapterFactory {

  private static final Logger LOGGER = LogManager.getLogger(PermissionResolverAdapterFactory.class);

  private static volatile boolean hasLoadedProvider = false;
  private static volatile @Nullable PermissionResolverProvider loadedProvider = null;

  private PermissionResolverAdapterFactory() {
  }

  /**
   * Creates an {@link PermissionResolver} for the supplied {@link PermissionSubject}.
   *
   * <p>If the native BTC provider is enabled,
   * this method delegates to {@link PermissionResolverProvider#createResolver(PermissionSubject, PermissionFunction)}.
   * Otherwise, it returns a {@link PermissionResolverFunctionAdapter} wrapping {@code delegate}.
   *
   * @param permissionSubject the subject the resolver will evaluate permissions for
   * @param delegate the base permission function to delegate to when the permission resolver is not available.
   *                 Existing {@link PermissionResolver} instances are preserved as the fallback.
   * @return a permission resolver when an integration is available; otherwise a simple adapter that adapts a permission function
   */
  public static PermissionResolver createPermissionResolverAdapter(
      PermissionSubject permissionSubject,
      PermissionFunction delegate
  ) {
    final Optional<PermissionResolver> nativeResolver = getLoadedProvider()
        .map(provider -> provider.createResolver(permissionSubject, delegate));
    if (nativeResolver.isPresent()) {
      return nativeResolver.get();
    }
    if (delegate instanceof PermissionResolver resolver) {
      return resolver;
    }
    return new PermissionResolverFunctionAdapter(delegate);
  }

  /**
   * Starts reading a freshly authenticated player's permissions and returns the point a login may
   * go on from: the snapshot is cached, or {@link NativePermissionService#LOAD_TIMEOUT} has passed.
   *
   * <p>Compose on the result; never {@code join} it on a connection event loop. It never fails, and
   * a slow or broken source costs the player their rights, never the entry. With the native provider
   * off it is already complete, so nothing waits.
   */
  public static CompletableFuture<Void> preloadPermissions(final UUID subject) {
    try {
      if (getLoadedProvider().orElse(null) instanceof NativePermissionResolverProvider nativeProvider) {
        return nativeProvider.awaitLoaded(subject);
      }
    } catch (RuntimeException error) {
      // The provider's own message names settings and variables, never values.
      LOGGER.error("Native BTC permissions preload could not start ({}); the player enters without rights.",
          error.getClass().getSimpleName());
    }
    return CompletableFuture.completedFuture(null);
  }

  /**
   * Drops what the native provider caches for an account whose last session has ended, so the cache
   * holds connected players only. Does nothing with the provider off.
   */
  public static void evictPermissions(final UUID subject) {
    if (getLoadedProvider().orElse(null) instanceof NativePermissionResolverProvider nativeProvider) {
      nativeProvider.evict(subject);
    }
  }

  /** Stops the native provider's asynchronous resources during proxy shutdown. */
  public static void shutdown() {
    final PermissionResolverProvider provider = loadedProvider;
    if (provider instanceof NativePermissionResolverProvider nativeProvider) {
      nativeProvider.close();
    }
  }

  private static Optional<PermissionResolverProvider> getLoadedProvider() {
    if (hasLoadedProvider) {
      return Optional.ofNullable(loadedProvider);
    }

    synchronized (PermissionResolverAdapterFactory.class) {
      // Check again in lock
      if (hasLoadedProvider) {
        return Optional.ofNullable(loadedProvider);
      }

      loadedProvider = loadProviderOnce().orElse(null);
      hasLoadedProvider = true;

      return Optional.ofNullable(loadedProvider);
    }
  }

  private static Optional<PermissionResolverProvider> loadProviderOnce() {
    final NativePermissionResolverProvider provider = new NativePermissionResolverProvider();
    if (provider.isAvailable()) {
      LOGGER.info("Native BTC permissions provider enabled; legacy permissions provider is disabled.");
      return Optional.of(provider);
    }
    return Optional.empty();
  }
}
