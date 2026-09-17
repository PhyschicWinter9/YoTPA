# Additional Paper Features & Process Topics

> Recipes, Dialog API, and particles verified against `docs.papermc.io`/`jd.papermc.io`; CI/CD verified against PaperMC's own Hangar-publishing docs and Modrinth's Minotaur plugin docs, mid-August 2026. Version-range examples below (§5) were touched up 2026-09-17 to reflect the `26.3` release — check `SKILL.md`'s Current Version Snapshot for what's current at read time.

## Table of Contents

1. [Custom Recipes](#1-custom-recipes)
2. [Dialog API](#2-dialog-api)
3. [Particles](#3-particles)
4. [Scoreboards, Boss Bars & Tab List](#4-scoreboards-boss-bars--tab-list)
5. [CI/CD: Automated Builds & Publishing](#5-cicd-automated-builds--publishing)
6. [Multi-Version Support Strategies](#6-multi-version-support-strategies)

---

## 1. Custom Recipes

Recipes are registered against `Server#addRecipe` and don't need to happen in `onEnable()` specifically — you can register one at any time, though if you register after the plugin is already enabled and players are online, you need to either resend recipes to them or use the update-players boolean overload of `addRecipe`.

**Shaped recipe:**

```java
ItemStack result = new ItemStack(Material.DIAMOND_SWORD);
NamespacedKey key = new NamespacedKey(this, "emerald_sword");

ShapedRecipe recipe = new ShapedRecipe(key, result);
recipe.shape("AAA", "ABA", "AAA");
recipe.setIngredient('A', Material.WHITE_CONCRETE);
recipe.setIngredient('B', Material.EMERALD);

getServer().addRecipe(recipe);
```

**Shapeless recipe:**

```java
NamespacedKey key = new NamespacedKey(this, "shapeless_example");
ShapelessRecipe recipe = new ShapelessRecipe(key, result);
recipe.addIngredient(3, Material.DIAMOND);
recipe.addIngredient(2, Material.STICK);
getServer().addRecipe(recipe);
```

Two easy-to-hit mistakes: **you cannot use `Material.AIR` as an ingredient in a shaped recipe** (Paper will throw); and every custom recipe needs a unique `NamespacedKey` — reusing a key (including accidentally re-registering on every `onEnable()` without checking) will conflict with the existing registration.

## 2. Dialog API

Introduced in `1.21.7`, the Dialog API lets a plugin show structured, clickable UI dialogs (confirmations, notices, multi-action menus, server-link lists) to a player without building a custom inventory GUI — the same system vanilla uses for things like the "are you sure" prompts.

**Note on stability**: this is one of the newest, fastest-moving areas of the Paper API — parts of it were already deprecated within a couple of point releases of its introduction (e.g. `Dialog#getKey()` deprecated as of `1.21.8` in favor of the general registry-key lookup pattern). Treat the exact method names here as a starting point to verify against current Javadocs, more so than most of the rest of this skill.

```java
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;

Dialog confirmDialog = Dialog.create(builder -> builder.empty()
    .base(io.papermc.paper.registry.data.dialog.DialogBase.builder(Component.text("Confirm purchase"))
        .build())
    .type(DialogType.confirmation(
        ActionButton.builder(Component.text("Confirm")).build(),
        ActionButton.builder(Component.text("Cancel")).build()
    ))
);

player.showDialog(confirmDialog); // Player implements Audience, which exposes showDialog
```

Dialogs can also be triggered in-game with `/dialog show <players> <dialog>` for dialogs registered through the registry, which is often simpler than building one ad hoc in code if the dialog is static content.

## 3. Particles

Two ways to spawn particles: the newer **`ParticleBuilder`** (preferred — reusable, more readable, and gives finer control over which players receive it), or the older direct `spawnParticle()` methods on `World`/`Player`.

**`ParticleBuilder` (preferred):**

```java
new ParticleBuilder(Particle.HAPPY_VILLAGER)
    .location(location)
    .count(14)
    .offset(2, 0.2, 2)
    .receivers(32, true) // all players within 32 blocks, spherical selection
    .spawn();
```

`receivers(radius, sphere)` — passing `true` selects players within a sphere of that radius; `false` selects within a cube instead.

**Colored "dust" particles** (used for things like custom redstone-style effects) take a `Particle.DustOptions`:

```java
world.spawnParticle(
    Particle.DUST,
    location,
    10, // count
    0, 0, 0, // offset
    new Particle.DustOptions(Color.fromRGB(0, 100, 255), 2.0f) // color, scale (0.01–4.0, clamped)
);
```

Only `ENTITY_EFFECT` supports a true alpha channel for translucency (`Color.fromARGB(...)`); `FLASH` and `TINTED_LEAVES` accept an ARGB color but silently ignore the alpha component. If `count = 0`, exactly one particle spawns at the exact location with no random offset applied — useful when you want deterministic placement rather than a scattered cloud.

## 4. Scoreboards, Boss Bars & Tab List

**Scoreboard / sidebar:**

```java
Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
Objective objective = board.registerNewObjective("stats", Criteria.DUMMY, Component.text("Server Stats"));
objective.setDisplaySlot(DisplaySlot.SIDEBAR);

objective.getScore("Players Online").setScore(Bukkit.getOnlinePlayers().size());
player.setScoreboard(board);
```

**Teams** (useful for nametag color/visibility/friendly-fire control, not just the scoreboard sidebar):

```java
Team team = board.registerNewTeam("no-friendly-fire");
team.setAllowFriendlyFire(false);
team.addEntry(player.getName());
```

**Boss bars** — use the Adventure `BossBar` (via the `Audience` interface every `Player` implements), not the legacy `org.bukkit.boss.BossBar`, for anything new:

```java
import net.kyori.adventure.bossbar.BossBar;

BossBar bossBar = BossBar.bossBar(
    Component.text("Boss Fight"),
    1.0f,
    BossBar.Color.RED,
    BossBar.Overlay.PROGRESS
);

player.showBossBar(bossBar);
// later, to update progress:
bossBar.progress(0.5f);
// and to remove it:
player.hideBossBar(bossBar);
```

**Tab list header/footer:**

```java
player.sendPlayerListHeaderAndFooter(
    Component.text("Welcome to the server!"),
    Component.text("Online: " + Bukkit.getOnlinePlayers().size())
);
```

## 5. CI/CD: Automated Builds & Publishing

Both major plugin platforms have official (or Paper-endorsed) Gradle tooling for automated publishing, which pairs naturally with a GitHub Actions workflow that runs on every push to your main branch.

### Publishing to Hangar

PaperMC's own Gradle plugin, `io.papermc.hangar-publish-plugin`:

```kotlin
// build.gradle.kts
plugins {
    id("io.papermc.hangar-publish-plugin") version "0.1.2" // check for the current version
}
```

```kotlin
import io.papermc.hangarpublishplugin.model.Platforms

hangarPublish {
    publications.register("plugin") {
        version.set(project.version as String)
        channel.set("Release") // or "Snapshot" for non-release builds — create the channel on Hangar first
        id.set("your-hangar-project-slug")
        apiKey.set(System.getenv("HANGAR_API_TOKEN"))

        platforms {
            register(Platforms.PAPER) {
                jar.set(tasks.shadowJar.flatMap { it.archiveFile }) // or tasks.jar if not shading
                platformVersions.set(listOf("26.1-26.3")) // your supported version range — include 26.3 only once you've actually verified compatibility, since it may still be Paper's experimental channel (check SKILL.md's version snapshot)
            }
        }
    }
}
```

Generate a Hangar API token (Hangar profile → API keys, with the `create_version` scope) and store it as a `HANGAR_API_TOKEN` repository secret in GitHub before wiring up the workflow below.

### Publishing to Modrinth

The official Gradle plugin is **Minotaur** (`com.modrinth.minotaur`):

```kotlin
plugins {
    id("com.modrinth.minotaur") version "2.+" // track 2.+ for latest fixes without manual bumps
}

modrinth {
    token.set(System.getenv("MODRINTH_TOKEN"))
    projectId.set("your-modrinth-project-id-or-slug")
    versionType.set("release") // or "beta" / "alpha"
    uploadFile.set(tasks.shadowJar) // or tasks.jar if not shading
    gameVersions.set(listOf("1.21", "26.1", "26.2", "26.3")) // trim to versions you've actually tested against
    loaders.set(listOf("paper"))
}
```

Generate a Modrinth personal access token with the `CREATE_VERSION` scope and store it as `MODRINTH_TOKEN`.

### Combined GitHub Actions workflow

```yaml
name: Build & Publish

on:
  push:
    branches: [main]

jobs:
  publish:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout Repository
        uses: actions/checkout@v7

      - name: Set up JDK 25
        uses: actions/setup-java@v5
        with:
          distribution: 'temurin'
          java-version: 25

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v6

      - name: Publish to Hangar
        env:
          HANGAR_API_TOKEN: ${{ secrets.HANGAR_API_TOKEN }}
        run: ./gradlew build publishPluginPublicationToHangar --stacktrace

      - name: Publish to Modrinth
        env:
          MODRINTH_TOKEN: ${{ secrets.MODRINTH_TOKEN }}
        run: ./gradlew modrinth --stacktrace
```

This is deliberately the minimal viable pipeline — build once, publish to both. For a real project, add a test job (running any MockBukkit-based tests from the main reference's testing section) as a required check before the publish job, so a broken build never reaches either platform.

## 6. Multi-Version Support Strategies

**When a new drop lands** (like `26.3` "Wilderness Bound" on 2026-09-15), most of the time it's additive — new biomes, blocks, and items — rather than API-breaking, since Bukkit/Paper's stable API is designed to absorb new `Material`/`Enchantment`/etc. entries without touching existing method signatures. The practical checklist for a plugin developer reacting to a new drop:

1. **Check whether it's plugin-API-relevant at all.** A content-only drop (new blocks/biomes/mobs) usually just means new enum constants exist to reference if you want them — it doesn't require touching existing plugin code unless you're specifically adding support for the new content.
2. **Check Paper's channel status** before recommending a server upgrade — a brand-new drop is typically experimental-only on Paper for some period before promotion to stable (see `SKILL.md`'s version snapshot, and Paper's own downloads page).
3. **Only worry about internals/NMS breakage** if the drop involved deeper engine refactors (these show up in modding-community changelogs — e.g. Fabric's own per-version compatibility notes — even though Paper's Bukkit-API abstraction shields most plugins from needing changes that mods would).
4. **Bump `api-version` only when you actually need something the new version added** — not reflexively on every release, per the floor-not-target guidance above.

Supporting several Minecraft versions from one plugin codebase gets harder the more you rely on internals rather than stable API:

- **Stay on the stable Bukkit-inherited API wherever possible.** Most of the Bukkit API surface (events, basic item/block/entity manipulation, scheduling, PDC) is backward-compatible across many Minecraft versions — a plugin that sticks to it can often set a low `api-version` and just work across a wide range without version-specific code branches at all.
- **Set `api-version` to the *lowest* version you actually support**, not the newest — this is a floor, not a target; the server refuses to load your plugin below that number, so setting it too high needlessly excludes users on slightly older (but still supported) server versions.
- **Isolate anything version-specific behind an interface**, with version-specific implementations selected at runtime (e.g. by checking `Bukkit.getBukkitVersion()` or a feature-detection check like "does this class/method exist") rather than scattering `if (version.startsWith("1.20"))`-style branches through your main logic.
- **For genuinely NMS-dependent code**, you generally need either a separate build per targeted version (using `paperweight-userdev` per version, and picking the right jar to ship based on the server's reported version) or a packet library like PacketEvents that already does this cross-version abstraction work for you (see the ecosystem-integrations reference) — hand-rolling raw NMS access across multiple versions is exactly the kind of thing the 2026 hard-fork's move to unobfuscated, real-name mappings was meant to make less painful, but it's still real work per version.
- **Test against your full supported version range in CI**, not just the latest one — spin up a matrix of Paper server versions (many CI setups use Docker images or the `itzg/docker-minecraft-server` image family) and run at least a smoke test (does the plugin load without error, do commands register) on each supported version as part of your pipeline.
