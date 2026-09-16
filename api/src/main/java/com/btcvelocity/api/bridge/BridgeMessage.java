/*
 * Copyright (C) 2018-2026 Velocity Contributors
 *
 * The Velocity API is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package com.btcvelocity.api.bridge;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/** Versioned, typed messages exchanged by the BTCVelocity bridge. */
public sealed interface BridgeMessage permits BridgeMessage.QueueJoin, BridgeMessage.QueueLeave,
    BridgeMessage.RequestStatus, BridgeMessage.WorldPreload, BridgeMessage.Health,
    BridgeMessage.WorldLoaded, BridgeMessage.WorldLoadFailed, BridgeMessage.WorldUnloaded,
    BridgeMessage.QueueStatusResponse, BridgeMessage.ConnectRequest, BridgeMessage.PartyWarp,
    BridgeMessage.Ack, BridgeMessage.Nack {

  int VERSION = 2;

  Envelope envelope();

  default int version() {
    return envelope().version();
  }

  default UUID messageId() {
    return envelope().messageId();
  }

  default String kind() {
    return envelope().type();
  }

  /** Compatibility alias for callers that use the discriminator name. */
  default String type() {
    return envelope().type();
  }

  default String sourceBackend() {
    return envelope().sourceBackend();
  }

  default String targetBackend() {
    return envelope().targetBackend();
  }

  default long issuedAt() {
    return envelope().issuedAt();
  }

  default long expiresAt() {
    return envelope().expiresAt();
  }

  record Envelope(int version, UUID messageId, String type, String sourceBackend,
                  String targetBackend, long issuedAt, long expiresAt) {
    public Envelope {
      Objects.requireNonNull(messageId, "messageId");
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(sourceBackend, "sourceBackend");
      Objects.requireNonNull(targetBackend, "targetBackend");
      if (version <= 0 || issuedAt < 0 || expiresAt < 0) {
        throw new IllegalArgumentException("invalid bridge envelope");
      }
    }

    public static Envelope now(final String type, final String sourceBackend,
                               final String targetBackend, final long lifetimeMillis) {
      final long issuedAt = System.currentTimeMillis();
      return new Envelope(VERSION, UUID.randomUUID(), type, sourceBackend, targetBackend,
          issuedAt, Math.addExact(issuedAt, lifetimeMillis));
    }
  }

  /**
   * The client platform a player-scoped message is about.
   *
   * <p>Closed on purpose, and deliberately without an {@code UNKNOWN} member: a message either
   * states a platform it can be held to, or states nothing at all. An {@code UNKNOWN} would be a
   * third answer that reads like a measurement while being an admission of ignorance, and it would
   * travel on the wire as if someone had established it.
   */
  enum Platform {
    JAVA,
    BEDROCK
  }

  record QueueJoin(Envelope envelope, UUID uuid, String username, String targetServer,
                   @Nullable String targetWorld, @Nullable Platform originPlatform)
      implements BridgeMessage {
    public QueueJoin {
      requireKind(envelope, "queue_join");
      Objects.requireNonNull(uuid, "uuid");
      Objects.requireNonNull(username, "username");
      Objects.requireNonNull(targetServer, "targetServer");
    }
  }

  record QueueLeave(Envelope envelope, UUID uuid, @Nullable Platform originPlatform)
      implements BridgeMessage {
    public QueueLeave {
      requireKind(envelope, "queue_leave");
      Objects.requireNonNull(uuid, "uuid");
    }
  }

  record RequestStatus(Envelope envelope, String serverName) implements BridgeMessage {
    public RequestStatus {
      requireKind(envelope, "request_status");
      Objects.requireNonNull(serverName, "serverName");
    }
  }

  record WorldPreload(Envelope envelope, String serverName, @Nullable String worldName)
      implements BridgeMessage {
    public WorldPreload {
      requireKind(envelope, "world_preload");
      Objects.requireNonNull(serverName, "serverName");
    }
  }

  record Health(Envelope envelope, String serverName, double mspt, double tps, int playerCount,
                List<String> loadedWorlds) implements BridgeMessage {
    public Health {
      requireKind(envelope, "health");
      Objects.requireNonNull(serverName, "serverName");
      Objects.requireNonNull(loadedWorlds, "loadedWorlds");
      loadedWorlds = List.copyOf(loadedWorlds);
    }
  }

  record WorldLoaded(Envelope envelope, String serverName, String worldName, long loadTimeMs)
      implements BridgeMessage {
    public WorldLoaded {
      requireKind(envelope, "world_loaded");
      Objects.requireNonNull(serverName, "serverName");
      Objects.requireNonNull(worldName, "worldName");
    }
  }

  record WorldLoadFailed(Envelope envelope, String serverName, String worldName, String reason,
                         long loadTimeMs) implements BridgeMessage {
    public WorldLoadFailed {
      requireKind(envelope, "world_load_failed");
      Objects.requireNonNull(serverName, "serverName");
      Objects.requireNonNull(worldName, "worldName");
      Objects.requireNonNull(reason, "reason");
    }
  }

  record WorldUnloaded(Envelope envelope, String serverName, String worldName)
      implements BridgeMessage {
    public WorldUnloaded {
      requireKind(envelope, "world_unloaded");
      Objects.requireNonNull(serverName, "serverName");
      Objects.requireNonNull(worldName, "worldName");
    }
  }

  record QueueStatusResponse(Envelope envelope, String serverName, int backendQueueSize)
      implements BridgeMessage {
    public QueueStatusResponse {
      requireKind(envelope, "queue_status_response");
      Objects.requireNonNull(serverName, "serverName");
    }
  }

  record ConnectRequest(Envelope envelope, UUID uuid, String targetServer,
                        @Nullable Platform originPlatform) implements BridgeMessage {
    public ConnectRequest {
      requireKind(envelope, "connect_request");
      Objects.requireNonNull(uuid, "uuid");
      Objects.requireNonNull(targetServer, "targetServer");
    }
  }

  /**
   * A group move.
   *
   * <p>A party has no single platform, so it states one <em>per member</em> rather than one for
   * the group — a single value would describe nobody. The map is optional, like the scalar field
   * on player-scoped messages, and is keyed by member.
   *
   * <p>It is not redundant with what the proxy already knows: the point of a stated platform has
   * never been to inform the proxy, it is to catch a backend that is wrong about who is who. That
   * is worth as much for sixty-four players as for one.
   */
  record PartyWarp(Envelope envelope, List<UUID> members, String targetServer,
                   @Nullable Map<UUID, Platform> memberPlatforms) implements BridgeMessage {
    public PartyWarp {
      requireKind(envelope, "party_warp");
      Objects.requireNonNull(members, "members");
      Objects.requireNonNull(targetServer, "targetServer");
      members = List.copyOf(members);
      memberPlatforms = memberPlatforms == null ? null : Map.copyOf(memberPlatforms);
    }
  }

  record Ack(Envelope envelope, boolean duplicate) implements BridgeMessage {
    public Ack {
      requireKind(envelope, "ack");
    }
  }

  record Nack(Envelope envelope, ErrorCode error) implements BridgeMessage {
    public Nack {
      requireKind(envelope, "nack");
      Objects.requireNonNull(error, "error");
    }
  }

  enum ErrorCode {
    INVALID_ENVELOPE,
    PAYLOAD_TOO_LARGE,
    EXPIRED,
    DUPLICATE,
    CLIENT_ORIGIN,
    BACKEND_NOT_ALLOWED,
    TARGET_NOT_ALLOWED,
    WORLD_NOT_ALLOWED,
    PLATFORM_MISMATCH,
    PLATFORM_UNRESOLVABLE,
    UNSUPPORTED,
    WORLD_LOAD_FAILED,
    INTERNAL_ERROR
  }

  private static void requireKind(final Envelope envelope, final String expected) {
    Objects.requireNonNull(envelope, "envelope");
    if (envelope.version() != VERSION || !expected.equals(envelope.type())) {
      throw new IllegalArgumentException("envelope does not describe " + expected);
    }
  }
}
