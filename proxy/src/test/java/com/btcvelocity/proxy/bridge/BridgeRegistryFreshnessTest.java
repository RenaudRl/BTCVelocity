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

package com.btcvelocity.proxy.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.btcvelocity.api.bridge.BridgeMessage;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * What a backend said stops being believed when it stops saying it.
 *
 * <p>The defect these tests pin down: a backend that dies silently keeps its last good report
 * forever, so the proxy goes on answering "healthy" about a dead server, and "this world is
 * loaded" about a world that no longer exists anywhere.</p>
 */
class BridgeRegistryFreshnessTest {

  private static final String SERVER = "lobby";
  private static final long FRESHNESS = 30_000L;

  private final AtomicLong now = new AtomicLong(1_000_000L);

  private BridgeMessage.Health health(final double mspt, final List<String> worlds) {
    final BridgeMessage.Envelope envelope = new BridgeMessage.Envelope(BridgeMessage.VERSION,
        UUID.randomUUID(), "health", SERVER, "btc-proxy", now.get(), now.get() + 30_000L);
    return new BridgeMessage.Health(envelope, SERVER, mspt, 20.0d, 3, worlds);
  }

  private BridgeMessage.WorldLoaded worldLoaded(final String world) {
    final BridgeMessage.Envelope envelope = new BridgeMessage.Envelope(BridgeMessage.VERSION,
        UUID.randomUUID(), "world_loaded", SERVER, "btc-proxy", now.get(), now.get() + 30_000L);
    return new BridgeMessage.WorldLoaded(envelope, SERVER, world, 120L);
  }

  private BridgeMessage.WorldUnloaded worldUnloaded(final String world) {
    final BridgeMessage.Envelope envelope = new BridgeMessage.Envelope(BridgeMessage.VERSION,
        UUID.randomUUID(), "world_unloaded", SERVER, "btc-proxy", now.get(), now.get() + 30_000L);
    return new BridgeMessage.WorldUnloaded(envelope, SERVER, world);
  }

  // --- health ------------------------------------------------------------------------------

  @Test
  void aFreshReportIsBelieved() {
    // Witness for every expiry test below: without it, a registry that answered UNKNOWN to
    // everything would pass them all.
    final BackendHealthRegistry registry = new BackendHealthRegistry(FRESHNESS, now::get);
    registry.update(health(5.0d, List.of()));

    assertEquals(5.0d, registry.getMspt(SERVER));
    assertTrue(registry.isHealthy(SERVER));
    assertFalse(registry.isStale(SERVER));
  }

  @Test
  void aSilentBackendStopsBeingHealthy() {
    final BackendHealthRegistry registry = new BackendHealthRegistry(FRESHNESS, now::get);
    registry.update(health(5.0d, List.of()));

    now.addAndGet(FRESHNESS + 1L);

    assertEquals(BackendHealthRegistry.UNKNOWN_MSPT, registry.getMspt(SERVER),
        "an expired report reads as unknown, never as the last good value");
    assertFalse(registry.isHealthy(SERVER));
    assertTrue(registry.isOverloaded(SERVER), "unknown is treated conservatively");
    assertTrue(registry.getHealth(SERVER).isEmpty());
  }

  @Test
  void aReportRightOnTheEdgeIsStillBelieved() {
    final BackendHealthRegistry registry = new BackendHealthRegistry(FRESHNESS, now::get);
    registry.update(health(5.0d, List.of()));

    now.addAndGet(FRESHNESS);

    assertTrue(registry.isHealthy(SERVER), "the window is inclusive: equal is not older");
  }

  @Test
  void neverSpokeAndStoppedSpeakingAreDifferentEvents() {
    final BackendHealthRegistry registry = new BackendHealthRegistry(FRESHNESS, now::get);

    assertFalse(registry.isStale("never-seen"), "nothing about it ever grew old");
    assertEquals(BackendHealthRegistry.UNKNOWN_MSPT, registry.getMspt("never-seen"));

    registry.update(health(5.0d, List.of()));
    now.addAndGet(FRESHNESS + 1L);
    assertTrue(registry.isStale(SERVER), "this one did grow old");
  }

