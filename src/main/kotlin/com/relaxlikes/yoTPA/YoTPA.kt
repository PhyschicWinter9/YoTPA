package com.relaxlikes.yoTPA

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.Sound
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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

    val pluginVersion: String get() = pluginMeta.version

    // ─── Performance mode ─────────────────────────────────────────────────────

    enum class PerformanceMode {
        AUTO, ULTRA_LIGHT, LIGHT, BALANCED, HIGH_PERFORMANCE
    }

    @Volatile
    private var performanceMode: PerformanceMode = PerformanceMode.AUTO

    @Volatile
    private var detectedMode: PerformanceMode = PerformanceMode.BALANCED

    // ─── Thread-safe data structures ─────────────────────────────────────────
    // All maps are ConcurrentHashMap — safe for concurrent read/write from
    // main thread, executor workers, and Folia region threads simultaneously.

    private lateinit var tpaRequests: ConcurrentHashMap<UUID, TpaRequest>
    private lateinit var cooldowns: ConcurrentHashMap<UUID, Long>
    private lateinit var teleportTasks: ConcurrentHashMap<UUID, ScheduledTask>
    private lateinit var teleportData: ConcurrentHashMap<UUID, TeleportData>
    private lateinit var playerNameCache: ConcurrentHashMap<UUID, String>

    // In-memory location tracking — replaces PDC (no serialization overhead,
    // no cross-restart pollution, O(1) lookup on every PlayerMoveEvent)
    private lateinit var countdownOrigins: ConcurrentHashMap<UUID, Location>  // movement-cancel origin
    private lateinit var lastLocations: ConcurrentHashMap<UUID, Location>      // /back destination
    private lateinit var backCooldowns: ConcurrentHashMap<UUID, Long>          // /back cooldown timestamps

    // ─── Configuration values (volatile — written on main thread, read anywhere)

    @Volatile
    private var requestTimeout = 60

    @Volatile
    private var requestCooldown = 30

    @Volatile
    private var teleportDelay = 5

    @Volatile
    private var backCooldown = 30

    @Volatile
    private var soundsEnabled = true

    @Volatile
    private var titlesEnabled = true

    // Sound keys AND resolved Sound objects — keys used for display/config,
    // resolved Sound cached once at load so playSound() needs zero registry lookups.
    @Volatile
    private var countdownSoundKey = NamespacedKey.minecraft("block.note_block.pling")

    @Volatile
    private var successSoundKey = NamespacedKey.minecraft("entity.enderman.teleport")

    @Volatile
    private var cancelSoundKey = NamespacedKey.minecraft("entity.villager.no")

    @Volatile
    private var requestSoundKey = NamespacedKey.minecraft("entity.experience_orb.pickup")

    @Volatile
    private var countdownSound: Sound? = null

    @Volatile
    private var successSound: Sound? = null

    @Volatile
    private var cancelSound: Sound? = null

    @Volatile
    private var requestSound: Sound? = null

    // Title.Times created once — immutable, allocation-free on every countdown tick
    private val titleTimes: Title.Times = Title.Times.times(
        java.time.Duration.ofMillis(250),
        java.time.Duration.ofSeconds(6),
        java.time.Duration.ofMillis(500)
    )

    // Cached performance settings — written only from main thread, read from any thread
    @Volatile
    private var cachedSettings: PerformanceSettings? = null
    private inline val settings: PerformanceSettings get() = cachedSettings ?: getPerformanceSettings()

    // Paper batch countdown task (single repeating task for all active countdowns)
    private var paperBatchTaskId: Int = -1

    // Background executor (null in ULTRA_LIGHT mode)
    private var executor: ScheduledExecutorService? = null

    // Monotonic counter for worker thread names — avoids the racy Thread.activeCount()
    private val workerThreadCounter = AtomicInteger(0)

    private lateinit var bStats: BStatsTPA
    private lateinit var messageManager: MessageManager
    private lateinit var updateChecker: UpdateChecker

    // ─── Data classes ─────────────────────────────────────────────────────────

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
        // Store UUID, not Player — avoids zombie Player references (Golden Rule #2)
        // and cross-region Player access on Folia. Player is resolved fresh each tick.
        val destinationUUID: UUID,
        val startTime: Long,
        val duration: Int,
        // @Volatile: processCountdown may run from different Folia region threads across ticks
        @Volatile var lastShownSecond: Int = -1
    )

    data class TpaRequest(
        val requesterUUID: UUID,
        val targetUUID: UUID,
        val timestamp: Long,
        val isHereRequest: Boolean,
        // Captured on the main thread at request creation so checkExpiredRequests() can
        // honour bypass.timeout without touching the Bukkit API from an executor thread.
        val bypassTimeout: Boolean = false
    )

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onEnable() {
        saveDefaultConfig()
        reloadConfig()
        loadConfig()

        detectAndApplyPerformanceMode()
        cachedSettings = getPerformanceSettings()

        initializeDataStructures()
        initializeExecutor()

        messageManager = MessageManager(this)
        messageManager.initialize()

        bStats = BStatsTPA(this)
        bStats.initialize()

        updateChecker = UpdateChecker(this, pluginVersion, "PhyschicWinter9/YoTPA", messageManager)
        updateChecker.check()

        registerCommands()
        // PlayerMoveListener reads movementThreshold directly from plugin.getMovementThreshold()
        // so it always reflects the current value after /tpareload — no stale capture
        server.pluginManager.registerEvents(PlayerMoveListener(plugin = this), this)
        server.pluginManager.registerEvents(updateChecker, this)

        startMaintenanceTasks()

        // Paper: one batch task drives all active countdowns — O(1) scheduler overhead
        // Folia: per-entity tasks are started in startTeleportCountdown instead
        if (!isFolia) startPaperBatchTask()

        logger.info("═══════════════════════════════════════")
        logger.info("YoTPA Developer: PhyschicWinter9 & VIBEs Coding XD")
        logger.info("YoTPA Version: $pluginVersion")
        logger.info("Performance Mode: ${detectedMode.name}")
        logger.info("Optimization Level: ${getOptimizationLevel()}")
        logger.info("═══════════════════════════════════════")
    }

    override fun onDisable() {
        if (isFolia) {
            teleportTasks.values.forEach { task -> runCatching { task.cancel() } }
        } else if (paperBatchTaskId != -1) {
            runCatching { Bukkit.getScheduler().cancelTask(paperBatchTaskId) }
        }

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

        bStats.shutdown()
        updateChecker.shutdown()
        clearAllData()

        logger.info("YoTPA plugin has been disabled!")
    }

    // ─── Countdown origin tracking (in-memory) ────────────────────────────────
    // Previously stored in PersistentDataContainer (PDC), which:
    //   • serialised/deserialised a Location string on every PlayerMoveEvent
    //   • persisted entries across server restarts (crash = stale data forever)
    // ConcurrentHashMap gives O(1) lookups with zero serialisation cost.

    /** Called by PlayerMoveListener — must be fast, called on every position change. */
    fun getCountdownOrigin(player: Player): Location? = countdownOrigins[player.uniqueId]

    private fun storeCountdownOrigin(uuid: UUID, location: Location) {
        countdownOrigins[uuid] = location
    }

    private fun removeCountdownOrigin(uuid: UUID) {
        countdownOrigins.remove(uuid)
    }

    // ─── Performance mode detection ───────────────────────────────────────────

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
                maxMemory <= 768 -> PerformanceMode.ULTRA_LIGHT
                maxMemory <= 1536 -> PerformanceMode.LIGHT
                maxMemory <= 3072 -> PerformanceMode.BALANCED
                else -> PerformanceMode.HIGH_PERFORMANCE
            }
        } else {
            configMode
        }

        logger.info("Performance mode: $performanceMode (detected: $detectedMode)")
    }

    private fun initializeDataStructures() {
        val s = getPerformanceSettings()

        tpaRequests = ConcurrentHashMap(s.initialCapacity, s.loadFactor, s.concurrencyLevel)
        cooldowns = ConcurrentHashMap(s.initialCapacity * 2, s.loadFactor, s.concurrencyLevel)
        teleportTasks = ConcurrentHashMap(s.initialCapacity, s.loadFactor, s.concurrencyLevel)
        countdownOrigins = ConcurrentHashMap(s.initialCapacity, s.loadFactor, s.concurrencyLevel)
        lastLocations = ConcurrentHashMap(s.initialCapacity, s.loadFactor, s.concurrencyLevel)
        backCooldowns = ConcurrentHashMap(s.initialCapacity * 2, s.loadFactor, s.concurrencyLevel)

        teleportData = if (s.enableTeleportDataCache)
            ConcurrentHashMap(s.initialCapacity, s.loadFactor, s.concurrencyLevel)
        else
            ConcurrentHashMap(4, 0.75f, 1)

        playerNameCache = if (s.enablePlayerCache)
            ConcurrentHashMap(s.initialCapacity * 2, s.loadFactor, s.concurrencyLevel)
        else
            ConcurrentHashMap(4, 0.75f, 1)
    }

    private fun initializeExecutor() {
        val s = getPerformanceSettings()
        if (s.useExecutor) {
            executor = Executors.newScheduledThreadPool(s.executorThreads) { runnable ->
                Thread(runnable, "YoTPA-Worker-${workerThreadCounter.incrementAndGet()}").apply {
                    isDaemon = true
                    priority = Thread.NORM_PRIORITY
                }
            }
        }
    }

    private fun getPerformanceSettings(): PerformanceSettings {
        return when (detectedMode) {
            PerformanceMode.ULTRA_LIGHT -> PerformanceSettings(
                useExecutor = false, executorThreads = 0,
                countdownInterval = 20L, expirationInterval = 600L, cleanupInterval = 6000L,
                initialCapacity = 8, loadFactor = 0.75f, concurrencyLevel = 2,
                enablePlayerCache = false, enableTeleportDataCache = false, movementThreshold = 0.5
            )

            PerformanceMode.LIGHT -> PerformanceSettings(
                useExecutor = true, executorThreads = 2,
                countdownInterval = 20L, expirationInterval = 400L, cleanupInterval = 4800L,
                initialCapacity = 16, loadFactor = 0.75f, concurrencyLevel = 2,
                enablePlayerCache = true, enableTeleportDataCache = false, movementThreshold = 0.4
            )

            PerformanceMode.BALANCED -> PerformanceSettings(
                useExecutor = true, executorThreads = 3,
                countdownInterval = 20L, expirationInterval = 200L, cleanupInterval = 2400L,
                initialCapacity = 16, loadFactor = 0.75f, concurrencyLevel = 4,
                enablePlayerCache = true, enableTeleportDataCache = true, movementThreshold = 0.3
            )

            PerformanceMode.HIGH_PERFORMANCE -> PerformanceSettings(
                useExecutor = true, executorThreads = 4,
                countdownInterval = 5L, expirationInterval = 100L, cleanupInterval = 1200L,
                initialCapacity = 32, loadFactor = 0.75f, concurrencyLevel = 8,
                enablePlayerCache = true, enableTeleportDataCache = true, movementThreshold = 0.25
            )
            // AUTO is always resolved to a concrete tier before getPerformanceSettings() is called.
            // This branch is a safety net — never reached in normal operation.
            PerformanceMode.AUTO -> PerformanceSettings(
                useExecutor = true, executorThreads = 3,
                countdownInterval = 20L, expirationInterval = 200L, cleanupInterval = 2400L,
                initialCapacity = 16, loadFactor = 0.75f, concurrencyLevel = 4,
                enablePlayerCache = true, enableTeleportDataCache = true, movementThreshold = 0.3
            )
        }
    }

    /** Read by PlayerMoveListener on every event — volatile read, no lock. */
    fun getMovementThreshold(): Double = settings.movementThreshold

    // Read-only views of the cached config values for BStatsTPA's chart callbacks —
    // bStats invokes suppliers from its own submission threads, where touching the
    // (non-thread-safe) FileConfiguration could race with /tpareload.
    val currentTeleportDelay: Int get() = teleportDelay
    val currentRequestTimeout: Int get() = requestTimeout
    val currentRequestCooldown: Int get() = requestCooldown
    val currentTitlesEnabled: Boolean get() = titlesEnabled

    /**
     * Save a player's current location as their /back destination.
     * Called by PlayerMoveListener on death so /back returns to the death spot.
     */
    fun saveLastLocation(player: Player) {
        lastLocations[player.uniqueId] = player.location.clone()
    }

    /**
     * Send the "death location saved, use /back" notification after respawn.
     * Only sent if the player actually has a saved death location.
     */
    fun sendDeathBackNotification(player: Player) {
        if (lastLocations.containsKey(player.uniqueId)) {
            sendMessage(player, messageManager.getBackDeathSaved())
        }
    }

    // ─── Commands ─────────────────────────────────────────────────────────────

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sendMessage(sender, messageManager.getPlayerOnly())
            return true
        }

        return when (command.name.lowercase()) {
            "tpa" -> {
                handleTpaCommand(sender, args); true
            }

            "tpaccept" -> {
                handleTpAcceptCommand(sender); true
            }

            "tpadeny" -> {
                handleTpDenyCommand(sender); true
            }

            "tpahere" -> {
                handleTpaHereCommand(sender, args); true
            }

            "tpareload" -> {
                handleReloadCommand(sender); true
            }

            "tpastats" -> {
                handleStatsCommand(sender); true
            }

            "tpainfo" -> {
                handleInfoCommand(sender); true
            }

            "back" -> {
                handleBackCommand(sender); true
            }

            else -> false
        }
    }

    private fun handleTpaCommand(player: Player, args: Array<out String>) {
        if (args.isEmpty()) {
            sendMessage(player, messageManager.getTpaUsage()); return
        }

        val target = getPlayerByName(args[0])
        if (target == null) {
            sendMessage(player, messageManager.getPlayerNotFound(args[0])); return
        }

        if (!validateTeleportRequest(player, target)) return

        storeRequest(player, target, false)
        updateCooldown(player)
        sendMessage(player, messageManager.getTpaSent(target.name))
        sendMessage(target, messageManager.getTpaReceived(player.name))
        playSound(target, requestSound)
        bStats.incrementRequestSent()
    }

    private fun handleTpaHereCommand(player: Player, args: Array<out String>) {
        if (args.isEmpty()) {
            sendMessage(player, messageManager.getTpaHereUsage()); return
        }

        val target = getPlayerByName(args[0])
        if (target == null) {
            sendMessage(player, messageManager.getPlayerNotFound(args[0])); return
        }

        if (!validateTeleportRequest(player, target)) return

        storeRequest(player, target, true)
        updateCooldown(player)
        sendMessage(player, messageManager.getTpaHereSent(target.name))
        sendMessage(target, messageManager.getTpaHereReceived(player.name))
        playSound(target, requestSound)
        bStats.incrementRequestSent()
    }

    private fun handleTpAcceptCommand(player: Player) {
        val request = tpaRequests.remove(player.uniqueId)
        if (request == null) {
            sendMessage(player, messageManager.getTpAcceptNoRequest()); return
        }

        val requester = Bukkit.getPlayer(request.requesterUUID)
        if (requester == null || !requester.isOnline) {
            sendMessage(player, messageManager.getTpAcceptRequesterOffline())
            return
        }

        val (teleporter, destination) = if (request.isHereRequest) player to requester else requester to player

        sendMessage(player, messageManager.getTpAcceptAcceptedSender(requester.name))
        sendMessage(requester, messageManager.getTpAcceptAcceptedTarget(player.name))
        startTeleportCountdown(teleporter, destination)
        bStats.incrementRequestAccepted()
    }

    private fun handleTpDenyCommand(player: Player) {
        val request = tpaRequests.remove(player.uniqueId)
        if (request == null) {
            sendMessage(player, messageManager.getTpDenyNoRequest()); return
        }

        val requester = Bukkit.getPlayer(request.requesterUUID)
        val requesterName = requester?.name ?: getPlayerName(request.requesterUUID)

        sendMessage(player, messageManager.getTpDenyDeniedSender(requesterName))
        requester?.let {
            sendMessage(it, messageManager.getTpDenyDeniedTarget(player.name))
            playSound(it, cancelSound)
        }
        playSound(player, cancelSound)
        bStats.incrementRequestDenied()
    }

    private fun handleBackCommand(player: Player) {
        if (!player.hasPermission("yotpa.back")) {
            sendMessage(player, messageManager.getNoPermission())
            return
        }

        // Cooldown check — bypass permission skips this block entirely
        if (backCooldown > 0 && !player.hasPermission("yotpa.bypass.back-cooldown")) {
            val lastUse = backCooldowns[player.uniqueId]
            if (lastUse != null) {
                val elapsed = System.currentTimeMillis() - lastUse
                val remaining = backCooldown * 1000L - elapsed
                if (remaining > 0) {
                    sendMessage(player, messageManager.getBackCooldown((remaining + 999) / 1000))
                    return
                }
            }
        }

        val lastLocation = lastLocations[player.uniqueId]
        // isWorldLoaded guard: the saved world may have been unloaded since (Multiverse etc.) —
        // teleport/teleportAsync would throw instead of failing gracefully
        if (lastLocation == null || !lastLocation.isWorldLoaded) {
            if (lastLocation != null) lastLocations.remove(player.uniqueId)
            sendMessage(player, messageManager.getBackNoLocation())
            return
        }

        if (isFolia) {
            player.teleportAsync(lastLocation).thenAccept { success ->
                if (success) {
                    // Single-use: consume the saved location so a second /back says "no location"
                    lastLocations.remove(player.uniqueId)
                    if (backCooldown > 0) backCooldowns[player.uniqueId] = System.currentTimeMillis()
                    // sendMessage + playSound must be on the player's region thread
                    player.scheduler.run(this, { _ ->
                        sendMessage(player, messageManager.getBackTeleporting())
                        playSound(player, successSound)
                    }, null)
                }
            }
        } else {
            // teleport() returns false if the world is unloaded or another plugin cancels it
            if (player.teleport(lastLocation)) {
                // Single-use: consume the saved location so a second /back says "no location"
                lastLocations.remove(player.uniqueId)
                if (backCooldown > 0) backCooldowns[player.uniqueId] = System.currentTimeMillis()
                sendMessage(player, messageManager.getBackTeleporting())
                playSound(player, successSound)
            }
        }
    }

    private fun handleReloadCommand(player: Player) {
        if (!player.hasPermission("yotpa.reload")) {
            sendMessage(player, messageManager.getTpaReloadNoPermission())
            return
        }

        val reloadOk = runCatching {
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

        if (!reloadOk) return

        val result = validateConfig()

        if (result.isValid) {
            loadConfig()
            val oldMode = detectedMode
            detectAndApplyPerformanceMode()
            cachedSettings = getPerformanceSettings()

            sendMessage(player, messageManager.getTpaReloadSuccess())

            if (result.warnings.isNotEmpty()) {
                sendMessage(player, messageManager.getTpaReloadWarningsHeader())
                result.warnings.forEach { player.sendMessage(messageManager.getTpaReloadWarningItem(it)) }
            }

            if (oldMode != detectedMode) {
                sendMessage(player, messageManager.getTpaReloadModeChanged(oldMode.name, detectedMode.name))
                sendMessage(player, messageManager.getTpaReloadRestartRecommended())
            }
        } else {
            sendMessage(player, messageManager.getTpaReloadValidationFailed())
            result.errors.forEach { player.sendMessage(messageManager.getTpaReloadErrorItem(it)) }

            if (result.warnings.isNotEmpty()) {
                sendMessage(player, messageManager.getTpaReloadWarningsHeader())
                result.warnings.forEach { player.sendMessage(messageManager.getTpaReloadWarningItem(it)) }
            }

            sendMessage(player, messageManager.getTpaReloadNotApplied())
            sendMessage(player, messageManager.getTpaReloadUsingPrevious())
        }
    }

    private fun handleStatsCommand(player: Player) {
        if (!player.hasPermission("yotpa.stats")) {
            sendMessage(player, messageManager.getTpaStatsNoPermission())
            return
        }

        val stats = bStats.getStatistics()
        val acceptanceRate = bStats.getAcceptanceRate()

        player.sendMessage(messageManager.getTpaStatsHeader())

        stats["All-Time"]?.let { s ->
            player.sendMessage(messageManager.getTpaStatsAllTime())
            player.sendMessage(messageManager.getTpaStatsSent(s["Sent"].toString()))
            player.sendMessage(messageManager.getTpaStatsAccepted(s["Accepted"].toString()))
            player.sendMessage(messageManager.getTpaStatsDenied(s["Denied"].toString()))
            player.sendMessage(messageManager.getTpaStatsExpired(s["Expired"].toString()))
            player.sendMessage(messageManager.getTpaStatsAcceptanceRate(acceptanceRate.toString()))
        }

        stats["Daily"]?.let { s ->
            player.sendMessage(messageManager.getTpaStatsDaily())
            player.sendMessage(messageManager.getTpaStatsSent(s["Sent"].toString()))
            player.sendMessage(messageManager.getTpaStatsAccepted(s["Accepted"].toString()))
            player.sendMessage(messageManager.getTpaStatsDenied(s["Denied"].toString()))
            player.sendMessage(messageManager.getTpaStatsExpired(s["Expired"].toString()))
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

    // ─── Teleport lifecycle ───────────────────────────────────────────────────

    fun startTeleportCountdown(teleporter: Player, destination: Player) {
        if (isFolia) {
            // The teleporter may be owned by a different region than the command sender
            // (plain /tpa: teleporter = requester). Its location read, title, and task
            // registration must run on ITS region thread. Dispatching also serialises the
            // countdown start with the teleporter's move events, closing the window where
            // a movement-cancel fires before the task handle is registered.
            teleporter.scheduler.run(this, { _ -> beginCountdown(teleporter, destination) }, null)
        } else {
            beginCountdown(teleporter, destination)
        }
    }

    private fun beginCountdown(teleporter: Player, destination: Player) {
        cancelTeleport(teleporter.uniqueId)

        storeCountdownOrigin(teleporter.uniqueId, teleporter.location.clone())

        val data = TeleportData(
            destinationUUID = destination.uniqueId,
            startTime = System.currentTimeMillis(),
            duration = teleportDelay * 1000,
            lastShownSecond = teleportDelay
        )
        teleportData[teleporter.uniqueId] = data

        if (titlesEnabled) {
            teleporter.showTitle(
                Title.title(
                    messageManager.getTeleportTitle(),
                    messageManager.getTeleportSubtitle(),
                    titleTimes
                )
            )
        }
        sendCountdownMessage(teleporter, teleportDelay)

        // Folia: per-entity task required for region-thread ownership
        // Paper: the single batch task in startPaperBatchTask() handles all countdowns
        if (isFolia) {
            teleporter.scheduler.runAtFixedRate(this, { task ->
                // Stale-task guard: never advance a countdown whose data entry was
                // cancelled or replaced — cancel this task instead of touching the map,
                // which may already hold the handle of a newer countdown.
                if (teleportData[teleporter.uniqueId] !== data) task.cancel()
                else processCountdown(teleporter, data)
            }, null, settings.countdownInterval, settings.countdownInterval)
                ?.let { teleportTasks[teleporter.uniqueId] = it }
        }
    }

    private fun processCountdown(teleporter: Player, data: TeleportData) {
        // Resolve Player fresh each tick — safe from any thread, avoids zombie references.
        // Bukkit.getPlayer() returns null for offline players, making the isOnline check redundant
        // and avoiding an entity-API call from a potentially foreign region thread on Folia.
        val destination = Bukkit.getPlayer(data.destinationUUID)
        if (destination == null) {
            cancelTeleport(teleporter.uniqueId)
            sendMessage(teleporter, messageManager.getTeleportCancelledDestinationOffline())
            playSound(teleporter, cancelSound)
            return
        }

        val elapsed = System.currentTimeMillis() - data.startTime
        val remaining = data.duration - elapsed

        if (remaining <= 0) {
            performTeleport(teleporter, destination)
            cancelTeleport(teleporter.uniqueId)
        } else {
            val remainingSeconds = ((remaining + 999) / 1000).toInt()
            if (remainingSeconds != data.lastShownSecond && remainingSeconds > 0) {
                data.lastShownSecond = remainingSeconds
                sendCountdownMessage(teleporter, remainingSeconds)
                playSound(teleporter, countdownSound)
            }
        }
    }

    private fun sendCountdownMessage(player: Player, seconds: Int) {
        sendMessage(player, messageManager.getTeleportCountdownMessage(seconds))
    }

    fun cancelTeleport(uuid: UUID) {
        // Folia: cancel the per-entity scheduled task
        // Paper: batch task skips entries absent from teleportData automatically
        if (isFolia) {
            teleportTasks.remove(uuid)?.let { task -> runCatching { task.cancel() } }
        }
        teleportData.remove(uuid)
        removeCountdownOrigin(uuid)
    }

    fun cancelTeleportDueToMovement(player: Player) {
        cancelTeleport(player.uniqueId)
        sendMessage(player, messageManager.getTeleportCancelledMovement())
        playSound(player, cancelSound)
    }

    /**
     * Called when a player disconnects. Cleans up any TPA requests they sent or received
     * so stale Player references and map entries don't linger until the expiry timer fires.
     * Note: countdownOrigins is already cleaned by cancelTeleport() called from onPlayerQuit.
     */
    fun cleanupPlayerOnQuit(player: Player) {
        val uuid = player.uniqueId
        tpaRequests.remove(uuid)
        tpaRequests.entries.removeIf { it.value.requesterUUID == uuid }
        cooldowns.remove(uuid)
        playerNameCache.remove(uuid)
        lastLocations.remove(uuid)
        backCooldowns.remove(uuid)
    }

    private fun performTeleport(teleporter: Player, destination: Player) {
        // teleporter.location is safe here — always called on teleporter's own region thread
        // (entity scheduler callback or Paper main thread).
        lastLocations[teleporter.uniqueId] = teleporter.location.clone()

        if (isFolia) {
            // destination may be owned by a completely different Folia region.
            // Reading destination.location from the teleporter's region thread is a thread violation.
            // Fix: dispatch to destination's entity scheduler to snapshot the location there,
            // then launch teleportAsync (which loads chunks and works from any thread).
            val destName = destination.name   // Player name is immutable — safe from any thread
            destination.scheduler.run(this, { _ ->
                val destLocation = destination.location.clone()
                teleporter.teleportAsync(destLocation).thenAccept { success ->
                    if (success) {
                        teleporter.scheduler.run(this, { _ ->
                            sendMessage(teleporter, messageManager.getTeleportSuccess(destName))
                            playSound(teleporter, successSound)
                        }, null)
                        destination.scheduler.run(this, { _ -> playSound(destination, successSound) }, null)
                    }
                }
            }, null)
        } else {
            if (teleporter.teleport(destination)) {
                sendMessage(teleporter, messageManager.getTeleportSuccess(destination.name))
                playSound(teleporter, successSound)
                playSound(destination, successSound)
            }
        }
    }

    // ─── Paper batch countdown task ───────────────────────────────────────────

    /**
     * One repeating task processes ALL active teleport countdowns each interval.
     * Replaces N per-player scheduled tasks with a single O(active-teleports) loop,
     * keeping scheduler overhead at O(1) regardless of concurrent player count.
     *
     * Period is driven by settings.countdownInterval rather than a hardcoded 1 tick:
     * - ULTRA_LIGHT / LIGHT / BALANCED: 20 ticks (1 s)  — no missed second since countdown is time-based
     * - HIGH_PERFORMANCE: 5 ticks (250 ms)               — tighter polling for better responsiveness
     * Polling every 1 tick (50 ms) would waste ~95-98 % of main-thread work for no gain.
     */
    private fun startPaperBatchTask() {
        val interval = settings.countdownInterval
        paperBatchTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, {
            if (teleportData.isEmpty()) return@scheduleSyncRepeatingTask
            // ConcurrentHashMap iteration is safe without a snapshot: the iterator uses weak-consistency
            // semantics and will not throw CME even when cancelTeleport() removes entries mid-loop.
            val iter = teleportData.entries.iterator()
            while (iter.hasNext()) {
                val (uuid, data) = iter.next()
                val teleporter = Bukkit.getPlayer(uuid)
                if (teleporter == null || !teleporter.isOnline) {
                    iter.remove()
                    removeCountdownOrigin(uuid)
                    continue
                }
                processCountdown(teleporter, data)
            }
        }, 1L, interval)
    }

    // ─── Maintenance tasks ────────────────────────────────────────────────────

    private fun startMaintenanceTasks() {
        val s = settings

        if (executor != null) {
            executor!!.scheduleAtFixedRate({
                runCatching { checkExpiredRequests() }
                    .onFailure { e -> logger.log(Level.WARNING, "Error during expiration check", e) }
            }, s.expirationInterval, s.expirationInterval, TimeUnit.MILLISECONDS)

            executor!!.scheduleAtFixedRate({
                runCatching { cleanupCaches() }
                    .onFailure { e -> logger.log(Level.WARNING, "Error during cache cleanup", e) }
            }, s.cleanupInterval, s.cleanupInterval, TimeUnit.MILLISECONDS)
        } else {
            // ULTRA_LIGHT: no executor, use scheduler
            val expirationTicks = s.expirationInterval / 50
            val cleanupTicks = s.cleanupInterval / 50
            if (isFolia) {
                server.globalRegionScheduler.runAtFixedRate(
                    this,
                    { _ -> checkExpiredRequests() },
                    expirationTicks,
                    expirationTicks
                )
                server.globalRegionScheduler.runAtFixedRate(this, { _ -> cleanupCaches() }, cleanupTicks, cleanupTicks)
            } else {
                Bukkit.getScheduler()
                    .scheduleSyncRepeatingTask(this, { checkExpiredRequests() }, expirationTicks, expirationTicks)
                Bukkit.getScheduler().scheduleSyncRepeatingTask(this, { cleanupCaches() }, cleanupTicks, cleanupTicks)
            }
        }
    }

    private fun checkExpiredRequests() {
        val currentTime = System.currentTimeMillis()
        val timeoutMillis = requestTimeout * 1000L
        val expiredTargets = mutableListOf<UUID>()

        // bypassTimeout is captured at request creation (main thread) — no Bukkit API call needed here,
        // making this loop safe to run from an executor thread or Folia global-region thread.
        tpaRequests.forEach { (targetUuid, request) ->
            if (!request.bypassTimeout && currentTime - request.timestamp > timeoutMillis) {
                expiredTargets.add(targetUuid)
            }
        }

        if (expiredTargets.isEmpty()) return

        val dispatch: () -> Unit = { processExpiredRequests(expiredTargets) }
        if (isFolia) server.globalRegionScheduler.run(this) { _ -> dispatch() }
        else Bukkit.getScheduler().runTask(this, dispatch)
    }

    private fun processExpiredRequests(expired: List<UUID>) {
        expired.forEach { targetUuid ->
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
        // playerNameCache: entries are removed individually in cleanupPlayerOnQuit on every disconnect.
        // Calling Bukkit.getPlayer() here from an executor/global-region thread is not thread-safe,
        // so this scan is intentionally omitted — the cache is already self-cleaning.
        val currentTime = System.currentTimeMillis()
        val cooldownExpiry = requestCooldown * 1000L
        cooldowns.entries.removeIf { currentTime - it.value > cooldownExpiry }
        val backExpiry = backCooldown * 1000L
        backCooldowns.entries.removeIf { currentTime - it.value > backExpiry }
    }

    // ─── Internal helpers ─────────────────────────────────────────────────────

    private fun registerCommands() {
        arrayOf("tpa", "tpaccept", "tpadeny", "tpahere", "tpareload", "tpastats", "tpainfo", "back").forEach { cmd ->
            getCommand(cmd)?.setExecutor(this)
        }
    }

    private fun clearAllData() {
        teleportTasks.clear()
        teleportData.clear()
        tpaRequests.clear()
        cooldowns.clear()
        playerNameCache.clear()
        countdownOrigins.clear()
        lastLocations.clear()
        backCooldowns.clear()
    }

    private fun validateTeleportRequest(requester: Player, target: Player): Boolean {
        if (target.uniqueId == requester.uniqueId) {
            sendMessage(requester, messageManager.getTpaSelfTeleport())
            return false
        }

        if (!requester.hasPermission("yotpa.bypass.cooldown") && isOnCooldown(requester)) {
            // Guard against TOCTOU: cleanupCaches() may concurrently remove the entry between
            // isOnCooldown() and the read below, yielding lastCooldown = 0 and a huge negative remaining.
            val lastCooldown = cooldowns[requester.uniqueId] ?: 0L
            val remaining = ((lastCooldown + requestCooldown * 1000L) - System.currentTimeMillis()) / 1000
            if (remaining > 0) sendMessage(requester, messageManager.getCooldown(remaining))
            return false
        }

        return true
    }

    // Bukkit.getPlayer(String) already matches case-insensitively — no manual O(n) scan needed
    private fun getPlayerByName(name: String): Player? = Bukkit.getPlayer(name)

    private fun getPlayerName(uuid: UUID): String {
        playerNameCache[uuid]?.let { return it }
        // Never cache a failed lookup: the player is offline, so cleanupPlayerOnQuit
        // will never remove the entry — a cached "Unknown" would be both wrong and a leak.
        val name = Bukkit.getPlayer(uuid)?.name ?: return "Unknown"
        if (settings.enablePlayerCache) playerNameCache[uuid] = name
        return name
    }

    private fun storeRequest(requester: Player, target: Player, isHereRequest: Boolean) {
        tpaRequests[target.uniqueId] = TpaRequest(
            requesterUUID = requester.uniqueId,
            targetUUID = target.uniqueId,
            timestamp = System.currentTimeMillis(),
            isHereRequest = isHereRequest,
            bypassTimeout = requester.hasPermission("yotpa.bypass.timeout")
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

    // ─── Config loading ───────────────────────────────────────────────────────

    // Parses values from the already-loaded config object — call reloadConfig() explicitly before this.
    private fun loadConfig() {
        requestTimeout = config.getInt("request-timeout", 60)
        requestCooldown = config.getInt("request-cooldown", 30)
        teleportDelay = config.getInt("teleport-delay", 5)
        backCooldown = config.getInt("back-cooldown", 30)
        soundsEnabled = config.getBoolean("features.sounds", true)
        titlesEnabled = config.getBoolean("features.titles", true)
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

        // Resolve and cache Sound objects once per config load.
        // playSound() receives Sound? directly — zero registry lookups at runtime.
        countdownSound = Registry.SOUNDS.get(countdownSoundKey)
        successSound = Registry.SOUNDS.get(successSoundKey)
        cancelSound = Registry.SOUNDS.get(cancelSoundKey)
        requestSound = Registry.SOUNDS.get(requestSoundKey)
    }

    private fun parseSoundKey(soundName: String, default: NamespacedKey): NamespacedKey {
        return runCatching {
            val key = if (soundName.contains(".")) {
                // lowercase here too — "Block.Note_Block.Pling" would otherwise throw
                // inside NamespacedKey.minecraft and silently fall back to the default
                NamespacedKey.minecraft(soundName.lowercase())
            } else {
                NamespacedKey.minecraft(soundName.lowercase().replace("_", "."))
            }
            if (Registry.SOUNDS.get(key) != null) key
            else {
                logger.fine("Sound '$soundName' not found in registry, using default"); default
            }
        }.getOrElse { logger.fine("Invalid sound name: $soundName, using default"); default }
    }

    private fun setDefaultSounds() {
        countdownSoundKey = NamespacedKey.minecraft("block.note_block.pling")
        successSoundKey = NamespacedKey.minecraft("entity.enderman.teleport")
        cancelSoundKey = NamespacedKey.minecraft("entity.villager.no")
        requestSoundKey = NamespacedKey.minecraft("entity.experience_orb.pickup")
    }

    // ─── Sound & messaging ────────────────────────────────────────────────────

    private fun sendMessage(sender: CommandSender, message: Component) = sender.sendMessage(message)

    /** Null-safe: sound is null when not found in registry or sounds are disabled — silently skipped. */
    private fun playSound(player: Player, sound: Sound?) {
        if (!soundsEnabled) return
        sound ?: return
        runCatching {
            // Entity-emitter overload — avoids reading player.location, which is a
            // region-guarded accessor on Folia when the player is owned by another region
            player.playSound(player, sound, 1.0f, 1.0f)
        }.onFailure { e ->
            logger.log(Level.WARNING, "Failed to play sound", e)
        }
    }

    // ─── Config validation ────────────────────────────────────────────────────

    private data class ValidationResult(val isValid: Boolean, val errors: List<String>, val warnings: List<String>)

    private fun validateSoundConfig(key: String, soundName: String, warnings: MutableList<String>) {
        if (soundName.isEmpty()) {
            warnings.add("Sound '$key' is not set, using default"); return
        }
        val testKey = runCatching {
            if (soundName.contains(".")) NamespacedKey.minecraft(soundName.lowercase())
            else NamespacedKey.minecraft(soundName.lowercase().replace("_", "."))
        }.getOrNull()

        when {
            testKey == null -> warnings.add("Sound '$key' ($soundName) has invalid format")
            Registry.SOUNDS.get(testKey) == null -> warnings.add("Sound '$key' ($soundName) not found in registry, will use default")
        }
    }

    private fun validateConfig(): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        try {
            val timeout = config.getInt("request-timeout", -1)
            when {
                timeout < 0 -> errors.add("request-timeout is missing or invalid")
                timeout < 10 -> warnings.add("request-timeout ($timeout) is very low, recommended: 30-120")
                timeout > 300 -> warnings.add("request-timeout ($timeout) is very high, recommended: 30-120")
            }

            val cooldown = config.getInt("request-cooldown", -1)
            when {
                cooldown < 0 -> errors.add("request-cooldown is missing or invalid")
                cooldown < 5 -> warnings.add("request-cooldown ($cooldown) is very low, recommended: 15-60")
                cooldown > 180 -> warnings.add("request-cooldown ($cooldown) is very high, recommended: 15-60")
            }

            val delay = config.getInt("teleport-delay", -1)
            when {
                delay < 0 -> errors.add("teleport-delay is missing or invalid")
                delay < 1 -> errors.add("teleport-delay must be at least 1 second")
                delay > 30 -> warnings.add("teleport-delay ($delay) is very high, recommended: 3-10")
            }

            val backCd = config.getInt("back-cooldown", -1)
            when {
                backCd < 0 -> errors.add("back-cooldown is missing or invalid (use 0 to disable)")
                backCd > 300 -> warnings.add("back-cooldown ($backCd) is very high, recommended: 0-60")
            }

            val mode = config.getString("performance.mode", "") ?: ""
            if (mode.isNotEmpty()) {
                runCatching { PerformanceMode.valueOf(mode.uppercase()) }
                    .onFailure { errors.add("Invalid performance.mode: '$mode'. Valid: AUTO, ULTRA_LIGHT, LIGHT, BALANCED, HIGH_PERFORMANCE") }
            }

            listOf("countdown", "success", "cancel", "request").forEach { key ->
                validateSoundConfig(key, config.getString("sounds.$key", "") ?: "", warnings)
            }

            listOf("statistics", "bstats", "titles", "sounds").forEach { feature ->
                if (!config.contains("features.$feature")) warnings.add("Feature setting 'features.$feature' is missing, using default")
            }
        } catch (e: Exception) {
            errors.add("Critical error reading config: ${e.message}")
            logger.log(Level.SEVERE, "Error validating config", e)
        }

        return ValidationResult(errors.isEmpty(), errors, warnings)
    }

    // ─── Utility ─────────────────────────────────────────────────────────────

    private fun getMaxMemoryMB(): Long = Runtime.getRuntime().maxMemory() / 1024 / 1024

    // freeMemory() alone only measures free space inside the currently committed heap;
    // true headroom is max minus what is actually used
    private fun getAvailableMemoryMB(): Long {
        val rt = Runtime.getRuntime()
        return (rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())) / 1024 / 1024
    }

    private fun getOptimizationLevel(): String = when (detectedMode) {
        PerformanceMode.ULTRA_LIGHT -> "Maximum (For 512 MB RAM)"
        PerformanceMode.LIGHT -> "High (For 1-2 GB RAM)"
        PerformanceMode.BALANCED -> "Moderate (For 2-4 GB RAM)"
        PerformanceMode.HIGH_PERFORMANCE -> "Minimal (For 4+ GB RAM)"
        else -> "Auto"
    }
}
