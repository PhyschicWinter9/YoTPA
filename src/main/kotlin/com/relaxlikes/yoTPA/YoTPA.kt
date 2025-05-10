package com.relaxlikes.yoTPA

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
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
import java.util.logging.Level

class YoTPA : JavaPlugin() {

    // Maps to store teleport requests and cooldowns
    private val tpaRequests = ConcurrentHashMap<UUID, TpaRequest>()
    private val cooldowns = ConcurrentHashMap<UUID, Long>()
    private val teleportTasks = ConcurrentHashMap<UUID, Int>()

    // Configuration values
    private var requestTimeout = 60
    private var requestCooldown = 30
    private var teleportDelay = 5
    private var serverName = "RELAX"

    private var countdownSound = Sound.BLOCK_NOTE_BLOCK_PLING
    private var successSound = Sound.ENTITY_ENDERMAN_TELEPORT
    private var cancelSound = Sound.ENTITY_VILLAGER_NO
    private var requestSound = Sound.ENTITY_EXPERIENCE_ORB_PICKUP

    // Constants
    private var prefix = Component.text("[", NamedTextColor.GREEN, TextDecoration.BOLD)
        .append(Component.text("YoTPA", NamedTextColor.AQUA, TextDecoration.BOLD))
        .append(Component.text("] ", NamedTextColor.GREEN, TextDecoration.BOLD)).append(Component.text(""))


    override fun onEnable() {
        // Plugin startup logic
        // Save default config if not present
        saveDefaultConfig()
        // Load configuration values
        loadConfig()
        getCommand("tpa")?.setExecutor(this)
        getCommand("tpaccept")?.setExecutor(this)
        getCommand("tpadeny")?.setExecutor(this)
        getCommand("tpahere")?.setExecutor(this)
        getCommand("tpareload")?.setExecutor(this)

        // Register event listener
        server.pluginManager.registerEvents(PlayerMoveListener(this), this)

        // Start expiration checker task
        startExpirationChecker()

        logger.info("YoTPA plugin has been enabled for $serverName!")
    }

    override fun onDisable() {
        // Plugin shutdown logic
        teleportTasks.forEach { (_, taskId) ->
            Bukkit.getScheduler().cancelTask(taskId)
        }
        teleportTasks.clear()
        tpaRequests.clear()
        cooldowns.clear()

        logger.info("YoTPA plugin has been disabled!")
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            val message: TextComponent = Component.text("This command can only be used by players.")
                .color(NamedTextColor.RED)
            sender.sendMessage(prefix.append(message))
            return true
        }

        when (command.name.lowercase()) {
            "tpa" -> handleTpaCommand(sender, args)
            "tpaccept" -> handleTpAcceptCommand(sender)
            "tpadeny" -> handleTpDenyCommand(sender)
            "tpahere" -> handleTpaHereCommand(sender, args)
            "tpareload" -> handleReloadCommand(sender)
            else -> return false
        }

