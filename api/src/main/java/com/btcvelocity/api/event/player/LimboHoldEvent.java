/*
 * Copyright (C) 2018-2026 Velocity Contributors
 *
 * The Velocity API is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package com.btcvelocity.api.event.player;

import static com.google.common.base.Preconditions.checkNotNull;

import com.velocitypowered.api.proxy.Player;
import java.util.Optional;
import net.kyori.adventure.text.Component;

/**
 * Asked by the arrival router about a player standing in the limbo, to learn whether a plugin
 * keeps them there on purpose.
 *
 * <p>The router releases every limbo player towards a lobby on a timer. A plugin that parks a
 * player in the limbo for its own reason (a service being woken up for them, for instance) calls
 * {@link #hold(Component)}: the router then neither releases the player nor tells them the
 * generic "no lobby available" text, and shows the reason given here instead. The plugin owns the
 * player until it stops answering {@code hold}; the next sweep then releases them as usual.</p>
 */
public final class LimboHoldEvent {

  private final Player player;
  private volatile Component reason;

  /**
   * Constructs a new {@code LimboHoldEvent}.
   *
   * @param player the limbo player the router is about to release or to inform
   */
  public LimboHoldEvent(final Player player) {
    this.player = checkNotNull(player, "player");
  }

  /**
   * Returns the player concerned.
   *
   * @return the limbo player
   */
  public Player getPlayer() {
    return player;
  }

  /**
   * Keeps the player in the limbo. The first plugin to answer decides the reason shown.
   *
   * @param reason the text shown to the player when they arrive in the limbo
   */
  public synchronized void hold(final Component reason) {
    if (this.reason == null) {
      this.reason = checkNotNull(reason, "reason");
    }
  }

  /**
   * Returns whether a plugin keeps the player in the limbo.
   *
   * @return {@code true} when {@link #hold(Component)} was called
   */
  public boolean isHeld() {
    return this.reason != null;
  }

  /**
   * Returns the reason given by the plugin that holds the player.
   *
   * @return the reason, empty when nobody holds the player
   */
  public Optional<Component> getReason() {
    return Optional.ofNullable(this.reason);
  }

  @Override
  public String toString() {
    return "LimboHoldEvent{player=" + player + ", held=" + isHeld() + '}';
  }
}
