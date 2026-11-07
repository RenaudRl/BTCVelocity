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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.Player;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What a login does while the proxy reads the player's permissions: wait a bounded time without
 * blocking, never refuse for a failure, and read an unknown player once.
 *
 * <p>The database is a {@link FakePermissionTables}, so the order of events is set by latches and the
 * only real time is the (shortened) login wait.
 */
class NativePermissionLoginLoadTest {

  private static final UUID SUBJECT = UUID.fromString("3c4d5e6f-7081-4a92-b3c4-d5e6f7081a92");
  private static final String NODE = "velocity.command.info";
  private static final Duration SHORT_WAIT = Duration.ofMillis(50);
  private static final Duration NEVER = Duration.ofMinutes(5);
  private static final long BOUND_SECONDS = 10;
  private static final NativePermissionConfig.Config CONFIG = new NativePermissionConfig.Config(
      "jdbc:postgresql://db.invalid:5432/btc", "reader", "", "btc", "proxy-1", "btc_permissions_", "");

  private FakePermissionTables tables;
  private NativePermissionService service;
  private NativePermissionResolver resolver;
  private ExecutorService eventLoop;

  @BeforeEach
  void setUp() {
    tables = new FakePermissionTables();
    final Player player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(SUBJECT);
    service = new NativePermissionService(tables, CONFIG, new ManualExecutor());
    resolver = new NativePermissionResolver(player, null, service);
    eventLoop = Executors.newSingleThreadExecutor(task -> new Thread(task, "event-loop-under-test"));
  }

  @AfterEach
  void tearDown() {
    tables.gate.countDown();
    eventLoop.shutdownNow();
    service.close();
  }

  @Test
  void aLoginPastTheDelayEntersWithoutRightsThenTheSnapshotApplies() throws Exception {
    tables.grants.put(SUBJECT, Set.of(NODE));
    tables.gate = new CountDownLatch(1);
    tables.readStarted = new CountDownLatch(1);

    final CompletableFuture<Void> login = service.awaitLoaded(SUBJECT, SHORT_WAIT);
    login.get(BOUND_SECONDS, TimeUnit.SECONDS);

    assertTrue(tables.readStarted.await(BOUND_SECONDS, TimeUnit.SECONDS), "the read was started");
    assertFalse(login.isCompletedExceptionally(), "a late snapshot is not a reason to refuse the entry");
    assertEquals(Tristate.FALSE, resolver.getPermissionValue(NODE), "no snapshot yet: everything is refused");
    assertEquals(Map.of(), resolver.getPermissionMap());

    tables.gate.countDown();
    assertNotNull(service.load(SUBJECT).get(BOUND_SECONDS, TimeUnit.SECONDS));

    assertEquals(Tristate.TRUE, resolver.getPermissionValue(NODE),
        "the snapshot applies on arrival, without the player reconnecting");
    assertEquals(1, tables.playerReads.get(), "the load that outlived the wait was not restarted");
  }

  @Test
  void aSnapshotThatArrivesInTimeReleasesTheLoginAtOnce() throws Exception {
    tables.grants.put(SUBJECT, Set.of(NODE));

    service.awaitLoaded(SUBJECT, NEVER).get(BOUND_SECONDS, TimeUnit.SECONDS);

    assertEquals(Tristate.TRUE, resolver.getPermissionValue(NODE));
  }

  @Test
  void aFailedLoadNeverFailsTheLoginAndTheNextTestRetries() throws Exception {
    tables.grants.put(SUBJECT, Set.of(NODE));
    tables.playerReadsFail = true;

    final CompletableFuture<Void> login = service.awaitLoaded(SUBJECT, NEVER);
    login.get(BOUND_SECONDS, TimeUnit.SECONDS);

    assertFalse(login.isCompletedExceptionally());
    assertEquals(Tristate.FALSE, resolver.getPermissionValue(NODE));

    tables.playerReadsFail = false;
    assertNotNull(service.load(SUBJECT).get(BOUND_SECONDS, TimeUnit.SECONDS));
    assertEquals(Tristate.TRUE, resolver.getPermissionValue(NODE), "rights appear once the database is back");
  }

  @Test
  void anUnknownPlayerCostsOneReadHoweverManyPermissionsAreTested() throws Exception {
    service.awaitLoaded(SUBJECT, NEVER).get(BOUND_SECONDS, TimeUnit.SECONDS);

    for (int test = 0; test < 20; test++) {
      assertEquals(Tristate.UNDEFINED, resolver.getPermissionValue(NODE + test));
    }

    assertEquals(1, tables.playerReads.get());
    assertNotNull(service.snapshot(SUBJECT), "the empty snapshot is cached");
  }

  @Test
  void anUnknownPlayerIsNotCachedWhileTheCatalogCouldNotBeRead() throws Exception {
    service.close();
    tables.groupReadsFail = true;
    service = new NativePermissionService(tables, CONFIG, new ManualExecutor());

    assertNull(service.load(SUBJECT).get(BOUND_SECONDS, TimeUnit.SECONDS));
    assertNull(service.load(SUBJECT).get(BOUND_SECONDS, TimeUnit.SECONDS));

    assertEquals(2, tables.playerReads.get(),
        "an unreadable catalog looks empty: caching the player as grade-less would outlive the outage");
  }

  @Test
  void theLoginWaitNeverBlocksTheCallingThread() throws Exception {
    tables.grants.put(SUBJECT, Set.of(NODE));
    tables.gate = new CountDownLatch(1);
    tables.readStarted = new CountDownLatch(1);
    final Thread[] caller = new Thread[1];

    // The read is gated shut: a call that waited for it would never return and fail this get().
    final CompletableFuture<Void> login = eventLoop.submit(() -> {
      caller[0] = Thread.currentThread();
      return service.awaitLoaded(SUBJECT, NEVER);
    }).get(BOUND_SECONDS, TimeUnit.SECONDS);

    assertFalse(login.isDone(), "the login is still waiting on the read");
    assertTrue(tables.readStarted.await(BOUND_SECONDS, TimeUnit.SECONDS));

    tables.gate.countDown();
    login.get(BOUND_SECONDS, TimeUnit.SECONDS);

    assertFalse(tables.readers.contains(caller[0]), "no read ran on the event thread");
  }

  @Test
  void withTheProviderOffTheLoginWaitsForNothing() {
    assertTrue(PermissionResolverAdapterFactory.preloadPermissions(SUBJECT).isDone());
  }
}
