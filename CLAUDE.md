# YoTPA — Working Context

> This document is the full working context for the YoTPA codebase. It is written for a
> competent Kotlin/Paper developer (human or AI) who has **never seen this repo**. It is
> intentionally long and precise; skim the table of contents and read the sections that
> apply to your task. When code and this document disagree, the code wins — then fix this
> document in the same PR.

**YoTPA** is a lightweight teleport-request plugin for Paper/Folia Minecraft servers:
`/tpa` (teleport to a player), `/tpahere` (summon a player), accept/deny, a movement-cancelled
countdown, `/back` (return to pre-teleport or death location), cooldowns, full MiniMessage
i18n, an adaptive performance system, bStats analytics, and a GitHub-release update checker.

- **Author**: PhyschicWinter9 & VIBEs Coding XD
- **Current version**: 1.6.1 (single source of truth: `VersionConfig.PLUGIN_VERSION` in `build.gradle.kts`)
- **GitHub**: https://github.com/PhyschicWinter9/YoTPA · **Modrinth**: https://modrinth.com/plugin/yotpa
- **bStats plugin ID**: 25926

---

## 1. Tech stack & build environment

| Layer | Technology | Notes |
|---|---|---|
| Language | Kotlin 2.3.0 | stdlib is shaded into the JAR (unrelocated) |
| Platform API | `io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT`, `compileOnly` | one JAR serves 1.21.x → 26.2.x |
| Java | Toolchain JDK 25, **bytecode target Java 21** (`jvmTarget=21`, `options.release=21`) | 21 bytecode runs on the Java 21 servers (1.21.x) *and* Java 25 servers (26.x) |
| Text | Kyori Adventure + MiniMessage, `compileOnly` | **provided by Paper — never shade** (see Gotchas) |
| Analytics | `org.bstats:bstats-bukkit:3.1.0`, `implementation`, relocated to `com.relaxlikes.yotpa.lib.bstats` | |
| Build | Gradle 8.13 wrapper, Kotlin DSL, Shadow (`com.gradleup.shadow`), run-paper | |

**Critical local-build fact:** Gradle 8.13 cannot *run* on JDK 25. If your default JDK is 25,
`./gradlew build` fails with the cryptic message `What went wrong: 25.0.2`. Run Gradle itself
on JDK ≤ 21 and let the toolchain compile with 25:

```bash
JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.7.6-hotspot" ./gradlew build
# Output: build/libs/YoTPA-<version>.jar  (shadowJar, classifier "" — this is the release artifact)
```

`processResources` template-expands `${version}` into `plugin.yml` and `messages.yml`
(Groovy `expand`). Consequence: **any literal `$` in those two files breaks the build.**
`plugin.yml` must keep `version: ${version}` — never hardcode a version there.

---

## 2. Architecture map

All plugin code lives in `src/main/kotlin/com/relaxlikes/yoTPA/` (package `com.relaxlikes.yoTPA`;
the bStats relocation uses lowercase `yotpa` — both spellings are intentional).

```
YoTPA.kt              Main plugin class. Owns ALL state, all command handling, the teleport
                      lifecycle, performance-mode selection, config load/validation, sounds.
MessageManager.kt     Loads messages.yml, merges bundled defaults, MiniMessage parsing,
                      placeholder substitution, one convenience method per message key.
PlayerMoveListener.kt Event listeners: move (countdown cancel), quit (cleanup), death/respawn
                      (/back death location), teleport (countdown cancel on external teleports).
UpdateChecker.kt      Async GitHub Releases poll (startup + daily), console banner, OP
                      notifications (join + mid-session), version comparison.
bStatsTPA.kt          class BStatsTPA — bStats charts + in-memory all-time/daily counters
                      backing /tpastats. Daily counters reset at local midnight.
```

### State (all in YoTPA.kt, all `ConcurrentHashMap`, sized per performance tier)

| Map | Key → Value | Written by | Cleaned by |
|---|---|---|---|
| `tpaRequests` | target UUID → `TpaRequest` | /tpa, /tpahere | accept/deny (remove), expiry sweep, quit cleanup |
| `teleportData` | teleporter UUID → `TeleportData` | countdown start | `cancelTeleport()` (completion, movement, quit, external teleport) |
| `teleportTasks` | teleporter UUID → Folia `ScheduledTask` | Folia countdown start only | `cancelTeleport()` (Folia only) |
| `countdownOrigins` | teleporter UUID → origin `Location` | countdown start | `cancelTeleport()` |
| `lastLocations` | UUID → `/back` destination | successful teleport, death | `/back` use (single-use), quit |
| `cooldowns` / `backCooldowns` | UUID → epoch ms | request sent / back used | periodic `cleanupCaches()`, quit |
| `playerNameCache` | UUID → name | `getPlayerName()` (only on successful lookup) | quit |

