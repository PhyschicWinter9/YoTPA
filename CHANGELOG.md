# Changelog

All notable changes to YoTPA will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.6.1] - 2026-07-07

### 🔧 Folia Thread-Safety Hardening, Faster Startup, ~35% Smaller JAR

### Fixed

#### Thread-Safety (Folia)
- **Countdown started on the wrong region thread** — accepting a plain `/tpa` ran `startTeleportCountdown` on the *accepter's* region thread, reading the requester's location and showing their title cross-region; the countdown setup is now dispatched to the teleporter's own entity scheduler
- **Ghost-countdown race** — a movement-cancel firing between countdown-data storage and task registration could leave a running task with no cancellable handle, completing a teleport that had been cancelled; dispatching to the owner thread serialises start with move events, and a stale-task guard makes each countdown tick verify its data entry is still current before advancing
- **Cross-region location read in `playSound`** — sounds sent to the *other* player (request received, deny) read `player.location`, a region-guarded accessor on Folia; switched to the entity-emitter `playSound(player, sound, …)` overload
- **`MessageManager` publication** — `messagesConfig` and the cached prefix are now `@Volatile`, so executor threads (request expiry) and Folia region threads reliably see the state after `/tpareload`
- **bStats chart callbacks** — now read the plugin's cached `@Volatile` config values instead of touching the non-thread-safe `FileConfiguration` from bStats' submission threads
- **bStats daily reset on Folia** — a server starting within ~50 ms of midnight produced a 0-tick delay, which Folia's scheduler rejects; now coerced to ≥1 tick

#### Correctness
- **`/back` into an unloaded world** — now fails gracefully with the "no previous location" message instead of throwing when the saved world has been unloaded (Multiverse etc.)
- **Player-name cache poisoning** — a lookup for an offline player permanently cached `"Unknown"` (and leaked the entry, since quit-cleanup had already run); failed lookups are no longer cached
- **`/tpainfo` Available RAM** — previously reported free space inside the currently committed heap; now reports true headroom (`max − used`)
- **Dotted sound keys not normalised** — `Block.Note_Block.Pling` style values threw inside `NamespacedKey` and silently fell back to the default sound; dotted keys are now lowercased like underscore keys

#### Performance
- **Startup no longer blocks on the update check** — the GitHub Releases request ran synchronously in `onEnable()` and could stall server startup by up to ~10 s on a slow network; it now runs on the async scheduler on both Paper and Folia

### Changed
- **JAR size reduced from ~3.0 MB to ~1.9 MB** — Adventure API and MiniMessage are no longer shaded into the JAR (`compileOnly`); Paper provides them natively, and bundling an unrelocated copy risked shadowing the server's newer Adventure on 26.x
- **Update notification message moved to `messages.yml`** — new `update.available` key (customizable/translatable, auto-merged into existing server files); the notification also respects `features.sounds` and uses the non-deprecated registry-based sound API
- **bStats bumped** from 3.0.2 to 3.1.0
- **`yotpa.admin` permission declared** in `plugin.yml` (default: OP, included in `yotpa.*`) — previously used by the update notifier but undeclared
- **`plugin.yml` version now injected from the build** — single source of truth in `build.gradle.kts`
- **Build cleanup** — removed the duplicate legacy Shadow plugin (`com.github.johnrengelman.shadow`) that was applied alongside its maintained fork
- **`plugin.yml` website URL fixed** to the actual repository

### Notes
- No new config keys, no migration — drop-in replacement for v1.6.0

---

## [1.6.0] - 2026-04-12

### 🎉 /back Command, Full Folia Support, Performance & Thread-Safety

### Added

#### /back Command
- **`/back` command** — teleports the player to their previous location after a completed TPA or death
- **Single-use** — the saved location is consumed on use; a second `/back` reports "no previous location" until a new one is set by the next TPA or death
- **Death location tracking** — `PlayerDeathEvent` automatically saves the death spot; `/back` returns players to where they died to collect items
- **Post-respawn notification** — a chat message reminds the player they can use `/back` after respawning (20-tick delay, Folia-aware scheduling)
- **Configurable cooldown** — `back-cooldown` in `config.yml` (default: 30s, set 0 to disable); prevents rapid cycling on death-heavy servers (OPs bypass via `yotpa.bypass.back-cooldown`)
- **Folia-compatible** — uses `teleportAsync()` with all post-teleport side effects dispatched via `player.scheduler.run()`
- **`yotpa.back`** permission (default: `true`)
- **`yotpa.bypass.back-cooldown`** permission (default: `op`)
- **New config key**: `back-cooldown: 30`
- **New messages**: `commands.back.no-location`, `commands.back.teleporting`, `commands.back.death-saved`, `commands.back.cooldown`

