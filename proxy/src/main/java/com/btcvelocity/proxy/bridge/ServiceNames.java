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

import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * How a bridge allow-list entry covers a server name: exactly, or as the task the service belongs to.
 *
 * <p>Under CloudNet a backend registers as a <em>service</em> ({@code Lobby-1}), and nobody can
 * write {@code Lobby-7} in a declaration before the node has decided there will be a seventh. The
 * only stable name is the <em>task</em> ({@code Lobby}), so an entry may name one. This is the same
 * rule the backend applies to its own allow-lists ({@code ServiceIdentity} in BTC-CORE); the two
 * sides must agree, or a transfer one side authorizes is refused by the other.</p>
 *
 * <p>Deliberately narrower than a prefix, and the cases that matter are the generous ones:</p>
 * <ul>
 *   <li>the task is what precedes the <b>last</b> separator, so {@code skyadventure-1-2} belongs
 *   to {@code skyadventure-1} and never to {@code skyadventure};</li>
 *   <li>what follows it must be a <b>number</b>, so {@code btc-proxy} is a name in its own right and
 *   not the service "proxy" of a task {@code btc}.</li>
 * </ul>
 *
 * <p>The separator is CloudNet's default, {@code -}, which every BTC task uses. The proxy cannot
 * read a task's {@code nameSplitter}: a task declaring another one would simply be matched by its
 * exact service names only — narrower, never wider.</p>
 */
public final class ServiceNames {

  /** CloudNet's default task name separator, used by every BTC task. */
  static final String SEPARATOR = "-";

  private ServiceNames() {
  }

  /**
   * The task a service name belongs to.
   *
   * @param serviceName a registered server name
   * @return the task, or {@code null} when the name is not shaped like a service of a task
   */
  public static @Nullable String taskOf(final @Nullable String serviceName) {
    if (serviceName == null) {
      return null;
    }
    final int cut = serviceName.lastIndexOf(SEPARATOR);
    if (cut <= 0) {
      return null;
    }
    final String suffix = serviceName.substring(cut + SEPARATOR.length());
    if (suffix.isEmpty() || !suffix.chars().allMatch(Character::isDigit)) {
      return null;
    }
    return serviceName.substring(0, cut);
  }

  /**
   * Whether a declared allow-list covers a name.
   *
   * @param allowed the declared entries, exact names or tasks
   * @param name    the server name to test
   * @return {@code true} when an entry names it exactly, or names its task
   */
  public static boolean covers(final Set<String> allowed, final @Nullable String name) {
    if (name == null) {
      return false;
    }
    if (allowed.contains(name)) {
      return true;
    }
    final String task = taskOf(name);
    return task != null && allowed.contains(task);
  }
}
