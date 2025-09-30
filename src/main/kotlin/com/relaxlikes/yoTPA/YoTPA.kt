package com.relaxlikes.yoTPA

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.plugin.java.JavaPlugin
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.logging.Level

class YoTPA : JavaPlugin() {

    // Thread-safe data structures
    private val tpaRequests = ConcurrentHashMap<UUID, TpaRequest>()
    private val cooldowns = ConcurrentHashMap<UUID, Long>()
    private val teleportTasks = ConcurrentHashMap<UUID, Int>()
    private val teleportData = ConcurrentHashMap<UUID, TeleportData>()
    private val playerNameCache = ConcurrentHashMap<UUID, String>()

    // Configuration values with volatile for thread-safe access
    @Volatile private var requestTimeout = 60
    @Volatile private var requestCooldown = 30
    @Volatile private var teleportDelay = 5
    @Volatile private var serverName = "RELAX"

    @Volatile private var countdownSound = Sound.BLOCK_NOTE_BLOCK_PLING
    @Volatile private var successSound = Sound.ENTITY_ENDERMAN_TELEPORT
    @Volatile private var cancelSound = Sound.ENTITY_VILLAGER_NO
    @Volatile private var requestSound = Sound.ENTITY_EXPERIENCE_ORB_PICKUP

    // Cached components to avoid recreation
    private val prefix by lazy {
        Component.text("[", NamedTextColor.GREEN, TextDecoration.BOLD)
            .append(Component.text("YoTPA", NamedTextColor.AQUA, TextDecoration.BOLD))
            .append(Component.text("] ", NamedTextColor.GREEN, TextDecoration.BOLD))
    }

    // Shared executor for async tasks
    private lateinit var executor: ScheduledExecutorService

    // Cached title components
    private val titleCache by lazy { CachedTitleComponents() }

    private lateinit var bStats: bStatsTPA

    data class TeleportData(
        val destination: Player,
        val startTime: Long,
        val duration: Int,
        var lastShownSecond: Int = -1
    )

    data class TpaRequest(
        val requesterUUID: UUID,
        val targetUUID: UUID,
        val timestamp: Long,
        val isHereRequest: Boolean
    )

    private data class CachedTitleComponents(
        val mainTitle: Component = Component.text("Teleporting...")
            .color(NamedTextColor.GREEN)
            .decoration(TextDecoration.BOLD, true),
        val subtitle: Component = Component.text("Don't move!")
            .color(NamedTextColor.YELLOW)
            .decoration(TextDecoration.BOLD, true),
        val titleTimes: Title.Times = Title.Times.times(
            java.time.Duration.ofMillis(250),
            java.time.Duration.ofSeconds(6),
            java.time.Duration.ofMillis(500)
        )
    )

    override fun onEnable() {
        // Initialize executor service with optimal thread count
        val threadCount = minOf(4, Runtime.getRuntime().availableProcessors())
        executor = Executors.newScheduledThreadPool(threadCount) { runnable ->
            Thread(runnable, "YoTPA-Worker").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY
            }
        }

        // Load configuration
        saveDefaultConfig()
        loadConfig()

        // Initialize bStats
        bStats = bStatsTPA(this)
        bStats.initialize()

        // Register commands
        registerCommands()

        // Register event listener (only once!)
        server.pluginManager.registerEvents(PlayerMoveListener(this), this)

        // Start maintenance tasks
        startMaintenanceTasks()

