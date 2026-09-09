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

package com.btcvelocity.proxy.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.velocitypowered.api.permission.Tristate;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Shared ranking contract between this proxy and the Paper extension (PERM-006).
 *
 * <p>Both surfaces resolve permissions with two separate implementations, in two separate
 * repositories and two languages: {@code NativePermissionEvaluator} here, {@code
 * PermissionEvaluator} in Kotlin inside {@code BORNTOCRAFT-Typewriter}. Nothing in the compiler
 * keeps them aligned. The vector file is therefore duplicated verbatim in both repositories and run
 * on each side against the same expectation, which turns a silent divergence into a red test.
 *
 * <p>The contract digest is checked on both sides: if one repository edits its vectors without
 * mirroring the change, the test names the drift instead of letting it through. The digest covers
 * the content normalised to LF, so it survives the line-ending conversion Git performs on checkout.
 *
 * <p>The scope is deliberately the shared one — profiles are excluded, since the proxy snapshot
 * carries none.
 */
class NativePermissionRankingVectorsTest {

  private static final String CONTRACT_RESOURCE = "permission-ranking-vectors.json";

  /**
   * Digest of the shared contract, to be kept identical in
   * {@code BORNTOCRAFT-Typewriter/.../core/PermissionRankingVectorsTest.kt}.
   */
  private static final String EXPECTED_CONTRACT_SHA256 =
      "14645d0042da78b1da4191ddbc14200572a704763d76d3425d759f22016060c4";

  @Test
  void sharedRankingContractHasNotDrifted() throws IOException, NoSuchAlgorithmException {
    final String normalized = contractText().replace("\r\n", "\n");
    final byte[] digest = MessageDigest.getInstance("SHA-256")
        .digest(normalized.getBytes(StandardCharsets.UTF_8));
    final StringBuilder hex = new StringBuilder(digest.length * 2);
    for (byte value : digest) {
      hex.append(String.format("%02x", value));
    }

    assertEquals(EXPECTED_CONTRACT_SHA256, hex.toString(),
        "les vecteurs partages ont change ; repercuter la modification dans "
            + "BORNTOCRAFT-Typewriter/extensions/TypeWriter-PermissionsExtension/src/test/resources/"
            + "permission-ranking-vectors.json et mettre a jour l'empreinte dans les deux tests");
  }

  @Test
  void everySharedVectorResolvesIdenticallyOnTheProxyEvaluator() throws IOException {
    final Gson gson = new Gson();
    final JsonObject contract = gson.fromJson(contractText(), JsonObject.class);
    final List<JsonElement> vectors = new ArrayList<>();
    contract.getAsJsonArray("vectors").forEach(vectors::add);
    assertTrue(vectors.size() >= 15,
        "le contrat doit porter au moins quinze vecteurs, obtenu " + vectors.size());

    final List<String> failures = new ArrayList<>();
    for (JsonElement element : vectors) {
      final JsonObject vector = element.getAsJsonObject();
      final String name = vector.get("name").getAsString();
      final NativePermissionSnapshot snapshot =
          gson.fromJson(vector.getAsJsonObject("snapshot"), NativePermissionSnapshot.class);
      final Tristate resolved = NativePermissionEvaluator.evaluate(
          snapshot,
          vector.get("query").getAsString(),
          readContext(vector.getAsJsonObject("context")),
          vector.get("now").getAsLong());

      final String actual = switch (resolved) {
        case TRUE -> "ALLOW";
        case FALSE -> "DENY";
        case UNDEFINED -> "UNDEFINED";
      };
      final String expected = vector.get("expected").getAsString();
      if (!actual.equals(expected)) {
        failures.add(name + " : attendu " + expected + ", obtenu " + actual);
      }
    }

    assertTrue(failures.isEmpty(), "vecteurs en echec :\n" + String.join("\n", failures));
  }

  private static Map<String, String> readContext(final JsonObject context) {
    final Map<String, String> values = new HashMap<>();
    context.entrySet().forEach(entry -> values.put(entry.getKey(), entry.getValue().getAsString()));
    return values;
  }

  private static String contractText() throws IOException {
    try (InputStream stream = NativePermissionRankingVectorsTest.class.getClassLoader()
        .getResourceAsStream(CONTRACT_RESOURCE)) {
      if (stream == null) {
        throw new IllegalStateException(CONTRACT_RESOURCE + " est absent des ressources de test");
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
