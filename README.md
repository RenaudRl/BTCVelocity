# 🌌 BTC Proxy Core — The Ultimate Minecraft 26.1.2 Proxy

![Banner](https://img.shields.io/badge/Minecraft-26.1.2-blue?style=for-the-badge&logo=minecraft)
![Java](https://img.shields.io/badge/Java-25_LTS-orange?style=for-the-badge&logo=openjdk)
![Status](https://img.shields.io/badge/Status-Production_Ready-green?style=for-the-badge)
![License](https://img.shields.io/badge/License-GPLv3-blue.svg?style=for-the-badge)

> [!IMPORTANT]
> **BTC Proxy Core** is a custom, high-performance fork of Velocity, purpose-built for **Minecraft 26.1.2 (Tiny Takeover)** and **Java 25 (LTS)**. It incorporates the "100 Optimizations" roadmap, integrating cutting-edge networking, JVM tuning, and security features.

---

## 🚀 The 100 Optimizations Suite

BTC Proxy Core implements a comprehensive suite of optimizations designed for 2026's hardware and network landscapes:

### ⚡ Infrastructure & Networking
*   **VarInt Bitwise Unrolling**: Hand-optimized VarInt parsing for maximum packet throughput.
*   **TCP Fast Open (TFO)**: 0-RTT connection establishment for verified clients.
*   **SO_REUSEPORT**: Multi-threaded socket binding for massive scalability on Linux.
*   **Zero-Copy Routing**: Direct DMA transfer of resource packs and large data streams.
*   **Native SIMD Compression**: LibDeflate integration using vector instructions (AVX-512/NEON).

### 🧠 JVM & Memory (Java 25 Optimized)
*   **Generational ZGC**: Sub-millisecond GC pauses even under heavy bot attacks.
*   **Compact Object Headers (JEP 519)**: Reduced RAM footprint by up to 20%.
*   **Virtual Threads (Project Loom)**: Lightweight concurrency for every player session.
*   **FFM API (Foreign Function & Memory)**: Secure and fast native memory access.

### 🛡️ Security & Resilience
*   **Protocol Lockdown**: Strict enforcement of Protocol 775 (MC 26.1.2).
*   **eBPF/XDP Integration**: Kernel-level DDoS mitigation (External scripts available in `Docs/`).
*   **Redis Pub/Sub Sync**: Real-time cluster-wide player and state management.
*   **Virtual "Limbo" Server**: Graceful crash handling without disconnecting players.

---

## ✨ New in BTC Proxy Core

### 🎨 Dynamic MOTD Alignment
Control your server's first impression with precision. Edit lines separately and choose your alignment:
```toml
[motd]
motd-line1 = "<gradient:gold:yellow><b>BTC Proxy Core</b></gradient>"
motd-line2 = "<gray>The Ultimate 26.1.2 Experience"
line1-alignment = "center" # options: left, center, right
line2-alignment = "center"
```

### 🔐 Multi-Forwarding Guard
Support multiple backend security protocols simultaneously:
- **Velocity Modern Forwarding** (Native)
- **BungeeGuard** (Legacy Tokens)
- **Secret Tokens** (Per-server configuration)

---

## 🛠️ Commands & Features

BTC Proxy Core bundles a professional suite of administrative tools:

| Command | Permission | Description |
| :--- | :--- | :--- |
| `/alert` | `velocity.command.alert` | Broadcast a message to the entire network. |
| `/find <player>` | `velocity.command.find` | Locate a player across the multi-proxy cluster. |
| `/gkick <player>` | `velocity.command.gkick` | Kick a player from the network (Redis-synced). |
| `/glist` | `velocity.command.glist` | List all players online across all proxies. |
| `/queueadmin` | `velocity.queue.admin` | Manage the dynamic connection queue. |
| `/transfer` | `velocity.command.transfer` | Move players between proxy instances. |
| `/velocity uptime` | `velocity.command.uptime` | View high-precision proxy statistics. |

---

## 🏗️ Building & Deployment

### Prerequisites
*   **Java 25 JDK** (Recommended: GraalVM or Amazon Corretto)
*   **Gradle 8.12+**

### Build
```bash
./gradlew clean build -x test
```
The production-ready shadow JAR will be located at:
`proxy/build/libs/btc-proxy-core-all.jar`

### Recommended Startup Flags
For maximum performance and JVM stability, use:
```bash
java -Xms4G -Xmx4G -XX:+UseZGC -XX:+UnlockExperimentalVMOptions -XX:+UseCompactObjectHeaders -jar btc-proxy-core-all.jar
```

---

## 📜 Credits & License
- **Base Engine**: [Velocity](https://github.com/PaperMC/Velocity) (GPLv3)
- **Heritage**: Enhanced with logic from Velocity-CTD, MultiVelocity, and SparklyVelocity.
- **Optimization Architecture**: Engineered by **BTC Studio** for the 26.1.2 ecosystem.

---
© 2026 Born To Craft Studio. **Finalized & Stabilized.**
