/*
 * Copyright (C) 2018-2026 Velocity Contributors
 *
 * The Velocity API is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package com.btcvelocity.api.connection;

/**
 * Netty user event the proxy fires through a connection's pipeline
 * ({@code fireUserEventTriggered}) once it has turned compression on or off, from the event loop.
 * The pipeline is already changed when the event arrives. See {@link ClientChannelHook} for how a
 * handler stays on the uncompressed side.
 */
public enum CompressionState {

  /**
   * {@link ClientChannelHook#COMPRESSION_DECODER} and {@link ClientChannelHook#COMPRESSION_ENCODER}
   * were just inserted right before the codec.
   */
  ENABLED,

  /**
   * The compression handlers were just removed.
   */
  DISABLED
}
