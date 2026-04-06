package com.relaxlikes.yoTPA

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.logging.Level

/**
 * Manages all plugin messages with support for customization and internationalization
 */
class MessageManager(private val plugin: JavaPlugin) {

    private lateinit var messagesConfig: FileConfiguration
    private lateinit var messagesFile: File
    private val miniMessage = MiniMessage.miniMessage()

    // Cache for parsed prefix component
    private var prefixComponent: Component? = null

    /**
     * Initialize the message manager and load messages
     */
    fun initialize() {
        messagesFile = File(plugin.dataFolder, "messages.yml")

        // Create messages.yml if it doesn't exist
        if (!messagesFile.exists()) {
            plugin.saveResource("messages.yml", false)
        }

        loadMessages()
    }

    /**
     * Load or reload messages from the configuration file.
     * Any key missing from the server's messages.yml falls back to the bundled default,
     * so new keys added in plugin updates are always available without manual migration.
     */
    fun loadMessages() {
        messagesConfig = YamlConfiguration.loadConfiguration(messagesFile)

        // Merge bundled defaults — keys absent in the server file fall through to the JAR resource
        plugin.getResource("messages.yml")?.bufferedReader()?.use { reader ->
            val defaults = YamlConfiguration.loadConfiguration(reader)
            messagesConfig.setDefaults(defaults)
        }

        // Parse and cache the prefix
        val prefixString = messagesConfig.getString("prefix") ?: "<green><bold>[<aqua>YoTPA</aqua>]</bold></green> "
        prefixComponent = parseMessage(prefixString)

        plugin.logger.info("Messages loaded successfully from messages.yml")
    }

    /**
     * Reload messages from file
     */
    fun reload() {
        try {
            loadMessages()
        } catch (e: Exception) {
            plugin.logger.log(Level.SEVERE, "Failed to reload messages.yml", e)
        }
    }

    /**
     * Get the prefix component
     */
    fun getPrefix(): Component {
        return prefixComponent ?: Component.text("[YoTPA] ")
    }

    /**
     * Get a message by path and replace placeholders
     *
     * @param path The configuration path (e.g., "commands.tpa.sent")
     * @param placeholders Map of placeholder keys to values
     * @return Parsed Component with replacements applied
     */
    fun getMessage(path: String, placeholders: Map<String, String> = emptyMap()): Component {
        var messageString = messagesConfig.getString(path) ?: ""

        if (messageString.isEmpty()) {
            plugin.logger.warning("Message not found for path: $path")
            return Component.text("Message not configured: $path")
        }

        // Replace all placeholders
        placeholders.forEach { (key, value) ->
            messageString = messageString.replace("{$key}", value)
        }

        return parseMessage(messageString)
    }

    /**
     * Get a message with prefix
     *
     * @param path The configuration path
     * @param placeholders Map of placeholder keys to values
     * @return Component with prefix prepended
     */
    fun getMessageWithPrefix(path: String, placeholders: Map<String, String> = emptyMap()): Component {
        return getPrefix().append(getMessage(path, placeholders))
    }

