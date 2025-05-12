
![Logo](https://media.relaxlikes.com/yotpa/yotpa.png)


# YoTPA

YoTPA is a plug-in to teleport to other people or let other people come to us. It is easy to use and very simple. The configuration is not complicated.

<!-- Build & Version Badges -->
[![Build](https://github.com/PhyschicWinter9/YoTPA/actions/workflows/build.yml/badge.svg)](https://github.com/PhyschicWinter9/YoTPA/actions/workflows/build.yml)
[![GitHub release (latest by date)](https://img.shields.io/github/v/release/PhyschicWinter9/YoTPA)](https://github.com/PhyschicWinter9/YoTPA/releases)
[![GitHub all releases](https://img.shields.io/github/downloads/PhyschicWinter9/YoTPA/total)](https://github.com/PhyschicWinter9/YoTPA/releases)
[![Paper API](https://img.shields.io/badge/Paper--API-1.21.5-yellow)](https://papermc.io/)
[![JDK](https://img.shields.io/badge/JDK-21-red)](https://www.oracle.com/java/technologies/downloads/#java21)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.20-blue)](https://kotlinlang.org/)

<!-- Platform & Language Badges -->


## Features

- **Simple TPA System** - Request to teleport to other players
- **Countdown Timer** - Configurable delay before teleporting
- **Title Display** - Clear visual indication of teleport countdown
- **Movement Detection** - Teleport cancels if player moves
- **Sound Effects** - Audible feedback for all actions
- **Permission System** - Fine-grained access control


## Documentation

### YoTPA.kt
Core class that contains most of the plugin logic:
- Command handling
- Configuration management
- Teleportation process
- Countdown implementation
- Message formatting

### PlayerMoveListener.kt
Handles player movement detection:
- Monitors player position changes
- Cancels teleportation if movement detected
- Handles edge cases like player disconnect


## Getting Started

### Prerequisites

- JDK 21
- Git
- Basic knowledge of Kotlin
- Familiarity with Minecraft/Paper plugin development

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

1. Download the latest JAR from the [Releases](https://github.com/yourusername/YoTPA/releases) page
2. Place the JAR in your server's `plugins` folder
3. Restart your server or use `/reload confirm`
4. Configure the plugin in `plugins/YoTPA/config.yml` (generated after first run)
   ## Configuration Overview

YoTPA's `config.yml` file allows you to customize various aspects of the plugin:

- Request timeout duration
- Cooldown between requests
- Teleport delay countdown
- Sound effects for different actions
- Title display settings

## Default Configuration File

```yaml
# YoTPA Configuration
# Plugin by PhyschicWinter & RELAXLIKES

# Server name that appears in messages
server-name: "RELAXLIKES"

# Time in seconds before a teleport request expires
request-timeout: 60

# Time in seconds before a player can make another teleport request
request-cooldown: 30

# Time in seconds the player must wait (countdown) before teleporting
teleport-delay: 5
```

## Configuration Sections Explained

### General Settings

- `server-name`: Your server name for display purposes
- `request-timeout`: How long (in seconds) teleport requests remain valid
- `request-cooldown`: Time (in seconds) before a player can send another request
- `teleport-delay`: Countdown time (in seconds) before teleportation happens

## How to Edit and Reload

1. Edit the `plugins/YoTPA/config.yml` file
2. Save your changes
3. Use `/tpareload` in-game or restart your server to apply changes## Commands Overview

| Command | Description | Permission |
| :------ | :---------- | :--------- |
| `/tpa <player>` | Request to teleport to another player | `yotpa.tpa` |
| `/tpaccept` | Accept a pending teleport request | `yotpa.tpaccept` |
| `/tpadeny` | Deny a pending teleport request | `yotpa.tpadeny` |
| `/tpahere <player>` | Request a player to teleport to you | `yotpa.tpahere` |
| `/tpareload` | Reload the plugin configuration | `yotpa.reload` |

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

### TPAReload Command

Reload the plugin configuration.

```
/tpareload
```

No parameters required - reloads the configuration file.

**Examples:**
```
/tpareload    # Reload the plugin configuration
```

![TPAReload Command](screenshot/tpareloadv2.gif)

**Notes:**
- Requires the `yotpa.reload` permission (default: op only)
- Useful after making changes to the config.yml file
- Reloads sound settings, message texts, timeouts, and other configuration values
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
- **Contributors**: [List of contributors](https://github.com/yourusername/YoTPA/graphs/contributors)
<p align="center">Made with ❤️ for the Minecraft community</p>