        // Log startup
        logger.info("YoTPA Developer: PhyschicWinter9 & VIBEs Coding XD")
        logger.info("YoTPA Version: 1.2.2-Optimized")
        logger.info("YoTPA plugin has been enabled!")
    }

    override fun onDisable() {
        // Cancel all active teleport tasks
        teleportTasks.values.forEach { taskId ->
            runCatching { Bukkit.getScheduler().cancelTask(taskId) }
        }

        // Shutdown executor service gracefully
        executor.shutdown()
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow()
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    logger.warning("Executor service did not terminate!")
                }
            }
        } catch (e: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }

        // Clear all data
        clearAllData()

        logger.info("YoTPA plugin has been disabled!")
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sendMessage(sender, Component.text("This command can only be used by players.", NamedTextColor.RED))
            return true
        }

        return when (command.name.lowercase(Locale.ENGLISH)) {
            "tpa" -> { handleTpaCommand(sender, args); true }
            "tpaccept" -> { handleTpAcceptCommand(sender); true }
            "tpadeny" -> { handleTpDenyCommand(sender); true }
            "tpahere" -> { handleTpaHereCommand(sender, args); true }
            "tpareload" -> { handleReloadCommand(sender); true }
            "tpastats" -> { handleStatsCommand(sender); true }
            else -> false
        }
    }

    private fun handleTpaCommand(player: Player, args: Array<out String>) {
        if (args.isEmpty()) {
            sendMessage(player, Component.text("Usage: /tpa <player>", NamedTextColor.GRAY))
            return
        }

        val target = resolvePlayer(player, args[0], false) ?: return

        if (!validateTeleportRequest(player, target)) return

        // Create and store request
        storeRequest(player, target, false)
        updateCooldown(player)

        // Send messages and play sound
        sendTpaRequestMessages(player, target)
        playSound(target, requestSound)
        bStats.incrementRequestSent()
    }

    private fun handleTpaHereCommand(player: Player, args: Array<out String>) {
        if (args.isEmpty()) {
            sendMessage(player, Component.text("Usage: /tpahere <player>", NamedTextColor.YELLOW))
            return
        }

        val target = resolvePlayer(player, args[0], true) ?: return

        if (!validateTeleportRequest(player, target)) return

        // Create and store request
        storeRequest(player, target, true)
        updateCooldown(player)

        // Send messages and play sound
        sendTpaHereRequestMessages(player, target)
        playSound(target, requestSound)
        bStats.incrementRequestSent()
    }

    private fun handleTpAcceptCommand(player: Player) {
        val request = tpaRequests.remove(player.uniqueId)

        if (request == null) {
            sendMessage(player, Component.text("You have no pending teleport requests.", NamedTextColor.RED))
            return
        }

        val requester = Bukkit.getPlayer(request.requesterUUID)

        if (requester == null || !requester.isOnline) {
            sendMessage(player, Component.text("Requester is offline.", NamedTextColor.RED))
            return
        }

        // Determine teleporter and destination
        val (teleporter, destination) = if (request.isHereRequest) {
            player to requester
        } else {
            requester to player
        }

        // Send acceptance messages
        sendAcceptanceMessages(player, requester)

        // Start teleport countdown
        startTeleportCountdown(teleporter, destination)
        bStats.incrementRequestAccepted()
    }

    private fun handleTpDenyCommand(player: Player) {
        val request = tpaRequests.remove(player.uniqueId)

        if (request == null) {
            sendMessage(player, Component.text("You have no pending teleport requests.", NamedTextColor.RED))
            return
        }

        val requester = Bukkit.getPlayer(request.requesterUUID)
        val requesterName = requester?.name ?: getPlayerName(request.requesterUUID)

        // Send denial messages
        sendMessage(player, Component.text("You denied $requesterName's teleport request.", NamedTextColor.RED))
        requester?.let {
            sendMessage(it, Component.text("${player.name} denied your teleport request.", NamedTextColor.RED))
            playSound(it, cancelSound)
        }

        playSound(player, cancelSound)
        bStats.incrementRequestDenied()
    }

    private fun handleReloadCommand(player: Player) {
        if (!player.hasPermission("yotpa.reload")) {
            sendMessage(player, Component.text("You don't have permission to reload the configuration.", NamedTextColor.RED))
            return
        }

        reloadConfig()
        loadConfig()
        sendMessage(player, Component.text("Configuration reloaded successfully.", NamedTextColor.GREEN))
    }

    private fun handleStatsCommand(player: Player) {
        if (!player.hasPermission("yotpa.stats")) {
            sendMessage(player, Component.text("You don't have permission to view statistics.", NamedTextColor.RED))
            return
        }

        // Get stats from bStats class
        val stats = bStats.getStatistics()
        val acceptanceRate = bStats.getAcceptanceRate()

        // Display statistics
        player.sendMessage(Component.text("===== YoTPA Statistics =====").color(NamedTextColor.GOLD))

        // All-time stats
        stats["All-Time"]?.let { allTimeStats ->
            player.sendMessage(Component.text("All-Time Statistics:").color(NamedTextColor.AQUA))
            displayStats(player, allTimeStats)
            player.sendMessage(Component.text("• Acceptance Rate: ").color(NamedTextColor.GOLD)
                .append(Component.text("$acceptanceRate%").color(NamedTextColor.WHITE)))
        }

        // Daily stats
        stats["Daily"]?.let { dailyStats ->
            player.sendMessage(Component.text("Today's Statistics:").color(NamedTextColor.AQUA))
            displayStats(player, dailyStats)
        }
    }

    private fun displayStats(player: Player, stats: Map<String, Any>) {
        player.sendMessage(Component.text("• Requests Sent: ").color(NamedTextColor.YELLOW)
            .append(Component.text(stats["Sent"].toString()).color(NamedTextColor.WHITE)))
        player.sendMessage(Component.text("• Requests Accepted: ").color(NamedTextColor.GREEN)
            .append(Component.text(stats["Accepted"].toString()).color(NamedTextColor.WHITE)))
        player.sendMessage(Component.text("• Requests Denied: ").color(NamedTextColor.RED)
            .append(Component.text(stats["Denied"].toString()).color(NamedTextColor.WHITE)))
        player.sendMessage(Component.text("• Requests Expired: ").color(NamedTextColor.GRAY)
            .append(Component.text(stats["Expired"].toString()).color(NamedTextColor.WHITE)))
    }

    // Optimized teleport countdown - runs every second instead of every 0.25s
    fun startTeleportCountdown(teleporter: Player, destination: Player) {
        // Cancel any existing teleport
        cancelTeleport(teleporter.uniqueId)

        // Store original location
        val originalLocation = teleporter.location.clone()
        teleporter.setMetadata("yotpa:original-location", FixedMetadataValue(this, originalLocation))

        // Create teleport data
        val data = TeleportData(
            destination = destination,
            startTime = System.currentTimeMillis(),
            duration = teleportDelay * 1000,
            lastShownSecond = teleportDelay
        )
        teleportData[teleporter.uniqueId] = data

        // Show title immediately
        teleporter.showTitle(Title.title(
            titleCache.mainTitle,
            titleCache.subtitle,
            titleCache.titleTimes
        ))

        // Initial countdown message
        sendCountdownMessage(teleporter, teleportDelay)

        // Run every 1 second (20 ticks) for better performance
        val taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, {
            processCountdown(teleporter, data)
        }, 20L, 20L)

        teleportTasks[teleporter.uniqueId] = taskId
    }

    private fun processCountdown(teleporter: Player, data: TeleportData) {
        val elapsed = System.currentTimeMillis() - data.startTime
        val remaining = data.duration - elapsed

        if (remaining <= 0) {
            performTeleport(teleporter, data.destination)
            cancelTeleport(teleporter.uniqueId)
        } else {
            val remainingSeconds = ((remaining + 999) / 1000).toInt()

            // Only show message when second changes
            if (remainingSeconds != data.lastShownSecond && remainingSeconds > 0) {
                data.lastShownSecond = remainingSeconds
                sendCountdownMessage(teleporter, remainingSeconds)
                playSound(teleporter, countdownSound)
            }
        }
    }

    private fun sendCountdownMessage(player: Player, seconds: Int) {
        sendMessage(player, Component.text("Teleporting in ", NamedTextColor.GREEN)
            .append(Component.text(seconds.toString(), NamedTextColor.YELLOW, TextDecoration.BOLD))
            .append(Component.text(" second${if (seconds != 1) "s" else ""}", NamedTextColor.GREEN, TextDecoration.BOLD)))
    }

    fun cancelTeleport(uuid: UUID) {
        teleportTasks.remove(uuid)?.let { taskId ->
            runCatching { Bukkit.getScheduler().cancelTask(taskId) }
        }
        teleportData.remove(uuid)
        Bukkit.getPlayer(uuid)?.removeMetadata("yotpa:original-location", this)
    }

    fun cancelTeleportDueToMovement(player: Player) {
        cancelTeleport(player.uniqueId)
        sendMessage(player, Component.text("Teleportation cancelled due to movement.", NamedTextColor.RED))
        playSound(player, cancelSound)
    }

    private fun performTeleport(teleporter: Player, destination: Player) {
        teleporter.teleport(destination)
        sendMessage(teleporter, Component.text("Teleported to ", NamedTextColor.GREEN)
            .append(Component.text(destination.name, NamedTextColor.YELLOW)))

        // Play success sounds
        playSound(teleporter, successSound)
        playSound(destination, successSound)
    }

    // Optimized maintenance tasks
    private fun startMaintenanceTasks() {
        // Request expiration checker - every 10 seconds
        executor.scheduleAtFixedRate({
            runCatching { checkExpiredRequests() }
                .onFailure { e -> logger.log(Level.WARNING, "Error during expiration check", e) }
        }, 10, 10, TimeUnit.SECONDS)

        // Cache cleanup - every 2 minutes
        executor.scheduleAtFixedRate({
            runCatching { cleanupCaches() }
                .onFailure { e -> logger.log(Level.WARNING, "Error during cache cleanup", e) }
        }, 120, 120, TimeUnit.SECONDS)

        // Cooldown cleanup - every 5 minutes
        executor.scheduleAtFixedRate({
            runCatching { cleanupExpiredCooldowns() }
                .onFailure { e -> logger.log(Level.WARNING, "Error during cooldown cleanup", e) }
        }, 300, 300, TimeUnit.SECONDS)
    }

    private fun checkExpiredRequests() {
        val currentTime = System.currentTimeMillis()
        val expiredRequests = mutableListOf<UUID>()

        tpaRequests.forEach { (targetUuid, request) ->
            if (currentTime - request.timestamp > requestTimeout * 1000L) {
                val requester = Bukkit.getPlayer(request.requesterUUID)
                if (requester?.hasPermission("yotpa.bypass.timeout") != true) {
                    expiredRequests.add(targetUuid)
                }
            }
        }

        if (expiredRequests.isNotEmpty()) {
            Bukkit.getScheduler().runTask(this, Runnable {
                processExpiredRequests(expiredRequests)
            })
        }
    }

    private fun processExpiredRequests(expiredRequests: List<UUID>) {
        expiredRequests.forEach { targetUuid ->
            tpaRequests.remove(targetUuid)?.let { request ->
                Bukkit.getPlayer(targetUuid)?.let { target ->
                    sendMessage(target, Component.text("Teleport request from ${getPlayerName(request.requesterUUID)} has expired.", NamedTextColor.RED))
                }

                Bukkit.getPlayer(request.requesterUUID)?.let { requester ->
                    sendMessage(requester, Component.text("Your teleport request to ${getPlayerName(targetUuid)} has expired.", NamedTextColor.RED))
                }

                bStats.incrementRequestExpired()
            }
        }
    }

    private fun cleanupCaches() {
        // More efficient cleanup using iterator
        val iterator = playerNameCache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (Bukkit.getPlayer(entry.key) == null) {
                iterator.remove()
            }
        }
    }

    private fun cleanupExpiredCooldowns() {
        val currentTime = System.currentTimeMillis()
        val cooldownExpiry = requestCooldown * 1000L

        val iterator = cooldowns.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (currentTime - entry.value > cooldownExpiry) {
                iterator.remove()
            }
        }
    }

    // Utility methods
    private fun registerCommands() {
        arrayOf("tpa", "tpaccept", "tpadeny", "tpahere", "tpareload", "tpastats").forEach { cmd ->
            getCommand(cmd)?.setExecutor(this)
        }
    }

    private fun clearAllData() {
        teleportTasks.clear()
        teleportData.clear()
        tpaRequests.clear()
        cooldowns.clear()
        playerNameCache.clear()
    }

    private fun resolvePlayer(sender: Player, targetName: String, isHereRequest: Boolean): Player? {
        val target = getPlayerByName(targetName)

        if (target == null) {
            sendMessage(sender, buildPlayerNotFoundMessage(targetName))
            return null
        }

        return target
    }

    private fun validateTeleportRequest(requester: Player, target: Player): Boolean {
        if (target.uniqueId == requester.uniqueId) {
            sendMessage(requester, Component.text("You cannot teleport to yourself.", NamedTextColor.RED))
            return false
        }

        if (isOnCooldown(requester) && !requester.hasPermission("yotpa.bypass.cooldown")) {
            sendCooldownMessage(requester)
            return false
        }

        return true
    }

    private fun getPlayerByName(name: String): Player? {
        // Optimized lookup: exact match first, then case-insensitive
        return Bukkit.getPlayer(name) ?:
        Bukkit.getOnlinePlayers().find { it.name.equals(name, ignoreCase = true) }
    }

    private fun getPlayerName(uuid: UUID): String {
        return playerNameCache.getOrPut(uuid) {
            Bukkit.getPlayer(uuid)?.name ?: "Unknown"
        }
    }

    private fun storeRequest(requester: Player, target: Player, isHereRequest: Boolean) {
        tpaRequests[target.uniqueId] = TpaRequest(
            requester.uniqueId,
            target.uniqueId,
            System.currentTimeMillis(),
            isHereRequest
        )
    }

    private fun updateCooldown(player: Player) {
        if (!player.hasPermission("yotpa.bypass.cooldown")) {
            cooldowns[player.uniqueId] = System.currentTimeMillis()
        }
    }

    private fun isOnCooldown(player: Player): Boolean {
        val lastRequest = cooldowns[player.uniqueId] ?: return false
        return System.currentTimeMillis() - lastRequest < requestCooldown * 1000L
    }

    private fun loadConfig() {
        reloadConfig()
        requestTimeout = config.getInt("request-timeout", 60)
        requestCooldown = config.getInt("request-cooldown", 30)
        teleportDelay = config.getInt("teleport-delay", 5)
        serverName = config.getString("server-name") ?: "RELAX Vanilla SMP"
        loadSounds()
    }

    private fun loadSounds() {
        runCatching {
            countdownSound = Sound.BLOCK_NOTE_BLOCK_PLING
            successSound = Sound.ENTITY_ENDERMAN_TELEPORT
            cancelSound = Sound.ENTITY_VILLAGER_NO
            requestSound = Sound.ENTITY_EXPERIENCE_ORB_PICKUP
        }.onFailure { e ->
            logger.log(Level.WARNING, "Invalid sound name in config, using defaults", e)
            setDefaultSounds()
        }
    }

    private fun setDefaultSounds() {
        countdownSound = Sound.BLOCK_NOTE_BLOCK_PLING
        successSound = Sound.ENTITY_ENDERMAN_TELEPORT
        cancelSound = Sound.ENTITY_VILLAGER_NO
        requestSound = Sound.ENTITY_EXPERIENCE_ORB_PICKUP
    }

    // Message helper methods
    private fun sendMessage(sender: CommandSender, message: Component) {
        sender.sendMessage(prefix.append(message))
    }

    private fun buildPlayerNotFoundMessage(playerName: String): Component {
        return Component.text("Player ", NamedTextColor.RED)
            .append(Component.text(playerName, NamedTextColor.YELLOW))
            .append(Component.text(" not found or is offline.", NamedTextColor.RED))
    }

    private fun sendCooldownMessage(player: Player) {
        val remainingCooldown = ((cooldowns[player.uniqueId]!! + (requestCooldown * 1000L)) - System.currentTimeMillis()) / 1000
        sendMessage(player, Component.text("You need to wait ", NamedTextColor.RED)
            .append(Component.text("$remainingCooldown ", NamedTextColor.YELLOW))
            .append(Component.text("second${if (remainingCooldown != 1L) "s" else ""} before sending another request.", NamedTextColor.RED)))
    }

    private fun sendTpaRequestMessages(requester: Player, target: Player) {
        sendMessage(requester, Component.text("Teleport request sent to ", NamedTextColor.GREEN)
            .append(Component.text(target.name, NamedTextColor.YELLOW)))

        sendMessage(target, Component.text(requester.name, NamedTextColor.YELLOW)
            .append(Component.text(" has requested to teleport to you.", NamedTextColor.GREEN)))
    }

    private fun sendTpaHereRequestMessages(requester: Player, target: Player) {
        sendMessage(requester, Component.text("Teleport request sent to ", NamedTextColor.GREEN)
            .append(Component.text(target.name, NamedTextColor.YELLOW)))

        sendMessage(target, Component.text(requester.name, NamedTextColor.YELLOW)
            .append(Component.text(" has requested you to teleport to them.", NamedTextColor.GREEN)))
    }

    private fun sendAcceptanceMessages(accepter: Player, requester: Player) {
        sendMessage(accepter, Component.text("You accepted ", NamedTextColor.GREEN)
            .append(Component.text(requester.name, NamedTextColor.YELLOW))
            .append(Component.text("'s teleport request.", NamedTextColor.GREEN)))

        sendMessage(requester, Component.text(accepter.name, NamedTextColor.YELLOW)
            .append(Component.text(" accepted your teleport request.", NamedTextColor.GREEN)))
    }

    private fun playSound(player: Player, sound: Sound) {
        runCatching {
            player.playSound(player.location, sound, 1.0f, 1.0f)
        }.onFailure { e ->
            logger.log(Level.WARNING, "Failed to play sound: $sound", e)
        }
    }
}