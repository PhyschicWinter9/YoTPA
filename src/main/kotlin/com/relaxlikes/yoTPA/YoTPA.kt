package com.relaxlikes.yoTPA

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.Registry
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

    // Performance mode enum
    enum class PerformanceMode {
        AUTO,           // Auto-detect based on available RAM
        ULTRA_LIGHT,    // For 512 MB - 1 GB RAM
        LIGHT,          // For 1-2 GB RAM
        BALANCED,       // For 2-4 GB RAM (default)
        HIGH_PERFORMANCE // For 4+ GB RAM
    }

    // Current performance mode
    @Volatile private var performanceMode: PerformanceMode = PerformanceMode.AUTO
    @Volatile private var detectedMode: PerformanceMode = PerformanceMode.BALANCED

    // Thread-safe data structures with adaptive sizing
    private lateinit var tpaRequests: ConcurrentHashMap<UUID, TpaRequest>
    private lateinit var cooldowns: ConcurrentHashMap<UUID, Long>
    private lateinit var teleportTasks: ConcurrentHashMap<UUID, Int>
    private lateinit var teleportData: ConcurrentHashMap<UUID, TeleportData>
    private lateinit var playerNameCache: ConcurrentHashMap<UUID, String>

    // Configuration values
    @Volatile private var requestTimeout = 60
    @Volatile private var requestCooldown = 30
    @Volatile private var teleportDelay = 5

    // Sound keys - store as Key instead of Sound
    @Volatile private var countdownSoundKey = NamespacedKey.minecraft("block.note_block.pling")
    @Volatile private var successSoundKey = NamespacedKey.minecraft("entity.enderman.teleport")
    @Volatile private var cancelSoundKey = NamespacedKey.minecraft("entity.villager.no")
    @Volatile private var requestSoundKey = NamespacedKey.minecraft("entity.experience_orb.pickup")

    // Cached components
    private val prefix by lazy {
        Component.text("[", NamedTextColor.GREEN, TextDecoration.BOLD)
            .append(Component.text("YoTPA", NamedTextColor.AQUA, TextDecoration.BOLD))
            .append(Component.text("] ", NamedTextColor.GREEN, TextDecoration.BOLD))
    }

    // Executor service (nullable for ultra-light mode)
    private var executor: ScheduledExecutorService? = null

    // Cached title components
    private val titleCache by lazy { CachedTitleComponents() }

    private lateinit var bStats: bStatsTPA

    // Performance settings based on mode
    private data class PerformanceSettings(
        val useExecutor: Boolean,
        val executorThreads: Int,
        val countdownInterval: Long,
        val expirationInterval: Long,
        val cleanupInterval: Long,
        val initialCapacity: Int,
        val loadFactor: Float,
        val concurrencyLevel: Int,
        val enablePlayerCache: Boolean,
        val enableTeleportDataCache: Boolean,
        val movementThreshold: Double
    )

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
        // Load configuration first
        saveDefaultConfig()
        loadConfig()

        // Detect and apply performance mode
        detectAndApplyPerformanceMode()

        // Initialize data structures with adaptive sizing
        initializeDataStructures()

        // Initialize executor if needed
        initializeExecutor()

        // Initialize bStats
        bStats = bStatsTPA(this)
        bStats.initialize()

        // Register commands and events
        registerCommands()
        server.pluginManager.registerEvents(PlayerMoveListener(this, getMovementThreshold()), this)

        // Start maintenance tasks
        startMaintenanceTasks()

        // Log startup info
        logger.info("═══════════════════════════════════════")
        logger.info("YoTPA Developer: PhyschicWinter9 & VIBEs Coding XD")
        logger.info("YoTPA Version: 1.4.0")
        logger.info("Performance Mode: ${detectedMode.name}")
        logger.info("Optimization Level: ${getOptimizationLevel()}")
        logger.info("═══════════════════════════════════════")
    }

    override fun onDisable() {
        // Cancel all active teleport tasks
        teleportTasks.values.forEach { taskId ->
            runCatching { Bukkit.getScheduler().cancelTask(taskId) }
        }

        // Shutdown executor service if exists
        executor?.let { exec ->
            exec.shutdown()
            try {
                if (!exec.awaitTermination(5, TimeUnit.SECONDS)) {
                    exec.shutdownNow()
                    if (!exec.awaitTermination(5, TimeUnit.SECONDS)) {
                        logger.warning("Executor service did not terminate!")
                    }
                }
            } catch (_: InterruptedException) {
                exec.shutdownNow()
                Thread.currentThread().interrupt()
            }
        }

        // Clear all data
        clearAllData()

        logger.info("YoTPA plugin has been disabled!")
    }

    /**
     * Detect available RAM and set appropriate performance mode
     */
    private fun detectAndApplyPerformanceMode() {
        val configMode = try {
            PerformanceMode.valueOf(config.getString("performance.mode", "AUTO")!!.uppercase())
        } catch (_: Exception) {
            PerformanceMode.AUTO
        }

        performanceMode = configMode

        detectedMode = if (configMode == PerformanceMode.AUTO) {
            val maxMemory = getMaxMemoryMB()
            when {
                maxMemory <= 768 -> PerformanceMode.ULTRA_LIGHT  // ≤ 768 MB
                maxMemory <= 1536 -> PerformanceMode.LIGHT       // ≤ 1.5 GB
                maxMemory <= 3072 -> PerformanceMode.BALANCED    // ≤ 3 GB
                else -> PerformanceMode.HIGH_PERFORMANCE         // > 3 GB
            }
        } else {
            configMode
        }

        logger.info("Performance mode: $performanceMode (detected: $detectedMode)")
    }

    /**
     * Initialize data structures based on performance mode
     */
    private fun initializeDataStructures() {
        val settings = getPerformanceSettings()

        tpaRequests = ConcurrentHashMap(settings.initialCapacity, settings.loadFactor, settings.concurrencyLevel)
        cooldowns = ConcurrentHashMap(settings.initialCapacity * 2, settings.loadFactor, settings.concurrencyLevel)
        teleportTasks = ConcurrentHashMap(settings.initialCapacity, settings.loadFactor, settings.concurrencyLevel)

        teleportData = if (settings.enableTeleportDataCache) {
            ConcurrentHashMap(settings.initialCapacity, settings.loadFactor, settings.concurrencyLevel)
        } else {
            ConcurrentHashMap(4, 0.75f, 1)
        }

        playerNameCache = if (settings.enablePlayerCache) {
            ConcurrentHashMap(settings.initialCapacity * 2, settings.loadFactor, settings.concurrencyLevel)
        } else {
            ConcurrentHashMap(4, 0.75f, 1)
        }
    }

    /**
     * Initialize executor service based on performance mode
     */
    private fun initializeExecutor() {
        val settings = getPerformanceSettings()

        if (settings.useExecutor) {
            executor = Executors.newScheduledThreadPool(settings.executorThreads) { runnable ->
                Thread(runnable, "YoTPA-Worker-${Thread.activeCount()}").apply {
                    isDaemon = true
                    priority = Thread.NORM_PRIORITY
                }
            }
        }
    }

    /**
     * Get performance settings based on detected mode
     */
    private fun getPerformanceSettings(): PerformanceSettings {
        return when (detectedMode) {
            PerformanceMode.ULTRA_LIGHT -> PerformanceSettings(
                useExecutor = false,
                executorThreads = 0,
                countdownInterval = 20L,
                expirationInterval = 600L,      // 30s
                cleanupInterval = 6000L,        // 5min
                initialCapacity = 8,
                loadFactor = 0.75f,
                concurrencyLevel = 2,
                enablePlayerCache = false,
                enableTeleportDataCache = false,
                movementThreshold = 0.5
            )
            PerformanceMode.LIGHT -> PerformanceSettings(
                useExecutor = true,
                executorThreads = 2,
                countdownInterval = 20L,
                expirationInterval = 400L,      // 20s
                cleanupInterval = 4800L,        // 4min
                initialCapacity = 16,
                loadFactor = 0.75f,
                concurrencyLevel = 2,
                enablePlayerCache = true,
                enableTeleportDataCache = false,
                movementThreshold = 0.4
            )
            PerformanceMode.BALANCED -> PerformanceSettings(
                useExecutor = true,
                executorThreads = 3,
                countdownInterval = 20L,
                expirationInterval = 200L,      // 10s
                cleanupInterval = 2400L,        // 2min
                initialCapacity = 16,
                loadFactor = 0.75f,
                concurrencyLevel = 4,
                enablePlayerCache = true,
                enableTeleportDataCache = true,
                movementThreshold = 0.3
            )
            PerformanceMode.HIGH_PERFORMANCE -> PerformanceSettings(
                useExecutor = true,
                executorThreads = 4,
                countdownInterval = 5L,
                expirationInterval = 100L,      // 5s
                cleanupInterval = 1200L,        // 1min
                initialCapacity = 32,
                loadFactor = 0.75f,
                concurrencyLevel = 8,
                enablePlayerCache = true,
                enableTeleportDataCache = true,
                movementThreshold = 0.25
            )
            else -> getPerformanceSettings() // Fallback to detected mode
        }
    }

    fun getMovementThreshold(): Double = getPerformanceSettings().movementThreshold

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sendMessage(sender, Component.text("This command can only be used by players.", NamedTextColor.RED))
            return true
        }

        return when (command.name.lowercase()) {
            "tpa" -> { handleTpaCommand(sender, args); true }
            "tpaccept" -> { handleTpAcceptCommand(sender); true }
            "tpadeny" -> { handleTpDenyCommand(sender); true }
            "tpahere" -> { handleTpaHereCommand(sender, args); true }
            "tpareload" -> { handleReloadCommand(sender); true }
            "tpastats" -> { handleStatsCommand(sender); true }
            "tpainfo" -> { handleInfoCommand(sender); true }
            else -> false
        }
    }

    private fun handleTpaCommand(player: Player, args: Array<out String>) {
        if (args.isEmpty()) {
            sendMessage(player, Component.text("Usage: /tpa <player>", NamedTextColor.GRAY))
            return
        }

        val target = getPlayerByName(args[0])
        if (target == null) {
            sendMessage(player, buildPlayerNotFoundMessage(args[0]))
            return
        }

        if (!validateTeleportRequest(player, target)) return

        storeRequest(player, target, false)
        updateCooldown(player)

        sendTpaRequestMessages(player, target)
        playSound(target, requestSoundKey)
        bStats.incrementRequestSent()
    }

    private fun handleTpaHereCommand(player: Player, args: Array<out String>) {
        if (args.isEmpty()) {
            sendMessage(player, Component.text("Usage: /tpahere <player>", NamedTextColor.YELLOW))
            return
        }

        val target = getPlayerByName(args[0])
        if (target == null) {
            sendMessage(player, buildPlayerNotFoundMessage(args[0]))
            return
        }

        if (!validateTeleportRequest(player, target)) return

        storeRequest(player, target, true)
        updateCooldown(player)

        sendTpaHereRequestMessages(player, target)
        playSound(target, requestSoundKey)
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

        val (teleporter, destination) = if (request.isHereRequest) {
            player to requester
        } else {
            requester to player
        }

        sendAcceptanceMessages(player, requester)
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

        sendMessage(player, Component.text("You denied $requesterName's teleport request.", NamedTextColor.RED))
        requester?.let {
            sendMessage(it, Component.text("${player.name} denied your teleport request.", NamedTextColor.RED))
            playSound(it, cancelSoundKey)
        }

        playSound(player, cancelSoundKey)
        bStats.incrementRequestDenied()
    }

    private fun handleReloadCommand(player: Player) {
        if (!player.hasPermission("yotpa.reload")) {
            sendMessage(player, Component.text("You don't have permission to reload the configuration.", NamedTextColor.RED))
            return
        }

        // Try to reload config first (catch YAML errors)
        val reloadResult = runCatching {
            reloadConfig()
            true
        }.getOrElse { e ->
            sendMessage(player, Component.text("Failed to load config.yml!", NamedTextColor.RED))
            player.sendMessage(Component.text("  Error: ${e.message}", NamedTextColor.GRAY))
            player.sendMessage(Component.text("  Fix the YAML syntax and try again.", NamedTextColor.YELLOW))
            logger.log(Level.SEVERE, "Failed to reload config", e)
            false
        }

        if (!reloadResult) {
            return // Stop if YAML is broken
        }

        // Now validate the reloaded config
        val validationResult = validateConfig()

        if (validationResult.isValid) {
            loadConfig()

            val oldMode = detectedMode
            detectAndApplyPerformanceMode()

            sendMessage(player, Component.text("Configuration reloaded successfully.", NamedTextColor.GREEN))

            if (validationResult.warnings.isNotEmpty()) {
                sendMessage(player, Component.text("Warnings:", NamedTextColor.YELLOW))
                validationResult.warnings.forEach { warning ->
                    player.sendMessage(Component.text("  • $warning", NamedTextColor.GRAY))
                }
            }

            if (oldMode != detectedMode) {
                sendMessage(player, Component.text("Performance mode changed: $oldMode → $detectedMode", NamedTextColor.YELLOW))
                sendMessage(player, Component.text("Restart recommended for full effect.", NamedTextColor.GRAY))
            }
        } else {
            sendMessage(player, Component.text("Configuration validation failed!", NamedTextColor.RED))
            validationResult.errors.forEach { error ->
                player.sendMessage(Component.text("  ✗ $error", NamedTextColor.RED))
            }

            if (validationResult.warnings.isNotEmpty()) {
                sendMessage(player, Component.text("Warnings:", NamedTextColor.YELLOW))
                validationResult.warnings.forEach { warning ->
                    player.sendMessage(Component.text("  • $warning", NamedTextColor.GRAY))
                }
            }

            sendMessage(player, Component.text("Config not applied. Fix errors and try again.", NamedTextColor.GRAY))
            sendMessage(player, Component.text("Using previous configuration.", NamedTextColor.DARK_GRAY))
        }
    }

    private data class ValidationResult(
        val isValid: Boolean,
        val errors: List<String>,
        val warnings: List<String>
    )

    private fun validateConfig(): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        try {
            // Validate timeout
            val timeout = config.getInt("request-timeout", -1)
            when {
                timeout < 0 -> errors.add("request-timeout is missing or invalid")
                timeout < 10 -> warnings.add("request-timeout ($timeout) is very low, recommended: 30-120")
                timeout > 300 -> warnings.add("request-timeout ($timeout) is very high, recommended: 30-120")
            }

            // Validate cooldown
            val cooldown = config.getInt("request-cooldown", -1)
            when {
                cooldown < 0 -> errors.add("request-cooldown is missing or invalid")
                cooldown < 5 -> warnings.add("request-cooldown ($cooldown) is very low, recommended: 15-60")
                cooldown > 180 -> warnings.add("request-cooldown ($cooldown) is very high, recommended: 15-60")
            }

            // Validate teleport delay
            val delay = config.getInt("teleport-delay", -1)
            when {
                delay < 0 -> errors.add("teleport-delay is missing or invalid")
                delay < 1 -> errors.add("teleport-delay must be at least 1 second")
                delay > 30 -> warnings.add("teleport-delay ($delay) is very high, recommended: 3-10")
            }

            // Validate performance mode
            val mode = config.getString("performance.mode", "") ?: ""
            if (mode.isNotEmpty()) {
                try {
                    PerformanceMode.valueOf(mode.uppercase())
                } catch (_: IllegalArgumentException) {
                    errors.add("Invalid performance.mode: '$mode'. Valid: AUTO, ULTRA_LIGHT, LIGHT, BALANCED, HIGH_PERFORMANCE")
                }
            }

            // Validate sounds
            val soundKeys = listOf("countdown", "success", "cancel", "request")
            soundKeys.forEach { key ->
                val soundName = config.getString("sounds.$key", "") ?: ""
                if (soundName.isEmpty()) {
                    warnings.add("Sound '$key' is not set, using default")
                } else {
                    // Validate by trying to parse the sound
                    val testKey = runCatching {
                        if (soundName.contains(".")) {
                            // New format: "block.note_block.pling"
                            NamespacedKey.minecraft(soundName)
                        } else {
                            // Old format: "BLOCK_NOTE_BLOCK_PLING" -> convert
                            val converted = soundName.lowercase().replace("_", ".")
                            NamespacedKey.minecraft(converted)
                        }
                    }.getOrNull()

                    if (testKey != null) {
                        val sound = Registry.SOUNDS.get(testKey)
                        if (sound == null) {
                            warnings.add("Sound '$key' ($soundName) not found in registry, will use default")
                        }
                    } else {
                        warnings.add("Sound '$key' ($soundName) has invalid format")
                    }
                }
            }

            // Validate features
            val features = listOf("statistics", "bstats", "titles", "sounds")
            features.forEach { feature ->
                if (!config.contains("features.$feature")) {
                    warnings.add("Feature setting 'features.$feature' is missing, using default")
                }
            }

        } catch (e: Exception) {
            errors.add("Critical error reading config: ${e.message}")
            logger.log(Level.SEVERE, "Error validating config", e)
        }

        return ValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings
        )
    }

    private fun handleStatsCommand(player: Player) {
        if (!player.hasPermission("yotpa.stats")) {
            sendMessage(player, Component.text("You don't have permission to view statistics.", NamedTextColor.RED))
            return
        }

        val stats = bStats.getStatistics()
        val acceptanceRate = bStats.getAcceptanceRate()

        player.sendMessage(Component.text("═══ YoTPA Statistics ═══", NamedTextColor.GOLD))

        stats["All-Time"]?.let { allTimeStats ->
            player.sendMessage(Component.text("All-Time:", NamedTextColor.AQUA))
            displayStats(player, allTimeStats)
            player.sendMessage(Component.text("• Acceptance Rate: ", NamedTextColor.GOLD)
                .append(Component.text("$acceptanceRate%", NamedTextColor.WHITE)))
        }

        stats["Daily"]?.let { dailyStats ->
            player.sendMessage(Component.text("Today:", NamedTextColor.AQUA))
            displayStats(player, dailyStats)
        }
    }

    private fun handleInfoCommand(player: Player) {
        if (!player.hasPermission("yotpa.info")) {
            sendMessage(player, Component.text("You don't have permission to view system info.", NamedTextColor.RED))
            return
        }

        player.sendMessage(Component.text("═══ YoTPA System Info ═══", NamedTextColor.GOLD))
        player.sendMessage(Component.text("Version: ", NamedTextColor.YELLOW)
            .append(Component.text("1.3.0", NamedTextColor.WHITE)))
        player.sendMessage(Component.text("Performance Mode: ", NamedTextColor.YELLOW)
            .append(Component.text(detectedMode.name, NamedTextColor.WHITE)))
        player.sendMessage(Component.text("Available RAM: ", NamedTextColor.YELLOW)
            .append(Component.text("${getAvailableMemoryMB()} MB", NamedTextColor.WHITE)))
        player.sendMessage(Component.text("Max RAM: ", NamedTextColor.YELLOW)
            .append(Component.text("${getMaxMemoryMB()} MB", NamedTextColor.WHITE)))
        player.sendMessage(Component.text("Optimization: ", NamedTextColor.YELLOW)
            .append(Component.text(getOptimizationLevel(), NamedTextColor.WHITE)))
        player.sendMessage(Component.text("Active Requests: ", NamedTextColor.YELLOW)
            .append(Component.text(tpaRequests.size.toString(), NamedTextColor.WHITE)))
        player.sendMessage(Component.text("Active Teleports: ", NamedTextColor.YELLOW)
            .append(Component.text(teleportTasks.size.toString(), NamedTextColor.WHITE)))
    }

    private fun displayStats(player: Player, stats: Map<String, Any>) {
        player.sendMessage(Component.text("• Sent: ", NamedTextColor.YELLOW)
            .append(Component.text(stats["Sent"].toString(), NamedTextColor.WHITE)))
        player.sendMessage(Component.text("• Accepted: ", NamedTextColor.GREEN)
            .append(Component.text(stats["Accepted"].toString(), NamedTextColor.WHITE)))
        player.sendMessage(Component.text("• Denied: ", NamedTextColor.RED)
            .append(Component.text(stats["Denied"].toString(), NamedTextColor.WHITE)))
        player.sendMessage(Component.text("• Expired: ", NamedTextColor.GRAY)
            .append(Component.text(stats["Expired"].toString(), NamedTextColor.WHITE)))
    }

    fun startTeleportCountdown(teleporter: Player, destination: Player) {
        cancelTeleport(teleporter.uniqueId)

        val originalLocation = teleporter.location.clone()
        teleporter.setMetadata("yotpa:original-location", FixedMetadataValue(this, originalLocation))

        val settings = getPerformanceSettings()
        val data = TeleportData(
            destination = destination,
            startTime = System.currentTimeMillis(),
            duration = teleportDelay * 1000,
            lastShownSecond = teleportDelay
        )

        if (settings.enableTeleportDataCache) {
            teleportData[teleporter.uniqueId] = data
        }

        // Show title
        teleporter.showTitle(Title.title(
            titleCache.mainTitle,
            titleCache.subtitle,
            titleCache.titleTimes
        ))

        // Initial countdown message
        sendCountdownMessage(teleporter, teleportDelay)

        // Run countdown task
        val taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, {
            processCountdown(teleporter, data)
        }, settings.countdownInterval, settings.countdownInterval)

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

            if (remainingSeconds != data.lastShownSecond && remainingSeconds > 0) {
                data.lastShownSecond = remainingSeconds
                sendCountdownMessage(teleporter, remainingSeconds)
                playSound(teleporter, countdownSoundKey)
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
        playSound(player, cancelSoundKey)
    }

    private fun performTeleport(teleporter: Player, destination: Player) {
        teleporter.teleport(destination)
        sendMessage(teleporter, Component.text("Teleported to ", NamedTextColor.GREEN)
            .append(Component.text(destination.name, NamedTextColor.YELLOW)))

        playSound(teleporter, successSoundKey)
        playSound(destination, successSoundKey)
    }

    private fun startMaintenanceTasks() {
        val settings = getPerformanceSettings()

        if (executor != null) {
            // Use executor for async tasks
            executor!!.scheduleAtFixedRate({
                runCatching { checkExpiredRequests() }
                    .onFailure { e -> logger.log(Level.WARNING, "Error during expiration check", e) }
            }, settings.expirationInterval, settings.expirationInterval, TimeUnit.MILLISECONDS)

            executor!!.scheduleAtFixedRate({
                runCatching { cleanupCaches() }
                    .onFailure { e -> logger.log(Level.WARNING, "Error during cache cleanup", e) }
            }, settings.cleanupInterval, settings.cleanupInterval, TimeUnit.MILLISECONDS)
        } else {
            // Use Bukkit scheduler for sync tasks (ultra-light mode)
            Bukkit.getScheduler().scheduleSyncRepeatingTask(this, {
                checkExpiredRequests()
            }, settings.expirationInterval / 50, settings.expirationInterval / 50) // Convert ms to ticks

            Bukkit.getScheduler().scheduleSyncRepeatingTask(this, {
                cleanupCaches()
            }, settings.cleanupInterval / 50, settings.cleanupInterval / 50)
        }
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
        val settings = getPerformanceSettings()

        if (settings.enablePlayerCache) {
            val iterator = playerNameCache.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (Bukkit.getPlayer(entry.key) == null) {
                    iterator.remove()
                }
            }
        }

        // Cleanup expired cooldowns
        val currentTime = System.currentTimeMillis()
        val cooldownExpiry = requestCooldown * 1000L
        cooldowns.entries.removeIf { entry ->
            currentTime - entry.value > cooldownExpiry
        }
    }

    private fun registerCommands() {
        arrayOf("tpa", "tpaccept", "tpadeny", "tpahere", "tpareload", "tpastats", "tpainfo").forEach { cmd ->
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
        return Bukkit.getPlayer(name) ?:
        Bukkit.getOnlinePlayers().find { it.name.equals(name, ignoreCase = true) }
    }

    private fun getPlayerName(uuid: UUID): String {
        val settings = getPerformanceSettings()
        return if (settings.enablePlayerCache) {
            playerNameCache.getOrPut(uuid) {
                Bukkit.getPlayer(uuid)?.name ?: "Unknown"
            }
        } else {
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
        loadSounds()
    }

    private fun loadSounds() {
        runCatching {
            countdownSoundKey = parseSoundKey(
                config.getString("sounds.countdown", "block.note_block.pling")!!,
                NamespacedKey.minecraft("block.note_block.pling")
            )
            successSoundKey = parseSoundKey(
                config.getString("sounds.success", "entity.enderman.teleport")!!,
                NamespacedKey.minecraft("entity.enderman.teleport")
            )
            cancelSoundKey = parseSoundKey(
                config.getString("sounds.cancel", "entity.villager.no")!!,
                NamespacedKey.minecraft("entity.villager.no")
            )
            requestSoundKey = parseSoundKey(
                config.getString("sounds.request", "entity.experience_orb.pickup")!!,
                NamespacedKey.minecraft("entity.experience_orb.pickup")
            )
        }.onFailure { e ->
            logger.log(Level.WARNING, "Error loading sounds from config, using defaults", e)
            setDefaultSounds()
        }
    }

    private fun parseSoundKey(soundName: String, default: NamespacedKey): NamespacedKey {
        return runCatching {
            // Support both formats:
            // 1. New format: "block.note_block.pling" (recommended)
            // 2. Old format: "BLOCK_NOTE_BLOCK_PLING" (auto-convert)

            val key = if (soundName.contains(".")) {
                // New format with dots: "block.note_block.pling"
                NamespacedKey.minecraft(soundName)
            } else {
                // Old format with underscores: "BLOCK_NOTE_BLOCK_PLING"
                // Convert to: "block.note_block.pling"
                val converted = soundName.lowercase().replace("_", ".")
                NamespacedKey.minecraft(converted)
            }

            // Validate that sound exists in registry
            if (Registry.SOUNDS.get(key) != null) {
                key
            } else {
                logger.fine("Sound '$soundName' not found in registry, using default")
                default
            }
        }.getOrElse {
            logger.fine("Invalid sound name: $soundName, using default")
            default
        }
    }

    private fun setDefaultSounds() {
        countdownSoundKey = NamespacedKey.minecraft("block.note_block.pling")
        successSoundKey = NamespacedKey.minecraft("entity.enderman.teleport")
        cancelSoundKey = NamespacedKey.minecraft("entity.villager.no")
        requestSoundKey = NamespacedKey.minecraft("entity.experience_orb.pickup")
    }

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

    private fun playSound(player: Player, soundKey: NamespacedKey) {
        runCatching {
            // Get sound from registry using the key
            val sound = Registry.SOUNDS.get(soundKey)
            if (sound != null) {
                player.playSound(player.location, sound, 1.0f, 1.0f)
            }
        }.onFailure { e ->
            logger.log(Level.WARNING, "Failed to play sound: ${soundKey.asString()}", e)
        }
    }

    // Utility methods for system info
    private fun getMaxMemoryMB(): Long = Runtime.getRuntime().maxMemory() / 1024 / 1024
    private fun getAvailableMemoryMB(): Long = Runtime.getRuntime().freeMemory() / 1024 / 1024

    private fun getOptimizationLevel(): String {
        return when (detectedMode) {
            PerformanceMode.ULTRA_LIGHT -> "Maximum (For 512 MB RAM)"
            PerformanceMode.LIGHT -> "High (For 1-2 GB RAM)"
            PerformanceMode.BALANCED -> "Moderate (For 2-4 GB RAM)"
            PerformanceMode.HIGH_PERFORMANCE -> "Minimal (For 4+ GB RAM)"
            else -> "Auto"
        }
    }
}