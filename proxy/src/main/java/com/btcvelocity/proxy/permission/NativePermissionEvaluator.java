/*
 * Copyright (C) 2018-2026 Velocity Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.btcvelocity.proxy.permission;

import com.velocitypowered.api.permission.Tristate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Pure, allocation-light evaluator for the proxy-side native permissions view. */
public final class NativePermissionEvaluator {

  private static final Map<String, Integer> CONTEXT_WEIGHTS = Map.of(
      "network", 1,
      "server", 2,
      "world", 3,
      "dimension", 4
  );

  private NativePermissionEvaluator() {
  }

  /** Resolves one node using the same deterministic ordering as the Paper extension. */
  public static Tristate evaluate(
      final NativePermissionSnapshot snapshot,
      final String requestedNode,
      final Map<String, String> context,
      final long nowEpochMillis
  ) {
    final Candidate winner = findWinner(snapshot, requestedNode, context, nowEpochMillis);
    if (winner == null) {
      return Tristate.UNDEFINED;
    }
    return winner.node.value ? Tristate.TRUE : Tristate.FALSE;
  }

  /** Returns explicit effective nodes for Velocity's numeric permission-map fast path. */
  public static Map<String, Boolean> permissionMap(final NativePermissionSnapshot snapshot) {
    final List<WeightedNode> nodes = effectiveNodes(snapshot);
    nodes.sort(Comparator.comparing((WeightedNode weighted) -> normalize(weighted.node.node))
        .thenComparing(weighted -> sourceId(weighted.node)));
    final Map<String, Boolean> result = new LinkedHashMap<>();
    for (WeightedNode weighted : nodes) {
      result.put(normalize(weighted.node.node), weighted.node.value);
    }
    return Collections.unmodifiableMap(result);
  }

  /** Checks whether a cached snapshot references a group directly or through inheritance. */
  public static boolean usesGroup(final NativePermissionSnapshot snapshot, final String groupName) {
    final String normalized = groupName.toLowerCase(Locale.ROOT);
    return effectiveGroups(snapshot).stream()
        .anyMatch(group -> normalized.equals(normalize(group.name)));
  }

  private static Candidate findWinner(
      final NativePermissionSnapshot snapshot,
      final String requestedNode,
      final Map<String, String> context,
      final long nowEpochMillis
  ) {
    final String query = normalize(requestedNode);
    if (query.isEmpty()) {
      return null;
    }

    Candidate winner = null;
    for (WeightedNode weighted : effectiveNodes(snapshot)) {
      final NativePermissionSnapshot.Node node = weighted.node;
      if (!isActive(node.expiresAtEpochMillis, nowEpochMillis)) {
        continue;
      }
      final Integer contextSpecificity = contextSpecificity(node.contexts, context);
      if (contextSpecificity == null) {
        continue;
      }
      final Integer nodeSpecificity = nodeSpecificity(node.node, query);
      if (nodeSpecificity == null) {
        continue;
      }
      final Candidate candidate = new Candidate(node, weighted.groupWeight, contextSpecificity, nodeSpecificity);
      if (winner == null || CANDIDATE_ORDER.compare(candidate, winner) < 0) {
        winner = candidate;
      }
    }
    return winner;
  }

  private static List<WeightedNode> effectiveNodes(final NativePermissionSnapshot snapshot) {
    final List<WeightedNode> nodes = new ArrayList<>();
    if (snapshot == null) {
      return nodes;
    }
    if (snapshot.permissions != null) {
      snapshot.permissions.forEach(node -> nodes.add(new WeightedNode(node, 0)));
    }
    for (NativePermissionSnapshot.Group group : effectiveGroups(snapshot)) {
      if (group.permissions != null) {
        group.permissions.forEach(node -> nodes.add(new WeightedNode(node, group.weight)));
      }
    }
    return nodes;
  }

  private static List<NativePermissionSnapshot.Group> effectiveGroups(final NativePermissionSnapshot snapshot) {
    if (snapshot == null || snapshot.groups == null) {
      return List.of();
    }
    final Set<String> visited = new HashSet<>();
    final List<NativePermissionSnapshot.Group> result = new ArrayList<>();
    final List<String> roots = new ArrayList<>();
    if (snapshot.directGroups != null) {
      roots.addAll(snapshot.directGroups);
    }
    if (snapshot.primaryGroup != null && !snapshot.primaryGroup.isBlank()) {
      roots.add(snapshot.primaryGroup);
    }
    roots.sort(String.CASE_INSENSITIVE_ORDER);
    for (String root : roots) {
      visitGroup(snapshot, root, visited, result);
    }
    return result;
  }

