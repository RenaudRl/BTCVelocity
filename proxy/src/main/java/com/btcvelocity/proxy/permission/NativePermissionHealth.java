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

/**
 * Observable state of the proxy-side native permissions backend (PERM-025, PERM-026).
 *
 * <p><b>Why this exists.</b> The proxy answers {@code UNDEFINED} for every node it cannot resolve,
 * which is exactly what it answers for a player who genuinely holds no permission. Without this
 * record, a backend that failed to initialize and a network where nobody has any right are the same
 * observation, and the operator has no way to tell them apart while players are being refused.
 *
 * <p><b>Staleness is a distinct condition from unavailability.</b> The group catalog is deliberately
 * kept after a failed refresh — dropping it would silently retract every inherited permission on the
 * whole network — so the backend can be perfectly reachable while still deciding from a catalog
 * frozen at the moment of an earlier incident. {@code groupCatalogStaleSinceEpochMillis} is what
 * makes that actionable: a catalog stale for one minute is an incident in progress, one stale for a
 * day is a catalog nobody will ever refresh.
 *
 * <p>No field carries a connection string, credential or payload: a health record is meant to be
 * displayed, and must not become a leak channel.
 *
 * @param backendAvailable whether the pool and configuration are currently usable
 * @param lastFailureReason reason of the last failure, without connection string, or {@code null}
 * @param groupCatalogSize number of groups currently held
 * @param groupCatalogStale whether the held catalog is known to be out of date
 * @param groupCatalogStaleSinceEpochMillis instant staleness began, or {@code 0} when not stale
 */
record NativePermissionHealth(
    boolean backendAvailable,
    String lastFailureReason,
    int groupCatalogSize,
    boolean groupCatalogStale,
    long groupCatalogStaleSinceEpochMillis
) {
}
