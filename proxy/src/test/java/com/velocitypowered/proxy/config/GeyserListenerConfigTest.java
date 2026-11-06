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

package com.velocitypowered.proxy.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code advanced.geyser-listener}: off by default, and refused when it cannot be bound safely. */
class GeyserListenerConfigTest {

  private static final String DISABLED = "geyser-listener = \"\"";

  private static final String DEFAULT_TRUSTED =
      "geyser-listener-trusted-proxies = [\"127.0.0.0/8\", \"::1/128\"]";

  private static VelocityConfiguration read(final Path dir, final String geyserLine)
      throws IOException {
    return read(dir, geyserLine, DEFAULT_TRUSTED);
  }

  private static VelocityConfiguration read(final Path dir, final String geyserLine,
      final String trustedLine) throws IOException {
    final Path secret = dir.resolve("forwarding.secret");
    Files.writeString(secret, "secret");
    final String defaults;
    try (InputStream in = VelocityConfiguration.class.getClassLoader()
        .getResourceAsStream("default-velocity.toml")) {
      defaults = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    assertTrue(defaults.contains(DISABLED), "the key ships disabled in the default file");
    assertTrue(defaults.contains(DEFAULT_TRUSTED), "the trusted list ships loopback only");
    final Path file = dir.resolve("velocity.toml");
    Files.writeString(file, defaults
        .replace("forwarding-secret-file = \"forwarding.secret\"",
            "forwarding-secret-file = '" + secret + "'")
        .replace(DISABLED, geyserLine)
        .replace(DEFAULT_TRUSTED, trustedLine), StandardCharsets.UTF_8);
    return VelocityConfiguration.read(file);
  }

  @Test
  void theDefaultFileLeavesTheListenerOffAndTheConfigurationValid(@TempDir final Path dir)
      throws IOException {
    final VelocityConfiguration config = read(dir, DISABLED);

    assertTrue(config.getGeyserListener().isEmpty());
    assertTrue(config.validate());
    assertFalse(config.isProxyProtocol(), "the main listener keeps its own flag");
  }

  @Test
  void aLiteralAddressWithAPortOtherThanBindIsAccepted(@TempDir final Path dir)
      throws IOException {
    final VelocityConfiguration config = read(dir, "geyser-listener = \"127.0.0.1:25566\"");

    assertTrue(config.validate());
    assertEquals(new InetSocketAddress("127.0.0.1", 25566), config.getGeyserListener().orElseThrow());
    assertFalse(config.isProxyProtocol(), "the Geyser listener does not change haproxy-protocol");
  }

  @Test
  void theSameAddressAsBindIsRefused(@TempDir final Path dir) throws IOException {
    // bind is 0.0.0.0:25565 in the default file: any address on that port clashes with it
    assertFalse(read(dir, "geyser-listener = \"0.0.0.0:25565\"").validate());
    assertFalse(read(dir, "geyser-listener = \"127.0.0.1:25565\"").validate());
  }

  @Test
  void theGeyserListenerTrustsOnlyLoopbackByDefaultWhileTheMainListSpansPrivateRanges(
      @TempDir final Path dir) throws IOException {
    final VelocityConfiguration config = read(dir, DISABLED);
    final InetAddress privateHost = InetAddress.ofLiteral("10.1.2.3");

    assertTrue(config.validate());
    assertTrue(config.getGeyserListenerTrustedProxies().contains(InetAddress.ofLiteral("127.0.0.1")));
    assertTrue(config.getGeyserListenerTrustedProxies().contains(InetAddress.ofLiteral("::1")));
    assertFalse(config.getGeyserListenerTrustedProxies().contains(privateHost));
    assertTrue(config.getProxyProtocolTrustedProxies().contains(privateHost));
  }

  @Test
  void theGeyserTrustedListIsReadFromItsOwnKey(@TempDir final Path dir) throws IOException {
    final VelocityConfiguration config = read(dir, DISABLED,
        "geyser-listener-trusted-proxies = [\"192.168.5.0/24\"]");

    assertTrue(config.validate());
    assertTrue(config.getGeyserListenerTrustedProxies().contains(InetAddress.ofLiteral("192.168.5.7")));
    assertFalse(config.getGeyserListenerTrustedProxies().contains(InetAddress.ofLiteral("127.0.0.1")));
  }

  @Test
  void aSingleStringIsOneEntryAndAWideOrMalformedEntryIsRefused(@TempDir final Path dir)
      throws IOException {
    final VelocityConfiguration single = read(dir, DISABLED,
        "geyser-listener-trusted-proxies = \"127.0.0.1\"");
    assertTrue(single.validate());
    assertTrue(single.getGeyserListenerTrustedProxies().contains(InetAddress.ofLiteral("127.0.0.1")));

    for (String bad : new String[] {"[\"0.0.0.0/0\"]", "[\"10.0.0.0/7\"]", "[\"::/0\"]",
        "[\"localhost\"]", "[\"10.0.0.0/abc\"]"}) {
      assertFalse(read(dir, DISABLED, "geyser-listener-trusted-proxies = " + bad).validate(), bad);
    }
  }

  @Test
  void aHostNameAnInvalidPortOrNoPortIsRefused(@TempDir final Path dir) throws IOException {
    for (String bad : new String[] {"localhost:25566", "127.0.0.1:0", "127.0.0.1:70000",
        "127.0.0.1", "127.0.0.1:abc"}) {
      assertFalse(read(dir, "geyser-listener = \"" + bad + "\"").validate(), bad);
    }
  }
}
