# 🌌 BTC Velocity — High-Performance Minecraft Proxy

![Banner](https://img.shields.io/badge/Minecraft-26.3-blue?style=for-the-badge&logo=minecraft)
![Java](https://img.shields.io/badge/Java-25-orange?style=for-the-badge&logo=openjdk)
![Status](https://img.shields.io/badge/Status-Active_development-yellow?style=for-the-badge)
![License](https://img.shields.io/badge/License-GPLv3-blue.svg?style=for-the-badge)

> [!IMPORTANT]
> **BTC Velocity** is a custom, high-performance fork of [Velocity-CTD](https://github.com/GemstoneGG/Velocity-CTD), purpose-built for the **Born to Craft** Minecraft network, kept in sync with PaperMC Velocity **4.3.0**. Compatible with **Minecraft 26.3** (protocol 777) and **Java 25**.

---

## 🚧 Work in Progress

These parts ship in the jar but are **not finished**. Do not rely on them as complete protections yet.

| Area | Status |
|---|---|
| **Sentinel admission shield** (anti-bot, anti-proxy/VPN, datacenter/Tor lists, ASN, per-network rate limits) | In progress: filters are in place, tuning and false-positive review on a test network are ongoing. |
| **CloudNet integration** (service registry, arrival routing, on-demand wake, resource-pack deduplication) | Running on the BTC test network under CloudNet, with real Java and Bedrock (Geyser 2.12) clients; the two-proxy drain hand-over is not yet proven with real clients. |

## 🚀 Architecture

```
Players → BTC Velocity (proxy) → BTC Core (backend servers)
                ↕
    Redis / Valkey / Dragonfly (pub/sub, cache, cluster sync)
                ↕
    PostgreSQL (persistent storage)
```

## ✨ What BTC Velocity Adds

Every row is a change this fork makes over its Velocity-CTD base. The **Origin** column says where
the work comes from — **BTC** means it was written here. Full attribution and the statement of
modifications required by GPLv3 §5(a) are in [NOTICE.md](NOTICE.md).

### Network and protocol

| Feature | Origin | What it does |
|---|---|---|
| Minecraft 26.3 | Velocity | Protocol 777 support from upstream 4.3.0; clients older than 26.3 are refused (BTC). |
| `btc:bridge` protocol **v2** | BTC | Typed, versioned messages between proxy and backends. Every message carries an envelope (version, message id, source backend); `Ack`/`Nack` let the sender tell a lost request from a slow one. The sealed hierarchy has an explicit `permits` list, so a message added outside it fails to compile instead of silently decoding as unknown. Frames are HMAC-sealed and bound to the backend connection that carried them; client-originated, oversized, replayed or unsigned messages are refused, and player-scoped messages carry an origin platform checked against the live session. Validated end to end with BTC-CORE, CloudNet and a real Bedrock player. |
| Modern forwarding | Velocity | Velocity-native forwarding plus BungeeGuard. |
| Anti-decompression bomb | BTC | Compression-ratio monitoring with a ratio-check floor, and oversized packet detection. |

### Permissions — native, LuckPerms removed

LuckPerms is no longer used anywhere on the BTC network. The `luckperms-integration` module and the
jar it embedded in the shadow JAR are gone.

| Piece | What it does |
|---|---|
| `NativePermissionEvaluator` | Wildcard resolution and inheritance, evaluated against an immutable snapshot — safe to read from any Netty thread. |
| `NativePermissionService` / `NativePermissionSnapshot` | Reads the Typewriter PermissionsExtension tables (`btc_permissions_players`, `btc_permissions_groups`) from PostgreSQL, read-only; Valkey invalidations (`btc:permissions:<network>`) evict the local cache. |
| `NativePermissionResolverProvider` | Discovered through `META-INF/services`. The shadow JAR now calls `mergeServiceFiles()` — without it the last service file wins and the resolver is never found. |

**Behaviour** (since proxy 0.4, validated with a real client on the BTC test network):

- **Off by default.** Enabled with `-Dbtc.permissions.enabled=true`, configured by `btc-permissions.properties`
  next to the jar (`jdbc-url`, `username`, `password`, `network-id`, `server-id`, `table-prefix`, `valkey-uri`).
  `password` and `valkey-uri` accept `${env:NAME}`; an unset or empty variable is an error, never an empty secret.
  A `jdbc-url` carrying `password=`, `readOnlyMode=`, `options=` or `socketTimeout=` is refused.
- **Same decisions as the extension.** The group catalog wins over the copy embedded in the player's row;
  shared test vectors (`permission-ranking-vectors.json`) are played by both the proxy and the extension.
- **Constant context** `network=<id>`, `server=proxy`: a node scoped to `server=lobby` never applies on the proxy,
  even while the player is on the lobby.
- **Login never refused.** The snapshot is preloaded at authentication; the login waits at most 3 s, without blocking
  a Netty thread. Past that the player enters with no rights and the snapshot applies on arrival.
- **A revocation is never lost.** Per-player revision floor and catalog epoch (a stale read cannot reinstall a
  revoked right); a stale catalog grants nothing; after a Valkey reconnection the cache is reloaded once the
  server confirms the resubscription, and a deaf bus is retried every 5 s while the cache is reloaded.
- **Fails closed.** With the flag set, an invalid configuration denies everything and logs an ERROR — the proxy never
  falls back to Velocity's defaults, which answer `UNDEFINED` (treated as allowed by `/server`).
- **Uncovered nodes stay `UNDEFINED`**, so commands with no permission set behave exactly as in Velocity.
- **Least privilege.** Run it with a dedicated PostgreSQL role (`SELECT` on the two tables only) and a Valkey user
  limited to `SUBSCRIBE` on the one channel — never the extension's writer account, which any proxy plugin could read.

### MOTD

| Before | Now |
|---|---|
| Two config keys (`line1-alignment`, `line2-alignment`), whole-line only | Inline `<left>` / `<center>` / `<right>` tags anywhere in the line |
| Alignment counted in characters | Counted in **pixels**, from the client font's real glyph widths (`MinecraftFontWidth`) — bold and wide glyphs no longer skew the centring |
| — | One `motd-width` key replaces both alignment keys; `MotdAlignmentMigration` converts existing configs |
| Padding leaked into the GameSpy query response | `getPlainMotd()` renders without alignment padding |

### Storage and cluster

| Feature | Origin | Notes |
|---|---|---|
| Redis / Valkey / Dragonfly | BTC | Protocol-compatible, switched with one `backend` option. Valkey for open-source purity, Dragonfly for raw single-node speed. |
| PostgreSQL | BTC | Native persistent backend on HikariCP for the native permissions snapshot. |
| Cluster sync | BTC | Multi-proxy player tracking over Redis pub/sub. |
| Queue system | BTC | Per-server connection queues with dynamic prioritisation. |

### Distribution

| | |
|---|---|
| Public API | `dev.btc.velocity:api`, published to <https://borntocraftstudio.net/repo/> |
| Repository source of truth | A separate private repository, uploaded to the site as-is (no longer `repo/` in this repository) |
| Also hosted there | `dev.btc.core:api` (BTC-CORE) and the BTC forks of PacketEvents, CraftEngine, BetterHud, BetterModel, CustomNameplates, BlueMap and MiniPlaceholders — each under its own upstream licence |

---

## 🗄️ Storage Configuration

### Redis / Valkey / Dragonfly

All three are protocol-compatible. Switch via the `backend` option:

```toml
[redis]
enabled = true
backend = "valkey"  # "redis" | "valkey" | "dragonfly"
host = "127.0.0.1"
port = 6379
username = ""
password = ""
use-ssl = false
proxy-id = "proxy-1"
```

**Recommendation**: Use **Valkey** for open-source purity, **Dragonfly** for raw single-node performance.

### PostgreSQL

Native persistent storage backend for the native BTC services:

```toml
[postgresql]
enabled = true
host = "127.0.0.1"
port = 5432
database = "btcvelocity"
username = "btcvelocity"
password = ""
use-ssl = false

# Connection pool (HikariCP)
max-pool-size = 10
min-idle = 2
connection-timeout = 5000
idle-timeout = 300000
max-lifetime = 600000
```

Or use a full JDBC URL:
```toml
jdbc-url = "jdbc:postgresql://127.0.0.1:5432/btcvelocity?ssl=false&application_name=BTCVelocity"
```

---

## 🛠️ Commands & Features

| Command | Permission | Description |
| :--- | :--- | :--- |
| `/alert` | `velocity.command.alert` | Broadcast a message to the entire network |
| `/find <player>` | `velocity.command.find` | Locate a player across the cluster |
| `/gkick <player>` | `velocity.command.gkick` | Kick a player (Redis-synced) |
| `/glist` | `velocity.command.glist` | List all players across all proxies |
| `/hub` | — | Return to the lobby server |
| `/ping` | — | View your connection latency |
| `/plist` | — | List players grouped by server |
| `/queueadmin` | `velocity.queue.admin` | Manage connection queues |
| `/server <name>` | — | Switch servers |
| `/transfer` | `velocity.command.transfer` | Move players between proxies |
| `/velocity uptime` | `velocity.command.uptime` | View proxy statistics |
| `/velocity drain [start\|cancel\|status]` | `velocity.command.drain` | Empty this proxy before a restart, without kicking anyone |

### Draining a proxy

`/velocity drain start` refuses new logins at the handshake and hands connected players to a live
peer proxy with a Transfer packet (batches of 25 per second). Redis marks the proxy `draining:<id>`
so peers stop sending players to it.

- **Update the whole fleet first.** Every proxy that can receive players must run a build that knows
  the drain marker and the hand-over hold; an older peer can claim a player twice or route new
  arrivals to the draining proxy.
- Peers must set `accepts-transfers = true`. The draining proxy releases the player's Redis entry
  before the Transfer, so the peer does not see a duplicate login even with
  `kick-existing-players = false` (not yet proven with real clients).
- Players who cannot follow a Transfer stay on the proxy until it stops: clients older than 1.20.5,
  and Bedrock players. Bedrock players are only recognised when they arrive through the dedicated
  Geyser listener; Bedrock traffic routed through the Java listener is treated as Java.

---

## 🏗️ Building & Deployment

### Prerequisites
* **Java 25 JDK**
* **Gradle 8.12+** (or use the included wrapper)

### Build
```bash
export JAVA_HOME=/path/to/java-25
./gradlew clean build
```

The production-ready shadow JAR will be at:
`proxy/build/libs/velocity-proxy-0.4-all.jar`

### Recommended Startup Flags
```bash
java -Xms4G -Xmx4G \
  -XX:+UseZGC \
  -XX:+UseCompactObjectHeaders \
  -XX:+UseStringDeduplication \
  -jar velocity-proxy-*-all.jar
```

---

## 🔌 Using the API

The public API is published to the **BTC Studio Maven repository** as `dev.btc.velocity:api`.
Use it to build plugins/extensions against BTC Velocity (scope `provided`/`compileOnly` — the
proxy provides the classes at runtime).

### Gradle (Kotlin DSL)
```kotlin
repositories {
    maven("https://borntocraftstudio.net/repo/")
}

dependencies {
    compileOnly("dev.btc.velocity:api:0.4")
}
```

### Gradle (Groovy DSL)
```groovy
repositories {
    maven { url 'https://borntocraftstudio.net/repo/' }
}

dependencies {
    compileOnly 'dev.btc.velocity:api:0.4'
}
```

### Maven
```xml
<repositories>
    <repository>
        <id>btcstudio</id>
        <url>https://borntocraftstudio.net/repo/</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>dev.btc.velocity</groupId>
        <artifactId>api</artifactId>
        <version>0.4</version>
        <scope>provided</scope>
    </dependency>
    <!-- Netty types of the pipeline hook (ClientChannelHook, ConnectionChannel). Gradle gets it
         from the API's module metadata (compileOnlyApi); the POM cannot express it. -->
    <dependency>
        <groupId>io.netty</groupId>
        <artifactId>netty-transport</artifactId>
        <version>4.2.19.Final</version>
        <scope>provided</scope>
    </dependency>
</dependencies>
```

- **Javadoc**: https://borntocraftstudio.net/repo/javadoc/velocity/
- The same repository also hosts the backend API `dev.btc.core:api` (BTC-CORE).
- API `0.3` moved to Adventure 5: plugins built against `0.2` must be recompiled.
- API `0.4` adds the pipeline hook below. `addClientChannelHook` / `removeClientChannelHook` are
  abstract methods of `ProxyServer`: a plugin that calls them needs a 0.4 proxy, or must check that
  the method exists before calling it (see the fallback note below).

### Pipeline hook (`ClientChannelHook`, API 0.4)

A plugin that needs the raw packets of every client (packet rewriting, resource-pack tooling) adds
its Netty handlers through the API instead of reflecting on the proxy's channel initializer:

```java
server.addClientChannelHook(this, channel -> channel.pipeline()
    .addBefore(ClientChannelHook.MINECRAFT_DECODER, "my-decoder", new MyDecoder())
    .addBefore(ClientChannelHook.MINECRAFT_ENCODER, "my-encoder", new MyEncoder()));
// later, from a PostLoginEvent: ConnectionChannel.of(player) gives the same channel
```

- Called once per new client channel, on every listener (Geyser included), from its event loop,
  after the proxy has built its pipeline and before the first byte is read. Must not block nor do
  I/O: the proxy warns when a hook takes more than 10 ms.
- **Compression**: when it turns on, the proxy inserts `compression-decoder` / `compression-encoder`
  right before the codec, i.e. between your handlers and the codec. To keep seeing uncompressed
  frames, listen to the `CompressionState` user event in an inbound handler and, on `ENABLED`, move
  your handlers back before `MINECRAFT_DECODER` / `MINECRAFT_ENCODER` (re-adding the same instance
  needs `@ChannelHandler.Sharable` handlers; otherwise add new instances).
- `channel.remoteAddress()` is the TCP peer (loopback behind Geyser or a PROXY sender), not the
  player; the connection checks may still close the channel after the hook; Floodgate adds its own
  handlers after the hooks.
- A hook that throws is logged (stack once, then a counted line every 30 s) and the handlers it
  added are removed; the connection and the other hooks go on. `plugin` must be a loaded plugin
  instance, and is named in those logs.
- Fallback for plugins that also run on other proxies: test for the method
  (`ProxyServer.class.getMethod("addClientChannelHook", Object.class, ClientChannelHook.class)`),
  not just for the class, and fall back on your own injection otherwise.

---

## 📁 Project Structure

```
BTCVelocity/
├── api/                        → Public API (compatible with velocity-api)
├── proxy/                      → Core implementation
│   ├── com.btcvelocity.proxy/  → Custom code (cluster, commands, queue, redis, storage)
│   └── com.velocitypowered.*   → Upstream Velocity code
├── native/                     → Netty native transports
├── proxy/src/main/.../permission → Native BTC permissions resolver (optional PostgreSQL/Valkey)
├── config/checkstyle/          → Code style configuration
└── Docs/                       → Reference documentation
```

---

## 📊 Dependencies

| Component | Version |
| :--- | :--- |
| Netty | 4.2.19.Final |
| Lettuce (Redis client) | 7.6.0 |
| PostgreSQL JDBC | 42.7.11 |
| HikariCP | 6.2.1 |
| Log4j | 2.26.0 |
| jline | 4.2.0 |

---

## 📜 Credits & License

### Heritage

| Layer | Project | Licence |
|---|---|---|
| Base engine | [Velocity](https://github.com/PaperMC/Velocity) (PaperMC) | GPLv3 (proxy) / MIT (api) |
| Fork base | [Velocity-CTD](https://github.com/GemstoneGG/Velocity-CTD) by GemstoneGG | GPLv3 |
| This fork | Born To Craft Studio | GPLv3 / MIT, per module |

### Licence — per module

This repository is **not** under a single licence. Check which module you are copying from.

| Module | Licence |
|---|---|
| `proxy/`, `native/`, repository as a whole | **GPL-3.0-or-later** — [LICENSE](LICENSE) |
| `api/` | **MIT** — [api/LICENSE](api/LICENSE) |

`api/` is MIT because Velocity's API is MIT. That is what lets a third-party plugin build against
`dev.btc.velocity:api` without becoming GPL — and it also means anything placed in `api/` can be
reused, relicensed and sold by anyone. Put what you want protected by copyleft in `proxy/`.

### What that means in practice

| | |
|---|---|
| You may | Run it, modify it, redistribute it — **including commercially**. GPLv3 §4 explicitly allows charging for a copy. |
| You must | Ship the complete corresponding source under GPLv3, preserve the copyright notices, and **state that you modified it and when** (§5(a)). |
| You may not | Relicense the GPL modules under stricter terms, distribute them closed source, or strip the attribution and present this work as your own — that terminates your rights under §8. |
| Marks | **"Born To Craft", "BTC Studio", "BTC Velocity", "BTCVelocity"** and the associated logos are **not** covered by the GPL or the MIT licence. Fork the code freely, but rebrand your fork. |

Full attribution, the statement of modifications required by §5(a), and the trademark reservation
are in **[NOTICE.md](NOTICE.md)**. Read it before redistributing.

---

© 2026 Born To Craft Studio. Proxy under GPLv3, API under MIT. See [NOTICE.md](NOTICE.md).
