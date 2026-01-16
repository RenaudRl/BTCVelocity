# BTC Velocity

[![Build Status](https://img.shields.io/github/actions/workflow/status/PaperMC/Velocity/gradle.yml?label=Build&style=for-the-badge)](https://papermc.io/downloads/velocity)
[![Discord](https://img.shields.io/discord/289587909051416579.svg?logo=discord&label=Discord&style=for-the-badge)](https://discord.gg/papermc)
[![License](https://img.shields.io/badge/License-GPLv3-blue.svg?style=for-the-badge)](LICENSE)

> [!IMPORTANT]
> **BTC Velocity** is a highly optimized fork of Velocity, engineered for **Minecraft 1.21.11**. It integrates advanced features from multiple high-performance forks to provide a robust, secure, and feature-rich proxy solution.

---

## 🧬 Fork Heritage

BTC Velocity is built upon the foundation of several cutting-edge projects:

*   **[Velocity](https://github.com/PaperMC/Velocity)**: The core high-performance proxy.
*   **[BTC-Velocity](https://github.com/GemstoneGG/Velocity-CTD)**: Custom commands, Queue system, and Redis integration.
*   **[MultiVelocity](https://github.com/KalpeGames/MultiVelocity)**: Enhanced security with per-server secret tokens.
*   **[SparklyVelocity](https://github.com/SparklyPower/SparklyVelocity)**: Advanced networking, manual listener binding, and Geyser detection.

## 🚀 Key Features

### ⚡ Performance & Optimization
*   **Java 21 Native**: Fully optimized for the modern Java 21 ecosystem.
*   **1.21.11 Ready**: Specific tuning for the latest Minecraft protocol versions.
*   **Advanced Networking**: Disabled default port bindings for plugin-managed listeners.

### 🛡️ Security
*   **Per-Server Secrets**: Configurable forwarding secrets for individual backend servers (`[secrets]` section).
*   **Secure Authentication**: Enhanced online mode checks and key handling.

### ⚙️ Extended Functionality
*   **Queue System**: Built-in priority queue handling for high-traffic servers.
*   **Custom Commands**: New administrative and utility commands (`/alert`, `/find`, `/hub`, `/send`, etc.).
*   **Geyser Support**: Native detection and handling for Bedrock players via Geyser.

## 🛠️ Building

BTC Velocity uses Gradle for build automation.

### Prerequisites
*   Java 21 JDK

### Build Command
To generate the distribution jar:

```bash
./gradlew build
```

The optimized artifact will be located in:
`proxy/build/libs/velocity-proxy-3.4.0-SNAPSHOT-all.jar`

## 🖥️ Configuration

BTC Velocity introduces new configuration sections in `velocity.toml`:

### Secrets
Define unique forwarding secrets for legacy or secure backend servers:
```toml
[secrets]
lobby = "my-secret-token"
factions = "another-secret"
```

### Queue
Configure queue behavior and priority settings in the `[queue]` section.

---

## 📜 License & disclaimer
- **Custom BTC-CORE Patches**: Proprietary to **BTC Studio**.
- **Upstream Source**: Original licenses (GPLv3 / MIT) apply to their respective components from Velocity, Velocity-CTD, MultiVelocity, SparklyVelocity, etc.
