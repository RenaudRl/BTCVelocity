/*
 * Copyright (C) 2018-2026 Velocity Contributors
 *
 * The Velocity API is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package com.btcvelocity.api.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import java.nio.charset.StandardCharsets;
import org.jetbrains.annotations.Nullable;

/**
 * Handles JSON serialization and deserialization of {@link BridgeMessage} records.
 *
 * <p>Each message is encoded as a JSON object containing every record component plus a
 * {@code "type"} discriminator field. Decoding reads the {@code "type"} field and routes
 * the payload to the matching record class. Malformed or unrecognized payloads decode to
 * {@code null} rather than throwing, so a single bad message can never destabilize the
 * proxy.</p>
 */
public final class BridgeCodec {

  private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

  private BridgeCodec() {
  }

  /**
   * Encodes a bridge message to a UTF-8 JSON byte array.
   *
   * @param message the message to encode
   * @return the JSON payload as a byte array
   */
  public static byte[] encode(final BridgeMessage message) {
    JsonObject json = GSON.toJsonTree(message).getAsJsonObject();
    json.addProperty("type", message.type());
    return GSON.toJson(json).getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Decodes a JSON byte array into a bridge message.
   *
   * @param data the JSON payload as a byte array
   * @return the decoded message, or {@code null} if the payload is malformed, missing a
   *         {@code "type"} field, or refers to an unknown message type
   */
  public static @Nullable BridgeMessage decode(final byte[] data) {
    if (data == null || data.length == 0) {
      return null;
    }

    final String json = new String(data, StandardCharsets.UTF_8);
    final JsonObject obj;
    try {
      obj = JsonParser.parseString(json).getAsJsonObject();
    } catch (JsonSyntaxException | IllegalStateException ignored) {
      return null;
    }

    if (obj.get("type") == null) {
      return null;
    }

    final String type = obj.get("type").getAsString();
    try {
      return switch (type) {
        case "queue_join" -> GSON.fromJson(obj, BridgeMessage.QueueJoin.class);
        case "queue_leave" -> GSON.fromJson(obj, BridgeMessage.QueueLeave.class);
        case "request_status" -> GSON.fromJson(obj, BridgeMessage.RequestStatus.class);
        case "world_preload" -> GSON.fromJson(obj, BridgeMessage.WorldPreload.class);
        case "health" -> GSON.fromJson(obj, BridgeMessage.Health.class);
        case "world_loaded" -> GSON.fromJson(obj, BridgeMessage.WorldLoaded.class);
        case "world_unloaded" -> GSON.fromJson(obj, BridgeMessage.WorldUnloaded.class);
        case "queue_status_response" -> GSON.fromJson(obj, BridgeMessage.QueueStatusResponse.class);
        default -> null;
      };
    } catch (JsonSyntaxException ignored) {
      return null;
    }
  }
}
