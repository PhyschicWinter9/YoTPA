![Logo](screenshot/yotpa.png)

# YoTPA

YoTPA is a lightweight and powerful teleport request plugin with adaptive performance optimization. Simple to use, easy to configure, and built for modern Minecraft servers.

<!-- Build & Version Badges -->
[![Build](https://github.com/PhyschicWinter9/YoTPA/actions/workflows/build.yml/badge.svg)](https://github.com/PhyschicWinter9/YoTPA/actions/workflows/build.yml)
[![GitHub release (latest by date)](https://img.shields.io/github/v/release/PhyschicWinter9/YoTPA)](https://github.com/PhyschicWinter9/YoTPA/releases)
[![GitHub all releases](https://img.shields.io/github/downloads/PhyschicWinter9/YoTPA/total)](https://github.com/PhyschicWinter9/YoTPA/releases)
[![Paper API](https://img.shields.io/badge/Paper--API-1.21.5-yellow)](https://papermc.io/)
[![JDK](https://img.shields.io/badge/JDK-21-red)](https://www.oracle.com/java/technologies/downloads/#java21)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.20-blue)](https://kotlinlang.org/)

## Features

### Adaptive Performance System
The plugin automatically detects your server's available RAM and optimizes itself accordingly - zero configuration needed!

**4 Performance Modes:**
- **ULTRA_LIGHT** - For 512 MB - 1 GB RAM (Free/Cheap VPS)
    - No async executor (Bukkit scheduler only)
    - Minimal caching
    - 30s expiration checks
    - Best for: 5-10 players, minimal plugins

- **LIGHT** - For 1-2 GB RAM (Low-end servers)
    - 2 executor threads
    - Moderate caching
    - 20s expiration checks
    - Best for: 10-15 players, light plugins

- **BALANCED** - For 2-4 GB RAM (Mid-range servers)
    - 3 executor threads
    - Full caching
    - 10s expiration checks
    - Best for: 15-25 players, moderate plugins

- **HIGH_PERFORMANCE** - For 4+ GB RAM (High-end servers)
    - 4 executor threads
    - Full caching
    - 5s expiration checks
    - Best for: 25+ players, any plugins

**Performance Benefits:**
- 30-70% less memory usage
- 25-60% less CPU usage
- Up to 83% faster maintenance tasks
- Dynamic cache and threading optimization

### Core Features

- **Simple TPA System** - Request to teleport to other players or invite them to you
- **Countdown Timer** - Configurable delay before teleporting with visual feedback
- **Title Display** - Clear visual indication of teleport countdown
- **Movement Detection** - Teleport cancels if player moves during countdown
- **Custom Sound Effects** - Fully customizable audio feedback using Minecraft's sound registry
- **Config Validation** - Prevents server crashes from broken configs with detailed error messages
- **Permission System** - Fine-grained access control
- **Request Management** - Toggle receiving requests, cancel outgoing requests
- **Multi-Threading Support** - Thread-safe for 500+ concurrent players
- **Customizable Messages** - Fully localizable and configurable messages

### 🌍 Internationalization (NEW in 1.4.0)

Customize all plugin messages in any language with full MiniMessage support:

```yaml
prefix: "[YoTPA] "

commands:
  tpa:
    sent: "Teleport request sent to {target}"
    received: "{requester} wants to teleport to you."
```
**MiniMessage Features:**
- Rich text formatting (bold, italic, underlined)
- Color gradients: `<gradient:green:aqua>text</gradient>`
- Rainbow effect: `<rainbow>text</rainbow>`
- Hex colors: `<#FF5733>text</#FF5733>`

### Custom Sound Effects

Customize all plugin sounds using Minecraft's sound IDs:

```yaml
sounds:
  countdown: "block.note_block.pling"
  success: "entity.enderman.teleport"
  cancel: "entity.villager.no"
  request: "entity.experience_orb.pickup"
```

Test sounds in-game: `/playsound minecraft:block.note_block.pling master @s`

Full sound list: https://minecraft.wiki/w/Sounds.json

### Performance Monitoring

Use `/tpainfo` to view real-time statistics:
- Current performance mode (auto-detected based on RAM)
- Optimization level and settings
- Active requests and ongoing teleports
- Memory usage statistics
- Plugin version information

## Documentation

### YoTPA.kt
Core class that contains most of the plugin logic:
- Command handling
- Configuration management with validation
- Teleportation process with movement detection
- Countdown implementation
- Message formatting and customization
- Adaptive performance optimization

## Getting Started

### Prerequisites

- **Server Software:** Paper, Spigot, or any Paper/Spigot fork
- **Minecraft Version:** 1.21 - 1.21.11
- **Java Version:** JDK 21 or higher
- **Development:** Git, basic knowledge of Kotlin

### Development Environment Setup

1. Fork the repository on GitHub
2. Clone your fork locally:
   ```bash
   git clone https://github.com/PhyschicWinter9/YoTPA.git
   cd YoTPA
   ```
3. Set up the upstream remote:
   ```bash
   git remote add upstream https://github.com/PhyschicWinter9/YoTPA.git
   ```
4. Create a branch for your work:
   ```bash
   git checkout -b feature/your-feature-name
   ```

### Building the Project

```bash
./gradlew clean build
```

The compiled JAR will be in `build/libs/` directory.

## Installation

1. Download the latest JAR from the [Releases](https://github.com/PhyschicWinter9/YoTPA/releases) page
2. Place the JAR in your server's `plugins` folder
3. Restart your server
4. That's it! The plugin will auto-configure based on your server's specs

The plugin will automatically generate a `config.yml` file in `plugins/YoTPA/` with sensible defaults.

## Configuration Overview

YoTPA's `config.yml` file allows you to customize various aspects of the plugin:

- Request timeout duration
- Cooldown between requests
- Teleport delay countdown
- Performance mode settings
- Sound effects for different actions
- Feature toggles (statistics, bStats, titles, sounds)

## Default Configuration File

```yaml
# YoTPA Configuration
# Smart Auto-Optimization for All Server Sizes

# Request timeout in seconds (how long before a request expires)
# Recommended: 30-120 seconds
request-timeout: 60

# Request cooldown in seconds (how long before sending another request)
# Recommended: 15-60 seconds
request-cooldown: 30

# Teleport delay in seconds (countdown before actual teleport)
# Recommended: 3-10 seconds
teleport-delay: 5

# Performance mode settings
performance:
  # Options: AUTO, ULTRA_LIGHT, LIGHT, BALANCED, HIGH_PERFORMANCE
  mode: AUTO

# Sound effects (use Minecraft sound names)
sounds:
  countdown: "block.note_block.pling"
  success: "entity.enderman.teleport"
  cancel: "entity.villager.no"
  request: "entity.experience_orb.pickup"

# Feature toggles
features:
  statistics: true
  bstats: true
  titles: true
  sounds: true
```
### Messages Configuration (messages.yml)
```yaml
# Customize your prefix
prefix: "[YoTPA] "

# All messages are customizable
commands:
  tpa:
    usage: "Usage: /tpa "
    sent: "Teleport request sent to {target}"
    received: "{requester} wants to teleport to you."

teleport:
  countdown:
    title: "Teleporting..."
    subtitle: "Don't move!"
    message: "Teleporting in {seconds} seconds"
```
## Customization Examples

### Change Language to Spanish

Edit `messages.yml`:
```yaml
prefix: "[YoTPA] "

commands:
  tpa:
    usage: "Uso: /tpa "
    sent: "Solicitud enviada a {target}"
    received: "{requester} quiere teletransportarse a ti."
```

Then use `/tpareload` to apply changes!

### Add Beautiful Gradients

```yaml
prefix: "✦ YoTPA ✦ "

teleport:
  countdown:
    title: "Teleporting..."
    message: "Teleporting in {seconds} seconds"
```

### Customize Colors

```yaml
prefix: "[TPA] "

commands:
  tpa:
    sent: "Request sent to {target}"
```

### Use Hex Colors

```yaml
prefix: "[YoTPA] "

teleport:
  success: "Teleported to {target} successfully!"
```

**Placeholders:**
- `{player}` - Player name
- `{target}` - Target player name
- `{requester}` - Requester player name
- `{seconds}` - Number of seconds
- `{cooldown}` - Cooldown duration

## Configuration Sections Explained

### General Settings

- **request-timeout** - How long (in seconds) teleport requests remain valid before expiring
    - Recommended: 30-120 seconds
    - Default: 60 seconds

- **request-cooldown** - Time (in seconds) a player must wait before sending another teleport request
    - Recommended: 15-60 seconds
    - Default: 30 seconds
    - Prevents request spam

- **teleport-delay** - Countdown time (in seconds) before the actual teleportation occurs
    - Recommended: 3-10 seconds
    - Default: 5 seconds
    - Minimum: 1 second
    - Player must stay still during this time

### Performance Settings

- **performance.mode** - Controls how the plugin optimizes itself
    - **AUTO** - Automatically detects RAM and selects best mode (RECOMMENDED)
    - **ULTRA_LIGHT** - For 512 MB - 1 GB RAM servers
    - **LIGHT** - For 1-2 GB RAM servers
    - **BALANCED** - For 2-4 GB RAM servers
    - **HIGH_PERFORMANCE** - For 4+ GB RAM servers

The plugin will automatically adjust:
- Thread pool size
- Cache settings
- Task scheduling frequency
- Movement detection threshold

### Sound Settings

All sounds use Minecraft's sound registry format:
- Use lowercase with dots (e.g., `block.note_block.pling`)
- Do not include `minecraft:` prefix
- Test sounds in-game: `/playsound minecraft:block.note_block.pling master @s`
- Full sound list: https://minecraft.wiki/w/Sounds.json

**Available sound options:**
- **countdown** - Plays every second during teleport countdown
- **success** - Plays when teleportation completes successfully
- **cancel** - Plays when teleport is cancelled
- **request** - Plays when a player receives a teleport request

### Feature Toggles

- **statistics** - Enable or disable statistics tracking (used by `/tpastats`)
- **bstats** - Enable or disable bStats metrics (helps developers)
- **titles** - Enable or disable title animations during countdown
- **sounds** - Enable or disable all sound effects

### Config Validation

When reloading the config with `/tpareload`, the plugin will:
- Validate YAML syntax
- Check all values are in valid ranges
- Verify sounds exist in Minecraft's registry
- Show detailed error messages with recommendations
- Prevent applying broken configs to avoid crashes

Example validation output:
```
Configuration validation failed!
✗ teleport-delay must be at least 1 second
✗ Sound 'countdown' (invalid.sound) not found in registry

Warnings:
• request-timeout (5) is very low, recommended: 30-120

Config not applied. Fix errors and try again.
Using previous configuration.
```

## Optimization Tips

### For FREE/LOW-SPEC servers (512 MB - 1 GB):
1. Keep mode as AUTO or ULTRA_LIGHT
2. Limit total plugins to 5-8
3. Set view-distance to 4 in server.properties
4. Use Paper instead of Spigot
5. Limit players to 5-10 concurrent

### For MID-RANGE servers (2-4 GB):
1. Use AUTO or BALANCED mode
2. Can run 10-15 plugins
3. view-distance 6-8
4. 15-25 players should be fine

### For HIGH-END servers (4+ GB):
1. Use AUTO or HIGH_PERFORMANCE mode
2. No significant plugin limitations
3. view-distance 10-12
4. 25+ players supported

## How to Edit and Reload

1. Edit the `plugins/YoTPA/config.yml` file
2. Save your changes
3. Use `/tpareload` in-game to apply changes with validation
4. The plugin will notify you of any errors or warnings

## Commands Overview

| Command | Description | Permission |
| :------ | :---------- | :--------- |
| `/tpa <player>` | Request to teleport to another player | `yotpa.tpa` |
| `/tpaccept` | Accept a pending teleport request | `yotpa.tpaccept` |
| `/tpadeny` | Deny a pending teleport request | `yotpa.tpadeny` |
| `/tpahere <player>` | Request a player to teleport to you | `yotpa.tpahere` |
| `/tpacancel` | Cancel your outgoing teleport request | `yotpa.tpacancel` |
| `/tpatoggle` | Toggle receiving teleport requests | `yotpa.tpatoggle` |
| `/tpareload` | Reload the plugin configuration | `yotpa.reload` |
| `/tpainfo` | View plugin information and statistics | `yotpa.info` |
| `/tpastats` | View teleport statistics | `yotpa.stats` |

**Default:** All permissions are granted to all players by default.

## Detailed Commands

### TPA Command

Send a request to teleport to another player.

```
/tpa <player>
```

| Parameter | Type | Description |
| :-------- | :--- | :---------- |
| `player` | `string` | **Required**. The player name you want to teleport to |

**Examples:**
```
/tpa Steve      # Request to teleport to player named Steve
/tpa Alex123    # Request to teleport to player named Alex123
```

![TPA Command](screenshot/tpav2.gif)

**Notes:**
- You cannot send a request to yourself
- There is a configurable cooldown between sending requests (default: 30 seconds)
- Request will expire after a configurable time (default: 60 seconds)
- Target player will receive a notification with clickable accept/deny buttons

---

### TPAccept Command

Accept a pending teleport request from another player.

```
/tpaccept
```

No parameters required - accepts the most recent teleport request.

**Examples:**
```
/tpaccept    # Accept the pending teleport request
```

![TPAccept Command](screenshot/tpaacceptv2.gif)

**Notes:**
- Only works if you have a pending teleport request
- After accepting, the teleportation will begin with a countdown
- The player must stay still during the countdown or it will be cancelled
- If accepting a `/tpa` request, the requester will teleport to you
- If accepting a `/tpahere` request, you will teleport to the requester

---

### TPADeny Command

Deny a pending teleport request from another player.

```
/tpadeny
```

No parameters required - denies the most recent teleport request.

**Examples:**
```
/tpadeny    # Deny the pending teleport request
```

![TPADeny Command](screenshot/tpadenyv2.gif)

**Notes:**
- Only works if you have a pending teleport request
- The requester will be notified that their request was denied
- Request is immediately removed from the system

---

### TPAHere Command

Request another player to teleport to your location.

```
/tpahere <player>
```

| Parameter | Type | Description |
| :-------- | :--- | :---------- |
| `player` | `string` | **Required**. The player name you want to request to teleport to you |

**Examples:**
```
/tpahere Steve      # Request Steve to teleport to you
/tpahere Alex123    # Request Alex123 to teleport to you
```

![TPAHere Command](screenshot/tpaherev2.gif)

**Notes:**
- You cannot send a request to yourself
- There is a configurable cooldown between sending requests (default: 30 seconds)
- Request will expire after a configurable time (default: 60 seconds)
- The target player must accept with `/tpaccept`

---

### TPACancel Command

Cancel your outgoing teleport request.

```
/tpacancel
```

No parameters required - cancels your most recent outgoing request.

**Examples:**
```
/tpacancel    # Cancel your pending request
```

**Notes:**
- Only works if you have an active outgoing request
- The target player will be notified that the request was cancelled
- Useful if you sent a request by mistake

---

### TPAToggle Command

Toggle whether you want to receive teleport requests.

```
/tpatoggle
```

No parameters required - toggles your request reception status.

**Examples:**
```
/tpatoggle    # Toggle receiving teleport requests on/off
```

**Notes:**
- When toggled off, other players cannot send you teleport requests
- You will receive a message showing your current status
- Your setting persists across server restarts

---

### TPAReload Command

Reload the plugin configuration with validation.

```
/tpareload
```

No parameters required - reloads and validates the configuration file.

**Examples:**
```
/tpareload    # Reload the plugin configuration
```

![TPAReload Command](screenshot/tpareloadv2.gif)

**Notes:**
- Requires the `yotpa.reload` permission (default: op only)
- Validates YAML syntax and all configuration values
- Checks that all sounds exist in Minecraft's registry
- Shows detailed errors if config is invalid
- Only applies config if all validation passes
- Useful after making changes to the config.yml file

---

### TPAInfo Command

View plugin information and real-time statistics.

```
/tpainfo
```

No parameters required - displays plugin information.

**Examples:**
```
/tpainfo    # View plugin info and stats
```

**Shows:**
- Current performance mode (auto-detected based on RAM)
- Optimization level and settings
- Active teleport requests
- Ongoing teleports
- Memory usage statistics
- Plugin version

**Notes:**
- Useful for monitoring plugin performance
- Helps diagnose issues
- Shows how the plugin has optimized itself for your server

---

### TPAStats Command

View teleport statistics and usage data.

```
/tpastats
```

No parameters required - displays teleport statistics.

**Examples:**
```
/tpastats    # View teleport statistics
```

**Shows:**
- Total teleport requests sent
- Total teleport requests received
- Success rate
- Most active players
- Other usage metrics

**Notes:**
- Requires `features.statistics: true` in config
- Statistics are tracked per player
- Data persists across server restarts

## Performance

YoTPA is built with performance in mind:

- **Lightweight** - Minimal impact on server TPS
- **Thread-Safe** - No lag from concurrent operations using ConcurrentHashMap
- **Memory Efficient** - Adapts to your server's resources automatically
- **Async Operations** - Non-blocking task execution
- **Lock-Free** - No race conditions or deadlocks

Tested and optimized for servers with **500+ concurrent players**.

## How to Contribute

### Reporting Bugs

1. Check if the bug has already been reported in [Issues](https://github.com/PhyschicWinter9/YoTPA/issues)
2. If not, create a new issue with:
    - Clear description of the bug
    - Steps to reproduce
    - Expected vs. actual behavior
    - Server version, plugin version, and any relevant configuration

### Suggesting Enhancements

1. Open a new issue describing the feature
2. Explain why this enhancement would be useful
3. Suggest an implementation approach if possible

### Pull Requests

1. Create a branch from `main` with a descriptive name
2. Make your changes
3. Run tests and ensure the build passes
4. Submit a pull request with:
    - Reference to any related issues
    - Description of changes
    - Screenshots if applicable

## License

YoTPA is released under the MIT License. See the [LICENSE](LICENSE) file for details.

## Credits

- **Developer**: PhyschicWinter9
- **Contributors**: [List of contributors](https://github.com/PhyschicWinter9/YoTPA/graphs/contributors)
- **Special Thanks**: RELAXLIKES

<p align="center">Made with ❤️ for the Minecraft community</p>