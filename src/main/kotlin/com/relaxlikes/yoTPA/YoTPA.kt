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

    // Use concurrent data structures for thread safety
    private val tpaRequests = ConcurrentHashMap<UUID, TpaRequest>()
    private val cooldowns = ConcurrentHashMap<UUID, Long>()
    private val teleportTasks = ConcurrentHashMap<UUID, Int>()
    private val teleportData = ConcurrentHashMap<UUID, TeleportData>()

    // Player name cache to avoid repeated lookups
    private val playerNameCache = ConcurrentHashMap<UUID, String>()

    // Configuration values with atomic references for thread-safe access
    @Volatile private var requestTimeout = 60
    @Volatile private var requestCooldown = 30
    @Volatile private var teleportDelay = 5
    @Volatile private var serverName = "RELAX"

    @Volatile private var countdownSound = Sound.BLOCK_NOTE_BLOCK_PLING
    @Volatile private var successSound = Sound.ENTITY_ENDERMAN_TELEPORT
    @Volatile private var cancelSound = Sound.ENTITY_VILLAGER_NO
    @Volatile private var requestSound = Sound.ENTITY_EXPERIENCE_ORB_PICKUP

    // Cached components to avoid recreation
    private val prefix = Component.text("[", NamedTextColor.GREEN, TextDecoration.BOLD)
        .append(Component.text("YoTPA", NamedTextColor.AQUA, TextDecoration.BOLD))
        .append(Component.text("] ", NamedTextColor.GREEN, TextDecoration.BOLD))
        .append(Component.text(""))

    // Shared executor for async tasks
    private lateinit var executor: ScheduledExecutorService

    // Cached title components
    private val titleCache = CachedTitleComponents()

    data class TeleportData(
        val destination: Player,
        val startTime: Long,
        val duration: Int,
        var lastShownSecond: Int = -1,
        var titleShown: Boolean = false
    )

    data class TpaRequest(
        val requesterUUID: UUID,
        val targetUUID: UUID,
        val timestamp: Long,
        val isHereRequest: Boolean
    )

    private data class CachedTitleComponents(
        val mainTitle: Component = Component.text("Teleporting in...")
            .color(NamedTextColor.GREEN)
            .decoration(TextDecoration.BOLD, true),
        val subtitle: Component = Component.text("Please don't move")
            .color(NamedTextColor.YELLOW)
            .decoration(TextDecoration.BOLD, true),
        val titleTimes: Title.Times = Title.Times.times(
            java.time.Duration.ofMillis(0),
            java.time.Duration.ofSeconds(7),
            java.time.Duration.ofMillis(500)
        )
    )

    private val bStats = bStatsTPA(this)


    override fun onEnable() {
        // Initialize executor service
        executor = Executors.newScheduledThreadPool(
            Runtime.getRuntime().availableProcessors(),
            { runnable -> Thread(runnable, "YoTPA-Executor-${Thread.activeCount()}") }
        )

        // Load configuration
        saveDefaultConfig()
        loadConfig()

        // Register commands
        registerCommands()

        // Register event listener
        server.pluginManager.registerEvents(PlayerMoveListener(this), this)

        // Start maintenance tasks
        startMaintenanceTasks()

        // Load BStats
        bStats.initialize()

        // Register event listener
        server.pluginManager.registerEvents(PlayerMoveListener(this), this)

        // Log startup
        logger.info("YoTPA Developer: PhyschicWinter9 & VIBEs Coding XD")
        logger.info("YoTPA Version: 1.2.0")
        logger.info("YoTPA plugin has been enabled!")
    }

    override fun onDisable() {
        // Cancel all active teleport tasks
        teleportTasks.values.forEach { taskId ->
            Bukkit.getScheduler().cancelTask(taskId)
        }

        // Shutdown executor service
        executor.shutdownNow()
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.warning("Executor service did not terminate in time!")
            }
        } catch (e: InterruptedException) {
            logger.log(Level.WARNING, "Interrupted while waiting for executor shutdown", e)
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

        when (command.name.lowercase(Locale.ENGLISH)) {
            "tpa" -> handleTpaCommand(sender, args)
            "tpaccept" -> handleTpAcceptCommand(sender)
            "tpadeny" -> handleTpDenyCommand(sender)
            "tpahere" -> handleTpaHereCommand(sender, args)
            "tpareload" -> handleReloadCommand(sender)
            "tpastats" -> handleStatsCommand(sender)
            else -> return false
        }

        return true
    }

    private fun handleTpaCommand(player: Player, args: Array<out String>) {
        if (args.isEmpty()) {
            sendMessage(player, Component.text("Usage: /tpa <player>", NamedTextColor.GRAY))
            return
        }

        val targetName = args[0]
        val target = getPlayerByName(targetName)

        if (target == null) {
            sendMessage(player, buildPlayerNotFoundMessage(targetName))
            return
        }

        if (target.uniqueId == player.uniqueId) {
            sendMessage(player, Component.text("You cannot teleport to yourself.", NamedTextColor.RED))
            return
        }

        if (isOnCooldown(player) && !player.hasPermission("yotpa.bypass.cooldown")) {
            sendCooldownMessage(player)
            return
        }

        // Create and store request
        storeRequest(player, target, false)

        // Update cooldown
        if (!player.hasPermission("yotpa.bypass.cooldown")) {
            cooldowns[player.uniqueId] = System.currentTimeMillis()
        }

        // Send messages
        sendTpaRequestMessages(player, target)
        playSound(target, requestSound)

        // Bstats request counter
        bStats.incrementRequestSent()
    }

    private fun handleTpaHereCommand(player: Player, args: Array<out String>) {
        if (args.isEmpty()) {
            sendMessage(player, Component.text("Usage: /tpahere <player>", NamedTextColor.YELLOW))
            return
        }

        val targetName = args[0]
        val target = getPlayerByName(targetName)

        if (target == null) {
            sendMessage(player, buildPlayerNotFoundMessage(targetName))
            return
        }

        if (target.uniqueId == player.uniqueId) {
            sendMessage(player, Component.text("You cannot teleport to yourself.", NamedTextColor.RED))
            return
        }

        if (isOnCooldown(player) && !player.hasPermission("yotpa.bypass.cooldown")) {
            sendCooldownMessage(player)
            return
        }

        // Create and store request
        storeRequest(player, target, true)

        // Update cooldown
        if (!player.hasPermission("yotpa.bypass.cooldown")) {
            cooldowns[player.uniqueId] = System.currentTimeMillis()
        }

        // Send messages
        sendTpaHereRequestMessages(player, target)
        playSound(target, requestSound)

        // Bstats request counter
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

        // Bstats acceptance counter
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
        }

        // Play cancel sounds
        playSound(player, cancelSound)
        requester?.let { playSound(it, cancelSound) }

        // Bstats denial counter
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

    // Optimized teleport countdown with precise timing
    fun startTeleportCountdown(teleporter: Player, destination: Player) {
        val originalLocation = teleporter.location.clone()

        // Cancel any existing teleport
        cancelTeleport(teleporter.uniqueId)

        // Create teleport data
        val data = TeleportData(
            destination = destination,
            startTime = System.currentTimeMillis(),
            duration = teleportDelay * 1000
        )
        teleportData[teleporter.uniqueId] = data

        // Use more frequent updates for smoother countdown
        val taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, {
            processCountdown(teleporter, data)
        }, 0L, 5L) // Every 0.25 seconds

        teleportTasks[teleporter.uniqueId] = taskId
        teleporter.setMetadata("yotpa:original-location", FixedMetadataValue(this, originalLocation))
    }

    private fun processCountdown(teleporter: Player, data: TeleportData) {
        val elapsed = System.currentTimeMillis() - data.startTime
        val remaining = data.duration - elapsed

        if (remaining <= 0) {
            performTeleport(teleporter, data.destination)
            cancelTeleport(teleporter.uniqueId)
        } else {
            val remainingSeconds = ((remaining + 500) / 1000).toInt()

            // Only show message when second changes
            if (remainingSeconds != data.lastShownSecond) {
                data.lastShownSecond = remainingSeconds
                sendMessage(teleporter, Component.text("Teleporting in ", NamedTextColor.GREEN).append(
                    Component.text("$remainingSeconds", NamedTextColor.YELLOW)
                        .decoration(TextDecoration.BOLD, true).append(Component.text(" seconds", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true))
                ))
                playSound(teleporter, countdownSound)

            }

            // Show title once
            if (!data.titleShown) {
                data.titleShown = true
                teleporter.showTitle(Title.title(
                    titleCache.mainTitle,
                    titleCache.subtitle,
                    titleCache.titleTimes
                ))
            }
        }
    }

    fun cancelTeleport(uuid: UUID) {
        teleportTasks.remove(uuid)?.let { taskId ->
            Bukkit.getScheduler().cancelTask(taskId)
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
        sendMessage(teleporter, Component.text("Teleported to ", NamedTextColor.GREEN).append(Component.text(destination.name, NamedTextColor.YELLOW)))

        // Play success sounds
        playSound(teleporter, successSound)
        playSound(destination, successSound)
    }

    // Optimized maintenance tasks
    private fun startMaintenanceTasks() {
        // Optimized expiration checker
        executor.scheduleAtFixedRate({
            try {
                checkExpiredRequests()
            } catch (e: Exception) {
                logger.log(Level.WARNING, "Error during expiration check", e)
            }
        }, 5, 5, TimeUnit.SECONDS)

        // Cache cleanup task
        executor.scheduleAtFixedRate({
            cleanupCaches()
        }, 60, 60, TimeUnit.SECONDS)
    }

    private fun checkExpiredRequests() {
        val currentTime = System.currentTimeMillis()
        val expiredRequests = ArrayList<UUID>()

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
                val targetPlayer = Bukkit.getPlayer(targetUuid)
                val requesterPlayer = Bukkit.getPlayer(request.requesterUUID)

                targetPlayer?.let { target ->
                    sendMessage(target, Component.text("Teleport request from ${getPlayerName(request.requesterUUID)} has expired.", NamedTextColor.RED))
                }

                requesterPlayer?.let { requester ->
                    sendMessage(requester, Component.text("Your teleport request to ${getPlayerName(targetUuid)} has expired.", NamedTextColor.RED))
                }
            }
            // Increment expired counter when a request expires
            bStats.incrementRequestExpired()
        }
    }

    private fun cleanupCaches() {
        // Clean up disconnected players from cache
        playerNameCache.keys.removeIf { uuid ->
            Bukkit.getPlayer(uuid) == null
        }
    }

    // Utility methods
    private fun registerCommands() {
        arrayOf("tpa", "tpaccept", "tpadeny", "tpahere", "tpareload").forEach { cmd ->
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

    private fun getPlayerByName(name: String): Player? {
        // First check exact match for efficiency
        return Bukkit.getPlayer(name) ?:
        // Then check UUID cache
        playerNameCache.entries.find { it.value.equals(name, ignoreCase = true) }?.let { Bukkit.getPlayer(it.key) }
    }

    private fun getPlayerName(uuid: UUID): String {
        return playerNameCache.computeIfAbsent(uuid) { id ->
            Bukkit.getPlayer(id)?.name ?: "Unknown"
        }
    }

    private fun storeRequest(requester: Player, target: Player, isHereRequest: Boolean) {
        val request = TpaRequest(requester.uniqueId, target.uniqueId, System.currentTimeMillis(), isHereRequest)
        tpaRequests[target.uniqueId] = request
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
        serverName = config.getString("server-name", "RELAX Vanilla SMP") ?: "RELAX Vanilla SMP"

        // Load sounds with error handling
        loadSounds()
    }

    private fun loadSounds() {
        try {
            countdownSound = Sound.BLOCK_NOTE_BLOCK_PLING
            successSound = Sound.ENTITY_ENDERMAN_TELEPORT
            cancelSound = Sound.ENTITY_VILLAGER_NO
            requestSound = Sound.ENTITY_EXPERIENCE_ORB_PICKUP
        } catch (e: Exception) {
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
            .append(Component.text("seconds before sending another request.", NamedTextColor.RED)))
    }

    private fun sendTpaRequestMessages(requester: Player, target: Player) {
        sendMessage(requester, Component.text("Teleport request sent to ", NamedTextColor.GREEN)
            .append(Component.text(target.name, NamedTextColor.YELLOW))
            .append(Component.text(".", NamedTextColor.GREEN)))

        sendMessage(target, Component.text(requester.name, NamedTextColor.YELLOW)
            .append(Component.text(" has requested to teleport to you.", NamedTextColor.GREEN)))
    }

    private fun sendTpaHereRequestMessages(requester: Player, target: Player) {
        sendMessage(requester, Component.text("Teleport request sent to ", NamedTextColor.GREEN)
            .append(Component.text(target.name, NamedTextColor.YELLOW))
            .append(Component.text(".", NamedTextColor.GREEN)))

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
        try {
            player.playSound(player.location, sound, 1.0f, 1.0f)
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Failed to play sound: $sound", e)
        }
    }

    private fun handleStatsCommand(player: Player) {
        if (!player.hasPermission("yotpa.stats")) {
            val message = Component.text("You don't have permission to view statistics.")
                .color(NamedTextColor.RED)
            player.sendMessage(prefix.append(message))
            return
        }

        // Get stats from bStats class
        val stats = bStats.getStatistics()
        val acceptanceRate = bStats.getAcceptanceRate()

        // Display statistics
        player.sendMessage(Component.text("===== YoTPA Statistics =====").color(NamedTextColor.GOLD))

        // All-time stats
        val allTimeStats = stats["All-Time"] ?: emptyMap()
        player.sendMessage(Component.text("All-Time Statistics:").color(NamedTextColor.AQUA))
        player.sendMessage(Component.text("• Requests Sent: ").color(NamedTextColor.YELLOW)
            .append(Component.text(allTimeStats["Sent"].toString()).color(NamedTextColor.WHITE)))
        player.sendMessage(Component.text("• Requests Accepted: ").color(NamedTextColor.GREEN)
            .append(Component.text(allTimeStats["Accepted"].toString()).color(NamedTextColor.WHITE)))
        player.sendMessage(Component.text("• Requests Denied: ").color(NamedTextColor.RED)
            .append(Component.text(allTimeStats["Denied"].toString()).color(NamedTextColor.WHITE)))
        player.sendMessage(Component.text("• Requests Expired: ").color(NamedTextColor.GRAY)
            .append(Component.text(allTimeStats["Expired"].toString()).color(NamedTextColor.WHITE)))
        player.sendMessage(Component.text("• Acceptance Rate: ").color(NamedTextColor.GOLD)
            .append(Component.text("$acceptanceRate%").color(NamedTextColor.WHITE)))

        // Daily stats
        val dailyStats = stats["Daily"] ?: emptyMap()
        player.sendMessage(Component.text("Today's Statistics:").color(NamedTextColor.AQUA))
        player.sendMessage(Component.text("• Requests Sent: ").color(NamedTextColor.YELLOW)
            .append(Component.text(dailyStats["Sent"].toString()).color(NamedTextColor.WHITE)))
        player.sendMessage(Component.text("• Requests Accepted: ").color(NamedTextColor.GREEN)
            .append(Component.text(dailyStats["Accepted"].toString()).color(NamedTextColor.WHITE)))
        player.sendMessage(Component.text("• Requests Denied: ").color(NamedTextColor.RED)
            .append(Component.text(dailyStats["Denied"].toString()).color(NamedTextColor.WHITE)))
        player.sendMessage(Component.text("• Requests Expired: ").color(NamedTextColor.GRAY)
            .append(Component.text(dailyStats["Expired"].toString()).color(NamedTextColor.WHITE)))
    }



}