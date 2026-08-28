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

### Core Features

- **Multi-backend cache** : Redis, Valkey, or Dragonfly — switch via single config option
- **PostgreSQL native support** : HikariCP connection pool, async-friendly
- **Cluster sync** : Multi-proxy player tracking via Redis pub/sub
- **Queue system** : Per-server connection queues with dynamic prioritization
- **Modern forwarding** : Velocity native + BungeeGuard support
- **Anti-decompression bomb** : Compression ratio monitoring and oversized packet detection
- **Sub-millisecond latency** : Optimized Netty pipeline, zero-copy where possible

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

- **Base Engine**: [Velocity](https://github.com/PaperMC/Velocity) (GPLv3)
- **Fork Base**: [Velocity-CTD](https://github.com/GemstoneGG/Velocity-CTD) by GemstoneGG
- **Packaging**: Born To Craft Studio

---

© 2026 Born To Craft Studio. Licensed under GPLv3.
