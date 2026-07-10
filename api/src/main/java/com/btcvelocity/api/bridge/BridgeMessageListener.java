/*
 * Copyright (C) 2018-2026 Velocity Contributors
 *
 * The Velocity API is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package com.btcvelocity.api.bridge;

/**
 * A functional callback invoked when a {@link BridgeMessage} is received by the proxy
 * from a backend server.
 *
 * <p>Listeners are registered with {@link BridgeChannel#registerListener(BridgeMessageListener)}
 * and receive every decoded message; each implementation is responsible for filtering on
 * the concrete message type it cares about (for example via {@code instanceof} checks).</p>
 */
@FunctionalInterface
public interface BridgeMessageListener {

  /**
   * Called when a bridge message arrives from a backend.
   *
   * @param sourceServer the name of the server that sent the message
   * @param message      the decoded bridge message
   */
  void onMessage(String sourceServer, BridgeMessage message);
}