    /**
     * Parse a message string using MiniMessage format
     */
    private fun parseMessage(message: String): Component {
        return try {
            miniMessage.deserialize(message)
        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "Failed to parse message: $message", e)
            Component.text(message)
        }
    }

    /**
     * Get a raw message string without parsing
     */
    fun getRawMessage(path: String): String {
        return messagesConfig.getString(path) ?: ""
    }

    // ═══════════════════════════════════════════════════════════════
    //                    CONVENIENCE MESSAGE METHODS
    // ═══════════════════════════════════════════════════════════════

    // TPA Command Messages
    fun getTpaUsage() = getMessageWithPrefix("commands.tpa.usage")
    fun getTpaSent(target: String) = getMessageWithPrefix("commands.tpa.sent", mapOf("target" to target))
    fun getTpaReceived(requester: String) = getMessageWithPrefix("commands.tpa.received", mapOf("requester" to requester))
    fun getTpaSelfTeleport() = getMessageWithPrefix("commands.tpa.self-teleport")

    // TPAHere Command Messages
    fun getTpaHereUsage() = getMessageWithPrefix("commands.tpahere.usage")
    fun getTpaHereSent(target: String) = getMessageWithPrefix("commands.tpahere.sent", mapOf("target" to target))
    fun getTpaHereReceived(requester: String) = getMessageWithPrefix("commands.tpahere.received", mapOf("requester" to requester))

    // TPAccept Command Messages
    fun getTpAcceptNoRequest() = getMessageWithPrefix("commands.tpaccept.no-request")
    fun getTpAcceptRequesterOffline() = getMessageWithPrefix("commands.tpaccept.requester-offline")
    fun getTpAcceptAcceptedSender(requester: String) = getMessageWithPrefix("commands.tpaccept.accepted-sender", mapOf("requester" to requester))
    fun getTpAcceptAcceptedTarget(player: String) = getMessageWithPrefix("commands.tpaccept.accepted-target", mapOf("player" to player))

    // TPDeny Command Messages
    fun getTpDenyNoRequest() = getMessageWithPrefix("commands.tpadeny.no-request")
    fun getTpDenyDeniedSender(requester: String) = getMessageWithPrefix("commands.tpadeny.denied-sender", mapOf("requester" to requester))
    fun getTpDenyDeniedTarget(player: String) = getMessageWithPrefix("commands.tpadeny.denied-target", mapOf("player" to player))

    // TPAReload Command Messages
    fun getTpaReloadNoPermission() = getMessageWithPrefix("commands.tpareload.no-permission")
    fun getTpaReloadSuccess() = getMessageWithPrefix("commands.tpareload.success")
    fun getTpaReloadFailed() = getMessageWithPrefix("commands.tpareload.failed")
    fun getTpaReloadError(error: String) = getMessage("commands.tpareload.error", mapOf("error" to error))
    fun getTpaReloadFixSyntax() = getMessage("commands.tpareload.fix-syntax")
    fun getTpaReloadValidationFailed() = getMessageWithPrefix("commands.tpareload.validation-failed")
    fun getTpaReloadWarningsHeader() = getMessageWithPrefix("commands.tpareload.warnings-header")
    fun getTpaReloadWarningItem(warning: String) = getMessage("commands.tpareload.warning-item", mapOf("warning" to warning))
    fun getTpaReloadModeChanged(oldMode: String, newMode: String) = getMessageWithPrefix("commands.tpareload.mode-changed", mapOf("old_mode" to oldMode, "new_mode" to newMode))
    fun getTpaReloadRestartRecommended() = getMessageWithPrefix("commands.tpareload.restart-recommended")
    fun getTpaReloadErrorsHeader() = getMessageWithPrefix("commands.tpareload.errors-header")
    fun getTpaReloadErrorItem(error: String) = getMessage("commands.tpareload.error-item", mapOf("error" to error))
    fun getTpaReloadNotApplied() = getMessageWithPrefix("commands.tpareload.not-applied")
    fun getTpaReloadUsingPrevious() = getMessageWithPrefix("commands.tpareload.using-previous")

    // TPAStats Command Messages
    fun getTpaStatsNoPermission() = getMessageWithPrefix("commands.tpastats.no-permission")
    fun getTpaStatsHeader() = getMessage("commands.tpastats.header")
    fun getTpaStatsAllTime() = getMessage("commands.tpastats.all-time")
    fun getTpaStatsDaily() = getMessage("commands.tpastats.daily")
    fun getTpaStatsSent(sent: String) = getMessage("commands.tpastats.sent", mapOf("sent" to sent))
    fun getTpaStatsAccepted(accepted: String) = getMessage("commands.tpastats.accepted", mapOf("accepted" to accepted))
    fun getTpaStatsDenied(denied: String) = getMessage("commands.tpastats.denied", mapOf("denied" to denied))
    fun getTpaStatsExpired(expired: String) = getMessage("commands.tpastats.expired", mapOf("expired" to expired))
    fun getTpaStatsAcceptanceRate(rate: String) = getMessage("commands.tpastats.acceptance-rate", mapOf("rate" to rate))

    // TPAInfo Command Messages
    fun getTpaInfoNoPermission() = getMessageWithPrefix("commands.tpainfo.no-permission")
    fun getTpaInfoHeader() = getMessage("commands.tpainfo.header")
    fun getTpaInfoVersion(version: String) = getMessage("commands.tpainfo.version", mapOf("version" to version))
    fun getTpaInfoPerformanceMode(mode: String) = getMessage("commands.tpainfo.performance-mode", mapOf("mode" to mode))
    fun getTpaInfoAvailableRam(ram: String) = getMessage("commands.tpainfo.available-ram", mapOf("ram" to ram))
    fun getTpaInfoMaxRam(ram: String) = getMessage("commands.tpainfo.max-ram", mapOf("ram" to ram))
    fun getTpaInfoOptimization(level: String) = getMessage("commands.tpainfo.optimization", mapOf("level" to level))
    fun getTpaInfoActiveRequests(count: String) = getMessage("commands.tpainfo.active-requests", mapOf("count" to count))
    fun getTpaInfoActiveTeleports(count: String) = getMessage("commands.tpainfo.active-teleports", mapOf("count" to count))

    // Teleport Messages
    fun getTeleportTitle() = getMessage("teleport.countdown.title")
    fun getTeleportSubtitle() = getMessage("teleport.countdown.subtitle")
    fun getTeleportCountdownMessage(seconds: Int): Component {
        val plural = if (seconds != 1) "s" else ""
        return getMessageWithPrefix("teleport.countdown.message", mapOf("seconds" to seconds.toString(), "plural" to plural))
    }
    fun getTeleportSuccess(target: String) = getMessageWithPrefix("teleport.success", mapOf("target" to target))
    fun getTeleportCancelledMovement() = getMessageWithPrefix("teleport.cancelled.movement")
    fun getTeleportCancelledDestinationOffline() = getMessageWithPrefix("teleport.cancelled.destination-offline")
    fun getTeleportCancelledGeneral() = getMessageWithPrefix("teleport.cancelled.general")
    fun getTeleportExpiredSender(target: String) = getMessageWithPrefix("teleport.expired.sender", mapOf("target" to target))
    fun getTeleportExpiredReceiver(requester: String) = getMessageWithPrefix("teleport.expired.receiver", mapOf("requester" to requester))

    // Error Messages
    fun getPlayerNotFound(player: String) = getMessageWithPrefix("errors.player-not-found", mapOf("player" to player))
    fun getPlayerOnly() = getMessageWithPrefix("errors.player-only")
    fun getCooldown(cooldown: Long): Component {
        val plural = if (cooldown != 1L) "s" else ""
        return getMessageWithPrefix("errors.cooldown", mapOf("cooldown" to cooldown.toString(), "plural" to plural))
    }
    fun getNoPermission() = getMessageWithPrefix("errors.no-permission")
}