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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.permission.Tristate;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisConnectionStateListener;
import io.lettuce.core.pubsub.RedisPubSubListener;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.sync.RedisPubSubCommands;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * How long a snapshot lives in the proxy's cache: until its player leaves, until the bus has been
 * away, or until its grade changes. Reads are a {@link FakePermissionTables}; time is a
 * {@link ManualExecutor}, so no test sleeps.
 */
class NativePermissionCacheLifecycleTest {

  private static final UUID ALICE = UUID.fromString("a1a1a1a1-0000-4000-8000-000000000001");
  private static final UUID BOB = UUID.fromString("b2b2b2b2-0000-4000-8000-000000000002");
  private static final String OWN_NODE = "velocity.command.server";
  private static final String STAFF_NODE = "velocity.command.info";
  private static final long BOUND_SECONDS = 10;
  private static final String CHANNEL = "btc:permissions:btc";
  private static final NativePermissionConfig.Config CONFIG = new NativePermissionConfig.Config(
      "jdbc:postgresql://db.invalid:5432/btc", "reader", "", "btc", "proxy-1", "btc_permissions_", "");

  private FakePermissionTables tables;
  private ManualExecutor refreshWindow;
  private NativePermissionService service;

  @BeforeEach
  void setUp() throws Exception {
    tables = new FakePermissionTables();
    refreshWindow = new ManualExecutor();
    tables.groups = Map.of("staff", staff(STAFF_NODE));
    tables.grants.put(ALICE, Set.of(OWN_NODE));
    tables.memberships.put(ALICE, Set.of("staff"));
    tables.memberships.put(BOB, Set.of("staff"));
    service = new NativePermissionService(tables, CONFIG, refreshWindow);
    load(ALICE);
    load(BOB);
  }

  @AfterEach
  void tearDown() {
    tables.gate.countDown();
    service.close();
  }

  // --- disconnect ----------------------------------------------------------------------------

  @Test
  void aPlayersCacheIsEmptiedWhenTheyDisconnectAndOnlyTheirs() {
    service.evict(ALICE);

    assertNull(service.snapshot(ALICE));
    assertNotNull(service.snapshot(BOB));
  }

  @Test
  void aReadStillInFlightAtDisconnectDoesNotPutTheSnapshotBack() throws Exception {
    service.evict(ALICE);
    tables.gate = new CountDownLatch(1);
    tables.readStarted = new CountDownLatch(1);
    final CompletableFuture<NativePermissionSnapshot> reading = service.load(ALICE);
    assertTrue(tables.readStarted.await(BOUND_SECONDS, TimeUnit.SECONDS));

    service.evict(ALICE);
    tables.gate.countDown();
    reading.get(BOUND_SECONDS, TimeUnit.SECONDS);

    assertNull(service.snapshot(ALICE), "no one is left to evict it: it would stay until the proxy restarts");
  }

  // --- bus reconnection ----------------------------------------------------------------------

  @Test
  void afterAReconnectionTheCatalogAndEveryCachedRowAreReadAgain() throws Exception {
    assertEquals(Tristate.TRUE, decision(ALICE, OWN_NODE));
    assertEquals(Tristate.TRUE, decision(BOB, STAFF_NODE));
    final int catalogReads = tables.groupReads.get();
    // Published while the bus was down, so never seen: a grade lost a node, and Alice lost her rights.
    tables.groups = Map.of("staff", staff());
    tables.grants.put(ALICE, Set.of());
    tables.memberships.put(ALICE, Set.of());

    service.reconcileAfterReconnect().get(BOUND_SECONDS, TimeUnit.SECONDS);

    assertEquals(catalogReads + 1, tables.groupReads.get(), "the catalog is read once");
    assertEquals(Tristate.UNDEFINED, decision(ALICE, OWN_NODE));
    assertEquals(Tristate.UNDEFINED, decision(BOB, STAFF_NODE), "the node the grade lost is refused");
  }

