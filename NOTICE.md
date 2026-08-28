# NOTICE — BTC Velocity

BTC Velocity is a fork of [Velocity-CTD](https://github.com/GemstoneGG/Velocity-CTD), itself a fork
of [Velocity](https://github.com/PaperMC/Velocity).

This file exists to satisfy GPLv3 §5(a) and §5(b): it states who modified this software, what was
modified, and which notices must be preserved when it is redistributed.

---

## 1. Licensing is per-module

This repository is **not** under a single licence. Check which module you are copying from.

| Module | Licence | File |
|---|---|---|
| `proxy/`, `native/`, repository as a whole | **GPL-3.0-or-later** | [LICENSE](LICENSE) |
| `api/` | **MIT** | [api/LICENSE](api/LICENSE) |

The `api/` module is MIT because Velocity's API is MIT. That is deliberate — it is what lets third
parties write plugins against `dev.btc.velocity:api` without their plugin becoming GPL. It also
means code placed in `api/` can be reused, relicensed and sold by anyone, with attribution as the
only condition. Put anything you want protected by copyleft in `proxy/`, not in `api/`.

## 2. Copyright

| Scope | Holder |
|---|---|
| Modifications and original code introduced by this fork | **Copyright © 2026 Born To Craft Studio** |
| Velocity-CTD | Copyright © GemstoneGG and contributors |
| Velocity | Copyright © 2018–2026 Velocity Contributors / PaperMC |

Original code introduced by Born To Craft Studio lives under:

- `api/src/main/java/com/btcvelocity/` *(MIT)*
- `proxy/src/main/java/com/btcvelocity/` *(GPLv3)*
- BTC-specific changes inside `proxy/src/main/java/com/velocitypowered/` *(GPLv3)*

## 3. Statement of modifications

Required by GPLv3 §5(a). A summary; the authoritative record is the git history of this repository.

| Area | Modification |
|---|---|
| Protocol | Support for Minecraft 26.2 (protocol 776). |
| Permissions | LuckPerms integration removed; replaced by a native BTC permission resolver with an immutable snapshot model and optional MySQL storage. |
| `btc:bridge` protocol | Versioned typed message protocol between proxy and backends (v2: envelope carrying version, message id and source backend; `Ack`/`Nack`; sealed hierarchy with an explicit `permits` list). |
| MOTD | Inline `<left>`/`<center>`/`<right>` alignment with pixel-accurate glyph widths, configurable width, and a config migration from the old alignment keys. |
| Storage | Native PostgreSQL backend (HikariCP); Redis / Valkey / Dragonfly selectable through one option. |
| Cluster | Multi-proxy player tracking over Redis pub/sub, per-server connection queues with dynamic prioritisation. |
| Security | Anti-decompression-bomb: compression ratio monitoring with a ratio-check floor, oversized packet detection. |
| Distribution | Static Maven repository under `repo/`, published at <https://borntocraftstudio.net/repo/>. |

## 4. What the GPL does and does not allow

For everything outside `api/`. Stated plainly, because it is often misread in both directions.

**You may**, under GPLv3:

- run BTC Velocity for any purpose, including commercially;
- study and modify it;
- redistribute it, modified or not, **including for a fee**;
- run a paid Minecraft network with it.

**You must**, when you redistribute it, modified or not:

- license the whole work under GPLv3 and provide the **complete corresponding source**;
- preserve every copyright notice, this NOTICE file, and the LICENSE files;
- **state prominently that you modified it, and on what date** (§5(a));
- keep the attribution to Born To Craft Studio, GemstoneGG and PaperMC intact.

**You may not**:

- relicense the GPL modules, or any derivative of them, under terms more restrictive than GPLv3 —
  including a "no resale" clause. GPLv3 does not permit it, and neither do we;
- distribute the GPL modules as closed source;
- strip or rewrite the attribution above and present this work as your own. That is not merely
  impolite — it is a licence violation, and it terminates your rights under §8;
- use the Born To Craft Studio names or marks on a redistribution (see §5).

## 5. Names and marks — reserved

**"Born To Craft", "Born To Craft Studio", "BTC Studio", "BTC Velocity", "BTCVelocity",
"BTC-CORE", "BTCCore"**, the associated logos, and the `borntocraftstudio.net` domain are marks of
Born To Craft Studio. **They are not licensed under the GPL or the MIT licence** — neither grants
trademark rights.

Concretely: you are free to fork this code and even to sell your fork, but you must **rebrand it**.
You may state factually that your work is derived from BTC Velocity. You may not name it BTC
Velocity, publish it under our marks, or present it in a way that suggests it comes from or is
endorsed by Born To Craft Studio.

## 6. Third-party components

Dependencies retain their own licences (Netty, Adventure, Lettuce, HikariCP, PostgreSQL JDBC,
MySQL Connector/J, Log4j, jline, and the others declared in `gradle/libs.versions.toml`). Nothing
in this NOTICE alters them.

The static Maven repository under `repo/` also hosts BTC forks of third-party projects
(PacketEvents, CraftEngine, BetterHud, BetterModel, CustomNameplates, BlueMap, MiniPlaceholders).
Each is redistributed **under its own upstream licence**, unchanged, and none of them is covered by
this NOTICE.

## 7. Reporting a licence violation

Open an issue at <https://github.com/RenaudRl/BTCVelocity/issues> or contact Born To Craft Studio
through <https://borntocraftstudio.net>.
