# Reliability, Error Handling & Troubleshooting

> This is the "avoid errors" reference — the actual source of most real-world plugin bugs isn't exotic API misuse, it's thread-safety mistakes, unhandled null/edge cases, and silent load failures. Error messages verified against PaperMC GitHub issues, forums, and docs in mid-August 2026; these are structural patterns that don't go stale with each Minecraft drop the way exact version numbers do.

## Table of Contents

1. [Thread-Safety Pitfalls](#1-thread-safety-pitfalls)
2. [Error Handling Patterns](#2-error-handling-patterns)
3. [Config & Data Migration Across Plugin Versions](#3-config--data-migration-across-plugin-versions)
4. [Cyclic Loading & Dependency Pitfalls](#4-cyclic-loading--dependency-pitfalls)
5. [Troubleshooting Checklist: Common Load Errors](#5-troubleshooting-checklist-common-load-errors)

---

## 1. Thread-Safety Pitfalls

The single most common category of hard-to-reproduce plugin bugs is code that touches Bukkit/Paper API or shared mutable state from the wrong thread.

- **Never touch Bukkit API from a raw thread.** If you spin up your own `Thread`, `ExecutorService`, or use a bare `CompletableFuture` without specifying an executor, any Bukkit/Paper API call inside it (spawning entities, reading inventories, sending messages) is unsafe — even things that look read-only. Always hop back onto the correct scheduler (see the main reference, §9/§19) before touching game state, whether or not you're targeting Folia.
- **Shared mutable collections need explicit thread-safety.** A plain `HashMap` or `ArrayList` written to from an async task (e.g. after a database query) and read from the main/region thread is a race condition waiting to happen — intermittent `ConcurrentModificationException`s or silently corrupted state. Use `ConcurrentHashMap`/`CopyOnWriteArrayList`, or better, avoid sharing mutable state across threads at all: pass immutable snapshots through the scheduler instead.
- **Exceptions inside event handlers don't crash the server, but they don't roll back your own state either.** Bukkit's plugin manager catches and logs exceptions thrown from `@EventHandler` methods and continues calling other listeners — which is good for server stability, but means a half-finished operation in your handler (e.g. you deducted currency before an exception occurred later in the same method) can leave your plugin's data inconsistent with no crash to alert you. Structure handlers so partial failure doesn't corrupt state: validate everything you can up front, and only mutate persistent state after the risky part has already succeeded.
- **Don't assume `onDisable()` always runs cleanly.** Server crashes, forced kills, and `/stop` timeouts can all skip or interrupt your shutdown logic. Don't treat `onDisable()` as your only save point for important data — write incrementally (e.g. on each change, or on a periodic timer) rather than batching everything for shutdown.
- **`/reload` is not a safe operation**, and this isn't really about your plugin specifically — it's a known, longstanding anti-pattern in the wider ecosystem. It doesn't fully unload plugins' static state, scheduled tasks, or listener registrations the way a real restart does, and is a common source of "works until someone reloads" bug reports. If a user reports a bug and mentions using `/reload`, ask them to reproduce with a full restart before treating it as a bug in your code.

## 2. Error Handling Patterns

- **Null-check event getters defensively.** Many event methods can legitimately return `null` even in the "happy path" — `InventoryClickEvent#getCurrentItem()` returns `null` for an empty slot, `Player#getKiller()` returns `null` for non-PvP deaths, etc. Treat any getter documented as `@Nullable` (or, on newer Paper API, typed as genuinely nullable per JSpecify annotations) as something you must check, not assume.
- **Validate user input before parsing.** Command arguments and chat input are attacker/typo-controlled strings — wrap `Integer.parseInt`/`Double.parseDouble` in try/catch (or use Brigadier's typed arguments, which validate for you before your code even runs — see the main reference, §7) rather than letting a malformed `/setlevel abc` throw an uncaught `NumberFormatException` out of your command handler.
- **Catch specific exceptions, not bare `Exception`/`Throwable`, except at a genuine top-level boundary.** Swallowing broad exception types hides real bugs (a `NullPointerException` from your own logic error looks identical to an expected `IOException` if you catch both the same way) and makes debugging much harder later. It's fine to have one outermost catch-all around a task that must not crash a scheduler thread, but log the full stack trace there — don't silently discard it.
- **Rate-limit anything a player can trigger repeatedly and cheaply.** Commands, GUI clicks, and event handlers that do real work (DB writes, file I/O, expensive computation) should have per-player cooldowns if a player could otherwise spam them — this is both a performance concern and, in adversarial contexts, a denial-of-service concern.

## 3. Config & Data Migration Across Plugin Versions

The moment your plugin has been used in production and you need to change your config or stored-data format, you have an upgrade problem: existing servers have files in the *old* format, and your new code expects the *new* one.

**Config schema versioning.** Include a version field and check it on load:

```yaml
# config.yml
config-version: 2
# ... rest of config
```

```java
@Override
public void onEnable() {
    saveDefaultConfig();
    int version = getConfig().getInt("config-version", 1);
    if (version < 2) migrateConfigV1ToV2();
    if (version < 3) migrateConfigV2ToV3();
    // ... each step only runs if the file predates it
}

private void migrateConfigV1ToV2() {
    // e.g. a key was renamed or restructured
    if (getConfig().isSet("old-key-name")) {
        getConfig().set("new-key-name", getConfig().get("old-key-name"));
        getConfig().set("old-key-name", null);
    }
    getConfig().set("config-version", 2);
    saveConfig();
}
```

Running migrations as an ordered chain of small steps (rather than one big "convert whatever version to latest" function) keeps each step simple to reason about and lets you add a new step later without touching the old ones.

**Stored player/world data migration** is riskier than config because it's usually larger and harder for a server owner to manually fix if something goes wrong. A few rules:

- **Back up before mutating.** Copy the file (or export the relevant DB rows) with a timestamp suffix before running a migration, and log that you did so — this costs almost nothing and turns a botched migration from a disaster into an inconvenience.
- **Prefer lazy, per-record migration over one big blocking pass** where feasible — e.g. migrate a player's data the next time they join, rather than iterating every stored player file synchronously on server startup (which can stall startup on large servers and doesn't parallelize well with Folia's region model anyway).
- **Keep a migration flag separate from the data itself** (e.g. a `PersistentDataContainer` entry, or a small marker file/DB row) so a partially-completed migration is detectable and resumable rather than silently reapplied or silently skipped.
- **Version your own data format independently of the Minecraft/Paper data version** — don't conflate "what Minecraft version wrote this world" with "what shape is this plugin's stored data in." They change on unrelated schedules.

## 4. Cyclic Loading & Dependency Pitfalls

Paper's own docs are explicit about this: **"Unlike Bukkit plugins, Paper plugins will not attempt to resolve cyclic loading issues."** Cyclic loading is when plugin A's loading depends on plugin B, whose loading depends back on plugin A (directly or through a longer chain) — a loop that can prevent the server from starting at all, and Paper won't try to untangle it for you the way older Bukkit loaders sometimes attempted to.

Practical guidance:

- **Prefer soft dependencies (`softdepend` / `dependencies.server.<name>.required: false`) plus a runtime presence check** over hard dependencies wherever your plugin can degrade gracefully without the other plugin, rather than genuinely needing it to load at all.
- **Don't reach for `join-classpath: true`** (which gives your plugin direct access to another plugin's internal classpath) unless you specifically need to call that plugin's internals directly — it's a much stronger coupling than a normal service-lookup dependency (like the Vault `ServicesManager` pattern in the ecosystem-integrations reference) and increases the odds of exactly the kind of loop Paper won't resolve for you.
- **If you do hit a cyclic loading failure**, the fix is architectural, not configurational: break the cycle by having one side depend on an *interface/service* the other registers at runtime (soft dependency + `ServicesManager` or your own simple registry), rather than both plugins hard-requiring each other to exist before either can start.

## 5. Troubleshooting Checklist: Common Load Errors

| Symptom (console error) | Likely cause | Fix |
|---|---|---|
| `Unsupported API version X` | Your `api-version` in `plugin.yml`/`paper-plugin.yml` is higher than what the server supports — **or** you wrote `api-version: 1.20` unquoted and YAML parsed it as the *float* `1.2`, truncating the trailing zero. This is a real, documented bug pattern, not a hypothetical. | Lower `api-version` to something the server supports, and **always quote it as a string**: `api-version: '1.20'` / `api-version: '26.2'`. |
| `Could not load 'X.jar' in folder 'plugins'` + `InvalidDescriptionException` / "Invalid plugin.yml" | YAML syntax error in `plugin.yml`/`paper-plugin.yml` — bad indentation, tabs instead of spaces, an unquoted string containing a colon, etc. | Validate the YAML (an online YAML linter or your IDE's YAML support catches most of these instantly). |
| `Cannot find main class 'com.example.Foo'` | The `main:` field doesn't match the actual fully-qualified class name — typo, wrong package, or the class got stripped/relocated unexpectedly during shading. | Double-check `main:` matches the real package + class name exactly, and confirm the class survives your shadowJar/relocate config. |
| `InvalidPluginException: java.lang.IllegalArgumentException: Plugin cannot be null` | Usually a malformed or missing required field in the descriptor file (commonly `name`, `version`, or `main` missing or empty). | Confirm all required `plugin.yml`/`paper-plugin.yml` fields are present and non-empty. |
| Plugin loads, but the server hangs or fails to fully start with plugins referencing each other | Cyclic loading between two or more plugins (§4). | Break the cycle — convert one side to a soft dependency + runtime service lookup instead of a hard mutual dependency. |
| `NoClassDefFoundError: some/shaded/package/Class` at runtime | A dependency you rely on (including `kotlin-stdlib` for Kotlin plugins — see the Kotlin reference) wasn't actually shaded into the final jar, or got excluded/relocated incorrectly. | Check your shadowJar/shade-plugin configuration; confirm the class is actually present inside the built jar (`unzip -l yourplugin.jar \| grep ClassName`). |
| A listener never fires, no error at all | Listener instance was never passed to `registerEvents`, the event class was imported from the wrong package (Bukkit vs. Paper vs. a similarly-named class in another library), or an earlier-priority listener cancelled the event and you didn't set `ignoreCancelled = false` intentionally. | Confirm `registerEvents(new YourListener(), this)` actually runs in `onEnable()`, double-check the import, and check `EventPriority`/`ignoreCancelled` logic (main reference, §6). |
| Commands don't appear or don't tab-complete | Command registered too late (e.g. after players already joined and no reload of the command tree occurred), or registered via `LifecycleEvents.COMMANDS` incorrectly. | Prefer registering inside a `PluginBootstrap` when possible (main reference, §7) — it's the earliest, most reliable registration point and needs no manual re-registration handling. |
| Plugin behaves correctly on restart but breaks after `/reload` | `/reload`-related state corruption — see §1. | Reproduce with a full server restart before treating it as a genuine bug; document that `/reload` isn't supported if that's your plugin's stance (common, and reasonable). |

When in doubt and the error message itself isn't self-explanatory, paste the **full stack trace** (not just the first line) into a search — Paper's own GitHub issues and forums are the most reliable source for interpreting an unfamiliar exception from the platform itself, as opposed to your own plugin's logic.
