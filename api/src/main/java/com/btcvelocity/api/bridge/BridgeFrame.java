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

package com.btcvelocity.api.bridge;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jetbrains.annotations.Nullable;

/**
 * Seals and opens {@code btc:bridge} payloads with an HMAC-SHA256, in both directions.
 *
 * <p>Why a signature at all: plugin messages travel through a player's connection, and a Bukkit
 * backend is always handed that player — including for messages the proxy sent. "Was there a player?"
 * therefore cannot tell a client from the proxy, and the backend that asked it rejected everything the
 * proxy ever sent (bench, 16/09). What proves the origin is knowledge of a secret the client never
 * sees.
 *
 * <p>No new secret to distribute: both ends already hold Velocity's modern-forwarding secret. The
 * bridge key is <em>derived</em> from it rather than used as is, so one secret never directly serves
 * two purposes, and a leaked bridge key would not forge a forwarding handshake.
 *
 * <p>Wire layout: {@code MAGIC (1) | VERSION (1) | MAC (32) | payload}. The MAC covers the magic and
 * the version too, so a frame cannot be relabelled as another version. There is deliberately no
 * unsigned fallback: accepting an unsigned payload "for compatibility" would let anyone downgrade to
 * exactly the channel this class closes. An unsigned payload is refused, and says so.
 *
 * <p>This class is duplicated byte for byte in BTC-CORE ({@code dev.btc.core.bridge.BridgeFrame}).
 * Both copies are pinned to the same test vector, computed with an independent HMAC implementation,
 * so a drift between them fails a build instead of a handshake.
 */
public final class BridgeFrame {

  /** First byte of every sealed frame. A JSON payload starts with an opening brace, never this. */
  public static final byte MAGIC = (byte) 0xB7;
  /** Frame format version, covered by the MAC. */
  public static final byte VERSION = 1;
  /** Length of an HMAC-SHA256 tag. */
  public static final int MAC_LENGTH = 32;
  /** Bytes preceding the payload. */
  public static final int HEADER_LENGTH = 2 + MAC_LENGTH;

  private static final String ALGORITHM = "HmacSHA256";
  private static final byte[] KEY_LABEL =
      "btc:bridge/v2/frame-mac".getBytes(StandardCharsets.US_ASCII);

  /** Why a frame was not opened. */
  public enum Rejection {
    /** No bridge frame at all: a plain payload, as sent by a peer that does not sign. */
    UNSIGNED,
    /** A frame of a version this side does not speak. */
    UNKNOWN_VERSION,
    /** Too short to hold a header. */
    TRUNCATED,
    /** Signed with another key, or altered in flight. */
    BAD_MAC
  }

  /**
   * The result of opening a frame: either the payload, or why it was refused — never both.
   *
   * @param payload   the authenticated payload, or {@code null} when refused
   * @param rejection why the frame was refused, or {@code null} when accepted
   */
  public record Opened(byte @Nullable [] payload, @Nullable Rejection rejection) {

    /** Whether the payload was authenticated. */
    public boolean accepted() {
      return rejection == null;
    }
  }

  private final SecretKeySpec key;

  private BridgeFrame(final byte[] derivedKey) {
    this.key = new SecretKeySpec(derivedKey, ALGORITHM);
  }

  /**
   * Derives the bridge key from the shared forwarding secret.
   *
   * @param sharedSecret the modern-forwarding secret both ends hold
   * @return a frame codec keyed for this deployment
   * @throws IllegalArgumentException when the secret is empty: a bridge signed with nothing would
   *                                  authenticate nothing, and must not start
   */
  public static BridgeFrame fromSharedSecret(final byte[] sharedSecret) {
    Objects.requireNonNull(sharedSecret, "sharedSecret");
    if (sharedSecret.length == 0) {
      throw new IllegalArgumentException("the bridge needs a non-empty shared secret");
    }
    return new BridgeFrame(hmac(new SecretKeySpec(sharedSecret, ALGORITHM), KEY_LABEL));
  }

  /**
   * Signs a payload.
   *
   * @param payload the encoded message
   * @return the sealed frame
   */
  public byte[] seal(final byte[] payload) {
    Objects.requireNonNull(payload, "payload");
    final byte[] frame = new byte[HEADER_LENGTH + payload.length];
    frame[0] = MAGIC;
    frame[1] = VERSION;
    System.arraycopy(payload, 0, frame, HEADER_LENGTH, payload.length);
    final byte[] tag = hmac(key, signedPart(frame));
    System.arraycopy(tag, 0, frame, 2, MAC_LENGTH);
    return frame;
  }

  /**
   * Authenticates a frame and extracts its payload.
   *
   * @param frame the bytes received on the channel
   * @return the payload, or why the frame was refused; never {@code null}
   */
  public Opened open(final byte[] frame) {
    Objects.requireNonNull(frame, "frame");
    if (frame.length == 0 || frame[0] != MAGIC) {
      return new Opened(null, Rejection.UNSIGNED);
    }
    if (frame.length < HEADER_LENGTH) {
      return new Opened(null, Rejection.TRUNCATED);
    }
    if (frame[1] != VERSION) {
      return new Opened(null, Rejection.UNKNOWN_VERSION);
    }
    final byte[] expected = hmac(key, signedPart(frame));
    final byte[] received = Arrays.copyOfRange(frame, 2, HEADER_LENGTH);
    // Constant time: a comparison that stops at the first differing byte leaks how much of a forged
    // tag was right.
    if (!MessageDigest.isEqual(expected, received)) {
      return new Opened(null, Rejection.BAD_MAC);
    }
    return new Opened(Arrays.copyOfRange(frame, HEADER_LENGTH, frame.length), null);
  }

  /** Magic and version, then the payload: everything but the tag itself. */
  private static byte[] signedPart(final byte[] frame) {
    final byte[] signed = new byte[frame.length - MAC_LENGTH];
    signed[0] = frame[0];
    signed[1] = frame[1];
    System.arraycopy(frame, HEADER_LENGTH, signed, 2, frame.length - HEADER_LENGTH);
    return signed;
  }

  private static byte[] hmac(final SecretKeySpec key, final byte[] data) {
    try {
      // A fresh instance per call: Mac is not thread-safe, and the proxy opens frames on several
      // Netty loops at once. The volume is a handful of messages per second.
      final Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(key);
      return mac.doFinal(data);
    } catch (GeneralSecurityException e) {
      // HmacSHA256 is mandatory on every Java platform: reaching this is a broken JVM, not a
      // condition to recover from.
      throw new IllegalStateException("HmacSHA256 unavailable", e);
    }
  }
}
