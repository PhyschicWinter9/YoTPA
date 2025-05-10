package com.relaxlikes.yoTPA

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.Location

class PlayerMoveListener(private val plugin: YoTPA) : Listener {
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerMove(event: PlayerMoveEvent) {
        val player = event.player

        // Check if player has metadata for teleportation in progress
        if (!player.hasMetadata("yotpa:original-location")) {
            return
        }

        val originalLoc = player.getMetadata("yotpa:original-location")[0].value() as? Location ?: return
        val currentLoc = player.location

        // If the player moved blocks (not just looking around), cancel teleportation
        if (originalLoc.x.toInt() != currentLoc.x.toInt() ||
            originalLoc.y.toInt() != currentLoc.y.toInt() ||
            originalLoc.z.toInt() != currentLoc.z.toInt()) {
            plugin.cancelTeleportDueToMovement(player)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.player

        // Cancel any ongoing teleport when the player disconnects
        plugin.cancelTeleport(player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerTeleport(event: PlayerTeleportEvent) {
        // Only cancel if it's not our plugin teleporting the player
        if (event.cause != PlayerTeleportEvent.TeleportCause.PLUGIN) {
            plugin.cancelTeleport(event.player.uniqueId)
        }
    }
}