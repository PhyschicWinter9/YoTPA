### Changelog v1.6.1

**Folia Thread-Safety Fixes:**
- Teleport countdown now starts on the teleporter's own region thread — fixes cross-region location/title access when a plain `/tpa` is accepted
- Closed a race where moving during the exact moment a countdown started could leave it running (and teleporting) after it was cancelled
- Sounds played to the other player no longer read their location from a foreign region thread
- Message system and bStats charts hardened against cross-thread visibility issues after `/tpareload`

**Bug Fixes:**
- `/back` into a world that has since been unloaded now fails gracefully instead of erroring
- `/tpainfo` "Available RAM" now shows true headroom instead of a misleading low number
- Offline players no longer get permanently cached as "Unknown" in expiry messages
- Sound names like `Block.Note_Block.Pling` are now normalised instead of silently falling back to the default sound
- Update notification now respects the `features.sounds` toggle

**Improvements:**
- Server startup no longer waits on the update check (previously up to ~10 s on a slow network)
- JAR is ~35% smaller (1.9 MB, down from 3.0 MB) — Adventure is provided by Paper and no longer bundled
- Update notification message is now customizable/translatable via `messages.yml` (`update.available`, auto-merged into existing files)
- `yotpa.admin` (update notifications, default: OP) is now properly declared
- bStats updated to 3.1.0

**Server Compatibility:**
- **Fully Tested:** Paper 1.21.x – 26.2.x, Folia 1.21.x – 26.1.x, Purpur 1.21.x
- **Should Work:** Pufferfish, and any Paper/Folia fork
- **Requires:** Java 21+ (1.21.x servers) / Java 25+ (26.x servers)

**Breaking Changes:**
- None. Drop-in replacement for v1.6.0 — no config or message migration needed.

**Migration from v1.6.0:**
1. Stop your server
2. Replace `YoTPA-1.6.0.jar` with `YoTPA-1.6.1.jar`
3. Start your server — existing `config.yml` and `messages.yml` are fully preserved

**Feedback & Ideas:** https://github.com/PhyschicWinter9/YoTPA/issues

---

### Changelog v1.6.0

**New Features:**
- `/back` command — teleports you to your previous location after a TPA or death (single-use, location is consumed on use)
- Death location tracking — death spot saved automatically so you can `/back` after respawning to collect your items
- Configurable `/back` cooldown — `back-cooldown` in `config.yml` (default: 30s, set 0 to disable)
- Daily update notifications — plugin re-checks for new releases every 24 hours; online OPs/admins are notified once when a new version is found, completely silent if nothing changed
- Full Folia regionized-server support — auto-detected at startup, no configuration needed

**Improvements:**
- Thread-safety: post-teleport `sendMessage` and `playSound` now correctly dispatched on the player's region thread on Folia
- `TeleportData` now stores destination as `UUID` instead of a live `Player` reference — eliminates zombie references and cross-region access on Folia
- `PerformanceSettings` cached on enable/reload — no per-tick object allocation on hot paths
- Single batch task drives all Paper countdowns — O(1) scheduler overhead at any player count
- Sound objects resolved once at config load — zero registry lookups at runtime per sound play

**Bug Fixes:**
- Teleport no longer sends a false success message if another plugin cancels the teleport event
- bStats `titles_enabled` chart now reads the correct config key (`features.titles`)
- TPA requests for disconnected players now cleaned up immediately on quit instead of waiting for the expiry timer
- bStats and update checker tasks are now properly cancelled on plugin disable

**New Permissions:**
- `yotpa.back` — access to `/back` (default: everyone)
- `yotpa.bypass.back-cooldown` — skip the `/back` cooldown (default: OP)

**Server Compatibility:**
- **Fully Tested:** Paper 1.21.x – 26.1.x, Folia 1.21.x, Purpur 1.21.x
- **Should Work:** Pufferfish, and any Paper/Folia fork
- **Requires:** Java 21+ (1.21.x servers) / Java 25+ (26.1.x servers)

**Breaking Changes:**
- None. Drop-in replacement for v1.5.0.

**Migration from v1.5.0:**
1. Stop your server
2. Replace `YoTPA-1.5.0.jar` with `YoTPA-1.6.0.jar`
3. Start your server — existing `config.yml` and `messages.yml` are fully preserved

**Feedback & Ideas:** https://github.com/PhyschicWinter9/YoTPA/issues
