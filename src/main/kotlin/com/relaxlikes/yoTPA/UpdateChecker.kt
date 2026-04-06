package com.relaxlikes.yoTPA

import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.plugin.java.JavaPlugin
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.logging.Level

/**
 * Checks for plugin updates by querying the GitHub Releases API.
 * Runs asynchronously on startup and notifies OPs on join.
 */
class UpdateChecker(
    private val plugin: JavaPlugin,
    private val currentVersion: String,
    private val githubRepo: String  // e.g. "PhyschicWinter9/YoTPA"
) : Listener {

    @Volatile var latestVersion: String? = null
        private set

    @Volatile var updateAvailable: Boolean = false
        private set

    private val miniMessage = MiniMessage.miniMessage()
    private val apiUrl = "https://api.github.com/repos/$githubRepo/releases/latest"

    fun check() {
        val task = Runnable {
            try {
                val url = URI(apiUrl).toURL()
                val connection = url.openConnection() as HttpURLConnection
                connection.apply {
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "YoTPA-UpdateChecker")
                    connectTimeout = 5000
                    readTimeout = 5000
                }

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val body = connection.inputStream.bufferedReader().readText()
                    // Parse "tag_name" from JSON without a full JSON library
                    val match = Regex(""""tag_name"\s*:\s*"([^"]+)"""").find(body)
                    val tag = match?.groupValues?.get(1)?.trimStart('v', 'V')

                    if (tag != null) {
                        latestVersion = tag
                        updateAvailable = isNewer(tag, currentVersion)

                        if (updateAvailable) {
                            plugin.logger.info("════════════════════════════════════════")
                            plugin.logger.info("  Update available: v$currentVersion → v$tag")
                            plugin.logger.info("  https://modrinth.com/plugin/yotpa")
                            plugin.logger.info("════════════════════════════════════════")
                        } else {
                            plugin.logger.info("YoTPA is up to date (v$currentVersion)")
                        }
                    }
                } else {
                    plugin.logger.warning("Update check failed: HTTP ${connection.responseCode}")
                }

                connection.disconnect()
            } catch (e: IOException) {
                plugin.logger.log(Level.WARNING, "Could not check for updates: ${e.message}")
            }
        }
        if (YoTPA.isFolia) {
            plugin.server.asyncScheduler.runNow(plugin) { task.run() }
        } else {
            plugin.server.scheduler.runTaskAsynchronously(plugin, task)
        }
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        if (!player.hasPermission("yotpa.admin") && !player.isOp) return
        if (!updateAvailable) return

        val latest = latestVersion ?: return
        if (YoTPA.isFolia) {
            player.scheduler.runDelayed(plugin, { _ -> notifyPlayer(player, latest) }, null, 40L)
        } else {
            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                notifyPlayer(player, latest)
            }, 40L)
        }
    }

    private fun notifyPlayer(player: Player, latest: String) {
        val msg = miniMessage.deserialize(
            "<green><bold>[<aqua>YoTPA</aqua>]</bold></green>  <yellow>Update available:</yellow> " +
            "<white>v$currentVersion</white> <gray>→</gray> <green>v$latest</green> " +
            "<dark_gray>(<click:open_url:'https://modrinth.com/plugin/yotpa'>" +
            "<underlined>Download</underlined></click>)</dark_gray>"
        )
        player.sendMessage(msg)
        player.playSound(player.location, "minecraft:block.note_block.pling", 1.0f, 1.0f)
    }

    /**
     * Returns true if [candidate] is a newer semantic version than [current].
     * Handles versions like "1.5.0", "2.0", "26.1.1" etc.
     */
    private fun isNewer(candidate: String, current: String): Boolean {
        val c = candidate.split(".").map { it.toIntOrNull() ?: 0 }
        val r = current.split(".").map { it.toIntOrNull() ?: 0 }
        val len = maxOf(c.size, r.size)
        for (i in 0 until len) {
            val cv = c.getOrElse(i) { 0 }
            val rv = r.getOrElse(i) { 0 }
            if (cv > rv) return true
            if (cv < rv) return false
        }
        return false
    }
}
