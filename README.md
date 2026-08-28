# 🌌 BTC Velocity — High-Performance Minecraft Proxy

![Banner](https://img.shields.io/badge/Minecraft-26.2-blue?style=for-the-badge&logo=minecraft)
![Java](https://img.shields.io/badge/Java-21%2B-orange?style=for-the-badge&logo=openjdk)
![Status](https://img.shields.io/badge/Status-Production_Ready-green?style=for-the-badge)
![License](https://img.shields.io/badge/License-GPLv3-blue.svg?style=for-the-badge)

> [!IMPORTANT]
> **BTC Velocity** is a custom, high-performance fork of [Velocity-CTD](https://github.com/GemstoneGG/Velocity-CTD), purpose-built for the **Born to Craft** Minecraft network. Compatible with **Minecraft 26.2** (protocol 776) and **Java 21+**.

---

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
| Minecraft 26.2 | BTC | Protocol 776 support. |
| `btc:bridge` protocol **v2** | BTC | Typed, versioned messages between proxy and backends. Every message carries an envelope (version, message id, source backend); `Ack`/`Nack` let the sender tell a lost request from a slow one. The sealed hierarchy has an explicit `permits` list, so a message added outside it fails to compile instead of silently decoding as unknown. |
| Modern forwarding | Velocity | Velocity-native forwarding plus BungeeGuard. |
| Anti-decompression bomb | BTC | Compression-ratio monitoring with a ratio-check floor, and oversized packet detection. |
| Netty pipeline tuning | BTC | Zero-copy where possible. |

### Permissions — native, LuckPerms removed

LuckPerms is no longer used anywhere on the BTC network. The `luckperms-integration` module and the
jar it embedded in the shadow JAR are gone.

| Piece | What it does |
|---|---|
| `NativePermissionEvaluator` | Wildcard resolution and inheritance, evaluated against an immutable snapshot — safe to read from any Netty thread. |
| `NativePermissionService` / `NativePermissionSnapshot` | Loads and swaps the snapshot; MySQL storage is optional. |
| `NativePermissionResolverProvider` | Discovered through `META-INF/services`. The shadow JAR now calls `mergeServiceFiles()` — without it the last service file wins and the resolver is never found. |

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
| PostgreSQL | BTC | Native persistent backend on HikariCP, drop-in for MySQL setups. |
| Cluster sync | BTC | Multi-proxy player tracking over Redis pub/sub. |
| Queue system | BTC | Per-server connection queues with dynamic prioritisation. |

### Distribution

| | |
|---|---|
| Public API | `dev.btc.velocity:api`, published to <https://borntocraftstudio.net/repo/> |
| Repository source of truth | [`repo/`](repo/README.md) in this repository, uploaded to the site as-is |
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

Native persistent storage backend. Drop-in replacement for MySQL setups:

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

---

## 🏗️ Building & Deployment

### Prerequisites
* **Java 21+ JDK** (Java 25 recommended for production)
* **Gradle 8.12+** (or use the included wrapper)

### Build
```bash
export JAVA_HOME=/path/to/java-21
./gradlew clean build
```

The production-ready shadow JAR will be at:
`proxy/build/libs/velocity-proxy-0.1-all.jar`

### Recommended Startup Flags
```bash
java -Xms4G -Xmx4G \
  -XX:+UseZGC -XX:+ZGenerational \
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
    compileOnly("dev.btc.velocity:api:0.1")
}
```

### Gradle (Groovy DSL)
```groovy
repositories {
    maven { url 'https://borntocraftstudio.net/repo/' }
}

dependencies {
    compileOnly 'dev.btc.velocity:api:0.1'
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
        <version>0.1</version>
        <scope>provided</scope>
    </dependency>
</dependencies>
```

- **Javadoc**: https://borntocraftstudio.net/repo/javadoc/velocity/
- The same repository also hosts the backend API `dev.btc.core:api` (BTC-CORE).
- Repo layout and republish instructions: [`repo/README.md`](repo/README.md).

---

## 📁 Project Structure

```
BTCVelocity/
├── api/                        → Public API (compatible with velocity-api)
├── proxy/                      → Core implementation
│   ├── com.btcvelocity.proxy/  → Custom code (cluster, commands, queue, redis, storage)
│   └── com.velocitypowered.*   → Upstream Velocity code
├── native/                     → Netty native transports
├── proxy/src/main/.../permission → Native BTC permissions resolver (optional MySQL/Redis)
├── config/checkstyle/          → Code style configuration
└── Docs/                       → Reference documentation
```

---

## 📊 Dependencies

| Component | Version |
| :--- | :--- |
| Netty | 4.2.15.Final |
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