`TpaRequest` and `TeleportData` hold **UUIDs, never `Player` references** (see Design decisions).
`TeleportData.lastShownSecond` is `@Volatile` because Folia countdown ticks can hop region threads.

### Control flow

- **Commands** — `plugin.yml` declares them; `registerCommands()` sets `this` as executor for all
  eight; `onCommand` dispatches by name to `handleXxx()`. Console is rejected (players only).
- **Request** — `handleTpaCommand`/`handleTpaHereCommand` → `validateTeleportRequest` (self-check,
  cooldown with TOCTOU guard) → `storeRequest` (captures `yotpa.bypass.timeout` *at creation time*
  so the expiry sweep never needs a Bukkit permission call off-thread) → messages + request sound.
  A new request to the same target **silently overwrites** the previous pending one.
- **Accept** — `handleTpAcceptCommand` removes the request, resolves who teleports
  (`isHereRequest` flips teleporter/destination), then `startTeleportCountdown(teleporter, dest)`.
- **Countdown** — `startTeleportCountdown`:
  - **Folia**: dispatches the whole setup to `teleporter.scheduler` (the teleporter may be owned
    by a *different region* than the command sender — location read, title, task registration must
    happen on its thread; this also serialises with its move events, closing the start/cancel race).
    Then a per-entity `runAtFixedRate` task ticks the countdown, with a **stale-task guard**:
    if `teleportData[uuid] !== data` the task cancels itself instead of advancing.
  - **Paper**: no per-player task. One **batch task** (`startPaperBatchTask`, period =
    `settings.countdownInterval`, 20 ticks on most tiers / 5 on HIGH_PERFORMANCE) iterates
    `teleportData` and calls `processCountdown` for every active entry. O(1) scheduler overhead
    regardless of player count; teleports may land up to one period late — by design.
  - `processCountdown` re-resolves the destination by UUID each tick (offline → cancel + message),
    is wall-clock based (`startTime` + `duration`), and only re-sends the countdown message/sound
    when the displayed second changes.
- **Completion** — `performTeleport`: saves teleporter's location into `lastLocations`, then
  - Paper: synchronous `teleport()`; success message/sounds only if it returned true.
  - Folia: hop to the **destination's** scheduler to snapshot its location, `teleportAsync`,
    then hop to each player's scheduler for message/sound.
- **Cancellation** — `cancelTeleport(uuid)` removes task (Folia), `teleportData`, `countdownOrigins`.
  Triggers: movement past threshold, quit, external (non-PLUGIN, non-END_GATEWAY) teleport,
  destination offline, or a new countdown replacing the old one.
- **Expiry** — `checkExpiredRequests` runs on the executor (or global-region/Bukkit scheduler in
  ULTRA_LIGHT), collects expired targets **without any Bukkit API calls**, then dispatches
  `processExpiredRequests` to the main thread (Paper) / global region (Folia) for messaging.
- **PlayerMoveListener** — fires on every position change (`hasChangedPosition()` early exit for
  head rotation); O(1) map lookup for the countdown origin; cancels when any axis moves beyond
  `settings.movementThreshold`. Runs at MONITOR priority. **Must stay allocation-light — this is
  the hottest path in the plugin.** It must never do I/O, permission checks, or config reads.

### Threading model (the most important thing to internalize)

| Work | Paper thread | Folia thread |
|---|---|---|
| Commands, events | main | region thread of the player/entity |
| Countdown ticks | main (batch task) | teleporter's region thread (entity scheduler) |
| Expiry sweep, cache cleanup | `YoTPA-Worker-N` executor (or main in ULTRA_LIGHT) | executor (or global region in ULTRA_LIGHT) |
| Expiry messaging | main (dispatched) | global region (dispatched) |
| Update check HTTP | Bukkit async scheduler | Folia async scheduler |
| bStats chart suppliers | bStats' own threads | bStats' own threads |

Rules that hold everywhere in this codebase:
1. Entity state (location, teleport, title-with-location) → only on the entity's owning thread.
2. Adventure audience methods (`sendMessage`, `showTitle`) are thread-safe; use freely.
3. `playSound` uses the **entity-emitter overload** `player.playSound(player, sound, …)` —
   the location-taking overload reads `player.location`, a region-guarded accessor on Folia.
4. Anything read cross-thread is `@Volatile` (all config fields, `cachedSettings`,
   `MessageManager.messagesConfig`, `MessageManager.prefixComponent`) or a `ConcurrentHashMap`.
5. bStats chart callbacks read the cached `@Volatile` values via `currentXxx` getters on the
   plugin — **never** `plugin.config` (FileConfiguration is not thread-safe vs `/tpareload`).

---

## 3. Design decisions & rationale

