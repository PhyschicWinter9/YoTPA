package com.relaxlikes.yoTPA

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.Location
import kotlin.math.abs

class PlayerMoveListener(
    private val plugin: YoTPA,
    private val movementThreshold: Double
) : Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerMove(event: PlayerMoveEvent) {
        // Ultra-fast early exit - skip if only head movement
        val from = event.from
        val to = event.to

        // Check if position actually changed (not just looking around)
        if (from.x == to.x && from.y == to.y && from.z == to.z) {
            return
        }

        val player = event.player

        // Quick metadata check
        if (!player.hasMetadata("yotpa:original-location")) {
            return
        }

        val originalLoc = player.getMetadata("yotpa:original-location")[0].value() as? Location ?: return

        // Calculate distance efficiently
        // Using abs comparison avoids sqrt calculation for better performance
        val dx = abs(originalLoc.x - to.x)
        val dy = abs(originalLoc.y - to.y)
        val dz = abs(originalLoc.z - to.z)

        // Cancel if moved beyond threshold
        // Higher threshold = more lenient (good for low spec servers)
        // Lower threshold = more strict (better for high spec servers)
        if (dx > movementThreshold || dy > movementThreshold || dz > movementThreshold) {
            plugin.cancelTeleportDueToMovement(player)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerQuit(event: PlayerQuitEvent) {
        // Cancel any ongoing teleport when player disconnects
        plugin.cancelTeleport(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerTeleport(event: PlayerTeleportEvent) {
        // Only cancel if it's not our plugin teleporting the player
        // Ignore end gateway to avoid interference with natural game mechanics
        if (event.cause != PlayerTeleportEvent.TeleportCause.PLUGIN &&
            event.cause != PlayerTeleportEvent.TeleportCause.END_GATEWAY) {
            plugin.cancelTeleport(event.player.uniqueId)
        }
    }
}