#### Update Notifications
- **Daily update check** — re-checks GitHub Releases every 24 hours while the server is running
- **Mid-session alerts** — if a new version is released while the server is up, all online OPs/admins are notified immediately (once per release, not once per check)
- **Silent when up to date** — daily check produces no output if nothing changed
- **Startup behaviour unchanged** — console banner + on-join OP notification still work as before
- **Task properly cancelled** — daily check task is cancelled in `onDisable()` via `updateChecker.shutdown()`

#### Folia Support
- **Full Folia compatibility** — plugin loads and runs correctly on Folia regionized servers
- **`folia-supported: true`** in `plugin.yml`
- **Runtime Folia detection** via `RegionizedServer` class check — auto-detected at startup, no config needed
- **Entity scheduler for countdowns** — `player.scheduler.runAtFixedRate()` on Folia instead of `BukkitScheduler`
- **`globalRegionScheduler`** for maintenance tasks and expired-request dispatch on Folia
- **`teleportAsync()`** on Folia to satisfy cross-region teleport requirements
- **`asyncScheduler.runAtFixedRate()`** for daily update check on Folia
- **`player.scheduler.runDelayed()`** for update join notification on Folia

#### Message System
- **Bundled message defaults** — `messages.yml` from the JAR is merged as defaults on every load; keys missing from the server file fall back automatically — no manual migration needed when new keys are added in future updates
- **`teleport.cancelled.destination-offline`** — new message when teleport is cancelled because the destination went offline

### Fixed

#### Thread-Safety (Folia)
- **`sendMessage()` from `thenAccept` callback** — previously called on an arbitrary async completion thread after `teleportAsync()`, violating Folia's thread-ownership model; now dispatched via `player.scheduler.run()` in both `performTeleport` and `handleBackCommand`

#### Memory Leaks
- **`TeleportData` held a live `Player` reference** — replaced `destination: Player` with `destinationUUID: UUID`; destination player is resolved fresh each tick via `Bukkit.getPlayer(UUID)`, eliminating zombie references and cross-region Player access on Folia
- **Destination offline mid-countdown** — teleport is now cancelled immediately and the teleporter notified if the destination disconnects during the countdown
- **TPA requests lingered after disconnect** — both sent and received pending requests are now removed immediately when a player quits, instead of waiting for the expiry timer
- **bStats daily reset task not cancellable** — task handle is now stored and cancelled in `onDisable()`
- **`cooldowns` and `playerNameCache` not cleaned on quit** — both maps now cleared immediately on `PlayerQuitEvent`

#### Bug Fixes
- **`performTeleport` Paper path ignored `teleport()` return value** — if another plugin cancelled the event, a false success message and sound played; now only fires when `teleport()` returns `true`
- **`bStatsTPA` wrong config key** — `titles_enabled` chart read `"titles.enabled"` (non-existent) instead of `"features.titles"`; chart now reports the correct value
- **Dead `if (isFolia)` branch in `startPaperBatchTask()`** — always `false` since the batch task is Paper-only; removed
- Fixed `UnsupportedOperationException` from `CraftScheduler` on Folia (`bStatsTPA`, `UpdateChecker`, `startMaintenanceTasks`)
- Fixed `UnsupportedOperationException: Must use teleportAsync` on Folia during teleport execution
- Fixed `[YoTPA] Message not found for path: commands.tpainfo.server-type` console warning

#### Performance
- **Cached `PerformanceSettings`** — computed once on enable/reload (`@Volatile cachedSettings`); hot paths no longer allocate a new settings object each call
- **Single batch countdown task on Paper** — one repeating task processes all active countdowns; scheduler overhead is O(1) at any player count
- **ConcurrentHashMap direct iterator in batch task** — eliminates per-tick `ArrayList` snapshot allocation
- **Sound objects cached at config load** — `Registry.SOUNDS.get()` called once per sound at load time; zero registry lookups at runtime per sound play
- **`AtomicInteger` worker thread counter** — replaces racy `Thread.activeCount()` for thread naming
- **`/tpainfo` active teleport count** — now reads from `teleportData.size` (accurate on both Paper and Folia)