- **Single JAR across 1.21.x–26.x** — bytecode target stays at Java 21 even though the toolchain
  is JDK 25, because Java 21 bytecode runs on both server generations. Compiling against the
  *oldest supported* Paper API (1.21.11) and relying on Paper's API stability gives one artifact,
  one Modrinth listing, one support surface. Do not raise `jvmTarget` or `api-version`
  until 1.21.x support is deliberately dropped.
- **UUID-based tracking, not `Player` references** — `TpaRequest`/`TeleportData` store UUIDs and
  resolve `Bukkit.getPlayer(uuid)` fresh at each use. Rationale: live `Player` refs leak entire
  entity graphs after disconnect ("zombie references") and are illegal to touch cross-region on
  Folia. Resolution returning null doubles as the online check.
- **In-memory maps replaced PersistentDataContainer** for countdown origins / back locations —
  PDC serialised a Location string on every move event and persisted stale state across restarts.
  CHM is O(1), allocation-free to read, and process-lifetime only (which is the desired semantics:
  a `/back` location should not survive a server restart).
- **Four performance tiers + AUTO** — small servers (512 MB VPS) can't afford executor threads and
  aggressive caches; big servers benefit from them. AUTO reads `Runtime.maxMemory()` at enable and
  picks ULTRA_LIGHT (≤768 MB) / LIGHT (≤1.5 GB) / BALANCED (≤3 GB) / HIGH_PERFORMANCE. Every tier
  difference funnels through one immutable `PerformanceSettings` snapshot (`cachedSettings`,
  `@Volatile`) so hot paths pay a single volatile read, never a `when`.
  The `AUTO` branch inside `getPerformanceSettings()` is an unreachable safety net — AUTO is always
  resolved to a concrete tier first.
- **Paper batch countdown task vs Folia per-entity tasks** — on Paper, one repeating task scanning
  a CHM beats N scheduled tasks (scheduler overhead, cancellation churn). On Folia there is no
  main thread, so per-entity tasks are *required* for region ownership — the entity scheduler also
  guarantees ticks stop when the entity retires.
- **Sounds resolved once at config load** — config strings → `NamespacedKey` → `Registry.SOUNDS`
  lookup happens in `loadSounds()`; hot paths receive a cached `Sound?`. Null means "not found or
  disabled" and is silently skipped.
- **Messages: bundled-defaults merging** — `MessageManager.loadMessages()` sets the JAR's
  `messages.yml` as the `defaults` of the server's file. New message keys added in updates work
  without any user migration. **This merging exists for `messages.yml` only — `config.yml` does
  NOT auto-merge**; new config keys must always be read with a code-side default
  (`config.getInt(key, default)`) so absent keys behave.
- **Permission snapshots at request creation** (`bypassTimeout`) — the expiry sweep runs off-thread
  where `player.hasPermission` is unsafe; capturing the boolean on the command thread keeps the
  sweep Bukkit-free.
- **`/back` is single-use** — the saved location is consumed on use. Prevents infinite ping-pong
  and keeps semantics obvious. A second `/back` intentionally reports "no previous location".
- **Update check is fully async** — the GitHub API call (5 s connect + 5 s read timeouts) runs on
  the async scheduler even at startup; a slow network must never stall `onEnable`.

---

## 4. Compatibility matrix (honest: tested vs assumed)

"Tested" = the mineflayer bot suite (or equivalent live-server session) actually ran against it.
"Assumed" = expected to work via Paper API stability, but no recorded runtime pass.

| Platform | Status | Evidence / caveat |
|---|---|---|
| Paper 1.21.x | **Tested** | bot suite runs (April 2026 era, v1.6.0); `runServer` targets 1.21 |
| Purpur 1.21.x | Assumed | claimed in release notes as tested historically; re-verify per release |
| Paper 26.1.x | Assumed | July 2026 research: scheduler/PDC/Sound/`teleportAsync` APIs unchanged from 1.21.x (medium confidence — formal release notes were sparse) |
| Paper 26.2.x | Assumed | same research basis; **no runtime pass recorded yet** |
| Folia 1.21.x | **Partially tested** | code paths designed for Folia and compile; the v1.6.1 countdown-start fix has **not** had a live Folia pass — do one before claiming "tested" |
| Folia 26.x | N/A yet | Folia's newest public build was 26.1.2 as of July 2026; no Folia 26.2 exists to test |
| Spigot (non-Paper) | **Not supported** | uses Paper-only API (`teleportAsync`, entity schedulers, `pluginMeta`, `hasChangedPosition`) |

Java: servers run 21+ (1.21.x) or 25 (26.x); the JAR is Java 21 bytecode and runs on both.

