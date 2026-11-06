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

package com.velocitypowered.proxy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.btcvelocity.proxy.redis.VelocityRedis;
import com.velocitypowered.proxy.config.VelocityConfiguration;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

/**
 * The proxy decides at startup whether Redis runs; a reload can flip the flag afterwards.
 * Shutdown must follow what was started, not the current flag (CTD {@code 94f72592b}).
 */
class RedisShutdownGuardTest {

  private final VelocityServer server = mock(VelocityServer.class, CALLS_REAL_METHODS);

  @Test
  void redisEnabledByReloadButNeverStartedDoesNotBreakShutdown() throws Exception {
    set("configuration", configWithRedis(true));
    set("redis", null);

    assertDoesNotThrow(server::shutdownRedis);
  }

  @Test
  void redisDisabledByReloadIsStillShutDown() throws Exception {
    final VelocityRedis redis = mock(VelocityRedis.class);
    set("configuration", configWithRedis(false));
    set("redis", redis);

    server.shutdownRedis();

    verify(redis).shutdown();
  }

  @Test
  void aFailingRedisShutdownDoesNotAbortTheProxyShutdown() throws Exception {
    final VelocityRedis redis = mock(VelocityRedis.class);
    doThrow(new IllegalStateException("redis down")).when(redis).shutdown();
    set("configuration", configWithRedis(true));
    set("redis", redis);

    assertDoesNotThrow(server::shutdownRedis);
  }

  private static VelocityConfiguration configWithRedis(final boolean enabled) {
    final VelocityConfiguration configuration = mock(VelocityConfiguration.class, RETURNS_DEEP_STUBS);
    when(configuration.getRedis().isEnabled()).thenReturn(enabled);
    return configuration;
  }

  private void set(final String name, final Object value) throws ReflectiveOperationException {
    final Field field = VelocityServer.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(server, value);
  }
}
