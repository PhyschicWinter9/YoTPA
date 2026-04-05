# YoTPA — Project Context for Claude

## What is this?

**YoTPA** is a lightweight Minecraft server plugin (Paper/Spigot) that handles teleportation requests between players. It supports `/tpa` (request to teleport to a player) and `/tpahere` (invite a player to teleport to you), with a configurable countdown, movement detection, cooldowns, and full message customization.

- **Author**: PhyschicWinter9 & VIBEs Coding XD
- **Current version**: 1.4.0
- **GitHub**: https://github.com/PhyschicWinter9/YoTPA

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin 2.3.0 |
| Platform | Paper API 1.21.11 (compiled against 1.21.x, supports 1.21.x – 26.1.x) |
| Java | JDK 25 toolchain, bytecode target Java 21 |
| Build | Gradle with Kotlin DSL (`build.gradle.kts`) |
| Text components | Kyori Adventure + MiniMessage |
| Analytics | bStats (plugin ID 25926) |

---

## Project Structure

```
YoTPA/
├── src/main/
│   ├── kotlin/com/relaxlikes/yoTPA/
│   │   ├── YoTPA.kt               # Main plugin class — all commands, TPA logic, performance modes
│   │   ├── MessageManager.kt      # Loads messages.yml, handles placeholder replacement
│   │   ├── PlayerMoveListener.kt  # Cancels teleport countdown if player moves
│   │   └── bStatsTPA.kt           # Analytics and metrics tracking
│   └── resources/
│       ├── plugin.yml             # Plugin metadata, commands, permissions
│       ├── config.yml             # Default runtime configuration
│       └── messages.yml           # Default messages (MiniMessage format)
├── .github/workflows/
│   ├── build.yml                  # CI: build on push
│   └── build-and-release.yml      # Release workflow
├── build.gradle.kts
├── settings.gradle.kts
├── README.md
└── CHANGELOG.md
```

---

## Commands & Permissions

| Command | Permission | Description |
|---|---|---|
| `/tpa <player>` | `yotpa.tpa` | Request to teleport to a player |
| `/tpahere <player>` | `yotpa.tpahere` | Request a player to teleport to you |
| `/tpaccept` | `yotpa.tpaccept` | Accept a pending TPA request |
| `/tpadeny` | `yotpa.tpadeny` | Deny a pending TPA request |
| `/tpareload` | `yotpa.reload` | Reload config and messages (OP) |
| `/tpastats` | `yotpa.stats` | View your TPA statistics |
| `/tpainfo` | `yotpa.info` | View real-time performance info |

---

## Key Configuration (`config.yml`)

- `request-timeout`: seconds before a TPA request expires (default 60s)
- `request-cooldown`: cooldown between requests (default 30s)
- `teleport-delay`: countdown seconds before teleporting (default 5s)
- `performance.mode`: AUTO | ULTRA_LIGHT | LIGHT | BALANCED | HIGH_PERFORMANCE
- Sound keys, feature toggles (titles, sounds, bStats, statistics)

---

## Adaptive Performance System

AUTO mode detects available server RAM and selects a tier automatically:

| Mode | RAM | Executor Threads | Cache | Check Interval |
|---|---|---|---|---|
| ULTRA_LIGHT | ≤768 MB | 0 | Minimal | 30s |
| LIGHT | ≤1.5 GB | 2 | Moderate | 20s |
| BALANCED | ≤3 GB | 3 | Full | 10s |
| HIGH_PERFORMANCE | >3 GB | 4 | Aggressive | 5s |

Movement threshold also adapts (0.5 blocks for low-end, 0.25 for high-end).

---

## Internationalization (v1.4.0+)

All messages live in `messages.yml` (MiniMessage format). `MessageManager.kt` handles:
- Loading and caching messages
- Placeholder replacement (`{player}`, `{target}`, `{seconds}`, `{cooldown}`, etc.)
- Live reload via `/tpareload` without server restart
- Customizable prefix

---

## Data Storage

- Uses Paper's `PersistentDataContainer` (modern API, no deprecated metadata)
- `ConcurrentHashMap` structures sized per performance mode
- Auto-cleanup on player disconnect
- `NamespacedKey` prevents data conflicts with other plugins

---

## How to Build & Run

```bash
# Build (outputs to build/libs/YoTPA-1.4.0.jar)
./gradlew clean build

# Run a local Paper 1.21 test server with the plugin loaded
./gradlew runServer

# Install to a real server
# Copy build/libs/YoTPA-1.4.0.jar → server/plugins/
# Restart the server
```

CI runs `./gradlew clean build shadowJar` on GitHub Actions. bStats is relocated to `com.relaxlikes.yotpa.lib.bstats` to avoid classpath conflicts.

---

## Git Branch Conventions

- `main` — stable releases
- `dev/<version>` — active development (e.g., `dev/26.1`)
- `feat/<name>` — feature branches
- `backup/<version>` — version snapshots

Current working branch: `dev/26.1`

---

## Development Notes

- All plugin logic (command handling, teleport lifecycle, sound, titles) is in `YoTPA.kt`
- Do not use deprecated Bukkit metadata APIs — use `PersistentDataContainer`
- When modifying messages, always update `messages.yml` (not hardcoded strings)
- When adding commands, register them in both `plugin.yml` and `YoTPA.kt`
- The build injects `${version}` into `plugin.yml` and `messages.yml` via `processResources`
- Group/package: `com.relaxlikes.yoTPA`
