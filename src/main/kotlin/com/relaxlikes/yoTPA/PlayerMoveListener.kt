package com.relaxlikes.yoTPA

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerTeleportEvent
import kotlin.math.abs

/**
 * Handles player movement detection during teleport countdown.
 *
 * Uses an in-memory ConcurrentHashMap (via plugin.getCountdownOrigin) instead of
 * PersistentDataContainer — zero serialisation overhead on every move event.
 *
 * movementThreshold is read fresh from plugin.getMovementThreshold() each event
 * so it always reflects the current value after /tpareload.
 */
class PlayerMoveListener(private val plugin: YoTPA) : Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerMove(event: PlayerMoveEvent) {
        val from = event.from
        val to   = event.to

        // Ultra-fast early exit: skip pure head rotation (no position change)
        if (from.x == to.x && from.y == to.y && from.z == to.z) return

        val player = event.player

        // getCountdownOrigin is an O(1) ConcurrentHashMap lookup — safe from any thread
        val origin = plugin.getCountdownOrigin(player) ?: return

        val threshold = plugin.getMovementThreshold()
        if (abs(origin.x - to.x) > threshold ||
            abs(origin.y - to.y) > threshold ||
            abs(origin.z - to.z) > threshold
        ) {
            plugin.cancelTeleportDueToMovement(player)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.player
        plugin.cancelTeleport(player.uniqueId)
        plugin.cleanupPlayerOnQuit(player)
    }

    /**
     * Save the player's death location so /back can return them to where they died.
     * Runs at MONITOR priority so all damage/death cancellations have already resolved.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerDeath(event: PlayerDeathEvent) {
        plugin.saveLastLocation(event.entity)
    }

    /**
     * Notify the player on respawn that their death location is saved for /back.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerRespawn(event: PlayerRespawnEvent) {
        val player = event.player
        // Small delay so the message appears after the respawn screen clears
        if (YoTPA.isFolia) {
            player.scheduler.runDelayed(plugin, { _ ->
                plugin.sendDeathBackNotification(player)
            }, null, 20L)
        } else {
            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                plugin.sendDeathBackNotification(player)
            }, 20L)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerTeleport(event: PlayerTeleportEvent) {
        // Cancel countdown only for non-plugin teleports (e.g. /home, death, nether portal)
        // END_GATEWAY is excluded to avoid interfering with natural game mechanics
        if (event.cause != PlayerTeleportEvent.TeleportCause.PLUGIN &&
            event.cause != PlayerTeleportEvent.TeleportCause.END_GATEWAY
        ) {
            plugin.cancelTeleport(event.player.uniqueId)
        }
    }
}