  @Test
  void aNewReportRevivesASilentBackend() {
    final BackendHealthRegistry registry = new BackendHealthRegistry(FRESHNESS, now::get);
    registry.update(health(5.0d, List.of()));
    now.addAndGet(FRESHNESS + 1L);
    assertFalse(registry.isHealthy(SERVER));

    registry.update(health(4.0d, List.of()));

    assertTrue(registry.isHealthy(SERVER));
    assertFalse(registry.isStale(SERVER));
  }

  // --- worlds ------------------------------------------------------------------------------

  @Test
  void aLoadedWorldIsRememberedWhileTheBackendSpeaks() {
    final WorldRegistry registry = new WorldRegistry(FRESHNESS, now::get);
    registry.onWorldLoaded(worldLoaded("btc_skyblock"));

    assertTrue(registry.isWorldLoaded(SERVER, "btc_skyblock"));
    assertEquals(Set.of("btc_skyblock"), registry.getLoadedWorlds(SERVER));
  }

  @Test
  void aSilentBackendHoldsNoWorld() {
    final WorldRegistry registry = new WorldRegistry(FRESHNESS, now::get);
    registry.onWorldLoaded(worldLoaded("btc_skyblock"));

    now.addAndGet(FRESHNESS + 1L);

    assertFalse(registry.isWorldLoaded(SERVER, "btc_skyblock"),
        "a world nobody confirms any more is not loaded");
    assertEquals(Set.of(), registry.getLoadedWorlds(SERVER));
    assertTrue(registry.isStale(SERVER));
  }

  @Test
  void theHealthReportKeepsTheWorldViewAliveAndReconcilesIt() {
    // The health report lists every loaded world: it is a full statement, not an increment.
    final WorldRegistry registry = new WorldRegistry(FRESHNESS, now::get);
    registry.onWorldLoaded(worldLoaded("btc_skyblock"));
    registry.onWorldLoaded(worldLoaded("disparu"));

    now.addAndGet(FRESHNESS - 1L);
    registry.onHealth(health(5.0d, List.of("btc_skyblock")));
    now.addAndGet(FRESHNESS - 1L);

    assertTrue(registry.isWorldLoaded(SERVER, "btc_skyblock"), "still alive, still believed");
    assertFalse(registry.isWorldLoaded(SERVER, "disparu"),
        "a world the backend no longer lists disappears, even if its unload message was lost");
  }

  @Test
  void aServerWithNoWorldIsNotTheSameAsASilentServer() {
    final WorldRegistry registry = new WorldRegistry(FRESHNESS, now::get);
    registry.onWorldLoaded(worldLoaded("btc_skyblock"));
    registry.onWorldUnloaded(worldUnloaded("btc_skyblock"));

    assertFalse(registry.isStale(SERVER), "it just spoke: it holds no world, and that is fresh");
    assertEquals(Set.of(), registry.getLoadedWorlds(SERVER));
  }

  @Test
  void forgettingAServerIsNotTheSameAsWaitingForItToExpire() {
    final BackendHealthRegistry healthRegistry = new BackendHealthRegistry(FRESHNESS, now::get);
    final WorldRegistry worldRegistry = new WorldRegistry(FRESHNESS, now::get);
    healthRegistry.update(health(5.0d, List.of()));
    worldRegistry.onWorldLoaded(worldLoaded("btc_skyblock"));

    healthRegistry.forget(SERVER);
    worldRegistry.forget(SERVER);

    assertFalse(healthRegistry.isStale(SERVER), "nothing is left to grow old");
    assertFalse(worldRegistry.isStale(SERVER));
    assertEquals(BackendHealthRegistry.UNKNOWN_MSPT, healthRegistry.getMspt(SERVER));
    assertEquals(Set.of(), worldRegistry.getLoadedWorlds(SERVER));
  }
}