        return true
    }

    private fun handleTpaCommand(player: Player, args: Array<out String>) {
        if (args.isEmpty()) {
            val messageTpaHelper = Component.text("Usage: /tpa <player>")
                .color(NamedTextColor.GRAY)
            player.sendMessage(prefix.append(messageTpaHelper))
            return
        }

        val targetName = args[0]
        val target = Bukkit.getPlayer(targetName)

        if (target == null) {
            val messagePlayerNotFound = Component.text("Player")
                .color(NamedTextColor.RED)
                .append(Component.text(" $targetName ").color(NamedTextColor.YELLOW))
                .append(Component.text("not found or is offline.").color(NamedTextColor.RED))
            player.sendMessage(prefix.append(messagePlayerNotFound))
            return
        }

        if (target.uniqueId == player.uniqueId) {
            val messageSelfTeleport = Component.text("You cannot teleport to yourself.")
                .color(NamedTextColor.RED)
            player.sendMessage(prefix.append(messageSelfTeleport))
            return
        }

        if (isOnCooldown(player) && !player.hasPermission("yotpa.bypass.cooldown")) {
            val remainingCooldown = ((cooldowns[player.uniqueId]!! + (requestCooldown * 1000)) - System.currentTimeMillis()) / 1000
            val messageRequestCooldown = Component.text("You need to wait ")
                .color(NamedTextColor.RED)
                .append(Component.text("$remainingCooldown ").color(NamedTextColor.YELLOW))
                .append(Component.text("seconds before sending another request.").color(NamedTextColor.RED))
            player.sendMessage(prefix.append(messageRequestCooldown))
            return
        }

        // Create a new TPA request
        val request = TpaRequest(player.uniqueId, target.uniqueId, System.currentTimeMillis(), false)
        tpaRequests[target.uniqueId] = request

        // Set cooldown
        if (!player.hasPermission("yotpa.bypass.cooldown")) {
            cooldowns[player.uniqueId] = System.currentTimeMillis()
        }

        // Send messages to both players
        val messageRequestSent = Component.text("Teleport request sent to ")
            .color(NamedTextColor.GREEN)
            .append(Component.text(target.name).color(NamedTextColor.YELLOW))
            .append(Component.text(".").color(NamedTextColor.GREEN))
        val messageRequestReceived = Component.text(player.name)
            .color(NamedTextColor.YELLOW)
            .append(Component.text(" has requested to teleport to you.").color(NamedTextColor.GREEN))

        // FIXED: Actually send the messages!
        player.sendMessage(prefix.append(messageRequestSent))
        target.sendMessage(prefix.append(messageRequestReceived))

        // Play sound for target
        try {
            target.playSound(target.location, requestSound, 1.0f, 1.0f)
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Failed to play request sound", e)
        }
    }

    private fun handleTpaHereCommand(player: Player, args: Array<out String>) {
        if (args.isEmpty()) {
            val message = Component.text("Usage: /tpahere <player>")
                .color(NamedTextColor.YELLOW)
            player.sendMessage(prefix.append(message))
            return // FIXED: Added missing return
        }

        val targetName = args[0]
        val target = Bukkit.getPlayer(targetName)

        if (target == null) {
            val messagePlayerNotFound = Component.text("Player")
                .color(NamedTextColor.RED)
                .append(Component.text(" $targetName ").color(NamedTextColor.YELLOW))
                .append(Component.text("not found or is offline.").color(NamedTextColor.RED))
            player.sendMessage(prefix.append(messagePlayerNotFound))
            return
        }

        if (target.uniqueId == player.uniqueId) {
            val messageSelfTeleport = Component.text("You cannot teleport to yourself.")
                .color(NamedTextColor.RED)
            player.sendMessage(prefix.append(messageSelfTeleport))
            return
        }

        if (isOnCooldown(player) && !player.hasPermission("yotpa.bypass.cooldown")) {
            val remainingCooldown = ((cooldowns[player.uniqueId]!! + (requestCooldown * 1000)) - System.currentTimeMillis()) / 1000
            val messageRequestCooldown = Component.text("You need to wait ")
                .color(NamedTextColor.RED)
                .append(Component.text("$remainingCooldown ").color(NamedTextColor.YELLOW))
                .append(Component.text("seconds before sending another request.").color(NamedTextColor.RED))
            player.sendMessage(prefix.append(messageRequestCooldown))
            return
        }

        // Create a new TPA here request
        val request = TpaRequest(player.uniqueId, target.uniqueId, System.currentTimeMillis(), true)
        tpaRequests[target.uniqueId] = request

        // Set cooldown
        if (!player.hasPermission("yotpa.bypass.cooldown")) {
            cooldowns[player.uniqueId] = System.currentTimeMillis()
        }

        // Send messages to both players
        val messageRequestSent = Component.text("Teleport request sent to ")
            .color(NamedTextColor.GREEN)
            .append(Component.text(target.name).color(NamedTextColor.YELLOW))
            .append(Component.text(".").color(NamedTextColor.GREEN))
        val messageRequestReceived = Component.text(player.name)
            .color(NamedTextColor.YELLOW)
            .append(Component.text(" has requested you to teleport to them.").color(NamedTextColor.GREEN))

        // FIXED: Actually send the messages!
        player.sendMessage(prefix.append(messageRequestSent))
        target.sendMessage(prefix.append(messageRequestReceived))

        // Play sound for target
        try {
            target.playSound(target.location, requestSound, 1.0f, 1.0f)
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Failed to play request sound", e)
        }
    }

    private fun handleTpAcceptCommand(player: Player) {
        val request = tpaRequests[player.uniqueId]

        if (request == null) {
            val messageRequestNoPending = Component.text("You have no pending teleport requests.")
                .color(NamedTextColor.RED)
            player.sendMessage(prefix.append(messageRequestNoPending))
            return
        }

        val requester = Bukkit.getPlayer(request.requesterUUID)

        if (requester == null || !requester.isOnline) {
            val messagePlayerNotFound = Component.text("Player")
                .color(NamedTextColor.RED)
                .append(Component.text(" requester ").color(NamedTextColor.YELLOW))
                .append(Component.text("not found or is offline.").color(NamedTextColor.RED))
            player.sendMessage(prefix.append(messagePlayerNotFound))
            tpaRequests.remove(player.uniqueId)
            return
        }

        // Remove the request
        tpaRequests.remove(player.uniqueId)

        // Determine who is teleporting to whom
        val teleporter: Player
        val destination: Player

        if (request.isHereRequest) {
            // For /tpahere, the target teleports to the requester
            teleporter = player
            destination = requester
        } else {
            // For /tpa, the requester teleports to the target
            teleporter = requester
            destination = player
        }

        // Send messages
        val messageRequestAccepted = Component.text("You accepted ")
            .color(NamedTextColor.GREEN)
            .append(Component.text(requester.name).color(NamedTextColor.YELLOW))
            .append(Component.text("'s teleport request.").color(NamedTextColor.GREEN))
        val messageRequesterAccepted = Component.text(player.name)
            .color(NamedTextColor.YELLOW)
            .append(Component.text(" accepted your teleport request.").color(NamedTextColor.GREEN))
        player.sendMessage(prefix.append(messageRequestAccepted))
        requester.sendMessage(prefix.append(messageRequesterAccepted))

        // Start teleport countdown
        startTeleportCountdown(teleporter, destination)
    }

    private fun handleTpDenyCommand(player: Player) {
        val request = tpaRequests[player.uniqueId]

        if (request == null) {
            val messageRequestNoPending = Component.text("You have no pending teleport requests.")
                .color(NamedTextColor.RED)
            player.sendMessage(prefix.append(messageRequestNoPending))
            return
        }

        val requester = Bukkit.getPlayer(request.requesterUUID)

        // Remove the request
        tpaRequests.remove(player.uniqueId)

        // Send messages
        val messageRequestDenied = Component.text("You denied ")
            .color(NamedTextColor.RED)
            .append(Component.text(requester?.name ?: "Unknown").color(NamedTextColor.YELLOW))
            .append(Component.text("'s teleport request.").color(NamedTextColor.RED))
        player.sendMessage(prefix.append(messageRequestDenied))

        // Play denial sound
        try {
            player.playSound(player.location, cancelSound, 1.0f, 1.0f)
            requester?.playSound(requester.location, cancelSound, 1.0f, 1.0f)
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Failed to play cancel sound", e)
        }
    }

    private fun handleReloadCommand(player: Player) {
        if (!player.hasPermission("yotpa.reload")) {
            val messageNoPermission = Component.text("You don't have permission to reload the configuration.")
                .color(NamedTextColor.RED)
            player.sendMessage(prefix.append(messageNoPermission))
            return
        }

        reloadConfig()
        loadConfig()
        val messageConfigReloaded = Component.text("Configuration reloaded successfully.")
            .color(NamedTextColor.GREEN)
        player.sendMessage(prefix.append(messageConfigReloaded))
    }

    fun startTeleportCountdown(teleporter: Player, destination: Player) {
        val originalLocation = teleporter.location.clone()

        // Cancel any existing teleport task for this player
        cancelTeleport(teleporter.uniqueId)

        // FIXED: Store the start time properly
        val startTime = System.currentTimeMillis()
        var titleShown = false // Track if title has been shown

        val taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, Runnable {
            // FIXED: Calculate remaining seconds correctly
            val elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000
            val remainingSeconds = teleportDelay - elapsedSeconds


            if (remainingSeconds <= 0) {
                // Teleport the player
                performTeleport(teleporter, destination)

                // Cancel the task
                cancelTeleport(teleporter.uniqueId)
            } else {
                // Send countdown message
                val messageTeleportingCountdown = Component.text("Teleporting in ")
                    .color(NamedTextColor.GREEN)
                    .append(Component.text("$remainingSeconds ").color(NamedTextColor.YELLOW))
                    .append(Component.text("seconds...").color(NamedTextColor.GREEN))
                teleporter.sendMessage(prefix.append(messageTeleportingCountdown))

                if (!titleShown) {
                    val mainTitle = Component.text("Teleporting in...")
                        .color(NamedTextColor.GREEN)
                        .decoration(TextDecoration.BOLD, true)

                    val subtitle = Component.text("Please don't move")
                        .color(NamedTextColor.YELLOW)
                        .decoration(TextDecoration.BOLD, true)

                    // Set title to stay for the entire countdown duration plus some buffer
                    val titleTimes = Title.Times.times(
                        java.time.Duration.ofMillis(0),              // No fade in
                        java.time.Duration.ofSeconds((teleportDelay + 2).toLong()), // Stay for the entire countdown + buffer
                        java.time.Duration.ofMillis(500)             // Fade out
                    )

                    val title = Title.title(mainTitle, subtitle, titleTimes)
                    teleporter.showTitle(title)
                    titleShown = true
                }

                // Play sound
                try {
                    teleporter.playSound(teleporter.location, countdownSound, 1.0f, 1.0f)
                } catch (e: Exception) {
                    logger.log(Level.WARNING, "Failed to play countdown sound", e)
                }
            }
        }, 0L, 20L)

        teleportTasks[teleporter.uniqueId] = taskId

        // Store the player's original location to check for movement
        teleporter.setMetadata("yotpa:original-location", FixedMetadataValue(this, originalLocation))
    }

    fun cancelTeleport(uuid: UUID) {
        teleportTasks[uuid]?.let { taskId ->
            Bukkit.getScheduler().cancelTask(taskId)
            teleportTasks.remove(uuid)
        }

        val player = Bukkit.getPlayer(uuid)
        player?.removeMetadata("yotpa:original-location", this)
    }

    fun cancelTeleportDueToMovement(player: Player) {
        cancelTeleport(player.uniqueId)
        val messageTeleportCancelled = Component.text("Teleportation cancelled due to movement.")
            .color(NamedTextColor.RED)
        player.sendMessage(prefix.append(messageTeleportCancelled))

        // Play cancel sound
        try {
            player.playSound(player.location, cancelSound, 1.0f, 1.0f)
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Failed to play cancel sound", e)
        }
    }

    private fun performTeleport(teleporter: Player, destination: Player) {
        teleporter.teleport(destination)
        val messageTeleportSuccess = Component.text("Teleported to ")
            .color(NamedTextColor.GREEN)
            .append(Component.text(destination.name).color(NamedTextColor.YELLOW))
            .append(Component.text(".").color(NamedTextColor.GREEN))

        teleporter.sendMessage(prefix.append(messageTeleportSuccess))

        // Play success sound
        try {
            teleporter.playSound(teleporter.location, successSound, 1.0f, 1.0f)
            destination.playSound(destination.location, successSound, 1.0f, 1.0f)
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Failed to play success sound", e)
        }
    }

    private fun startExpirationChecker() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, Runnable {
            val currentTime = System.currentTimeMillis()
            val expiredRequests = ArrayList<UUID>()

            // Find expired requests
            tpaRequests.forEach { (targetUuid, request) ->
                if (currentTime - request.timestamp > requestTimeout * 1000 &&
                    !Bukkit.getPlayer(request.requesterUUID)?.hasPermission("yotpa.bypass.timeout")!!) {
                    expiredRequests.add(targetUuid)
                }
            }

            // Remove expired requests
            Bukkit.getScheduler().runTask(this, Runnable {
                expiredRequests.forEach { targetUuid ->
                    val request = tpaRequests[targetUuid]
                    if (request != null) {
                        val targetPlayer = Bukkit.getPlayer(targetUuid)
                        val requesterPlayer = Bukkit.getPlayer(request.requesterUUID)

                        tpaRequests.remove(targetUuid)

                        val messageRequestExpired = Component.text("Teleport request from ")
                            .color(NamedTextColor.RED)
                            .append(Component.text(requesterPlayer?.name ?: "Unknown").color(NamedTextColor.YELLOW))
                            .append(Component.text(" has expired.").color(NamedTextColor.RED))
                        targetPlayer?.sendMessage(prefix.append(messageRequestExpired))

                        val messageRequesterExpired = Component.text("Your teleport request to ")
                            .color(NamedTextColor.RED)
                            .append(Component.text(targetPlayer?.name ?: "Unknown").color(NamedTextColor.YELLOW))
                            .append(Component.text(" has expired.").color(NamedTextColor.RED))
                        requesterPlayer?.sendMessage(prefix.append(messageRequesterExpired))
                    }
                }
            })
        }, 20L, 20L) // Check every second
    }

    private fun isOnCooldown(player: Player): Boolean {
        val lastRequest = cooldowns[player.uniqueId] ?: return false
        return System.currentTimeMillis() - lastRequest < requestCooldown * 1000
    }

    private fun loadConfig() {
        reloadConfig()
        requestTimeout = config.getInt("request-timeout", 60)
        requestCooldown = config.getInt("request-cooldown", 30)
        teleportDelay = config.getInt("teleport-delay", 5)
        serverName = config.getString("server-name", "RELAX Vanilla SMP") ?: "RELAX Vanilla SMP"

        // Load sounds
        try {
            countdownSound = Sound.BLOCK_NOTE_BLOCK_PLING
            successSound = Sound.ENTITY_ENDERMAN_TELEPORT
            cancelSound = Sound.ENTITY_VILLAGER_NO
            requestSound = Sound.ENTITY_EXPERIENCE_ORB_PICKUP
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Invalid sound name in config, using defaults", e)
        }
    }

    data class TpaRequest(
        val requesterUUID: UUID,
        val targetUUID: UUID,
        val timestamp: Long,
        val isHereRequest: Boolean
    )
}