**Rule:** when this matrix changes, update it here, in `RELEASE_NOTES_MODRINTH.md`'s
"Server Compatibility" block, and in the README in the same commit. Never move a row to
"Tested" without a bot-suite (or manual scenario) pass on that exact platform+version.

---

## 5. Config schema

### `config.yml` (does NOT auto-merge new keys — code defaults are the safety net)

| Key | Default | Effect | Reload behavior (`/tpareload`) |
|---|---|---|---|
| `request-timeout` | 60 | seconds before a pending request expires | ✅ applies immediately |
| `request-cooldown` | 30 | seconds between requests per player | ✅ immediately |
| `teleport-delay` | 5 | countdown seconds (validation floor: ≥ 1) | ✅ for new countdowns |
| `back-cooldown` | 30 | `/back` cooldown seconds; 0 disables | ✅ immediately |
| `performance.mode` | AUTO | AUTO / ULTRA_LIGHT / LIGHT / BALANCED / HIGH_PERFORMANCE | ⚠️ partially — thresholds/caches re-resolve, but the executor pool, map sizing, and already-scheduled task periods are built at enable; a mode change prints "restart recommended". Treat mode changes as restart-required. |
| `sounds.countdown` | `block.note_block.pling` | countdown tick sound | ✅ (re-resolved from registry) |
| `sounds.success` | `entity.enderman.teleport` | teleport success | ✅ |
| `sounds.cancel` | `entity.villager.no` | deny/cancel | ✅ |
| `sounds.request` | `entity.experience_orb.pickup` | request received | ✅ |
| `features.titles` | true | countdown title/subtitle | ✅ |
| `features.sounds` | true | master sound toggle (also gates update-notify sound) | ✅ |
| `features.statistics` | true | **currently a no-op** — validated but never checked by `/tpastats` or the counters | — |
| `features.bstats` | true | **currently a no-op** — bStats initializes unconditionally | — |

Sound values accept dotted (`block.note_block.pling`) or enum-ish (`BLOCK_NOTE_BLOCK_PLING`)
forms; both are lowercased/normalised. Unknown sounds warn at validation and fall back to defaults.

`/tpareload` semantics: reload file → reload messages → `validateConfig()`. On **errors** the new
config is *not applied* (previous values stay live); warnings apply but are echoed to the issuer.

### `messages.yml` (auto-merges: bundled defaults back any missing key)

Structure: `prefix`, `commands.{tpa,tpahere,tpaccept,tpadeny,tpareload,tpastats,tpainfo,back}.*`,
`teleport.{countdown.*, success, cancelled.*, expired.*}`, `update.available`, `errors.*`.
All values are MiniMessage. Placeholders are literal `{name}` tokens substituted by string-replace
**before** MiniMessage parsing: `{player}` `{target}` `{requester}` `{seconds}` `{cooldown}`
`{plural}` `{version}` `{mode}` `{ram}` `{level}` `{count}` `{current}` `{latest}`
`{old_mode}`/`{new_mode}` `{error}` `{warning}`. `{plural}` is "s"/"" computed in code.
Reloadable live via `/tpareload`. A parse failure logs a warning and shows the raw string.

### Commands & permissions (`plugin.yml`)

| Command | Permission (default) |
|---|---|
| `/tpa <player>` (alias `teleportask`), `/tpahere`, `/tpaccept`, `/tpadeny` (alias `tpdeny`) | `yotpa.use` (true) |
| `/back` | `yotpa.back` (true) |
| `/tpareload` | `yotpa.reload` (op) |
| `/tpastats` | `yotpa.stats` (op) |
| `/tpainfo` | `yotpa.info` (op) |
| — update notifications | `yotpa.admin` (op) — also granted to raw OPs |
| — bypasses | `yotpa.bypass.cooldown`, `yotpa.bypass.back-cooldown`, `yotpa.bypass.timeout` (op) |
| `yotpa.*` | parent of all of the above (op) |

Commands gate via plugin.yml `permission:` *and* re-check in the handler (defense in depth).

---

## 6. Known gotchas

**Things that look like bugs but aren't**
- Second `/back` says "no previous location" — single-use by design.
- Teleport lands up to 1 s "late" on Paper — batch task period, by design.
- `PerformanceMode.AUTO` branch in `getPerformanceSettings()` is dead code — intentional safety net.
- `cleanupCaches()` doesn't touch `playerNameCache` — deliberate; scanning would need
  `Bukkit.getPlayer` off-thread. The cache is cleaned per-player on quit instead.
- `/home`-style teleports from other plugins cancel an active countdown; YoTPA's own teleports
  don't — the teleport listener ignores `PLUGIN` and `END_GATEWAY` causes only.
- The update checker treats `1.6.1-beta` as equal to `1.6.1` (numeric segment comparison;
  suffixes parse to 0). Don't publish suffixed tags expecting notification precision.

