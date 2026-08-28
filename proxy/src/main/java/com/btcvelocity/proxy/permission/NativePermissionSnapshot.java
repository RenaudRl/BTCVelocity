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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Gson DTO matching the stable TypeWriter Permissions snapshot contract. */
final class NativePermissionSnapshot {

  UUID subject;
  List<Node> permissions = new ArrayList<>();
  List<Metadata> metadata = new ArrayList<>();
  Map<String, Group> groups = new HashMap<>();
  Set<String> directGroups = new HashSet<>();
  String primaryGroup;
  long revision;
  long updatedAtEpochMillis;

  static final class Node {
    String node = "";
    boolean value;
    int priority;
    Map<String, String> contexts = new HashMap<>();
    Long expiresAtEpochMillis;
    String sourceId = "";
  }

  static final class Metadata {
    String key = "";
    String value = "";
    int priority;
    Map<String, String> contexts = new HashMap<>();
    Long expiresAtEpochMillis;
    String sourceId = "";
  }

  static final class Group {
    String name = "";
    int weight;
    List<Node> permissions = new ArrayList<>();
    List<Metadata> metadata = new ArrayList<>();
    Set<String> inherits = new HashSet<>();
  }
}
