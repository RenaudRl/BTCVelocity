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

import com.btcvelocity.proxy.security.AdmissionStats;
import com.btcvelocity.proxy.security.ConnectionAdmission;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The connection limit and the handshake deadline: off as shipped, refused when unusable. */
class ConnectionLimitConfigTest {

  private static final String LIMIT = "connection-rate-limit = 0";
  private static final String WINDOW = "connection-rate-window-ms = 10000";
  private static final String EXEMPT = "connection-rate-exempt = []";
  private static final String DEADLINE = "handshake-timeout-ms = 0";

  private static VelocityConfiguration read(final Path dir, final String limit, final String window,
      final String exempt, final String deadline) throws IOException {
    final Path secret = dir.resolve("forwarding.secret");
    Files.writeString(secret, "secret");
    final String text;
    try (InputStream in = VelocityConfiguration.class.getClassLoader()
        .getResourceAsStream("default-velocity.toml")) {
      text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    for (String shipped : new String[] {LIMIT, WINDOW, EXEMPT, DEADLINE}) {
      assertTrue(text.contains(shipped), shipped + " must ship in the default file");
    }
    final Path file = dir.resolve("velocity.toml");
    Files.writeString(file, text.replace(LIMIT, limit).replace(WINDOW, window)
        .replace(EXEMPT, exempt).replace(DEADLINE, deadline)
        .replace("forwarding-secret-file = \"forwarding.secret\"",
            "forwarding-secret-file = '" + secret + "'"), StandardCharsets.UTF_8);
    return VelocityConfiguration.read(file);
  }

  @Test
  void theShippedFileLimitsNothingAndSetsNoDeadline(@TempDir final Path dir) throws IOException {
    final VelocityConfiguration config = read(dir, LIMIT, WINDOW, EXEMPT, DEADLINE);

    assertTrue(config.validate());
    final ConnectionAdmission admission = config.createConnectionAdmission(new AdmissionStats());
    for (int i = 0; i < 500; i++) {
      assertTrue(admission.admitConnection(InetAddress.ofLiteral("203.0.113.9")));
    }
    assertEquals(0, admission.handshakeTimeoutMillis());
  }

  @Test
  void theValuesAreReadAndBuildTheAdmission(@TempDir final Path dir) throws IOException {
    final VelocityConfiguration config = read(dir, "connection-rate-limit = 2",
        "connection-rate-window-ms = 60000", "connection-rate-exempt = [\"198.51.100.0/24\"]",
        "handshake-timeout-ms = 5000");

    assertTrue(config.validate());
    final AdmissionStats stats = new AdmissionStats();
    final ConnectionAdmission admission = config.createConnectionAdmission(stats);
    final InetAddress client = InetAddress.ofLiteral("203.0.113.9");
    assertTrue(admission.admitConnection(client));
    assertTrue(admission.admitConnection(client));
    assertFalse(admission.admitConnection(client));
    assertTrue(admission.admitConnection(InetAddress.ofLiteral("198.51.100.7")));
    assertTrue(admission.admitConnection(InetAddress.ofLiteral("198.51.100.7")));
    assertTrue(admission.admitConnection(InetAddress.ofLiteral("198.51.100.7")));
    assertEquals(5000, admission.handshakeTimeoutMillis());
    assertEquals(1, stats.count(AdmissionStats.Result.RATE_LIMITED));
  }

  @Test
  void aReloadBuildsNewCountsButTheStatsAreShared(@TempDir final Path dir) throws IOException {
    final VelocityConfiguration config = read(dir, "connection-rate-limit = 1", WINDOW, EXEMPT,
        DEADLINE);
    final AdmissionStats stats = new AdmissionStats();
    final InetAddress client = InetAddress.ofLiteral("203.0.113.9");

    final ConnectionAdmission before = config.createConnectionAdmission(stats);
    before.admitConnection(client);
    before.admitConnection(client);
    final ConnectionAdmission after = config.createConnectionAdmission(stats);

    assertTrue(after.admitConnection(client), "the window starts again");
    assertEquals(1, stats.count(AdmissionStats.Result.RATE_LIMITED), "the counter is kept");
  }

  @Test
  void unusableValuesAreRefused(@TempDir final Path dir) throws IOException {
    assertFalse(read(dir, "connection-rate-limit = -1", WINDOW, EXEMPT, DEADLINE).validate());
    assertFalse(read(dir, "connection-rate-limit = 5", "connection-rate-window-ms = 0", EXEMPT,
        DEADLINE).validate());
    assertTrue(read(dir, LIMIT, "connection-rate-window-ms = 0", EXEMPT, DEADLINE).validate(),
        "the window does not matter while the limit is off");
    assertFalse(read(dir, LIMIT, WINDOW, "connection-rate-exempt = [\"0.0.0.0/0\"]", DEADLINE)
        .validate());
    assertFalse(read(dir, LIMIT, WINDOW, "connection-rate-exempt = [\"localhost\"]", DEADLINE)
        .validate());
    assertFalse(read(dir, LIMIT, WINDOW, EXEMPT, "handshake-timeout-ms = -5").validate());
  }

  @Test
  void aDeadlineBeyondTheReadTimeoutIsOnlyAWarning(@TempDir final Path dir) throws IOException {
    assertTrue(read(dir, LIMIT, WINDOW, EXEMPT, "handshake-timeout-ms = 60000").validate());
  }
}
