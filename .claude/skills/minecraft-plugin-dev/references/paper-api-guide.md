# Paper / Spigot / Folia API Reference (Java)

> Compiled against the post-hard-fork Paper ecosystem (Minecraft `26.x` versioning, Adventure 5), verified via official PaperMC docs/GitHub in mid-August 2026, with version numbers refreshed 2026-09-17. `paper-plugin.yml`, bootstrappers/loaders, and parts of the Command/Registry APIs are marked experimental by Paper itself — verify exact signatures against `https://jd.papermc.io/` for production code. **For which specific version is current/stable right now, see the Current Version Snapshot table in `SKILL.md` rather than trusting version numbers baked into this file's prose** — those drift with every Minecraft drop, while everything else in this file (the API patterns themselves) doesn't.

## Table of Contents

1. [The 2026 Hard Fork — What Changed](#1-the-2026-hard-fork--what-changed)
2. [Choosing a Platform](#2-choosing-a-platform)
3. [Environment & Project Setup](#3-environment--project-setup)
4. [plugin.yml vs paper-plugin.yml](#4-pluginyml-vs-paper-pluginyml)
5. [The Plugin Lifecycle](#5-the-plugin-lifecycle)
6. [The Event System](#6-the-event-system)
7. [Commands (Brigadier)](#7-commands-brigadier)
8. [Permissions](#8-permissions)
9. [Scheduling](#9-scheduling)
10. [Configuration Files](#10-configuration-files)
11. [Persistent Data Containers (PDC)](#11-persistent-data-containers-pdc)
12. [ItemStacks & the Data Component API](#12-itemstacks--the-data-component-api)
13. [Inventories & GUIs](#13-inventories--guis)
14. [Text & Adventure / MiniMessage](#14-text--adventure--minimessage)
15. [Entities, Worlds & Blocks](#15-entities-worlds--blocks)
16. [Registries API](#16-registries-api)
17. [Plugin Messaging Channels](#17-plugin-messaging-channels)
18. [Library & Dependency Loading](#18-library--dependency-loading)
19. [Supporting Folia](#19-supporting-folia)
20. [Databases](#20-databases)
21. [Testing](#21-testing)
22. [Performance Best Practices](#22-performance-best-practices)
23. [Building, Shading & Publishing](#23-building-shading--publishing)
24. [Full Worked Example](#24-full-worked-example)

---

## 1. The 2026 Hard Fork — What Changed

- **PaperMC hard-forked from Spigot** (announced Dec 2024, effectively complete through 2026). Paper no longer depends on Spigot's obfuscation/mapping pipeline; Paper holds roughly 85–90% of the modern-version server market.
- **Versioning changed.** Mojang moved to `YEAR.DROP[.PATCH]` starting in 2026 (`26.1`, hotfix `26.1.1`, `26.2`, `26.3` "Wilderness Bound" released 2026-09-15, etc.), replacing `1.21.x`. Don't parse version strings assuming a leading `1.`. New drops so far have landed roughly every three months and have been content-focused (new biomes/blocks) rather than plugin-API-breaking — but each one gets its own Paper build cycle, and Paper typically ships that build to its **experimental** channel first, promoting to **stable** only after it's had time to bake. Don't assume a brand-new drop is production-ready on Paper just because Mojang released it — check the channel (see `SKILL.md`'s version snapshot, or `https://papermc.io/downloads/paper` directly).
- **Unobfuscated, Mojang-mapped servers.** As of Paper `26.1`, server jars ship real class/method/field names; Paper's remapper for old obfuscated/Spigot names (`EntityHuman`, `PacketPlayIn...`) was dropped. Plugins using old-style internals/reflection **will not load on `26.1`+**, on Paper or Spigot. Use `paperweight-userdev` for internals access instead of hand rolling reflection against old names.
- **New Maven coordinates/version strings**: `groupId io.papermc.paper`, `artifactId paper-api`, versions like `26.2.build.+` (latest build, Gradle dynamic), `26.2.build.112-stable` (pinned), or `[26.2.build,)` (Maven dynamic range). Repository: `https://repo.papermc.io/repository/maven-public/`.
- **Adventure 5** shipped in Paper `26.2`, removing previously-deprecated text API.
- Vanilla gamerules became a registry with `snake_case` names (old `GameRule` class values still work for now).
- Beds are regular blocks again and lost PDC support — listen for `AsyncServerDataFixerRemoveBlockEntityEvent` if migrating stored data.
- **Paper requires Java 25 to run.**

## 2. Choosing a Platform

| Platform | Use when… |
|---|---|
| **Paper** | Default choice for any new plugin. Superset of Spigot capability. |
| **Spigot** | Maintaining a legacy plugin, or a host mandates it. No upside for new work. |
| **Folia** | Large (200+ concurrent player), spread-out worlds, willing to trade plugin compatibility for CPU parallelism. Not for lobbies/hubs where players cluster. |
| **Velocity** | Proxy layer in front of multiple backend servers, not a gameplay plugin. |

Folia is maintained by the Paper team as its own separate fork and, per the Folia repository itself, is **not** planned to merge into Paper for the foreseeable future.

## 3. Environment & Project Setup

**JDK**: Java 25 to run current Paper; use a full JDK distribution, never `-headless` (Paper needs packages headless builds omit). Corretto, Temurin, or Zulu are all fine.

**Build tool**: Gradle (Kotlin DSL) is the modern default; Maven still works for simpler projects.

**Package naming**: reverse domain (`com.example.pluginname`) or `io.github.username.pluginname` if you don't own a domain.

### Gradle (`build.gradle.kts`)

```kotlin
plugins {
    id("java")
    id("com.gradleup.shadow") version "8.3.5"
}

group = "io.github.yourname"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // 26.2 shown as the stable baseline as of writing — check SKILL.md's version snapshot
    // for the current stable/experimental split before picking a version for a real build
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(25)
    }
    build { dependsOn(shadowJar) }
}
```

`compileOnly` is intentional — Paper API classes exist on the server at runtime; never bundle them.

### Maven (`pom.xml`, relevant sections)

```xml
<properties>
    <maven.compiler.source>25</maven.compiler.source>
    <maven.compiler.target>25</maven.compiler.target>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
</properties>

<repositories>
    <repository>
        <id>papermc</id>
        <url>https://repo.papermc.io/repository/maven-public/</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>io.papermc.paper</groupId>
        <artifactId>paper-api</artifactId>
        <version>26.2.build.112-stable</version>
        <scope>provided</scope>
    </dependency>
</dependencies>
```

### Folder layout

```
my-plugin/
├── build.gradle.kts
├── settings.gradle.kts
└── src/main/
    ├── java/io/github/yourname/exampleplugin/ExamplePlugin.java
    └── resources/
        ├── paper-plugin.yml   (or plugin.yml)
        └── config.yml
```

## 4. plugin.yml vs paper-plugin.yml

Both live in `src/main/resources/`. `plugin.yml` is universally understood (Paper, Spigot). `paper-plugin.yml` is Paper-only and unlocks bootstrappers, custom loaders, and the modern lifecycle system.

### plugin.yml

```yaml
name: ExamplePlugin
version: '1.0.0'
main: io.github.yourname.exampleplugin.ExamplePlugin
api-version: '26.2'
author: YourName
description: An example plugin.
website: https://example.com
load: POSTWORLD          # STARTUP or POSTWORLD (default)
prefix: Example
depend: [Vault]
softdepend: [PlaceholderAPI]
permissions:
  exampleplugin.use:
    description: Allows use of /example
    default: true
```

Key fields: `name`/`version`/`main` required. `api-version` gates loading on older servers — set it to the lowest Paper version you actually support, not the newest available (see §2's multi-version note and `SKILL.md`'s version snapshot for what "newest" currently means). `load` controls STARTUP vs POSTWORLD timing. `depend`/`softdepend` control load order.

### paper-plugin.yml

```yaml
name: ExamplePlugin
version: '1.0.0'
main: io.github.yourname.exampleplugin.ExamplePlugin
api-version: '26.2'
folia-supported: true
authors: [YourName]
description: An example plugin.
website: https://example.com

bootstrapper: io.github.yourname.exampleplugin.ExamplePluginBootstrap
loader: io.github.yourname.exampleplugin.ExamplePluginLoader

dependencies:
  server:
    Vault:
      load: BEFORE
      required: false
      join-classpath: false

libraries:
  - com.google.code.gson:gson:2.11.0
```

Differences from classic `plugin.yml`:
- **No `commands:` block needed** — commands register programmatically via Brigadier (§7).
- **`bootstrapper`** — a `PluginBootstrap` implementation, runs before the server finishes starting; earliest point to register commands/lifecycle events.
- **`loader`** — a `PluginLoader` implementation, adds classpath libraries at load time.
- **`folia-supported: true`** is mandatory for the plugin to load on Folia at all (§19).
- **`libraries:`** auto-downloads/isolates Maven artifacts at runtime — no shading needed for those (§18).

## 5. The Plugin Lifecycle

```java
package io.github.yourname.exampleplugin;

import org.bukkit.plugin.java.JavaPlugin;

public final class ExamplePlugin extends JavaPlugin {

    @Override
    public void onLoad() {
        // Before any world loads — register things other plugins might need early.
    }

    @Override
    public void onEnable() {
        getLogger().info("ExamplePlugin has been enabled!");
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(new JoinListener(), this);
    }

    @Override
    public void onDisable() {
        getLogger().info("ExamplePlugin has been disabled!");
        // Cancel tasks, close DB connections, flush caches here.
    }
}
```

Don't name this class `Main` — pick something descriptive.

Bootstrapper (requires `paper-plugin.yml`), for work that needs to happen before the plugin instance exists:

```java
public final class ExamplePluginBootstrap implements PluginBootstrap {
    @Override
    public void bootstrap(BootstrapContext context) {
        context.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            // register commands here — see §7
        });
    }
}
```

## 6. The Event System

```java
package io.github.yourname.exampleplugin.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class JoinListener implements Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent event) {
        event.joinMessage(
            Component.text(event.getPlayer().getName() + " joined the server!", NamedTextColor.YELLOW)
        );
    }
}
```

Register: `getServer().getPluginManager().registerEvents(new JoinListener(), this);`

**Priority order** (call order, low→high): `LOWEST → LOW → NORMAL → HIGH → HIGHEST → MONITOR`. `MONITOR` is observation-only — never mutate event state there. `ignoreCancelled = true` skips your handler if already cancelled upstream.

**Custom events:**

```java
public class PlayerLevelUpEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final int newLevel;

    public PlayerLevelUpEvent(Player player, int newLevel) {
        this.player = player;
        this.newLevel = newLevel;
    }

    public Player getPlayer() { return player; }
    public int getNewLevel() { return newLevel; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
```

Fire with `getServer().getPluginManager().callEvent(new PlayerLevelUpEvent(player, level));` — make sure you fire from the correct thread/region if the plugin also supports Folia.

## 7. Commands (Brigadier)

Paper's command system is built on **Brigadier** (Mojang's own parser, the same one vanilla commands use) — real argument types, client-side tab completion and syntax highlighting for free. Register through `LifecycleEventManager` listening for `LifecycleEvents.COMMANDS`; this survives `/reload` automatically.

```java
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import com.mojang.brigadier.Command;

@Override
public void onEnable() {
    this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
        final Commands commands = event.registrar();
        commands.register(
            Commands.literal("hello")
                .executes(ctx -> {
                    ctx.getSource().getSender().sendPlainMessage("Hello, world!");
                    return Command.SINGLE_SUCCESS;
                })
                .build(),
            "Says hello",
            java.util.List.of("hi") // aliases
        );
    });
}
```

With an argument (`/greet <player>`):

```java
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;

commands.register(
    Commands.literal("greet")
        .then(Commands.argument("target", ArgumentTypes.player())
            .executes(ctx -> {
                PlayerSelectorArgumentResolver resolver = ctx.getArgument("target", PlayerSelectorArgumentResolver.class);
                Player target = resolver.resolve(ctx.getSource()).get(0);
                target.sendMessage("You were greeted by " + ctx.getSource().getSender().getName() + "!");
                return Command.SINGLE_SUCCESS;
            }))
        .build(),
    "Greets a player"
);
```

**Preferred registration point**: inside `PluginBootstrap#bootstrap()` (requires `paper-plugin.yml`) — commands registered there are also visible to datapack `function` command parsing.

**Legacy alternative** (works everywhere including Spigot): `CommandExecutor`/`TabCompleter` plus a `commands:` block in `plugin.yml` — still perfectly valid for simple commands:

```java
getCommand("hello").setExecutor((sender, command, label, args) -> {
    sender.sendMessage("Hello, world!");
    return true;
});
```

## 8. Permissions

```yaml
permissions:
  exampleplugin.admin:
    description: Grants access to all admin commands
    default: op
  exampleplugin.use:
    description: Grants access to basic commands
    default: true
  exampleplugin.*:
    default: op
    children:
      exampleplugin.admin: true
      exampleplugin.use: true
```

`default` accepts `true`, `false`, `op`, `not op`.

```java
if (!player.hasPermission("exampleplugin.use")) {
    player.sendMessage(Component.text("You don't have permission.", NamedTextColor.RED));
    return;
}
```

## 9. Scheduling

**Legacy `BukkitScheduler`** (works everywhere, **not** Folia-safe — assumes one main thread):

```java
getServer().getScheduler().runTaskLater(this, () -> player.sendMessage("Tick!"), 20L);
getServer().getScheduler().runTaskTimer(this, this::tickLogic, 20L, 100L);
getServer().getScheduler().runTaskAsynchronously(this, this::doNetworkCall);
```

**Modern region-aware schedulers** (Paper 1.20+; required for Folia compatibility — §19). Identical behavior on plain Paper; correctly target the owning thread on Folia:

```java
// Global — not tied to a location (periodic save-all, broadcasts)
Bukkit.getGlobalRegionScheduler().run(this, task -> doGlobalWork());
Bukkit.getGlobalRegionScheduler().runDelayed(this, task -> doGlobalWork(), 20L);

// Region-tied — a specific location/chunk region
Bukkit.getRegionScheduler().run(this, location, task -> spawnEffectAt(location));

// Entity-tied — follows the entity wherever it currently is
entity.getScheduler().run(this, task -> tickEntity(entity), null);

// Off-thread, not tied to any region
Bukkit.getAsyncScheduler().runNow(this, task -> doNetworkCall());
Bukkit.getAsyncScheduler().runDelayed(this, task -> doNetworkCall(), 1, TimeUnit.SECONDS);
```

**Rule of thumb**: task touches a specific block/entity/location → region or entity scheduler for that location/entity. Genuinely global → global scheduler. Blocking I/O → always async scheduler, never synchronous ones.

## 10. Configuration Files

```java
@Override
public void onEnable() {
    saveDefaultConfig(); // no-op if config.yml already exists
    reloadConfig();
}
```

```yaml
# config.yml
welcome-message: "&aWelcome to the server, %player%!"
feature-toggles:
  economy: true
  pvp: false
max-homes: 3
```

```java
FileConfiguration config = getConfig();
String message = config.getString("welcome-message", "Welcome!");
int maxHomes = config.getInt("max-homes", 3);
boolean economyEnabled = config.getBoolean("feature-toggles.economy", true);
```

Additional files:

```java
File file = new File(getDataFolder(), "items.yml");
if (!file.exists()) saveResource("items.yml", false);
YamlConfiguration itemsConfig = YamlConfiguration.loadConfiguration(file);
```

Never load/save on the main thread in a hot path — schedule async. For structured config with validation, consider **Configurate** (Sponge project) instead of raw `FileConfiguration`.

## 11. Persistent Data Containers (PDC)

Typed, NBT-backed custom data on items, entities, blocks, and chunks:

```java
NamespacedKey key = new NamespacedKey(this, "owner-uuid");

ItemMeta meta = item.getItemMeta();
meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, player.getUniqueId().toString());
item.setItemMeta(meta);

String ownerId = meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);

entity.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, 42);
```

Common types: `STRING`, `INTEGER`, `LONG`, `DOUBLE`, `BOOLEAN`, `BYTE_ARRAY`, `TAG_CONTAINER`, and `LIST` wrappers. Always namespace keys with `new NamespacedKey(this, "...")`.

> Beds lost PDC support in `26.x` (they became regular blocks again) — migrate any bed-stored data via `AsyncServerDataFixerRemoveBlockEntityEvent`.

## 12. ItemStacks & the Data Component API

Modern Paper (1.20.5+) models item properties as **data components**, mirroring vanilla's internal representation:

```java
ItemStack sword = ItemStack.of(Material.DIAMOND_SWORD);

sword.setData(DataComponentTypes.CUSTOM_NAME, Component.text("Excalibur", NamedTextColor.AQUA));
sword.setData(DataComponentTypes.LORE, ItemLore.lore(java.util.List.of(
    Component.text("A legendary blade", NamedTextColor.GRAY)
)));
sword.setData(DataComponentTypes.MAX_STACK_SIZE, 1);
sword.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
```

Classic `ItemMeta` (`getItemMeta()`, `setDisplayName(...)`, `setLore(...)`) still works and maps onto the same components — mixing both is fine, but prefer `DataComponentTypes` in new code for access to newer component types without `ItemMeta` equivalents.

## 13. Inventories & GUIs

```java
Inventory gui = Bukkit.createInventory(null, 27, Component.text("Shop"));
gui.setItem(13, sword);
player.openInventory(gui);
```

```java
@EventHandler
public void onClick(InventoryClickEvent event) {
    if (!event.getView().title().equals(Component.text("Shop"))) return;
    event.setCancelled(true); // always cancel unless you want free item movement

    ItemStack clicked = event.getCurrentItem();
    if (clicked == null || clicked.getType() == Material.AIR) return;
    // handle purchase logic here
}
```

Also handle `InventoryDragEvent` (block drag-duplication exploits) and clean up per-player GUI state in `InventoryCloseEvent`.

## 14. Text & Adventure / MiniMessage

```java
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

player.sendMessage(Component.text("Welcome!", NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
```

**MiniMessage** — human-friendly tag syntax for config-driven rich text:

```java
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

Component message = MiniMessage.miniMessage().deserialize(
    "<gold>Welcome, <player>!</gold> You have <yellow><coins></yellow> coins.",
    Placeholder.unparsed("player", player.getName()),
    Placeholder.unparsed("coins", String.valueOf(coins))
);
player.sendMessage(message);
```

Store MiniMessage strings in `config.yml` so server owners can restyle without touching code. **Paper `26.2` ships Adventure 5**, which drops previously-deprecated API — watch for compile errors around legacy serializers when porting old plugins.

## 15. Entities, Worlds & Blocks

```java
Zombie zombie = world.spawn(location, Zombie.class, z -> {
    z.customName(Component.text("Boss Zombie"));
    z.setCustomNameVisible(true);
    z.getAttribute(Attribute.MAX_HEALTH).setBaseValue(100);
    z.setHealth(100);
});

world.getNearbyEntities(location, 10, 10, 10).forEach(entity -> {
    if (entity instanceof Player player) player.sendMessage("You're near the boss!");
});

Block block = location.getBlock();
block.setType(Material.GLOWSTONE);
BlockData data = block.getBlockData();
```

Prefer bounding-box queries (`getNearbyEntities`) over scanning `world.getEntities()`. For bulk block edits, batch changes and avoid triggering physics per-block where a no-physics variant exists.

## 16. Registries API

```java
Registry<Enchantment> enchantments = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT);
Enchantment sharpness = enchantments.getOrThrow(EnchantmentKeys.SHARPNESS);
```

Registering new entries (e.g. custom trim materials) uses the same `LifecycleEvents`-based pattern as commands (§7). Check current docs for which registries are freezable/extensible, since this has changed across versions.

## 17. Plugin Messaging Channels

For plugin-to-proxy communication (Velocity/BungeeCord):

```java
getServer().getMessenger().registerOutgoingPluginChannel(this, "bungeecord:main");

ByteArrayDataOutput out = ByteStreams.newDataOutput();
out.writeUTF("Connect");
out.writeUTF("lobby");
player.sendPluginMessage(this, "bungeecord:main", out.toByteArray());
```

## 18. Library & Dependency Loading

`paper-plugin.yml` plugins can declare libraries instead of shading them:

```yaml
libraries:
  - com.google.code.gson:gson:2.11.0
  - org.apache.commons:commons-lang3:3.17.0
```

Paper's runtime resolver fetches and classpath-isolates these per plugin, shrinking your jar and avoiding version clashes. For everything else, or on classic `plugin.yml`, shade+relocate via Gradle Shadow / Maven Shade (§23).

## 19. Supporting Folia

Folia's regionized threading means there's no single main thread — each independently-ticking chunk **region** has its own owning thread.

**Step 1 — declare support** (mandatory, or the plugin won't load on Folia):

```yaml
folia-supported: true
```

**Step 2 — use region-aware schedulers everywhere** (§9), never `BukkitScheduler`.

**Step 3 — respect data isolation.** Regions tick in parallel and don't share mutable state directly; touching a region you don't own without going through a scheduler is a correctness bug, not a style issue — Paper's thread-context checks will throw.

```java
if (Bukkit.isOwnedByCurrentRegion(location)) {
    // safe to touch synchronously
} else {
    Bukkit.getRegionScheduler().run(plugin, location, task -> {
        // now safe — runs on the thread that owns `location`
    });
}
```

**Step 4 — entities need entity-specific scheduling** since they can cross region boundaries:

```java
entity.getScheduler().run(plugin, task -> {
    // follows the entity across regions automatically
}, null);
```

**Reality check for 2026**: Folia is still explicitly experimental. Many popular plugins (especially ones built around `BukkitScheduler` or raw NMS) remain incompatible. Audit every code path touching entities/blocks/chunk-local state if genuinely targeting Folia — a couple of region-scheduler calls isn't sufficient on its own.

## 20. Databases

SQLite for single-server persistence; MySQL/MariaDB/PostgreSQL for networked setups. Two rules matter more than which database:

1. **Never run JDBC on the main or a region thread** — dispatch through the async scheduler, then hop back to the appropriate scheduler for any resulting game-state change.
2. **Use a connection pool** (HikariCP is standard) rather than opening a connection per query.

```java
HikariConfig config = new HikariConfig();
config.setJdbcUrl("jdbc:mysql://localhost:3306/mydb");
config.setUsername("user");
config.setPassword("pass");
config.setMaximumPoolSize(10);
HikariDataSource dataSource = new HikariDataSource(config);

Bukkit.getAsyncScheduler().runNow(plugin, task -> {
    try (Connection conn = dataSource.getConnection();
         PreparedStatement stmt = conn.prepareStatement("SELECT coins FROM players WHERE uuid = ?")) {
        stmt.setString(1, player.getUniqueId().toString());
        ResultSet rs = stmt.executeQuery();
        // schedule any resulting game-state change back onto the right scheduler
    } catch (SQLException e) {
        plugin.getLogger().log(Level.SEVERE, "Database query failed", e);
    }
});
```

## 21. Testing

**MockBukkit** provides a mock server for JUnit tests without a real running server:

```java
@BeforeEach
void setUp() {
    server = MockBukkit.mock();
    plugin = MockBukkit.load(ExamplePlugin.class);
}

@AfterEach
void tearDown() { MockBukkit.unmock(); }

@Test
void testWelcomeMessage() {
    PlayerMock player = server.addPlayer();
    // assert on player.nextMessage(), inventory state, etc.
}
```

For anything MockBukkit doesn't cover (deep NMS behavior, real physics), use a disposable local test server.

## 22. Performance Best Practices

- Never block the main/region thread — file I/O, HTTP, DB queries go on the async scheduler.
- Cache expensive lookups (PDC reads, permission checks, config lookups) in hot paths.
- Prefer bounding-box entity/block queries over full scans.
- Be deliberate with event priorities — avoid expensive work in high-frequency `MONITOR` handlers (e.g. `PlayerMoveEvent`) unless necessary.
- Avoid synchronous chunk loading; prefer Paper's async chunk-loading methods.
- Profile with `spark` (bundled with recent Paper builds) before optimizing blind.
- On Folia, cross-region access without proper scheduling is incorrect, not just slow.

## 23. Building, Shading & Publishing

Relocate bundled third-party libraries to avoid classpath collisions with other plugins:

```kotlin
plugins {
    id("com.gradleup.shadow") version "8.3.5"
}

tasks.shadowJar {
    relocate("com.google.gson", "io.github.yourname.exampleplugin.libs.gson")
    archiveClassifier.set("")
}
```

**Publishing channels**: **Hangar** (`hangar.papermc.io`, PaperMC's own, primary recommended target for Paper/Folia plugins), **Modrinth** (general Minecraft content platform, well-regarded API), **SpigotMC** (long-running community marketplace, still widely used).

## 24. Full Worked Example

`/heal <player>` command with a config-driven message and a PDC-backed cooldown (survives restarts, works correctly across regions on Folia without extra sync logic).

**`paper-plugin.yml`**
```yaml
name: HealPlugin
version: '1.0.0'
main: io.github.yourname.healplugin.HealPlugin
api-version: '26.2'
folia-supported: true
authors: [YourName]
description: Heals a player with a cooldown, via /heal.
```

**`config.yml`**
```yaml
cooldown-seconds: 30
healed-message: "<green>You healed <target>!</green>"
```

**`HealPlugin.java`**
```java
package io.github.yourname.healplugin;

import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import com.mojang.brigadier.Command;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

public final class HealPlugin extends JavaPlugin {

    private NamespacedKey cooldownKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.cooldownKey = new NamespacedKey(this, "heal-cooldown-expiry");

        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            event.registrar().register(
                Commands.literal("heal")
                    .then(Commands.argument("target", ArgumentTypes.player())
                        .executes(this::executeHeal))
                    .build(),
                "Heals a player (with cooldown)"
            );
        });
    }

    private int executeHeal(com.mojang.brigadier.context.CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx) {
        PlayerSelectorArgumentResolver resolver = ctx.getArgument("target", PlayerSelectorArgumentResolver.class);
        Player target = resolver.resolve(ctx.getSource()).get(0);

        long now = System.currentTimeMillis();
        Long expiry = target.getPersistentDataContainer().get(cooldownKey, PersistentDataType.LONG);
        if (expiry != null && expiry > now) {
            ctx.getSource().getSender().sendPlainMessage("That player is still on cooldown.");
            return Command.SINGLE_SUCCESS;
        }

        target.setHealth(target.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue());

        long cooldownMs = getConfig().getInt("cooldown-seconds", 30) * 1000L;
        target.getPersistentDataContainer().set(cooldownKey, PersistentDataType.LONG, now + cooldownMs);

        var message = MiniMessage.miniMessage().deserialize(
            getConfig().getString("healed-message", "<green>You healed <target>!</green>"),
            Placeholder.unparsed("target", target.getName())
        );
        ctx.getSource().getSender().sendMessage(message);
        return Command.SINGLE_SUCCESS;
    }
}
```

### Reference links

- Paper developer docs: https://docs.papermc.io/paper/dev/
- Paper Javadocs: https://jd.papermc.io/
- Folia docs: https://docs.papermc.io/folia/ · Folia GitHub: https://github.com/PaperMC/Folia
- Hangar: https://hangar.papermc.io/
- Paper news/changelogs: https://papermc.io/news/
- MockBukkit: https://github.com/MockBukkit/MockBukkit
- Adventure: https://docs.advntr.dev/ · MiniMessage format: https://docs.advntr.dev/minimessage/format.html
