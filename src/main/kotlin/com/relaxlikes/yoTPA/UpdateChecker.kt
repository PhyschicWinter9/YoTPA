package com.relaxlikes.yoTPA

import net.kyori.adventure.platform.bukkit.BukkitAudiences
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.logging.Level

/**
 * Checks for plugin updates by querying the GitHub Releases API.
 *
 * Behaviour:
 * - Startup: checks immediately, logs result to console, notifies OPs on join
 * - Daily: re-checks every 24 hours; notifies online OPs only when a new version
 *   is discovered — completely silent if nothing changed
 */
class UpdateChecker(
    private val plugin: YoTPA,
    private val currentVersion: String,
    private val githubRepo: String,
    private val audiences: BukkitAudiences
) : Listener {

    @Volatile var latestVersion: String? = null
        private set

    @Volatile var updateAvailable: Boolean = false
        private set

    // Version we last alerted about — prevents re-notifying for the same release
    @Volatile private var notifiedVersion: String? = null

    private val miniMessage = MiniMessage.miniMessage()
    private val apiUrl = "https://api.github.com/repos/$githubRepo/releases/latest"

    private var periodicFoliaTask: Any? = null
    private var periodicTaskId: Int = -1

    companion object {
        private const val CHECK_INTERVAL_HOURS = 24L
    }

    /** Run the startup check and schedule the daily re-check. */
    fun check() {
        runCheck(isStartup = true)
        scheduleDailyCheck()
    }

    /** Cancel the daily task — call from onDisable(). */
    fun shutdown() {
        runCatching { periodicFoliaTask?.javaClass?.getMethod("cancel")?.invoke(periodicFoliaTask) }
        if (periodicTaskId != -1) {
            runCatching { plugin.server.scheduler.cancelTask(periodicTaskId) }
        }
    }

    // ─── Scheduling ───────────────────────────────────────────────────────────

    private fun scheduleDailyCheck() {
        val (foliaTask, paperId) = plugin.scheduleRepeatingAsync(
            Runnable { runCheck(isStartup = false) },
            CHECK_INTERVAL_HOURS, CHECK_INTERVAL_HOURS, TimeUnit.HOURS
        )
        periodicFoliaTask = foliaTask
        periodicTaskId = paperId
    }

    // ─── HTTP check ───────────────────────────────────────────────────────────

    private fun runCheck(isStartup: Boolean) {
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

            try {
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val body = connection.inputStream.bufferedReader().readText()
                    val match = Regex(""""tag_name"\s*:\s*"([^"]+)"""").find(body)
                    val tag = match?.groupValues?.get(1)?.trimStart('v', 'V')

                    if (tag != null) {
                        latestVersion = tag
                        updateAvailable = isNewer(tag, currentVersion)

                        if (isStartup) {
                            // Startup: always log whether an update was found or not
                            if (updateAvailable) {
                                plugin.logger.info("════════════════════════════════════════")
                                plugin.logger.info("  Update available: v$currentVersion → v$tag")
                                plugin.logger.info("  https://modrinth.com/plugin/yotpa")
                                plugin.logger.info("════════════════════════════════════════")
                            } else {
                                plugin.logger.info("YoTPA is up to date (v$currentVersion)")
                            }
                        } else if (updateAvailable && tag != notifiedVersion) {
                            // Daily check: only act when a version we haven't seen before is found
                            notifiedVersion = tag
                            plugin.logger.info("[YoTPA] New version available: v$currentVersion → v$tag")
                            notifyOnlineOps(tag)
                        }
                        // Daily check + no new version = completely silent
                    }
                } else {
                    plugin.logger.warning("Update check failed: HTTP ${connection.responseCode}")
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: IOException) {
            plugin.logger.log(Level.WARNING, "Could not check for updates: ${e.message}")
        }
    }

    // ─── Notifications ────────────────────────────────────────────────────────

    /** Notify all currently online OPs/admins immediately when a new version is detected. */
    private fun notifyOnlineOps(latest: String) {
        plugin.runForOnlineOps { player -> notifyPlayer(player, latest) }
    }

    /** Notify a joining OP/admin if an update is already known. */
    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        if (!player.hasPermission("yotpa.admin") && !player.isOp) return
        if (!updateAvailable) return

        val latest = latestVersion ?: return
        plugin.runDelayedForPlayer(player, Runnable { notifyPlayer(player, latest) }, 40L)
    }

    private fun notifyPlayer(player: Player, latest: String) {
        val msg = miniMessage.deserialize(
            "<green><bold>[<aqua>YoTPA</aqua>]</bold></green>  <yellow>Update available:</yellow> " +
            "<white>v$currentVersion</white> <gray>→</gray> <green>v$latest</green> " +
            "<dark_gray>(<click:open_url:'https://modrinth.com/plugin/yotpa'>" +
            "<underlined>Download</underlined></click>)</dark_gray>"
        )
        audiences.player(player).sendMessage(msg)
        player.playSound(player.location, "minecraft:block.note_block.pling", 1.0f, 1.0f)
    }

    // ─── Version comparison ───────────────────────────────────────────────────

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