  @Test
  void aConnectedPlayerKeepsTheOldSnapshotUntilTheNewOneArrives() throws Exception {
    final NativePermissionSnapshot before = service.snapshot(ALICE);
    tables.grants.put(ALICE, Set.of());
    tables.gate = new CountDownLatch(1);
    tables.readStarted = new CountDownLatch(1);

    final CompletableFuture<Void> reconciling = service.reconcileAfterReconnect();
    assertTrue(tables.readStarted.await(BOUND_SECONDS, TimeUnit.SECONDS));

    assertSame(before, service.snapshot(ALICE), "no hole at \"everything refused\" while the reload runs");
    assertEquals(Tristate.TRUE, decision(ALICE, OWN_NODE));

    tables.gate.countDown();
    reconciling.get(BOUND_SECONDS, TimeUnit.SECONDS);

    assertEquals(Tristate.UNDEFINED, decision(ALICE, OWN_NODE), "then the new snapshot applies");
  }

  @Test
  void aPlayerWhoLeftDuringTheReloadIsNotBroughtBack() throws Exception {
    tables.gate = new CountDownLatch(1);
    tables.readStarted = new CountDownLatch(1);
    final CompletableFuture<Void> reconciling = service.reconcileAfterReconnect();
    assertTrue(tables.readStarted.await(BOUND_SECONDS, TimeUnit.SECONDS));

    service.evict(ALICE);
    service.evict(BOB);
    tables.gate.countDown();
    reconciling.get(BOUND_SECONDS, TimeUnit.SECONDS);

    assertNull(service.snapshot(ALICE));
    assertNull(service.snapshot(BOB));
  }

  @Test
  void anUnreadableCatalogEmptiesTheCacheRatherThanKeepingUnverifiedRights() throws Exception {
    tables.groupReadsFail = true;

    service.reconcileAfterReconnect().get(BOUND_SECONDS, TimeUnit.SECONDS);

    assertNull(service.snapshot(ALICE));
    assertNull(service.snapshot(BOB));
    assertNull(load(ALICE), "a reload against the catalog that could not be read grants nothing");
  }

  @Test
  void aRowThatFailsToReloadDropsTheSnapshotsNotYetReloaded() throws Exception {
    tables.playerReadsFail = true;
    final int reads = tables.playerReads.get();

    service.reconcileAfterReconnect().get(BOUND_SECONDS, TimeUnit.SECONDS);

    assertNull(service.snapshot(ALICE));
    assertNull(service.snapshot(BOB));
    assertEquals(reads + 1, tables.playerReads.get(), "one failure stops the pass: no wait per remaining player");
  }

  @Test
  void theBusIsSubscribedAndItsMessagesAreInvalidations() {
    final Bus bus = attachBus();

    bus.messages().message("btc:permissions:elsewhere", subjectInvalidation(ALICE, 5));
    assertNotNull(service.snapshot(ALICE), "another network's channel is not ours");
    bus.messages().message(CHANNEL, subjectInvalidation(ALICE, 5));

    assertNull(service.snapshot(ALICE));
  }

  @Test
  void aConfirmedResubscriptionReloadsTheCacheAndTheFirstSubscriptionDoesNot() throws Exception {
    final Bus bus = attachBus();
    bus.messages().subscribed(CHANNEL, 1);
    final int catalogReads = tables.groupReads.get();
    tables.readStarted = new CountDownLatch(2);

    bus.state().onRedisDisconnected(null);
    bus.state().onRedisConnected(null, null);
    assertEquals(catalogReads, tables.groupReads.get(), "the connection event alone reads nothing: SUBSCRIBE is not live yet");
    bus.messages().subscribed(CHANNEL, 1);

    assertTrue(tables.readStarted.await(BOUND_SECONDS, TimeUnit.SECONDS), "both cached rows are read again");
    refreshWindow.runAll();
    assertNotNull(service.snapshot(BOB), "a confirmed resubscription does not empty the cache");
  }

  @Test
  void aReconnectionNeverConfirmedEmptiesTheCache() {
    final Bus bus = attachBus();
    bus.messages().subscribed(CHANNEL, 1);

    bus.state().onRedisDisconnected(null);
    bus.state().onRedisConnected(null, null);
    assertEquals(1, refreshWindow.pending(), "the grace delay is running");
    refreshWindow.runAll();

    assertNull(service.snapshot(ALICE));
    assertNull(service.snapshot(BOB));
  }

