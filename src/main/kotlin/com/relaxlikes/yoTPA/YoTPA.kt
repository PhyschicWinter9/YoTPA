package com.relaxlikes.yoTPA

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.logging.Level

class YoTPA : JavaPlugin() {

    companion object {
        /** True when running on Folia (regionized multithreading server). */
        val isFolia: Boolean = try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer")
            true
        } catch (_: ClassNotFoundException) {
            false
        }
    }

    // Version is read from plugin.yml at runtime (injected by processResources from VersionConfig.PLUGIN_pluginVersion)
    val pluginVersion: String get() = pluginMeta.version

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
    private lateinit var teleportTasks: ConcurrentHashMap<UUID, ScheduledTask>
    private lateinit var teleportData: ConcurrentHashMap<UUID, TeleportData>
    private lateinit var playerNameCache: ConcurrentHashMap<UUID, String>

    // PersistentDataContainer key for storing original locations
    private lateinit var originalLocationKey: NamespacedKey

    // Configuration values
    @Volatile private var requestTimeout = 60
    @Volatile private var requestCooldown = 30
    @Volatile private var teleportDelay = 5

    // Sound keys - store as NamespacedKey instead of Sound
    @Volatile private var countdownSoundKey = NamespacedKey.minecraft("block.note_block.pling")
    @Volatile private var successSoundKey = NamespacedKey.minecraft("entity.enderman.teleport")
    @Volatile private var cancelSoundKey = NamespacedKey.minecraft("entity.villager.no")
    @Volatile private var requestSoundKey = NamespacedKey.minecraft("entity.experience_orb.pickup")

    // Cached title components
    private val titleCache by lazy { CachedTitleComponents() }

    // Cached performance settings — written only from main thread (onEnable/reload), read from any thread
    @Volatile private var cachedSettings: PerformanceSettings? = null
    private inline val settings: PerformanceSettings get() = cachedSettings ?: getPerformanceSettings()

    // Single batch-countdown task ID for Paper mode (one task processes all active teleports)
    private var paperBatchTaskId: Int = -1

    // Executor service (nullable for ultra-light mode)
    private var executor: ScheduledExecutorService? = null

    private lateinit var bStats: bStatsTPA

    // Message manager for customizable messages
    private lateinit var messageManager: MessageManager

    // Update checker
    private lateinit var updateChecker: UpdateChecker

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
        val titleTimes: Title.Times = Title.Times.times(
            java.time.Duration.ofMillis(250),
            java.time.Duration.ofSeconds(6),
            java.time.Duration.ofMillis(500)
        )
    )

    override fun onEnable() {
        // Initialize PersistentDataContainer key
        originalLocationKey = NamespacedKey(this, "original_location")

        // Load configuration first
        saveDefaultConfig()
        loadConfig()

        // Detect and apply performance mode, then cache settings once
        detectAndApplyPerformanceMode()
        cachedSettings = getPerformanceSettings()

        // Initialize data structures with adaptive sizing
        initializeDataStructures()

        // Initialize executor if needed
        initializeExecutor()

        // Initialize message manager
        messageManager = MessageManager(this)
        messageManager.initialize()

        // Initialize bStats
        bStats = bStatsTPA(this)
        bStats.initialize()

        // Initialize update checker
        updateChecker = UpdateChecker(this, pluginVersion, "PhyschicWinter9/YoTPA")
        updateChecker.check()

        // Register commands and events
        registerCommands()
        server.pluginManager.registerEvents(PlayerMoveListener(this, getMovementThreshold()), this)
        server.pluginManager.registerEvents(updateChecker, this)

        // Start maintenance tasks
        startMaintenanceTasks()

        // On Paper: one batch task drives ALL active countdowns (O(1) scheduler overhead)
        // On Folia: per-entity tasks are used instead (scheduled in startTeleportCountdown)
        if (!isFolia) {
            startPaperBatchTask()
        }

        // Log startup info
        logger.info("═══════════════════════════════════════")
        logger.info("YoTPA Developer: PhyschicWinter9 & VIBEs Coding XD")
        logger.info("YoTPA Version: $pluginVersion")
        logger.info("Server: ${if (isFolia) "Folia" else "Paper/Spigot"}")
        logger.info("Performance Mode: ${detectedMode.name}")
        logger.info("Optimization Level: ${getOptimizationLevel()}")
        logger.info("═══════════════════════════════════════")
    }

    override fun onDisable() {
        // Cancel active teleport tasks
        if (isFolia) {
            teleportTasks.values.forEach { task -> runCatching { task.cancel() } }
        } else if (paperBatchTaskId != -1) {
            runCatching { Bukkit.getScheduler().cancelTask(paperBatchTaskId) }
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

        // Cancel bStats daily reset task
        bStats.shutdown()

        // Clear all data
        clearAllData()

        logger.info("YoTPA plugin has been disabled!")
    }

    /**
     * Store player's original location in their PersistentDataContainer
     */
    private fun storeOriginalLocation(player: Player, location: Location) {
        // Serialize location to string
        val locString = "${location.world?.name},${location.x},${location.y},${location.z},${location.yaw},${location.pitch}"

        player.persistentDataContainer.set(
            originalLocationKey,
            PersistentDataType.STRING,
            locString
        )
    }

    /**
     * Get player's original location from PersistentDataContainer
     * Returns null if not found or invalid
     *
     * Note: Used by PlayerMoveListener for movement detection
     */
    fun getOriginalLocation(player: Player): Location? {
        val locString = player.persistentDataContainer.get(
            originalLocationKey,
            PersistentDataType.STRING
        ) ?: return null

        return deserializeLocation(locString)
    }

    /**
     * Get original location by UUID
     *
     * Note: Used by PlayerMoveListener for movement detection
     */
    fun getOriginalLocation(uuid: UUID): Location? {
        val player = Bukkit.getPlayer(uuid) ?: return null
        return getOriginalLocation(player)
    }

    /**
     * Remove original location from PersistentDataContainer
     */
    private fun removeOriginalLocation(player: Player) {
        player.persistentDataContainer.remove(originalLocationKey)
    }


    /**
     * Deserialize location from string
     */
    private fun deserializeLocation(locString: String): Location? {
        return try {
            val parts = locString.split(",")
            val world = Bukkit.getWorld(parts[0]) ?: return null
            Location(
                world,
                parts[1].toDouble(),
                parts[2].toDouble(),
                parts[3].toDouble(),
                parts[4].toFloat(),
                parts[5].toFloat()
            )
        } catch (_: Exception) {
            logger.warning("Failed to deserialize location: $locString")
            null
        }
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

    fun getMovementThreshold(): Double = settings.movementThreshold

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sendMessage(sender, messageManager.getPlayerOnly())
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
            sendMessage(player, messageManager.getTpaUsage())
            return
        }

        val target = getPlayerByName(args[0])
        if (target == null) {
            sendMessage(player, messageManager.getPlayerNotFound(args[0]))
            return
        }

        if (!validateTeleportRequest(player, target)) return

        storeRequest(player, target, false)
        updateCooldown(player)

        sendMessage(player, messageManager.getTpaSent(target.name))
        sendMessage(target, messageManager.getTpaReceived(player.name))
        playSound(target, requestSoundKey)
        bStats.incrementRequestSent()
    }

    private fun handleTpaHereCommand(player: Player, args: Array<out String>) {
        if (args.isEmpty()) {
            sendMessage(player, messageManager.getTpaHereUsage())
            return
        }

        val target = getPlayerByName(args[0])
        if (target == null) {
            sendMessage(player, messageManager.getPlayerNotFound(args[0]))
            return
        }

        if (!validateTeleportRequest(player, target)) return

        storeRequest(player, target, true)
        updateCooldown(player)

        sendMessage(player, messageManager.getTpaHereSent(target.name))
        sendMessage(target, messageManager.getTpaHereReceived(player.name))
        playSound(target, requestSoundKey)
        bStats.incrementRequestSent()
    }

    private fun handleTpAcceptCommand(player: Player) {
        val request = tpaRequests.remove(player.uniqueId)
        if (request == null) {
            sendMessage(player, messageManager.getTpAcceptNoRequest())
            return
        }

        val requester = Bukkit.getPlayer(request.requesterUUID)
        if (requester == null || !requester.isOnline) {
            sendMessage(player, messageManager.getTpAcceptRequesterOffline())
            return
        }

        val (teleporter, destination) = if (request.isHereRequest) {
            player to requester
        } else {
            requester to player
        }

        sendMessage(player, messageManager.getTpAcceptAcceptedSender(requester.name))
        sendMessage(requester, messageManager.getTpAcceptAcceptedTarget(player.name))
        startTeleportCountdown(teleporter, destination)
        bStats.incrementRequestAccepted()
    }

    private fun handleTpDenyCommand(player: Player) {
        val request = tpaRequests.remove(player.uniqueId)
        if (request == null) {
            sendMessage(player, messageManager.getTpDenyNoRequest())
            return
        }

        val requester = Bukkit.getPlayer(request.requesterUUID)
        val requesterName = requester?.name ?: getPlayerName(request.requesterUUID)

        sendMessage(player, messageManager.getTpDenyDeniedSender(requesterName))
        requester?.let {
            sendMessage(it, messageManager.getTpDenyDeniedTarget(player.name))
            playSound(it, cancelSoundKey)
        }

        playSound(player, cancelSoundKey)
        bStats.incrementRequestDenied()
    }

    private fun handleReloadCommand(player: Player) {
        if (!player.hasPermission("yotpa.reload")) {
            sendMessage(player, messageManager.getTpaReloadNoPermission())
            return
        }

        // Try to reload config first (catch YAML errors)
        val reloadResult = runCatching {
            reloadConfig()
            messageManager.reload()
            true
        }.getOrElse { e ->
            sendMessage(player, messageManager.getTpaReloadFailed())
            player.sendMessage(messageManager.getTpaReloadError(e.message ?: "Unknown error"))
            player.sendMessage(messageManager.getTpaReloadFixSyntax())
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
            cachedSettings = getPerformanceSettings()

            sendMessage(player, messageManager.getTpaReloadSuccess())

            if (validationResult.warnings.isNotEmpty()) {
                sendMessage(player, messageManager.getTpaReloadWarningsHeader())
                validationResult.warnings.forEach { warning ->
                    player.sendMessage(messageManager.getTpaReloadWarningItem(warning))
                }
            }

            if (oldMode != detectedMode) {
                sendMessage(player, messageManager.getTpaReloadModeChanged(oldMode.name, detectedMode.name))
                sendMessage(player, messageManager.getTpaReloadRestartRecommended())
            }
        } else {
            sendMessage(player, messageManager.getTpaReloadValidationFailed())
            validationResult.errors.forEach { error ->
                player.sendMessage(messageManager.getTpaReloadErrorItem(error))
            }

            if (validationResult.warnings.isNotEmpty()) {
                sendMessage(player, messageManager.getTpaReloadWarningsHeader())
                validationResult.warnings.forEach { warning ->
                    player.sendMessage(messageManager.getTpaReloadWarningItem(warning))
                }
            }

            sendMessage(player, messageManager.getTpaReloadNotApplied())
            sendMessage(player, messageManager.getTpaReloadUsingPrevious())
        }
    }

    private data class ValidationResult(
        val isValid: Boolean,
        val errors: List<String>,
        val warnings: List<String>
    )

    /**
     * Validate a sound configuration entry
     */
    private fun validateSoundConfig(key: String, soundName: String, warnings: MutableList<String>) {
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
                validateSoundConfig(key, soundName, warnings)
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
            sendMessage(player, messageManager.getTpaStatsNoPermission())
            return
        }

        val stats = bStats.getStatistics()
        val acceptanceRate = bStats.getAcceptanceRate()

        player.sendMessage(messageManager.getTpaStatsHeader())

        stats["All-Time"]?.let { allTimeStats ->
            player.sendMessage(messageManager.getTpaStatsAllTime())
            player.sendMessage(messageManager.getTpaStatsSent(allTimeStats["Sent"].toString()))
            player.sendMessage(messageManager.getTpaStatsAccepted(allTimeStats["Accepted"].toString()))
            player.sendMessage(messageManager.getTpaStatsDenied(allTimeStats["Denied"].toString()))
            player.sendMessage(messageManager.getTpaStatsExpired(allTimeStats["Expired"].toString()))
            player.sendMessage(messageManager.getTpaStatsAcceptanceRate(acceptanceRate.toString()))
        }

        stats["Daily"]?.let { dailyStats ->
            player.sendMessage(messageManager.getTpaStatsDaily())
            player.sendMessage(messageManager.getTpaStatsSent(dailyStats["Sent"].toString()))
            player.sendMessage(messageManager.getTpaStatsAccepted(dailyStats["Accepted"].toString()))
            player.sendMessage(messageManager.getTpaStatsDenied(dailyStats["Denied"].toString()))
            player.sendMessage(messageManager.getTpaStatsExpired(dailyStats["Expired"].toString()))
        }
    }

    private fun handleInfoCommand(player: Player) {
        if (!player.hasPermission("yotpa.info")) {
            sendMessage(player, messageManager.getTpaInfoNoPermission())
            return
        }

        player.sendMessage(messageManager.getTpaInfoHeader())
        player.sendMessage(messageManager.getTpaInfoVersion(pluginVersion))
        player.sendMessage(messageManager.getTpaInfoPerformanceMode(detectedMode.name))
        player.sendMessage(messageManager.getTpaInfoAvailableRam(getAvailableMemoryMB().toString()))
        player.sendMessage(messageManager.getTpaInfoMaxRam(getMaxMemoryMB().toString()))
        player.sendMessage(messageManager.getTpaInfoOptimization(getOptimizationLevel()))
        player.sendMessage(messageManager.getTpaInfoActiveRequests(tpaRequests.size.toString()))
        player.sendMessage(messageManager.getTpaInfoActiveTeleports(teleportData.size.toString()))
    }

    fun startTeleportCountdown(teleporter: Player, destination: Player) {
        cancelTeleport(teleporter.uniqueId)

        // Store original location in PersistentDataContainer
        val originalLocation = teleporter.location.clone()
        storeOriginalLocation(teleporter, originalLocation)

        val data = TeleportData(
            destination = destination,
            startTime = System.currentTimeMillis(),
            duration = teleportDelay * 1000,
            lastShownSecond = teleportDelay
        )

        // Always store in teleportData — it's the source of truth for both Paper and Folia
        teleportData[teleporter.uniqueId] = data

        // Show title
        teleporter.showTitle(Title.title(
            messageManager.getTeleportTitle(),
            messageManager.getTeleportSubtitle(),
            titleCache.titleTimes
        ))

        // Initial countdown message
        sendCountdownMessage(teleporter, teleportDelay)

        // Folia: schedule a per-entity task (required for region threading)
        // Paper: the single batch task (started in onEnable) drives all countdowns — no per-player task needed
        if (isFolia) {
            teleporter.scheduler.runAtFixedRate(this, { _ ->
                processCountdown(teleporter, data)
            }, null, settings.countdownInterval, settings.countdownInterval)
                ?.let { teleportTasks[teleporter.uniqueId] = it }
        }
    }

    private fun processCountdown(teleporter: Player, data: TeleportData) {
        // Release the Player reference and abort if destination went offline — prevents holding
        // a strong reference to an offline Player object for the rest of the countdown duration
        if (!data.destination.isOnline) {
            cancelTeleport(teleporter.uniqueId)
            sendMessage(teleporter, messageManager.getTeleportCancelledDestinationOffline())
            playSound(teleporter, cancelSoundKey)
            return
        }

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
        sendMessage(player, messageManager.getTeleportCountdownMessage(seconds))
    }

    fun cancelTeleport(uuid: UUID) {
        // Folia: cancel the per-entity scheduled task; Paper: batch task skips removed entries automatically
        if (isFolia) {
            teleportTasks.remove(uuid)?.let { task -> runCatching { task.cancel() } }
        }
        teleportData.remove(uuid)

        // Remove from PersistentDataContainer
        Bukkit.getPlayer(uuid)?.let { player ->
            removeOriginalLocation(player)
        }
    }

    /**
     * Called when a player disconnects. Cleans up any TPA requests they sent or received
     * so stale Player references and map entries don't linger until the expiry timer fires.
     */
    fun cleanupPlayerOnQuit(player: Player) {
        val uuid = player.uniqueId
        // Remove request where this player is the target
        tpaRequests.remove(uuid)
        // Remove request where this player is the requester (requires a scan — bounded by online count)
        tpaRequests.entries.removeIf { it.value.requesterUUID == uuid }
    }

    fun cancelTeleportDueToMovement(player: Player) {
        cancelTeleport(player.uniqueId)
        sendMessage(player, messageManager.getTeleportCancelledMovement())
        playSound(player, cancelSoundKey)
    }

    private fun performTeleport(teleporter: Player, destination: Player) {
        if (isFolia) {
            // Capture destination location and name on the current thread before going async
            val destLocation = destination.location
            val destName = destination.name
            teleporter.teleportAsync(destLocation).thenAccept { success ->
                if (success) {
                    sendMessage(teleporter, messageManager.getTeleportSuccess(destName))
                    teleporter.scheduler.run(this, { _ -> playSound(teleporter, successSoundKey) }, null)
                    destination.scheduler.run(this, { _ -> playSound(destination, successSoundKey) }, null)
                }
            }
        } else {
            teleporter.teleport(destination)
            sendMessage(teleporter, messageManager.getTeleportSuccess(destination.name))
            playSound(teleporter, successSoundKey)
            playSound(destination, successSoundKey)
        }
    }

    /**
     * Paper-only: one repeating task processes ALL active teleport countdowns each tick.
     * Replaces N per-player scheduled tasks with a single O(active-teleports) loop,
     * keeping scheduler overhead at O(1) regardless of concurrent player count.
     */
    private fun startPaperBatchTask() {
        paperBatchTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, {
            if (teleportData.isEmpty()) return@scheduleSyncRepeatingTask
            // Snapshot keys first to avoid ConcurrentModificationException while cancelTeleport mutates the map
            val uuids = ArrayList(teleportData.keys)
            for (uuid in uuids) {
                val data = teleportData[uuid] ?: continue
                val teleporter = Bukkit.getPlayer(uuid)
                if (teleporter == null || !teleporter.isOnline) {
                    cancelTeleport(uuid)
                    continue
                }
                processCountdown(teleporter, data)
            }
        }, 1L, 1L)
    }

    private fun startMaintenanceTasks() {
        val settings = this.settings

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
            // Use scheduler for sync tasks (ultra-light mode)
            val expirationTicks = settings.expirationInterval / 50
            val cleanupTicks = settings.cleanupInterval / 50
            if (isFolia) {
                server.globalRegionScheduler.runAtFixedRate(this, { _ ->
                    checkExpiredRequests()
                }, expirationTicks, expirationTicks)
                server.globalRegionScheduler.runAtFixedRate(this, { _ ->
                    cleanupCaches()
                }, cleanupTicks, cleanupTicks)
            } else {
                Bukkit.getScheduler().scheduleSyncRepeatingTask(this, {
                    checkExpiredRequests()
                }, expirationTicks, expirationTicks)
                Bukkit.getScheduler().scheduleSyncRepeatingTask(this, {
                    cleanupCaches()
                }, cleanupTicks, cleanupTicks)
            }
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
            if (isFolia) {
                server.globalRegionScheduler.run(this) { _ ->
                    processExpiredRequests(expiredRequests)
                }
            } else {
                Bukkit.getScheduler().runTask(this, Runnable {
                    processExpiredRequests(expiredRequests)
                })
            }
        }
    }

    private fun processExpiredRequests(expiredRequests: List<UUID>) {
        expiredRequests.forEach { targetUuid ->
            tpaRequests.remove(targetUuid)?.let { request ->
                Bukkit.getPlayer(targetUuid)?.let { target ->
                    sendMessage(target, messageManager.getTeleportExpiredReceiver(getPlayerName(request.requesterUUID)))
                }

                Bukkit.getPlayer(request.requesterUUID)?.let { requester ->
                    sendMessage(requester, messageManager.getTeleportExpiredSender(getPlayerName(targetUuid)))
                }

                bStats.incrementRequestExpired()
            }
        }
    }

    private fun cleanupCaches() {
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
            sendMessage(requester, messageManager.getTpaSelfTeleport())
            return false
        }

        if (isOnCooldown(requester) && !requester.hasPermission("yotpa.bypass.cooldown")) {
            val remainingCooldown = ((cooldowns[requester.uniqueId]!! + (requestCooldown * 1000L)) - System.currentTimeMillis()) / 1000
            sendMessage(requester, messageManager.getCooldown(remainingCooldown))
            return false
        }

        return true
    }

    private fun getPlayerByName(name: String): Player? {
        return Bukkit.getPlayer(name) ?:
        Bukkit.getOnlinePlayers().find { it.name.equals(name, ignoreCase = true) }
    }

    private fun getPlayerName(uuid: UUID): String {
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
                config.getString("sounds.countdown") ?: "block.note_block.pling",
                NamespacedKey.minecraft("block.note_block.pling")
            )
            successSoundKey = parseSoundKey(
                config.getString("sounds.success") ?: "entity.enderman.teleport",
                NamespacedKey.minecraft("entity.enderman.teleport")
            )
            cancelSoundKey = parseSoundKey(
                config.getString("sounds.cancel") ?: "entity.villager.no",
                NamespacedKey.minecraft("entity.villager.no")
            )
            requestSoundKey = parseSoundKey(
                config.getString("sounds.request") ?: "entity.experience_orb.pickup",
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
                NamespacedKey.minecraft(soundName)
            } else {
                // Convert underscores to dots and lowercase
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
        }.getOrElse { _ ->
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
        sender.sendMessage(message)
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