### Removed

- **`commands.tpainfo.server-type`** message and display — redundant information

### 📦 Technical Details

#### Modified Files
- `YoTPA.kt` — `/back` command, UUID refactor in `TeleportData`, Folia scheduler, batch task, cached settings, thread-safety fixes, memory leak fixes, `updateChecker.shutdown()` in `onDisable()`
- `PlayerMoveListener.kt` — `onPlayerDeath` saves location, `onPlayerRespawn` sends delayed notification, `onPlayerQuit` cleanup
- `bStatsTPA.kt` — task handle stored, `shutdown()` added, `features.titles` config key fix, class renamed to `BStatsTPA`
- `UpdateChecker.kt` — daily 24-hour re-check, mid-session OP notification, Folia scheduler support, `shutdown()` added
- `MessageManager.kt` — bundled defaults merged on load, destination-offline message added
- `config.yml` — `back-cooldown: 30` added
- `messages.yml` — `commands.back.*` added, `teleport.cancelled.destination-offline` added, `commands.tpainfo.server-type` removed
- `plugin.yml` — `back` command, `yotpa.back`, `yotpa.bypass.back-cooldown` permissions, `folia-supported: true`

### 📝 Migration Guide

#### From 1.5.0 to 1.6.0

No breaking changes. Drop-in replacement.

1. Stop your server
2. Replace `YoTPA-1.5.0.jar` with `YoTPA-1.6.0.jar`
3. Start your server

Your existing `config.yml` and `messages.yml` are fully preserved. A `back-cooldown: 30` key will be added to `config.yml` automatically.

### ⚠️ Breaking Changes

**None.** Fully backward compatible with all existing configurations.

---

## [1.5.0] - 2026-04-05

### 🎉 Major Update - Multi-Version Support, JDK 25 & Update Checker

### Added