  // --- group invalidation bursts -------------------------------------------------------------

  @Test
  void aBurstOfGroupInvalidationsCostsOneCatalogRead() {
    final int catalogReads = tables.groupReads.get();
    tables.groups = Map.of("staff", staff());

    for (int published = 0; published < 10; published++) {
      service.handleInvalidation(groupInvalidation("staff"));
    }
    service.handleInvalidation(groupInvalidation("vip"));

    assertEquals(1, refreshWindow.pending(), "the whole burst waits on a single scheduled read");
    assertEquals(catalogReads, tables.groupReads.get(), "nothing is read before the window ends");

    refreshWindow.runAll();

    assertEquals(catalogReads + 1, tables.groupReads.get());
    assertNull(service.snapshot(ALICE), "the members of the changed grade are evicted");
    assertNull(service.snapshot(BOB));
  }

  @Test
  void aBurstEvictsOnlyThePlayersWhoUseAChangedGroup() throws Exception {
    final UUID carol = UUID.fromString("c3c3c3c3-0000-4000-8000-000000000003");
    tables.grants.put(carol, Set.of(OWN_NODE));
    load(carol);

    service.handleInvalidation(groupInvalidation("staff"));
    refreshWindow.runAll();

    assertNull(service.snapshot(ALICE));
    assertNotNull(service.snapshot(carol), "carol is in no group: her snapshot is still right");
  }

  @Test
  void anInvalidationAfterTheWindowStartsTheNextBurst() {
    service.handleInvalidation(groupInvalidation("staff"));
    refreshWindow.runAll();
    final int catalogReads = tables.groupReads.get();

    service.handleInvalidation(groupInvalidation("staff"));

    assertEquals(1, refreshWindow.pending());
    refreshWindow.runAll();
    assertEquals(catalogReads + 1, tables.groupReads.get());
  }

  @Test
  void aFailedCatalogReadEvictsTheMembersKeepsThemRefusedAndRetries() throws Exception {
    tables.groups = Map.of("staff", staff());
    tables.groupReadsFail = true;

    service.handleInvalidation(groupInvalidation("staff"));
    refreshWindow.runAll();

    assertNull(service.snapshot(BOB), "a member keeps no right merged from a catalog known to be outdated");
    assertNull(load(BOB), "and a reload does not merge that catalog either");
    assertEquals(1, refreshWindow.pending(), "the group change is retried, not forgotten");

    tables.groupReadsFail = false;
    refreshWindow.runAll();
    load(BOB);

    assertEquals(Tristate.UNDEFINED, decision(BOB, STAFF_NODE), "the node the grade lost stays refused");
  }

  // --- stale catalog -------------------------------------------------------------------------

  @Test
  void aCatalogUnreadableAtStartGrantsNothing() throws Exception {
    restartWithUnreadableCatalog();

    assertNull(load(ALICE), "her own row merged with an empty, stale catalog would be cached as is");
    assertEquals(Tristate.FALSE, decision(ALICE, OWN_NODE));
  }

  @Test
  void aCatalogUnreadableAtStartIsReadAgainByTheNextLoad() throws Exception {
    restartWithUnreadableCatalog();
    tables.groupReadsFail = false;

    load(BOB);

    assertEquals(Tristate.TRUE, decision(BOB, STAFF_NODE), "no invalidation is needed to recover");
  }

  // --- reads in flight across an invalidation ------------------------------------------------

  @Test
  void aReadStartedBeforeARevocationDoesNotPutTheRevokedRightBack() throws Exception {
    final CompletableFuture<NativePermissionSnapshot> reading = readInTransit(ALICE);

    // The revocation commits and is published while that read is in transit.
    tables.grants.put(ALICE, Set.of());
    tables.revisions.put(ALICE, 3L);
    service.handleInvalidation(subjectInvalidation(ALICE, 3));
    tables.gate.countDown();
    reading.get(BOUND_SECONDS, TimeUnit.SECONDS);

    assertNull(service.snapshot(ALICE), "revision 2 predates the revocation");
    load(ALICE);
    assertEquals(Tristate.UNDEFINED, decision(ALICE, OWN_NODE));
  }

