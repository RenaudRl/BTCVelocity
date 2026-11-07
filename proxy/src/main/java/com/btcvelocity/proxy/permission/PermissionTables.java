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

import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

/**
 * Blocking reads of the permission tables, the only part of {@link NativePermissionService} that
 * touches the database. Every call blocks: the service runs them on its own I/O executor, never on
 * a connection event loop. A seam so the cache lifecycle can be tested without PostgreSQL.
 */
interface PermissionTables {

  /** The stored snapshot of {@code subject}, or {@code null} when the database holds no row for it. */
  NativePermissionSnapshot readPlayer(UUID subject) throws SQLException;

  /** The whole group catalog, keyed by lower-cased group name. */
  Map<String, NativePermissionSnapshot.Group> readGroups() throws SQLException;
}
