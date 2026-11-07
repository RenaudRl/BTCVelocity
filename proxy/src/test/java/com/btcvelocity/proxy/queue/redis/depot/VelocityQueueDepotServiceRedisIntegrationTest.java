/*
 * Copyright (C) 2026 Velocity Contributors
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

package com.btcvelocity.proxy.queue.redis.depot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.btcvelocity.api.queue.QueueState;
import com.btcvelocity.api.queue.ServerStatus;
import com.btcvelocity.proxy.queue.RedisVelocityQueue;
import com.btcvelocity.proxy.redis.VelocityRedis;
import com.btcvelocity.proxy.redis.depot.Depot;
import com.btcvelocity.proxy.redis.packet.PacketSerializer;
import com.btcvelocity.proxy.redis.provider.LettuceProvider;
import com.velocitypowered.api.scheduler.Scheduler;
import com.velocitypowered.proxy.config.VelocityConfiguration;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The queue depot against one real Redis: a snapshot is removed only when it is empty, whoever
 * wrote it.
 *
 * <p>Opt-in: {@code -PredisIntegration -PredisContainer=name -PredisPort=56391
 * [-PredisImage=valkey/valkey:8.1.1-alpine]}, with the server started by {@code docker run --rm -d
 * --name name -p 127.0.0.1:56391:6379 image}.</p>
 */
@Tag("redis-integration")
class VelocityQueueDepotServiceRedisIntegrationTest {

  private static final int PORT = Integer.getInteger("btc.redis.port", 56379);
  private static final String HASH = "velocityqueuedepotentry";
  private static final String SERVER_NAME = "Dungeon-7";

  private final LettuceProvider provider = provider();
  private Depot<String, VelocityQueueDepotEntry> depot;
  private VelocityQueueDepotService service;

  @BeforeEach
  void setUp() {
    provider.deleteKey(HASH);
    depot = provider.createDepot(VelocityQueueDepotEntry.class);
    final VelocityRedis redis = mock(VelocityRedis.class);
    when(redis.getProvider()).thenReturn(provider);
    service = new VelocityQueueDepotService(redis);
  }

  @AfterEach
  void tearDown() {
    try {
      provider.deleteKey(HASH);
    } finally {
      provider.disconnect();
    }
  }

  @Test
  void anEmptySnapshotIsRemoved() {
    service.upsertQueue(emptyQueue());

    assertTrue(service.removeQueueIfEmpty(SERVER_NAME));

    assertFalse(depot.contains(SERVER_NAME));
  }

  @Test
  void anEmptySnapshotIsRemovedWhateverProxyWroteIt() {
    // Written by a master that has since gone, and by an older build with an owner field.
    raw("{\"uniqueId\":\"" + SERVER_NAME + "\",\"entries\":[],\"serverStatus\":\"OFFLINE\","
        + "\"state\":\"ACTIVE\",\"proxyId\":\"proxy-gone\"}");

    assertTrue(service.removeQueueIfEmpty(SERVER_NAME));

    assertFalse(depot.contains(SERVER_NAME));
  }

  @Test
  void aSnapshotWithPlayersSurvives() {
    // Witness for the removals above: emptiness is the whole guard.
    raw("{\"uniqueId\":\"" + SERVER_NAME + "\",\"entries\":[{\"username\":\"alice\"}],"
        + "\"serverStatus\":\"ONLINE\",\"state\":\"ACTIVE\"}");

    assertFalse(service.removeQueueIfEmpty(SERVER_NAME));

    assertTrue(depot.contains(SERVER_NAME));
  }

  @Test
  void aSnapshotWithNoListOrUnreadableSurvives() {
    raw("{\"uniqueId\":\"" + SERVER_NAME + "\",\"entries\":null}");
    assertFalse(service.removeQueueIfEmpty(SERVER_NAME));
    assertTrue(depot.contains(SERVER_NAME));

    raw("not json at all");
    assertFalse(service.removeQueueIfEmpty(SERVER_NAME));
    assertTrue(depot.contains(SERVER_NAME));
  }

  @Test
  void anAbsentKeyRemovesNothing() {
    assertFalse(service.removeQueueIfEmpty(SERVER_NAME));
    assertEquals(List.of(), List.copyOf(service.getQueueKeys()));
  }

  @Test
  void theKeysAreTheServerNamesThatHaveASnapshot() {
    service.upsertQueue(emptyQueue());

    assertEquals(List.of(SERVER_NAME), List.copyOf(service.getQueueKeys()));
  }

  private static void raw(final String json) {
    final RedisClient client = RedisClient.create("redis://127.0.0.1:" + PORT);
    try (StatefulRedisConnection<String, String> connection = client.connect()) {
      connection.sync().hset(HASH, SERVER_NAME, json);
    } finally {
      client.shutdown();
    }
  }

  private static RedisVelocityQueue emptyQueue() {
    final RedisVelocityQueue queue = mock(RedisVelocityQueue.class);
    when(queue.getName()).thenReturn(SERVER_NAME);
    when(queue.getInternalEntries()).thenReturn(List.of());
    when(queue.getServerStatus()).thenReturn(ServerStatus.ONLINE);
    when(queue.getState()).thenReturn(QueueState.ACTIVE);
    return queue;
  }

  private static LettuceProvider provider() {
    final VelocityConfiguration.Redis config = mock(VelocityConfiguration.Redis.class);
    when(config.getHost()).thenReturn("127.0.0.1");
    when(config.getPort()).thenReturn(PORT);
    when(config.getUsername()).thenReturn("");
    when(config.getPassword()).thenReturn("");
    when(config.getBackend()).thenReturn("redis");
    final LettuceProvider provider = new LettuceProvider(config,
        mock(Scheduler.class, RETURNS_DEEP_STUBS), new PacketSerializer());
    provider.restart();
    return provider;
  }
}