  private static void visitGroup(
      final NativePermissionSnapshot snapshot,
      final String name,
      final Set<String> visited,
      final List<NativePermissionSnapshot.Group> result
  ) {
    final String normalized = normalize(name);
    if (!visited.add(normalized)) {
      return;
    }
    final NativePermissionSnapshot.Group group = findGroup(snapshot, name);
    if (group == null) {
      return;
    }
    result.add(group);
    if (group.inherits != null) {
      final List<String> inherited = new ArrayList<>(group.inherits);
      inherited.sort(String.CASE_INSENSITIVE_ORDER);
      inherited.forEach(inherit -> visitGroup(snapshot, inherit, visited, result));
    }
  }

  private static NativePermissionSnapshot.Group findGroup(
      final NativePermissionSnapshot snapshot,
      final String name
  ) {
    if (snapshot.groups == null) {
      return null;
    }
    for (Map.Entry<String, NativePermissionSnapshot.Group> entry : snapshot.groups.entrySet()) {
      final NativePermissionSnapshot.Group group = entry.getValue();
      if (entry.getKey().equalsIgnoreCase(name) || (group != null && group.name.equalsIgnoreCase(name))) {
        return group;
      }
    }
    return null;
  }

  private static Integer contextSpecificity(
      final Map<String, String> required,
      final Map<String, String> actual
  ) {
    if (required == null || required.isEmpty()) {
      return 0;
    }
    final Map<String, String> values = actual == null ? Map.of() : actual;
    int specificity = 0;
    for (Map.Entry<String, String> entry : required.entrySet()) {
      final String actualValue = values.get(entry.getKey());
      if (actualValue == null || !actualValue.equalsIgnoreCase(entry.getValue())) {
        return null;
      }
      specificity += CONTEXT_WEIGHTS.getOrDefault(entry.getKey(), 0);
    }
    return specificity;
  }

  private static Integer nodeSpecificity(final String pattern, final String query) {
    final String normalized = normalize(pattern);
    if (normalized.equals(query)) {
      return 100_000 + countDots(query);
    }
    if (normalized.equals("*")) {
      return 0;
    }
    if (normalized.endsWith(".*")) {
      final String prefix = normalized.substring(0, normalized.length() - 2);
      if (query.startsWith(prefix + ".")) {
        return 50_000 + countDots(prefix) + 1;
      }
    }
    return null;
  }

  private static boolean isActive(final Long expiresAtEpochMillis, final long nowEpochMillis) {
    return expiresAtEpochMillis == null || expiresAtEpochMillis > nowEpochMillis;
  }

  private static String normalize(final String value) {
    if (value == null) {
      return "";
    }
    final String normalized = value.trim().toLowerCase(Locale.ROOT);
    return normalized.startsWith("-") ? normalized.substring(1) : normalized;
  }

  private static String sourceId(final NativePermissionSnapshot.Node node) {
    return node.sourceId == null ? "" : node.sourceId;
  }

  private static int countDots(final String value) {
    return (int) value.chars().filter(character -> character == '.').count();
  }

  private record WeightedNode(NativePermissionSnapshot.Node node, int groupWeight) {
  }

  private record Candidate(
      NativePermissionSnapshot.Node node,
      int groupWeight,
      int contextSpecificity,
      int nodeSpecificity
  ) {
  }

  private static final Comparator<Candidate> CANDIDATE_ORDER = Comparator
      .comparingInt(Candidate::contextSpecificity).reversed()
      .thenComparing(Comparator.comparingInt(Candidate::nodeSpecificity).reversed())
      .thenComparing(Comparator.comparingInt((Candidate candidate) -> candidate.node.priority).reversed())
      .thenComparing(Comparator.comparingInt(Candidate::groupWeight).reversed())
      .thenComparingInt(candidate -> candidate.node.value ? 0 : 1)
      .thenComparing(candidate -> sourceId(candidate.node));
}