**Things that look fine but aren't**
- Building with default JDK 25 → Gradle dies with `What went wrong: 25.0.2`. Use JDK ≤ 21 for
  Gradle (see §1). CI uses JDK 21 explicitly.
- A literal `$` anywhere in `plugin.yml` or `messages.yml` breaks the build (`processResources`
  Groovy expansion). This includes user-suggested message strings.
- Shading Adventure (making it `implementation`) will *work in testing* and then produce
  class-skew bugs on servers whose bundled Adventure is newer — it must stay `compileOnly`.
  Same trap: adding a second Shadow plugin id (the legacy `com.github.johnrengelman.shadow`
  coexisted with `com.gradleup.shadow` until v1.6.1 — never reintroduce it).
- `features.statistics` / `features.bstats` are documented toggles that currently do nothing
  (validated, never enforced). Either wire them up or expect confused-user reports.
- Reading `plugin.config` from any non-main thread (bStats suppliers, executor jobs) races with
  `/tpareload`. Use the `@Volatile` fields / `currentXxx` getters.
- On Folia, reading `player.location` (or any entity state) for a player who may belong to
  another region throws. This includes the innocent-looking `playSound(player.location, …)`
  overload. Grep for `.location` when touching Folia paths.
- `MessageManager.getMessage` substitutes placeholders *before* parsing — a placeholder value
  containing MiniMessage tags will be parsed as markup. Player names are safe (charset), but
  never pipe arbitrary user input through a placeholder.
- Bot testing: Paper's connection throttle (default 4000 ms) kicks simultaneous bot logins —
  the harness staggers logins 5 s apart; keep that. Server must be `online-mode=false` for
  mineflayer's offline auth. mineflayer's protocol support ceiling lags new MC releases —
  a too-new `SERVER.version` in `bot-test/config.js` fails at handshake.

---

## 7. Build & release process

```bash
# Local build (JAR → build/libs/YoTPA-<version>.jar; shadowJar is the default artifact)
JAVA_HOME=<jdk21> ./gradlew clean build

# Local test server (Paper, version pinned in build.gradle.kts runServer block)
JAVA_HOME=<jdk21> ./gradlew runServer
```

**Versioning** — semver-ish (`MAJOR.MINOR.PATCH`): feature releases bump minor (1.6.0),
fix/optimization releases bump patch (1.6.1). The only place a version is written by hand is
`VersionConfig.PLUGIN_VERSION` in `build.gradle.kts`; `plugin.yml` receives it via `${version}`.

**Release checklist** (order matters):
1. Bump `PLUGIN_VERSION`.
2. Write `CHANGELOG.md` entry (Keep-a-Changelog style, dated, emoji headline — match prior entries).
3. Prepend a section to `RELEASE_NOTES_MODRINTH.md` (player-facing tone, compatibility block,
   migration steps) — this file's newest section becomes the Modrinth version description.
4. Update the compatibility matrix (§4) if the supported range changed.
5. Build + run the bot suite (§9) against the primary target version.
6. Commit; release via **either** a commit on `main` containing `[release]` or `[version]`
   (CI `build-and-release.yml` extracts the version, creates tag `v<version>`, uploads the JAR
   to a GitHub Release) **or** pushing a `v*` tag directly.
7. **Modrinth upload is manual** — upload the JAR, paste the new `RELEASE_NOTES_MODRINTH.md`
   section, set the game-version range and loaders (Paper, Folia, Purpur).

CI: `build.yml` builds every push to `dev/**` and PRs to main (JDK 21, `clean build`, uploads
artifact). `build-and-release.yml` handles main/tags as above.

**Branches** — `main` = released; `dev/<mc-version>` = active development (currently `dev/26.2`);
`feat/<name>`; `backup/<version>` snapshots.

**Update checker coupling** — `UpdateChecker` polls
`api.github.com/repos/PhyschicWinter9/YoTPA/releases/latest`; a GitHub Release (not just a tag)
must exist for users to be notified, and its tag must be `v<semver>`.

---

## 8. Current state vs roadmap

### Implemented (by version it landed)

- **≤1.5.0** — /tpa, /tpahere, /tpaccept, /tpadeny, countdown + movement cancel, cooldowns,
  titles, sounds, /tpareload with validation, /tpastats, /tpainfo, adaptive performance system,
  full MiniMessage i18n (1.4.0), bStats.
- **1.6.0** (2026-04-12) — `/back` (post-teleport + death location, single-use, cooldown +
  bypass), full Folia support (entity/global/async schedulers, `teleportAsync`), daily update
  re-check with OP notify, bundled-defaults merging for messages.yml, UUID-based `TeleportData`,
  Paper batch countdown task, cached `PerformanceSettings` & sounds.
