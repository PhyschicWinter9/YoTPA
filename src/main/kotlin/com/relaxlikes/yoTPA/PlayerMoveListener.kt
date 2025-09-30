package com.relaxlikes.yoTPA

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.Location
import kotlin.math.abs

class PlayerMoveListener(private val plugin: YoTPA) : Listener {

    // Movement threshold in blocks (0.1 blocks = very small movement)
    private val movementThreshold = 0.3

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerMove(event: PlayerMoveEvent) {
        // Early exit optimization - check if from/to are different at block level
        val from = event.from
        val to = event.to ?: return

        // Skip if only head movement (no position change)
        if (from.x == to.x && from.y == to.y && from.z == to.z) {
            return
        }

        val player = event.player

        // Check if player has metadata for teleportation in progress
        if (!player.hasMetadata("yotpa:original-location")) {
            return
        }

        val originalLoc = player.getMetadata("yotpa:original-location")[0].value() as? Location ?: return

        // Use distance-based threshold instead of integer block comparison
        // This allows for minor position adjustments without canceling teleport
        val distance = calculateHorizontalDistance(originalLoc, to)
        val verticalDistance = abs(originalLoc.y - to.y)

        // Cancel if player moved beyond threshold
        if (distance > movementThreshold || verticalDistance > movementThreshold) {
            plugin.cancelTeleportDueToMovement(player)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerQuit(event: PlayerQuitEvent) {
        // Cancel any ongoing teleport when the player disconnects
        plugin.cancelTeleport(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerTeleport(event: PlayerTeleportEvent) {
        // Only cancel if it's not our plugin teleporting the player
        // Also ignore end gateway teleports to avoid interference
        if (event.cause != PlayerTeleportEvent.TeleportCause.PLUGIN &&
            event.cause != PlayerTeleportEvent.TeleportCause.END_GATEWAY) {
            plugin.cancelTeleport(event.player.uniqueId)
        }
    }

    /**
     * Calculates horizontal distance between two locations efficiently
     * Avoids sqrt for better performance
     */
    private fun calculateHorizontalDistance(loc1: Location, loc2: Location): Double {
        val dx = loc1.x - loc2.x
        val dz = loc1.z - loc2.z
        return kotlin.math.sqrt(dx * dx + dz * dz)
    }
}