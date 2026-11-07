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

import com.btcvelocity.proxy.security.HandshakeHosts;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code advanced.handshake-host-filter}: off by default, refused when it cannot work. */
class HandshakeHostConfigTest {

  private static final String OFF = "handshake-host-filter = \"off\"";
  private static final String NO_HOSTS = "handshake-allowed-hosts = []";

  private static String defaults() throws IOException {
    try (InputStream in = VelocityConfiguration.class.getClassLoader()
        .getResourceAsStream("default-velocity.toml")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static VelocityConfiguration read(final Path dir, final String filterLine,
      final String hostsLine, final boolean withForcedHosts) throws IOException {
    final Path secret = dir.resolve("forwarding.secret");
    Files.writeString(secret, "secret");
    String text = defaults();
    assertTrue(text.contains(OFF), "the filter ships off");
    assertTrue(text.contains(NO_HOSTS), "the list ships empty");
    text = text.replace(OFF, filterLine).replace(NO_HOSTS, hostsLine)
        .replace("forwarding-secret-file = \"forwarding.secret\"",
            "forwarding-secret-file = '" + secret + "'");
    if (!withForcedHosts) {
      text = text.replaceAll("(?m)^\"[a-z]+\\.example\\.com\" = \\[\\R\\s+\"[a-z]+\"\\R\\]\\R", "");
    }
    final Path file = dir.resolve("velocity.toml");
    Files.writeString(file, text, StandardCharsets.UTF_8);
    return VelocityConfiguration.read(file);
  }

  @Test
  void theShippedFileIsOffAndValid(@TempDir final Path dir) throws IOException {
    final VelocityConfiguration config = read(dir, OFF, NO_HOSTS, true);

    assertTrue(config.validate());
    assertEquals(HandshakeHosts.Mode.OFF, config.getHandshakeHosts().mode());
  }

  @Test
  void aKeyMissingFromAnOldFileMeansOff(@TempDir final Path dir) throws IOException {
    final VelocityConfiguration config = read(dir, "", "", true);

    assertTrue(config.validate());
    assertEquals(HandshakeHosts.Mode.OFF, config.getHandshakeHosts().mode());
  }

  @Test
  void observeAndEnforceAreReadAndForcedHostsAreAllowed(@TempDir final Path dir)
      throws IOException {
    final VelocityConfiguration observe = read(dir, "handshake-host-filter = \"observe\"",
        "handshake-allowed-hosts = [\"Play.BornToCraft.net\"]", true);

    assertTrue(observe.validate());
    assertEquals(HandshakeHosts.Mode.OBSERVE, observe.getHandshakeHosts().mode());
    assertEquals(HandshakeHosts.Verdict.ADMIT,
        observe.getHandshakeHosts().check("play.borntocraft.net\0FML\0"));
    assertEquals(HandshakeHosts.Verdict.OBSERVED, observe.getHandshakeHosts().check("203.0.113.9"));
    // the forced hosts of the shipped file
    assertEquals(HandshakeHosts.Verdict.ADMIT, observe.getHandshakeHosts().check("lobby.example.com."));

    final VelocityConfiguration enforce = read(dir, "handshake-host-filter = \"ENFORCE\"",
        "handshake-allowed-hosts = [\"play.borntocraft.net\"]", false);
    assertTrue(enforce.validate());
    assertEquals(HandshakeHosts.Verdict.REFUSE, enforce.getHandshakeHosts().check("203.0.113.9"));
    assertEquals(HandshakeHosts.Verdict.REFUSE, enforce.getHandshakeHosts().check("lobby.example.com"),
        "the forced hosts were removed from this file");
  }

  @Test
  void aSingleStringIsOneEntry(@TempDir final Path dir) throws IOException {
    final VelocityConfiguration config = read(dir, "handshake-host-filter = \"enforce\"",
        "handshake-allowed-hosts = \"play.borntocraft.net\"", false);

    assertTrue(config.validate());
    assertEquals(HandshakeHosts.Verdict.ADMIT, config.getHandshakeHosts().check("play.borntocraft.net"));
  }

  @Test
  void anUnknownModeIsRefused(@TempDir final Path dir) throws IOException {
    for (String bad : new String[] {"\"block\"", "\"\"", "true"}) {
      assertFalse(read(dir, "handshake-host-filter = " + bad, NO_HOSTS, true).validate(), bad);
    }
  }

  @Test
  void enforceWithNothingToAllowIsRefusedButObserveIsNot(@TempDir final Path dir)
      throws IOException {
    assertFalse(read(dir, "handshake-host-filter = \"enforce\"", NO_HOSTS, false).validate());
    assertTrue(read(dir, "handshake-host-filter = \"observe\"", NO_HOSTS, false).validate());
    assertTrue(read(dir, "handshake-host-filter = \"observe\"", NO_HOSTS, true).validate());
    // the shipped file has three example forced hosts: they must not let enforce start
    assertFalse(read(dir, "handshake-host-filter = \"enforce\"", NO_HOSTS, true).validate(),
        "forced hosts alone are not a list");
  }

  @Test
  void anEntryThatCanNeverMatchIsOnlyAWarning(@TempDir final Path dir) throws IOException {
    final VelocityConfiguration config = read(dir, "handshake-host-filter = \"enforce\"",
        "handshake-allowed-hosts = [\"play.borntocraft.net\", \"*.borntocraft.net\", "
            + "\"play.borntocraft.net:25565\", \"10.0.0.0/8\"]", false);

    assertTrue(config.validate());
    assertEquals(HandshakeHosts.Verdict.REFUSE, config.getHandshakeHosts().check("mc.borntocraft.net"));
  }

  @Test
  void anIpv6LiteralIsAnEntryAndIsNotWarnedAbout(@TempDir final Path dir) throws IOException {
    final VelocityConfiguration config = read(dir, "handshake-host-filter = \"enforce\"",
        "handshake-allowed-hosts = [\"2001:db8::1\"]", false);

    assertTrue(config.validate());
    assertEquals(HandshakeHosts.Verdict.ADMIT, config.getHandshakeHosts().check("2001:db8::1"));
    assertFalse(VelocityConfiguration.neverMatches("2001:db8::1"));
    // still warned about: a port, a wildcard, a network
    assertTrue(VelocityConfiguration.neverMatches("play.borntocraft.net:25565"));
    assertTrue(VelocityConfiguration.neverMatches("*.borntocraft.net"));
    assertTrue(VelocityConfiguration.neverMatches("10.0.0.0/8"));
  }

  @Test
  void aBlankEntryIsRefused(@TempDir final Path dir) throws IOException {
    assertFalse(read(dir, OFF, "handshake-allowed-hosts = [\"play.borntocraft.net\", \" . \"]", true)
        .validate());
  }
}
