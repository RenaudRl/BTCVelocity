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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Factory for producing an {@link PermissionResolver} for a given {@link PermissionSubject}.
 *
 * <p>This factory supports an optional native BTC permissions provider. The provider is enabled
 * explicitly through the proxy's native permissions configuration and uses the same MySQL/Redis
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
