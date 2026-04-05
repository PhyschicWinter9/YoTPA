# Changelog

All notable changes to YoTPA will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.5.0] - 2026-04-05

### 🎉 Major Update - Multi-Version Support & JDK 25

### Added

#### Multi-Version Compatibility
- **Minecraft 1.21.x – 26.1.x support** - Single JAR now runs on servers from 1.21 through Paper 26.1
- **JDK 25 development toolchain** - Project upgraded to compile with JDK 25 while targeting Java 21 bytecode for backwards compatibility

### Changed

#### Build & Toolchain
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

#### Build Configuration
```kotlin
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
| 1.5.0 | 2026-04-05 | Multi-Version Support (1.21.x–26.1.x), JDK 25 |
| 1.4.0 | 2025-01-10 | Internationalization, Modern Paper API |
| 1.3.0 | 2025-10-02 | Adaptive Performance, Auto-optimization |
| 1.2.0 | 2025-05-12 | Timeout & Cooldown System |
| 1.1.0 | 2025-05-12 | Movement Detection, Titles |
| 1.0.0 | 2025-05-12 | Initial Release |

---

## Upgrade Guide

### From Any Version to 1.5.0

1. Backup your `config.yml`
2. Stop your server
3. Replace the JAR file
4. Start your server
5. New `messages.yml` will be created automatically
6. Customize `messages.yml` if desired
7. Use `/tpareload` to apply changes

---

## Support

- **Bug Reports:** [GitHub Issues](https://github.com/PhyschicWinter9/YoTPA/issues)
- **Feature Requests:** [GitHub Discussions](https://github.com/PhyschicWinter9/YoTPA/discussions)
- **Documentation:** [README.md](README.md)

---

<p align="center">
  <strong>Thank you for using YoTPA!</strong><br>
</p>