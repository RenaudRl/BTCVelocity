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
   * The fetcher used in production: HTTPS through the JDK client, bounded in time and in size.
   *
   * @param timeout how long a whole download may take
   * @param maxBytes the most a download may write; a longer body is an error
   * @return the fetcher
   */
  static ListFetcher http(final Duration timeout, final long maxBytes) {
    final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(timeout)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
    return (url, destination) -> {
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
        try (OutputStream out = Files.newOutputStream(destination)) {
          copyBounded(body, out, maxBytes);
        }
      }
    };
  }

  private static void copyBounded(final InputStream in, final OutputStream out, final long max)
      throws IOException {
    final byte[] buffer = new byte[64 * 1024];
    long total = 0;
    int read;
    while ((read = in.read(buffer)) != -1) {
      total += read;
      if (total > max) {
        throw new IOException("list larger than " + max + " bytes, download abandoned");
      }
      out.write(buffer, 0, read);
    }
  }
}
