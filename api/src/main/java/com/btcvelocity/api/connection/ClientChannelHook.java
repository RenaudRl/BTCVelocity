/*
 * Copyright (C) 2018-2026 Velocity Contributors
 *
 * The Velocity API is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package com.btcvelocity.api.connection;

import io.netty.channel.Channel;

/**
 * Gives a plugin the Netty pipeline of every client connection, in the place where it can add
 * handlers around the Minecraft codec without reflecting on the proxy.
 *
 * <p>Register it with {@link com.velocitypowered.api.proxy.ProxyServer#addClientChannelHook}. The
 * proxy calls it once for each channel a client opens, on every listener (the Geyser one included),
 * from the channel's event loop, as soon as the proxy has built its own pipeline and before the
 * first byte is read. The handlers named {@link #MINECRAFT_DECODER} and {@link #MINECRAFT_ENCODER}
 * are in the pipeline then.
 *
 * <p><b>Compression.</b> When the proxy turns compression on, it inserts {@link #COMPRESSION_DECODER}
 * and {@link #COMPRESSION_ENCODER} right before the codec, so a handler added with
 * {@code addBefore(MINECRAFT_DECODER, ...)} ends up on the compressed side. To keep seeing
 * uncompressed frames, have an inbound handler of yours listen to {@link CompressionState} in
 * {@code userEventTriggered} and, on {@link CompressionState#ENABLED}, move your handlers back right
 * before {@link #MINECRAFT_DECODER} and {@link #MINECRAFT_ENCODER} (remove them, then
 * {@code addBefore} the same instances, which Netty only accepts for
 * {@link io.netty.channel.ChannelHandler.Sharable} handlers; otherwise add new instances). The
 * moved handler is then further down the event's path and sees {@code ENABLED} again: make the
 * move idempotent. When compression goes off, the compression handlers are removed and yours are
 * next to the codec again.
 *
 * <p><b>What the hook sees.</b>
 * <ul>
 *   <li>{@link Channel#remoteAddress()} is the TCP peer. Behind Geyser or a PROXY protocol sender it
 *   is that sender (often loopback), not the player: read the player's address later, from the
 *   {@link com.velocitypowered.api.proxy.InboundConnection}.</li>
 *   <li>The channel is not admitted yet: the connection checks may still close it afterwards (for
 *   instance when the PROXY header arrives), so a hook must not count it as a player.</li>
 *   <li>A plugin that wraps the proxy's channel initializer (Floodgate) adds its own handlers after
 *   the hooks have run, so they are not in the pipeline yet.</li>
 *   <li>Channels that are already open when a hook is registered are not passed to it, nor are the
 *   connections the proxy opens to backend servers.</li>
 * </ul>
 *
 * <p><b>Order and failures.</b> Hooks run in the order they were registered. A hook that throws
 * anything but a {@link VirtualMachineError} (an {@link Exception}, a {@link LinkageError}, an
 * {@link AssertionError}...) is logged and skipped, and the handlers it added under a new name
 * before failing are removed: the other hooks and the connection go on. A handler that the hook
 * removed or replaced (same name as an existing one) is not restored. A
 * {@link VirtualMachineError} (out of memory, stack overflow) is not caught: Netty closes that
 * connection. A hook that was just removed may still be called once, by a channel that was being
 * set up on another event loop. A hook must not block nor do I/O, as it runs on the event loop: the
 * proxy warns when one is slow (the first call of each registered hook, which loads its classes,
 * is not measured).
 */
@FunctionalInterface
public interface ClientChannelHook {

  /**
   * Name of the proxy's packet decoder (bytes to packets) in a client pipeline.
   */
  String MINECRAFT_DECODER = "minecraft-decoder";

  /**
   * Name of the proxy's packet encoder (packets to bytes) in a client pipeline.
   */
  String MINECRAFT_ENCODER = "minecraft-encoder";

  /**
   * Name of the decompressor, present right before {@link #MINECRAFT_DECODER} while compression is
   * on.
   */
  String COMPRESSION_DECODER = "compression-decoder";

  /**
   * Name of the compressor, present right before {@link #MINECRAFT_ENCODER} while compression is
   * on.
   */
  String COMPRESSION_ENCODER = "compression-encoder";

  /**
   * Called for a new client channel.
   *
   * @param channel the channel, with the proxy's pipeline already built
   * @throws Exception if the hook cannot do its work; the proxy logs it, removes the handlers the
   *     hook added under a new name, and keeps the connection
   */
  void initChannel(Channel channel) throws Exception;
}