- **1.6.1** (2026-07-07) — Folia thread-safety hardening (countdown starts on the teleporter's
  region thread; stale-task guard kills ghost countdowns; entity-emitter `playSound`; `@Volatile`
  MessageManager; bStats reads cached values), async startup update check, `/back` unloaded-world
  guard, name-cache poisoning fix, `/tpainfo` RAM math fix, dotted-sound-key normalisation,
  Adventure un-shaded (JAR 3.0 → 1.9 MB), bStats 3.1.0, `yotpa.admin` declared, `update.available`
  message key, `plugin.yml` version injected from the build.

### Explicitly deferred (next up, in rough order)

1. **PlaceholderAPI integration** — most-requested integration class. Plan: `compileOnly`
   dependency + `softdepend: [PlaceholderAPI]` + an expansion class instantiated only after a
   `getPlugin("PlaceholderAPI") != null` check (same class-loading-guard trick as the `isFolia`
   detection — keeps the single JAR with zero hard deps). Placeholders can be O(1) reads of the
   existing maps: `%yotpa_cooldown_remaining%`, `%yotpa_pending_request%`, `%yotpa_back_available%`,
   `%yotpa_performance_mode%`. No config keys needed.
2. **Combat detection** — cancel countdown on damage. Plan: own `EntityDamageEvent` listener
   mirroring the movement-cancel pattern (early-return when `teleportData.isEmpty()`), gated by
   a new `combat.cancel-on-damage` key defaulting **false** (old configs keep behavior; no
   migration). Soft-hooks into CombatLogX/PvPManager tag state are a later, separate layer.
3. **GUI-based requests** — deferred because it drags in inventory-menu plumbing and is the
   feature most likely to conflict with the "lightweight" positioning; needs a design pass first.
4. Housekeeping candidates: wire up `features.statistics`/`features.bstats`, `/back` toggle mode
   (a dead `currentLocation` variable was removed in 1.6.1 that hinted at it), tab completion.

---

## 9. Testing approach (`bot-test/`)

Mineflayer-based end-to-end suite driving real bots against a live server. There are **no unit
tests** — all verification is build + bot suite. Structure:

```
bot-test/config.js       Server host/port/protocol version, bot names, admin bot, plugin
                         version to expect, timing constants, recommended test-server config
bot-test/bot-factory.js  Offline-auth bot creation; retries on Paper's connection throttle;
                         5 s login stagger (do not reduce)
bot-test/admin.js        OP bot helpers: cross-world tp, freeze, kill, reset-to-spawn, announce
bot-test/index.js        The suite: ~35 scenarios — tpa/tpahere accept/deny/timeout/self/
                         invalid, movement cancel, cooldowns, /back (origin, single-use, death,
                         PvP death, cross-world, cooldown + bypass), cross-world & long-distance
                         TPA, reload, concurrency, plus v1.6.x regression scenarios
                         (destination-offline mid-countdown, requester-quit cleanup,
                         accept-while-moving race, /tpainfo RAM sanity)
bot-test/stress.js       Latency/throughput/flood measurements
bot-test/version-check.js  Asserts GitHub latest release vs config.js currentVersion
```

Run: start a test server (`online-mode=false`, plugin installed, ideally the fast test config
documented in `config.js`: timeout 15 / cooldown 5 / delay 5 / back-cooldown 10), then
`bun run index.js` (`--quick` skips stress, `--stress` runs only stress). Core scenarios
**hard-assert on the plugin's default chat messages** — customized `messages.yml` on the test
server will cause false failures. The suite needs LuckPerms (`/lp`) for the two permission
scenarios; they fail gracefully without it.

Not covered: Folia-specific regionization (needs a real Folia server with players in distinct
regions), update-notification flow, `/tpareload` validation-failure paths, non-English messages.

---

## 10. Extension-point checklists

### Add a new player-facing command
1. `src/main/resources/plugin.yml` — add under `commands:` (description/usage/permission/aliases)
   and declare the permission under `permissions:` **plus** add it to `yotpa.*`'s children.
2. `src/main/resources/messages.yml` — add `commands.<name>.*` keys (usage, success, errors…).
   Defaults-merging makes them live on existing servers automatically. No `$` characters.
3. `MessageManager.kt` — one convenience method per key (follow existing naming: `getXxxYyy()`).
4. `YoTPA.kt` — add the name to the `registerCommands()` array; add an `onCommand` branch;
   write `handleXxxCommand(player)` with (a) handler-level permission check, (b) messages via
   MessageManager only, (c) Folia-aware scheduling if it touches entity state (see §2 rules).
5. If it needs state: a new `ConcurrentHashMap` sized in `initializeDataStructures()`, cleaned in
   `cleanupPlayerOnQuit()` and `clearAllData()`.
