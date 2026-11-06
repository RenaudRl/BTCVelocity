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

package com.btcvelocity.proxy.redis;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.btcvelocity.proxy.queue.redis.depot.VelocityQueueDepotService;
import com.btcvelocity.proxy.redis.depot.player.PlayerDepotService;
import com.btcvelocity.proxy.redis.depot.proxy.ProxyDepotService;
import com.btcvelocity.proxy.redis.provider.LettuceProvider;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

class VelocityRedisShutdownTest {

  @Test
  void theConnectionIsClosedEvenWhenATeardownFails() throws Exception {
    final VelocityRedis redis = mock(VelocityRedis.class, CALLS_REAL_METHODS);
    final PlayerDepotService players = mock(PlayerDepotService.class);
    final ProxyDepotService proxies = mock(ProxyDepotService.class);
    final LettuceProvider provider = mock(LettuceProvider.class);
    doThrow(new IllegalStateException("redis down")).when(players).teardown();
    set(redis, "playerService", players);
    set(redis, "proxyService", proxies);
    set(redis, "queueService", mock(VelocityQueueDepotService.class));
    set(redis, "provider", provider);

    assertThrows(IllegalStateException.class, redis::shutdown);

    verify(proxies).teardown();
    verify(provider).disconnect();
  }

  private static void set(final Object target, final String name, final Object value)
      throws ReflectiveOperationException {
    final Field field = VelocityRedis.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }
}
