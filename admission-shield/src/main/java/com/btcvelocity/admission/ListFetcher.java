/*
 * Copyright (C) 2026 Velocity Contributors
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

package com.btcvelocity.admission;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Downloads one public list into a file.
 *
 * <p>Fetching a list is the only network traffic this module ever makes. It asks for a public URL
 * and carries no player address, no player name and nothing else about a connection: a request
 * for {@code .../tor_exits.ipset} is the same request whoever is online.
 */
@FunctionalInterface
public interface ListFetcher {

  /**
   * Writes the body found at the URL into the destination.
   *
   * @param url where to download from
   * @param destination the file to write, created or overwritten
   * @throws IOException if the download fails, times out, or is refused
   */
  void fetch(URI url, Path destination) throws IOException;

  /**
   * The fetcher used in production: HTTPS through the JDK client, bounded in size and in total
   * duration (connection, headers and body together).
   *
   * @param timeout how long a whole download may take, from the first byte sent to the last read
   * @param maxBytes the most a download may write; a longer body is an error
   * @return the fetcher
   */
  static ListFetcher http(final Duration timeout, final long maxBytes) {
    final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(timeout)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
    return (url, destination) -> {
      final long deadline = System.nanoTime() + timeout.toNanos();
      final HttpRequest request = HttpRequest.newBuilder(url)
          .timeout(timeout)
          .header("User-Agent", "btc-admission-shield")
          .GET()
          .build();
      final HttpResponse<InputStream> response;
      try {
        response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IOException("download interrupted", interrupted);
      }
      try (InputStream body = response.body()) {
        if (response.statusCode() != 200) {
          throw new IOException("HTTP " + response.statusCode() + " from " + url);
        }
        // A read blocked on a server that went silent does not look at any clock: closing the
        // stream from here is what makes it fail at the deadline.
        final ScheduledFuture<?> watchdog = Watchdog.INSTANCE.schedule(
            () -> closeQuietly(body), Math.max(1L, deadline - System.nanoTime()),
            TimeUnit.NANOSECONDS);
        try (OutputStream out = Files.newOutputStream(destination)) {
          BoundedCopy.copy(body, out, maxBytes, deadline, System::nanoTime);
        } finally {
          watchdog.cancel(false);
        }
      }
    };
  }

  private static void closeQuietly(final InputStream stream) {
    try {
      stream.close();
    } catch (IOException ignored) {
      // The download is being abandoned anyway.
    }
  }

  /** One daemon thread that fires the deadlines of the downloads. */
  final class Watchdog {

    private static final ScheduledExecutorService INSTANCE =
        Executors.newSingleThreadScheduledExecutor(task -> {
          final Thread thread = new Thread(task, "btc-admission-download-watchdog");
          thread.setDaemon(true);
          return thread;
        });

    private Watchdog() {
    }
  }
}
