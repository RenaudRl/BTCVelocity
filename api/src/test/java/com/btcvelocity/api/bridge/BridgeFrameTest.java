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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/** Only a holder of the shared secret can produce a frame the other side opens. */
class BridgeFrameTest {

  private static final byte[] SECRET =
      "btc-bridge-test-vector-secret-0001".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] PAYLOAD =
      "{\"version\":2,\"type\":\"health\"}".getBytes(StandardCharsets.UTF_8);

  /**
   * Computed with .NET's HMACSHA256, not with this class: a vector produced by the code under test
   * would only prove the code agrees with itself. The same constant pins the BTC-CORE copy.
   */
  private static final String VECTOR_MAC =
      "04d188162874c1861801f58a2992beac6f357bd5e35101b72e4d8f9030eac305";

  private final BridgeFrame frame = BridgeFrame.fromSharedSecret(SECRET);

  @Test
  void sealedFrameMatchesIndependentTestVector() {
    final byte[] sealed = frame.seal(PAYLOAD);

    assertEquals(BridgeFrame.MAGIC, sealed[0]);
    assertEquals(BridgeFrame.VERSION, sealed[1]);
    assertEquals(VECTOR_MAC, HexFormat.of().formatHex(Arrays.copyOfRange(sealed, 2, 34)));
    assertArrayEquals(PAYLOAD, Arrays.copyOfRange(sealed, BridgeFrame.HEADER_LENGTH, sealed.length));
  }

  @Test
  void sealedFrameOpensToTheSamePayload() {
    final BridgeFrame.Opened opened = frame.open(frame.seal(PAYLOAD));

    assertTrue(opened.accepted());
    assertArrayEquals(PAYLOAD, opened.payload());
  }

  @Test
  void plainPayloadIsRefusedAsUnsigned() {
    // The downgrade the frame exists to close: no fallback for a peer that does not sign.
    final BridgeFrame.Opened opened = frame.open(PAYLOAD);

    assertEquals(BridgeFrame.Rejection.UNSIGNED, opened.rejection());
    assertNull(opened.payload(), "a refused frame carries nothing");
  }

  @Test
  void frameSignedWithAnotherSecretIsRefused() {
    // What a client who does not know the forwarding secret can at best produce.
    final byte[] forged = BridgeFrame.fromSharedSecret(
        "not-the-secret".getBytes(StandardCharsets.US_ASCII)).seal(PAYLOAD);

    assertEquals(BridgeFrame.Rejection.BAD_MAC, frame.open(forged).rejection());
  }

  @Test
  void everyAlteredByteIsDetected() {
    final byte[] sealed = frame.seal(PAYLOAD);
    for (int i = 2; i < sealed.length; i++) {
      final byte[] altered = sealed.clone();
      altered[i] ^= 0x01;
      assertEquals(BridgeFrame.Rejection.BAD_MAC, frame.open(altered).rejection(),
          "a flip at byte " + i + " must be refused");
    }
  }

  @Test
  void versionCannotBeRelabelled() {
    final byte[] relabelled = frame.seal(PAYLOAD);
    relabelled[1] = 2;

    assertEquals(BridgeFrame.Rejection.UNKNOWN_VERSION, frame.open(relabelled).rejection());
  }

  @Test
  void truncatedFrameIsRefused() {
    assertEquals(BridgeFrame.Rejection.TRUNCATED,
        frame.open(Arrays.copyOf(frame.seal(PAYLOAD), 10)).rejection());
    assertEquals(BridgeFrame.Rejection.UNSIGNED, frame.open(new byte[0]).rejection());
  }

  @Test
  void emptySecretRefusesToBuildBridge() {
    assertThrows(IllegalArgumentException.class, () -> BridgeFrame.fromSharedSecret(new byte[0]));
  }
}
