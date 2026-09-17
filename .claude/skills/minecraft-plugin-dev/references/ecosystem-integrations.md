# Ecosystem Integrations

> Verified against official docs/GitHub sources in mid-August 2026: Vault/VaultUnlocked, PlaceholderAPI, bStats, Modrinth API, ProtocolLib, and PacketEvents. These are library/ecosystem facts rather than Minecraft-version facts, so they don't need a refresh on every Paper drop — but library version numbers still age faster than the patterns around them, so check each project's own repo before pinning an exact version in a real build.

## Table of Contents

1. [Economy: Vault and VaultUnlocked](#1-economy-vault-and-vaultunlocked)
2. [PlaceholderAPI](#2-placeholderapi)
3. [bStats Metrics](#3-bstats-metrics)
4. [Update-Checking](#4-update-checking)
5. [Packet-Level Control: ProtocolLib vs PacketEvents](#5-packet-level-control-protocollib-vs-packetevents)

---

## 1. Economy: Vault and VaultUnlocked

**Vault** is an abstraction layer, not an economy itself — it doesn't hold balances or manage permissions on its own. It lets your plugin ask "what's this player's balance?" or "does this player have this permission?" without hard-depending on whichever specific economy/permissions plugin the server owner picked (EssentialsX, CMI, etc.). As of 2026, classic Vault is still what the large majority of popular plugins hook into, but **VaultUnlocked** — a modern, actively maintained fork with Folia support, multi-currency, and full backward compatibility with the original Vault API — is increasingly the recommended target for new plugins. (There's also a newer alternative called **Treasury** gaining some traction, but Vault/VaultUnlocked remains the dominant standard.) Write against classic Vault if you want maximum compatibility with older economy plugins; write against VaultUnlocked if you want Folia-safety and multi-currency and are fine requiring a more modern provider.

**`paper-plugin.yml`**: soft-depend, since your plugin should still function (just without economy features) if no economy plugin is installed:

```yaml
dependencies:
  server:
    Vault:
      load: BEFORE
      required: false
```

**Classic Vault hook** (works with any Vault-compatible economy plugin):

```java
import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public final class ExamplePlugin extends JavaPlugin {

    private Economy economy;

    @Override
    public void onEnable() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            getLogger().warning("Vault not found — economy features disabled.");
            return;
        }
        RegisteredServiceProvider<Economy> provider = getServer().getServicesManager().getRegistration(Economy.class);
        if (provider == null) {
            getLogger().warning("No economy plugin registered with Vault — economy features disabled.");
            return;
        }
        this.economy = provider.getProvider();
    }
}
```

Usage:

```java
if (economy != null) {
    double balance = economy.getBalance(player);
    economy.withdrawPlayer(player, 100.0);
    economy.depositPlayer(player, 50.0);
}
```

**VaultUnlocked** follows the same `ServicesManager` pattern but imports from `net.milkbowl.vault2.economy.Economy` instead, and adds multi-currency methods on top. If you want the enhanced functionality (not just backward-compat mode), implement against the `vault2` package explicitly rather than the classic `vault` one.

Always null-check both the plugin presence *and* the service registration — a Vault-family plugin can be installed with zero economy providers behind it, which is a valid (if useless) server state your plugin shouldn't crash on.

## 2. PlaceholderAPI

PlaceholderAPI lets your plugin either **expose** placeholders for other plugins to use (`%yourplugin_something%`), or **consume** placeholders from any installed expansion inside your own messages.

**Exposing placeholders** — extend `PlaceholderExpansion`:

```java
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class ExampleExpansion extends PlaceholderExpansion {

    private final ExamplePlugin plugin;

    public ExampleExpansion(ExamplePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() { return "exampleplugin"; } // used as %exampleplugin_x%

    @Override
    public @NotNull String getAuthor() { return "YourName"; }

    @Override
    public @NotNull String getVersion() { return plugin.getDescription().getVersion(); }

    @Override
    public boolean persist() { return true; } // keeps this expansion registered across PlaceholderAPI reloads

    @Override
    public String onPlaceholderRequest(Player player, @NotNull String params) {
        if (player == null) return "";
        if (params.equals("coins")) {
            return String.valueOf(getCoins(player));
        }
        return null; // null = placeholder not recognized by this expansion
    }
}
```

Register it once PlaceholderAPI is confirmed present:

```java
@Override
public void onEnable() {
    if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
        new ExampleExpansion(this).register();
    }
}
```

`persist()` must return `true` for expansions registered from inside another plugin's code (as opposed to a standalone expansion jar dropped in PlaceholderAPI's own expansions folder) — otherwise PlaceholderAPI unregisters it on `/papi reload` and it silently stops working until the next server restart.

**Consuming placeholders from other plugins:**

```java
depend: [PlaceholderAPI]
```

```java
String parsed = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, "You are rank %vault_rank%");
```

`setPlaceholders` quietly leaves unrecognized placeholders untouched (it doesn't throw) if the relevant expansion or its underlying plugin isn't installed — plan your message formatting with that in mind rather than assuming every placeholder will resolve.

## 3. bStats Metrics

bStats is the standard, low-friction analytics service for Bukkit/Paper/Velocity plugins — anonymous usage metrics (player counts, server versions, OS, etc.) shown on a public dashboard for your plugin. It's opt-out for server owners via a global config, and is Folia-aware internally (it detects Folia and adjusts how it schedules its own reporting task, so you don't need to do anything special).

**Setup**: register a project at bstats.org to get a plugin ID, add the dependency, and — critically — shade **and relocate** it (this is not optional; without relocation, multiple plugins bundling bStats can collide on the same class on one server):

```kotlin
dependencies {
    implementation("org.bstats:bstats-bukkit:3.0.2") // check bstats.org for the current version
}

tasks.shadowJar {
    relocate("org.bstats", "io.github.yourname.exampleplugin.libs.bstats")
}
```

```java
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;

@Override
public void onEnable() {
    int pluginId = 12345; // your real ID from bstats.org — never hardcode a placeholder in shipped code
    Metrics metrics = new Metrics(this, pluginId);

    metrics.addCustomChart(new SimplePie("storage_backend", () -> getConfig().getString("storage", "yaml")));
}
```

## 4. Update-Checking

The safest pattern is: **check, notify, let the server owner update manually.** Don't auto-download and hot-swap your own jar — that's fragile (a running JVM can't cleanly replace its own loaded classes) and a security foot-gun if the check endpoint is ever compromised. A once-on-startup async check that logs/notifies ops is standard practice.

**Modrinth** (no auth needed for public read endpoints):

```java
Bukkit.getAsyncScheduler().runNow(this, task -> {
    try {
        URL url = new URL("https://api.modrinth.com/v2/project/YOUR_PROJECT_SLUG/version");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("User-Agent", "YourName/ExamplePlugin/" + getDescription().getVersion());
        // parse the JSON array response (e.g. with Gson) — the first entry is the newest version
        // compare its "version_number" field against getDescription().getVersion()
    } catch (IOException e) {
        getLogger().warning("Could not check for updates: " + e.getMessage());
    }
});
```

Always set a real `User-Agent` — Modrinth (like most APIs) may reject or rate-limit generic/default Java user agents. `GET /project/{id|slug}/version` returns versions newest-first, so the first element is the current release.

**Hangar** and the **SpigotMC** resource API expose comparable "latest version" endpoints if you'd rather check there instead of or in addition to Modrinth — the same async-fetch-and-compare pattern applies regardless of which platform you check against. If you want to support several sources without hand-rolling all three, community libraries like **PluginUpdater** wrap Modrinth/Hangar/SpigotMC/GitHub Releases checking behind one shadeable API — reasonable to reach for if update-checking isn't the core value of your plugin.

## 5. Packet-Level Control: ProtocolLib vs PacketEvents

Only reach for a packet library when the Bukkit/Paper event API genuinely can't do what you need (fake blocks/holograms, custom UI overlays, anti-cheat-style raw movement inspection, protocol-level tricks). For anything expressible as a normal event/API call, use that instead — packet manipulation is inherently more fragile across Minecraft versions.

**As of 2026, the community and library-ecosystem trend has shifted toward PacketEvents for new development**: it explicitly supports the unobfuscated, Mojang-mapped server jars from Paper's `26.1`+ hard fork directly, processes packets asynchronously on Netty threads (vs. ProtocolLib's synchronous-by-default model), and works across more platforms (Spigot, Paper, Velocity, Fabric, BungeeCord). ProtocolLib remains the older, more battle-tested option with the largest existing ecosystem (Citizens, LibsDisguises, and other long-established plugins still depend on it), but multiple sources note it has had stability and maintenance-pace concerns recently. **Recommendation: default to PacketEvents for new plugins; only reach for ProtocolLib if you're maintaining an existing ProtocolLib-based codebase or specifically need a dependency that requires it.** The two coexist fine on the same server if you have no choice.

### PacketEvents setup

```kotlin
repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io") // PacketEvents is commonly consumed via JitPack — check its README for the current canonical repo
}
dependencies {
    implementation("com.github.retrooper:packetevents-spigot:2.7.0") // check the PacketEvents repo for the current version
}
```

```java
import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;

public final class ExamplePlugin extends JavaPlugin {

    @Override
    public void onLoad() {
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEvents.getAPI().load(); // must happen in onLoad when bundling PacketEvents
    }

    @Override
    public void onEnable() {
        PacketEvents.getAPI().init();
        PacketEvents.getAPI().getEventManager().registerListener(new ExampleListener(), PacketListenerPriority.NORMAL);
    }

    @Override
    public void onDisable() {
        PacketEvents.getAPI().terminate();
    }
}
```

```java
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientChatMessage;

public class ExampleListener extends PacketListenerAbstract {
    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.CHAT_MESSAGE) return;
        WrapperPlayClientChatMessage chat = new WrapperPlayClientChatMessage(event);
        String message = chat.getMessage();
        // inspect/modify/cancel as needed
    }
}
```

### ProtocolLib setup (if required)

```java
ProtocolManager manager = ProtocolLibrary.getProtocolManager();
manager.addPacketListener(new PacketAdapter(this, ListenerPriority.NORMAL, PacketType.Play.Client.CHAT) {
    @Override
    public void onPacketReceiving(PacketEvent event) {
        PacketContainer packet = event.getPacket();
        String message = packet.getStrings().read(0);
        // inspect/modify/cancel as needed
    }
});
```

Both libraries evolve their exact artifact versions and repository locations frequently — check each project's own README/docs for the current dependency coordinates before pinning a version in a real build.