#### Update Checker
- **Automatic update notifications** - Plugin checks for new releases on startup via GitHub Releases API
- **Console alert** - Logs update banner with download link when a newer version is found
- **In-game notification** - OPs and players with `yotpa.admin` permission receive a clickable chat message on join
- **Modrinth download link** - Notification links directly to [modrinth.com/plugin/yotpa](https://modrinth.com/plugin/yotpa)
- **Async check** - Network request runs off the main thread, no server tick impact
- **Graceful failure** - Network errors are caught and logged as warnings without affecting startup

#### Version Compatibility
- **Minecraft 1.21.x – 26.1.x support** - Single JAR now runs on servers from 1.21 through Paper 26.1
- **JDK 25 development toolchain** - Project upgraded to compile with JDK 25 while targeting Java 21 bytecode for backwards compatibility

### Changed

#### Build & Toolchain
- **Centralized version management** - `VersionConfig.PLUGIN_VERSION` in `build.gradle.kts` is the single source of truth for the plugin version
- **Kotlin upgraded to 2.3.0** - Required for proper JDK 25 toolchain support
- **JVM toolchain set to JDK 25** - Development now uses JDK 25
- **Bytecode target stays at Java 21** - Ensures the plugin runs on both 1.21.x (Java 21) and 26.1.x (Java 25) servers
- **Explicit `options.release = 21`** - Java compilation also locked to Java 21 output


### 📦 Technical Details

#### API Compatibility
- Minimum supported Minecraft: `1.21.x`
- Maximum tested Minecraft: `26.1.x` (Paper alpha)
- Java runtime requirement: 21+ (1.21.x servers) / 25+ (26.1.x servers)
- Kotlin version: 2.3.0
- JVM bytecode target: Java 21

#### New Files
- `UpdateChecker.kt` - Async update checking and OP notification system

#### Build Configuration
```kotlin
object VersionConfig {
    const val PLUGIN_VERSION = "1.5.0"
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}
```

### 📝 Migration Guide

#### From 1.4.0 to 1.5.0

No breaking changes. Drop-in replacement for both 1.21.x and 26.1.x servers.

1. Stop your server
2. Replace `YoTPA-1.4.0.jar` with `YoTPA-1.5.0.jar`
3. Start your server

Your existing `config.yml` and `messages.yml` are fully preserved.

### ⚠️ Breaking Changes

**None.** Fully backward compatible with all existing configurations.

---

## [1.4.0] - 2025-01-10

### 🎉 Major Release - Full Internationalization & Modern Paper API

This is a significant update bringing full internationalization support, modern Paper API usage, and numerous improvements to make YoTPA the best teleport plugin for Minecraft 1.21+.

### Added

#### Internationalization System
- **Full multi-language support** - Complete message customization system via `messages.yml`
- **MessageManager class** - Centralized message handling with MiniMessage support
- **10+ language examples** - Pre-made translations for Spanish, French, German, Japanese, Russian, Portuguese, Thai, Chinese, Korean, and more
- **Placeholder system** - Dynamic content replacement in messages (`{player}`, `{target}`, `{seconds}`, etc.)
- **Rich text formatting** - Full MiniMessage support with gradients, rainbow effects, hex colors
- **Customizable prefix** - Change the `[YoTPA]` prefix to anything you want
- **Live reload** - Use `/tpareload` to reload both `config.yml` and `messages.yml` without restart

#### Modern Paper API Implementation
- **PersistentDataContainer** - Replaced deprecated metadata system with Paper's recommended approach
- **Automatic cleanup** - Data automatically cleaned up when players disconnect
- **Type-safe storage** - NamespacedKey system prevents data conflicts
- **Better error handling** - Improved error messages and debugging information

### 🔧 Changed

#### Core Improvements
- **Refactored data storage** - Migrated from deprecated metadata to PersistentDataContainer
- **Improved message system** - All hardcoded messages moved to `messages.yml`
- **Better validation** - Enhanced config validation with detailed error messages
- **Cleaner code** - Removed code duplication and improved maintainability

#### Performance Enhancements
- **Lower memory footprint** - PersistentDataContainer is more efficient than metadata
- **Faster lookups** - Direct data access instead of metadata iteration
- **Better cleanup** - Explicit cleanup prevents memory leaks
- **Optimized parsing** - Improved MiniMessage parsing with caching

#### User Experience
- **Better error messages** - Clear, actionable error messages with recommendations
- **Improved feedback** - Config validation now shows warnings and suggestions
- **Live reload support** - Both config and messages can be reloaded without restart
- **Color validation** - Validates MiniMessage colors during config reload

### 🐛 Fixed

#### Critical Fixes
- **Removed deprecated code** - No more deprecation warnings from metadata system
- **Fixed memory leaks** - Proper cleanup of player data on disconnect
- **Fixed race conditions** - Thread-safe data access with proper synchronization

#### Code Quality Fixes
- **Removed dead code** - Deleted unused `hasPendingTeleport()` method
- **Fixed duplicate code** - Extracted common validation logic to helper methods
- **Fixed unused parameters** - Proper exception handling with `_` for ignored exceptions
- **Improved documentation** - Added notes for cross-file method usage

#### Bug Fixes
- **Fixed gradient parsing** - Better error handling for invalid MiniMessage formats
- **Fixed color names** - Updated documentation to show valid/invalid color names
- **Fixed config validation** - Better YAML error handling prevents crashes
- **Fixed sound loading** - Improved error recovery when sounds are invalid

### 🔄 Deprecated

- **Metadata system** - Now uses PersistentDataContainer (migration automatic)
- **FixedMetadataValue class** - Removed in favor of modern approach

### 🗑️ Removed

- **FixedMetadataValue.kt** - No longer needed with PersistentDataContainer
- **Hardcoded messages** - All moved to `messages.yml`
- **Unused methods** - Cleaned up dead code

### 📦 Technical Details

#### API Changes
- Minimum Paper version: `1.21-R0.1-SNAPSHOT`
- Maximum tested version: `1.21.11-R0.1-SNAPSHOT`
- Java version: 21 (required)
- Kotlin version: 2.1.20

#### Dependencies Updated
```kotlin
compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
implementation("net.kyori:adventure-api:4.20.0")
implementation("net.kyori:adventure-text-minimessage:4.20.0")
implementation("org.bstats:bstats-bukkit:3.0.2")
```

#### New Files
- `messages.yml` - Customizable messages configuration
- `MessageManager.kt` - Message handling and i18n system

#### Modified Files
- `YoTPA.kt` - Complete refactor with modern Paper API
- `PlayerMoveListener.kt` - Updated to use PersistentDataContainer
- `README.md` - Comprehensive documentation update
- `build.gradle.kts` - Updated to Paper 1.21.11

### 📝 Migration Guide

#### From 1.3.0 to 1.4.0

1. **Backup your config:**
2. **Replace the JAR file:**
   - Stop your server
   - Replace `YoTPA-1.3.0.jar` with `YoTPA-1.4.0.jar`
   - Start your server

3. **New files will be created:**
   - `messages.yml` - Customize your messages here!
   - Your `config.yml` settings are preserved

4. **Customize messages (optional):**
   ```bash
   # Edit plugins/YoTPA/messages.yml
   # Change to your language or customize colors
   ```

5. **Reload configuration:**
   ```
   /tpareload
   ```

**Note:** Migration is automatic! The plugin will work immediately with default English messages. Customize `messages.yml` if you want different colors or languages.

### 🌍 Internationalization Examples

#### Spanish
```yaml
prefix: "<green><bold>[<aqua>YoTPA</aqua>]</bold></green> "
commands:
  tpa:
    sent: "<green>Solicitud enviada a <yellow>{target}</yellow></green>"
```

#### Thai
```yaml
prefix: "<green><bold>[<aqua>YoTPA</aqua>]</bold></green> "
teleport:
  countdown:
    title: "<gradient:green:aqua><bold>กำลังทำการวาร์ป...</bold></gradient>"
```

#### Japanese
```yaml
prefix: "<green><bold>[<aqua>YoTPA</aqua>]</bold></green> "
commands:
  tpa:
    sent: "<green><yellow>{target}</yellow>にテレポートリクエストを送信しました</green>"
```

### 🎨 MiniMessage Features

Now fully supported with proper validation:

```yaml
# Gradients
title: "<gradient:green:aqua>Teleporting...</gradient>"

# Rainbow effect
title: "<rainbow>Teleporting...</rainbow>"

# Hex colors
message: "<#50C878>Success!</#50C878>"

# Multiple effects
title: "<gradient:gold:yellow><bold><italic>VIP Teleport</italic></bold></gradient>"
```

### ⚠️ Breaking Changes

**None!** This release is fully backward compatible. Your existing `config.yml` will work without modifications.

The only change is that messages are now in `messages.yml` instead of being hardcoded, but default English messages are provided automatically.

### 🐛 Known Issues

None currently known. Please report any issues on [GitHub Issues](https://github.com/PhyschicWinter9/YoTPA/issues).

---

## [1.3.0] - 2025-10-02

### Added
- Adaptive performance system with 4 optimization modes
- Automatic RAM detection and optimization
- Custom sound effects configuration
- Movement threshold configuration
- Config validation with warnings
- `/tpainfo` command for system information
- `/tpastats` command for statistics
- bStats integration for usage metrics

### Changed
- Improved countdown system with better timing
- Enhanced thread management
- Better cache management
- Optimized data structures

### Fixed
- Memory leaks in teleport tasks
- Race conditions in concurrent teleports
- Sound registry issues

---

## [1.2.0] - 2025-05-12

### Added
- Added bStats
- Support 1.21 - 1.21.5

---

## [1.1.0] - 2025-05-12

### Added
- Initial release to Public

---

## Version History Summary

| Version | Release Date | Major Features |
|---------|--------------|----------------|
| 1.6.0 | 2026-04-12 | /back Command, Full Folia Support, Thread-Safety & Memory Leak Fixes |
| 1.5.0 | 2026-04-05 | Multi-Version Support (1.21.x–26.1.x), JDK 25 |
| 1.4.0 | 2025-01-10 | Internationalization, Modern Paper API |
| 1.3.0 | 2025-10-02 | Adaptive Performance, Auto-optimization |
| 1.2.0 | 2025-05-12 | Timeout & Cooldown System |
| 1.1.0 | 2025-05-12 | Movement Detection, Titles |
| 1.0.0 | 2025-05-12 | Initial Release |

---

## Upgrade Guide

### From Any Version to 1.6.0

1. Backup your `config.yml`
2. Stop your server
3. Replace the JAR file
4. Start your server
5. New `messages.yml` will be created automatically if missing
6. Customize `messages.yml` if desired
7. Use `/tpareload` to apply changes

---

## Support & Feedback

- **Bug Reports:** [GitHub Issues](https://github.com/PhyschicWinter9/YoTPA/issues)
- **Feature Requests & Ideas:** [GitHub Issues](https://github.com/PhyschicWinter9/YoTPA/issues)
- **Documentation:** [README.md](README.md)

---

<p align="center">
  <strong>Thank you for using YoTPA!</strong><br>
</p>