6. `bot-test/index.js` — add a scenario with message-based assertions; register it in `main()`.
7. `CHANGELOG.md` + `RELEASE_NOTES_MODRINTH.md` + §5/§8 of this file.

### Add a new config option
1. `config.yml` — key + comment block. (Remember: no auto-merge; existing servers won't have it.)
2. `YoTPA.kt` — `@Volatile` field with the same default; read in `loadConfig()` with
   `config.getX(key, default)`.
3. `validateConfig()` — error/warning rules mirroring the existing style.
4. If read off-thread (bStats etc.): expose a `currentXxx` getter.
5. Document reload behavior in §5; if it only takes effect at enable, say so in the config comment.
6. Behavior-changing keys default to **preserving old behavior** (see combat-detection plan) —
   that's what keeps releases migration-free.

### Add a new language
`messages.yml` is single-file: users translate values in place; missing keys fall back to bundled
English via defaults-merging. To ship an example, append a commented block under
"ALTERNATIVE LANGUAGE EXAMPLES". A real multi-locale system (per-locale files + `locale` key)
would be a feature — follow the config-option checklist and extend `MessageManager.initialize()`.

### Add a performance mode / tuning parameter
1. `PerformanceMode` enum (YoTPA.kt) + branch in `getPerformanceSettings()`.
2. Threshold logic in `detectAndApplyPerformanceMode()` (AUTO selection).
3. Valid-values string in `validateConfig()`; label in `getOptimizationLevel()`.
4. `config.yml` mode docs block; §5 table. New tuning parameters go into `PerformanceSettings`
   (immutable data class) — never read config in a hot path.

---

## 11. Version-upgrade playbook (new Minecraft/Paper release)

Historical baseline: **1.21 → 26.1 → 26.2 required zero code changes** — the entire cost was
research + runtime verification + docs. Expect that to continue but never assume it.

1. **Research first** (do not trust model memory for post-cutoff versions): Paper release
   announcement + docs.papermc.io "update" notes; deprecations affecting the APIs this plugin
   actually uses — grep list: `Registry.SOUNDS`, `NamespacedKey`, entity/global/async schedulers,
   `teleportAsync`, `PlayerMoveEvent`/`hasChangedPosition`, `PlayerDeathEvent`/`PlayerRespawnEvent`,
   `PlayerTeleportEvent.TeleportCause`, Adventure `Title`/MiniMessage, `pluginMeta`,
   `scheduleSyncRepeatingTask` (legacy — a known future removal candidate). Check the new
   server's required Java version.
2. **Branch** `dev/<new-version>` from main.
3. **Decide the compile target**: keep compiling against the *oldest* supported Paper API unless
   a new API is needed. Only bump `paper-api` (and possibly `api-version`) when dropping old
   versions — that's a support-range decision, not a routine step.
4. **Build** (JDK 21 for Gradle). Fix any deprecation warnings now, not later.
5. **Runtime pass**: point `runServer` (or a manual server) at the new version; confirm clean
   enable, `/tpainfo`, one full tpa→accept→countdown→teleport→/back cycle.
6. **Bot suite**: update `bot-test/config.js` `SERVER.version` — but check mineflayer's protocol
   support first; if mineflayer lags the new version, either put ViaVersion on the *test* server
   and keep handshaking an older protocol, or defer "Tested" status and say so in the matrix.
7. **Folia**: check whether Folia has shipped for the new version at all (it lags Paper).
   No Folia build → the matrix says "N/A yet", not "supported".
8. **Docs**: update §4 matrix (tested vs assumed, honestly), `RELEASE_NOTES_MODRINTH.md`
   compatibility block, README, `runServer` pin, and the Modrinth listing's game-version range.
9. **Release** per §7. Only claim "Tested" for combos that ran the suite.

---

## 12. Multi-loader readiness map (future, NOT committed)

**Framing:** a Fabric/NeoForge port is a fundamentally bigger shift than Paper→Folia was.
Folia still exposes the Bukkit/Paper API — that migration was "same API, stricter threading".
Fabric and NeoForge do not run Bukkit at all: no `plugin.yml`, no Bukkit events, no Bukkit
scheduler, no Bukkit permissions, no `FileConfiguration`. Zero code is shared automatically just
because everything is "a Minecraft server". Every Bukkit-touching line would need a platform
abstraction. Nothing below proposes doing this now; it is a map for a future decision.

### What is genuinely platform-agnostic today (candidate `common/` module)

The *logic* is portable; the *code* is not — all of it currently lives inside Bukkit-coupled
classes and would need extraction behind interfaces:

- The TPA request state machine: request/accept/deny/expire semantics, target-keyed request
  replacement, cooldown arithmetic, timeout-bypass snapshots (`TpaRequest`, `TeleportData`,
  the maps' lifecycle rules in §2).
- Countdown timing model (wall-clock based, displayed-second dedup, movement-threshold rule).
- Performance-tier table and AUTO selection by max-heap size.
- Message-key schema + `{placeholder}` conventions (MiniMessage itself is portable — see below).
- Version comparison, update-check protocol (plain HTTPS + JSON), statistics counters.

### What is Bukkit/Paper-bound (would need per-platform implementations)

- Command registration/dispatch (`plugin.yml` + `onCommand`) → Brigadier on Fabric/NeoForge.
- All event listeners (move, quit, death, respawn, teleport) → per-loader event systems.
- Schedulers (Bukkit main-thread, Folia entity/global/async) → server tick events / own executors.
- `Player`/`Location`/`teleportAsync`, Sound registry, titles → per-platform adapters
  (Adventure covers text/title/sound *delivery* cross-platform; entity teleportation it does not).
- Permissions (`hasPermission` + plugin.yml declarations) → fabric-permissions-api / NeoForge
  permission API / LuckPerms platform hooks.
- YAML config via `FileConfiguration` → a portable config library or hand-rolled loader.
- bStats: separate per-platform bStats artifacts exist; the bukkit one won't load elsewhere.

### Current tooling landscape (researched July 2026 — re-verify at decision time; this moves fast)

- **Module split**: Architectury (architectury-api + loom) remained actively maintained as of
  mid-2026, tracking MC 26.2 on both Fabric and NeoForge, with no announced successor. Teams
  increasingly pair it with **Stonecutter** for multi-*version* builds, or skip Architectury API
  entirely in favor of a plain Gradle multi-module "MultiLoader Template" layout
  (`common/` + `fabric/` + `neoforge/`, Mojang mappings). For a server-only plugin like this,
  the plain template is likely sufficient — Architectury API's value is mostly client/registry
  abstractions this plugin doesn't need. *(confidence: medium-high)*
- **Adventure/MiniMessage** carries over: the old standalone `adventure-platform-fabric` was
  superseded by `net.kyori:adventure-platform-mod-shared` (plus a `-fabric-repack` variant for
  Loom), maintained under the PaperMC org, with full server-side MiniMessage on both loaders.
  The entire `messages.yml` + MiniMessage layer is therefore portable in concept; only the
  Audience acquisition differs per loader (Fabric: interface injection on native types;
  NeoForge: explicit `MinecraftAudiences` wrapping). *(confidence: high)*
- **Kotlin**: both loader-side Kotlin runtimes were current as of June 2026 — Fabric Language
  Kotlin (Kotlin 2.4.x) and Kotlin for Forge/NeoForge 6.x (NeoForge 26.2). Kotlin is not a
  blocker on any loader. *(confidence: medium — pin exact versions at decision time)*
- **Server-only mods are normal** on both loaders (no client install needed) — fine for a
  commands+chat+teleport plugin. Metadata is `fabric.mod.json` / `neoforge.mods.toml`;
  **commands are Brigadier trees**, not `CommandExecutor`; there is no `plugin.yml`.
- **Permissions**: the loaders' native model is vanilla **op-levels (0–4)**, not string nodes —
  the single biggest culture shock coming from Bukkit. `fabric-permissions-api` (by the LuckPerms
  author) bridges to string nodes, and LuckPerms itself ships current Fabric + NeoForge builds,
  so `yotpa.*`-style nodes remain feasible. NeoForge's permission story is less standardized
  than Fabric's. *(confidence: medium on the NeoForge half)*
- **Scheduling**: neither loader has a Bukkit-style scheduler. Delayed/repeating work hangs off
  **tick-event callbacks** (e.g. `ServerTickEvents`) or your own executors + main-thread
  hand-off. The countdown engine would need a small per-platform "ticker" abstraction — which
  the wall-clock-based countdown design (§2) is already well-suited to.
- **Paper trajectory**: Paper's hard-fork away from Spigot internals continued (with 26.1 it
  dropped obfuscated-plugin support entirely, following Mojang's un-obfuscated server jars).
  Mapping-wise Paper and the mod loaders are converging; API-wise Paper still is and will
  remain its own surface — convergence does **not** create shared code. *(confidence: medium-high)*

Full research notes with per-claim source URLs were captured in the session scratchpad
(`multiloader-research.md`); the facts above are the durable summary.

### Decision guidance

If/when this is seriously considered: first extract the agnostic logic listed above into plain
Kotlin classes **within the existing single module** (an internal `core` package with zero
`org.bukkit`/`io.papermc` imports, enforced by review or a lint rule). That refactor is valuable
even if multi-loader never happens (testability), and it converts the port from "rewrite" to
"write three adapters". Do not adopt multi-loader build tooling before that boundary exists.