  @Test
  void aReadThatMergedTheOldCatalogIsNotCachedOnceTheGradeChanged() throws Exception {
    final CompletableFuture<NativePermissionSnapshot> reading = readInTransit(BOB);

    tables.groups = Map.of("staff", staff());
    service.handleInvalidation(groupInvalidation("staff"));
    refreshWindow.runAll();
    tables.gate.countDown();
    reading.get(BOUND_SECONDS, TimeUnit.SECONDS);

    assertNotEquals(Tristate.TRUE, decision(BOB, STAFF_NODE), "the grade lost that node before the read ended");
  }

  // --- helpers -------------------------------------------------------------------------------

  /** The listeners the bus registers, taken from the Lettuce boundary it is attached to. */
  private record Bus(RedisPubSubListener<String, String> messages, RedisConnectionStateListener state) {
  }

  @SuppressWarnings("unchecked")
  private Bus attachBus() {
    final RedisClient client = mock(RedisClient.class);
    final StatefulRedisPubSubConnection<String, String> connection = mock(StatefulRedisPubSubConnection.class);
    final RedisPubSubCommands<String, String> commands = mock(RedisPubSubCommands.class);
    when(connection.sync()).thenReturn(commands);

    service.bus(CHANNEL).attach(client, connection);

    final ArgumentCaptor<RedisPubSubListener<String, String>> messages =
        ArgumentCaptor.forClass((Class<RedisPubSubListener<String, String>>) (Class<?>) RedisPubSubListener.class);
    final ArgumentCaptor<RedisConnectionStateListener> state = ArgumentCaptor.forClass(RedisConnectionStateListener.class);
    verify(connection).addListener(messages.capture());
    verify(commands).subscribe(CHANNEL);
    verify(client).addListener(state.capture());
    return new Bus(messages.getValue(), state.getValue());
  }

  private NativePermissionSnapshot load(final UUID subject) throws Exception {
    return service.load(subject).get(BOUND_SECONDS, TimeUnit.SECONDS);
  }

  /** A connected player out of the cache whose revision-2 row is read and held at the gate. */
  private CompletableFuture<NativePermissionSnapshot> readInTransit(final UUID subject) throws Exception {
    tables.revisions.put(subject, 2L);
    service.handleInvalidation(subjectInvalidation(subject, 2));
    assertNull(service.snapshot(subject));
    tables.gate = new CountDownLatch(1);
    tables.readStarted = new CountDownLatch(1);
    final CompletableFuture<NativePermissionSnapshot> reading = service.load(subject);
    assertTrue(tables.readStarted.await(BOUND_SECONDS, TimeUnit.SECONDS));
    return reading;
  }

  private void restartWithUnreadableCatalog() {
    service.close();
    tables.groupReadsFail = true;
    service = new NativePermissionService(tables, CONFIG, refreshWindow);
  }

  /** What the resolver answers for a node the snapshot covers; no snapshot refuses everything. */
  private Tristate decision(final UUID subject, final String node) {
    final NativePermissionSnapshot snapshot = service.snapshot(subject);
    return snapshot == null
        ? Tristate.FALSE
        : NativePermissionEvaluator.evaluate(snapshot, node, service.context(), 0L);
  }

  private static String subjectInvalidation(final UUID subject, final long revision) {
    return "{\"version\":1,\"networkId\":\"btc\",\"serverId\":\"backend-1\",\"subject\":\""
        + subject + "\",\"revision\":" + revision + "}";
  }

  /** A message a backend would publish; {@code proxy-1} is this proxy, so the sender is another. */
  private static String groupInvalidation(final String group) {
    return "{\"version\":1,\"networkId\":\"btc\",\"serverId\":\"backend-1\",\"group\":\""
        + group + "\",\"revision\":1}";
  }

  private static NativePermissionSnapshot.Group staff(final String... nodes) {
    final NativePermissionSnapshot.Group group = new NativePermissionSnapshot.Group();
    group.name = "staff";
    group.weight = 10;
    for (final String name : List.of(nodes)) {
      final NativePermissionSnapshot.Node node = new NativePermissionSnapshot.Node();
      node.node = name;
      node.value = true;
      group.permissions.add(node);
    }
    return group;
  }
}
