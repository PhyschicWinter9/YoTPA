package com.relaxlikes.yoTPA

import org.bstats.bukkit.Metrics
import org.bstats.charts.MultiLineChart
import org.bstats.charts.SimplePie
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.collections.HashMap


class bStatsTPA(private val plugin: YoTPA) {

    // Counters for all-time statistics
    private val requestsSent = AtomicInteger(0)
    private val requestsAccepted = AtomicInteger(0)
    private val requestsDenied = AtomicInteger(0)
    private val requestsExpired = AtomicInteger(0)

    // Counters for daily statistics
    private val dailyRequestsSent = AtomicInteger(0)
    private val dailyRequestsAccepted = AtomicInteger(0)
    private val dailyRequestsDenied = AtomicInteger(0)
    private val dailyRequestsExpired = AtomicInteger(0)


    fun initialize() {
        // Add custom charts or metrics here if needed
        // Example: metrics.addCustomChart(CustomChart("example_chart") { 42 })
        // BStats configuration
        val pluginId = 25926 // Replace with your actual plugin ID
        val metrics = Metrics(plugin, pluginId)

        // Set up custom charts
        setupPluginCharts(metrics)

        // Schedule daily counter reset
        scheduleDailyReset()

        plugin.logger.info("bStats metrics initialized for YoTPA")
    }

    /**
     * Set up all the custom charts for bStats
     */
    private fun setupPluginCharts(metrics: Metrics) {
        // Basic plugin configuration charts
        setupConfigCharts(metrics)

        // Request tracking charts
        setupRequestCharts(metrics)
    }

    /**
     * Set up configuration related charts
     */
    private fun setupConfigCharts(metrics: Metrics) {
        // Teleport delay setting
        metrics.addCustomChart(SimplePie("teleport_delay") {
            val delay = plugin.config.getInt("teleport-delay", 5)
            delay.toString()
        })

        // Title display enabled/disabled
        metrics.addCustomChart(SimplePie("titles_enabled") {
            if (plugin.config.getBoolean("titles.enabled", true)) "Enabled" else "Disabled"
        })

        // Request timeout setting
        metrics.addCustomChart(SimplePie("request_timeout") {
            val timeout = plugin.config.getInt("request-timeout", 60)
            when (timeout) {
                in 0..30 -> "0-30 seconds"
                in 31..60 -> "31-60 seconds"
                in 61..120 -> "61-120 seconds"
                else -> "120+ seconds"
            }
        })

        // Request cooldown setting
        metrics.addCustomChart(SimplePie("request_cooldown") {
            val cooldown = plugin.config.getInt("request-cooldown", 30)
            when (cooldown) {
                in 0..15 -> "0-15 seconds"
                in 16..30 -> "16-30 seconds"
                in 31..60 -> "31-60 seconds"
                else -> "60+ seconds"
            }
        })
    }

    /**
     * Set up request tracking charts
     */
    private fun setupRequestCharts(metrics: Metrics) {
        // All-time request statistics
        metrics.addCustomChart(MultiLineChart("teleport_requests") {
            val values = HashMap<String, Int>()
            values["Sent"] = requestsSent.get()
            values["Accepted"] = requestsAccepted.get()
            values["Denied"] = requestsDenied.get()
            values["Expired"] = requestsExpired.get()
            values
        })

        // Daily request statistics
        metrics.addCustomChart(MultiLineChart("daily_teleport_requests") {
            val values = HashMap<String, Int>()
            values["Daily Sent"] = dailyRequestsSent.get()
            values["Daily Accepted"] = dailyRequestsAccepted.get()
            values["Daily Denied"] = dailyRequestsDenied.get()
            values["Daily Expired"] = dailyRequestsExpired.get()
            values
        })

        // Request success rate
        metrics.addCustomChart(SimplePie("success_rate") {
            val sent = requestsSent.get()
            val accepted = requestsAccepted.get()

            if (sent > 0) {
                val rate = (accepted.toDouble() / sent * 100).toInt()
                when (rate) {
                    in 0..25 -> "0-25%"
                    in 26..50 -> "26-50%"
                    in 51..75 -> "51-75%"
                    else -> "76-100%"
                }
            } else {
                "No Requests"
            }
        })
    }
    /**
     * Schedule a task to reset daily counters at midnight
     */
    private fun scheduleDailyReset() {
        val calendar = Calendar.getInstance()

        // Set to next midnight
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.add(Calendar.DAY_OF_MONTH, 1)

        // Calculate delay until next midnight
        val currentTimeMillis = System.currentTimeMillis()
        val midnightMillis = calendar.timeInMillis
        val delayTicks = (midnightMillis - currentTimeMillis) / 50

        // Schedule the reset task
        plugin.server.scheduler.scheduleSyncRepeatingTask(plugin, {
            // Reset all daily counters
            dailyRequestsSent.set(0)
            dailyRequestsAccepted.set(0)
            dailyRequestsDenied.set(0)
            dailyRequestsExpired.set(0)

            plugin.logger.info("Daily teleport statistics have been reset")
        }, delayTicks, 1728000) // 24 hours in ticks (20tps * 60s * 60m * 24h)
    }

    /**
     * Increment counters when a teleport request is sent
     */
    fun incrementRequestSent() {
        requestsSent.incrementAndGet()
        dailyRequestsSent.incrementAndGet()
    }

    /**
     * Increment counters when a teleport request is accepted
     */
    fun incrementRequestAccepted() {
        requestsAccepted.incrementAndGet()
        dailyRequestsAccepted.incrementAndGet()
    }

    /**
     * Increment counters when a teleport request is denied
     */
    fun incrementRequestDenied() {
        requestsDenied.incrementAndGet()
        dailyRequestsDenied.incrementAndGet()
    }

    /**
     * Increment counters when a teleport request expires
     */
    fun incrementRequestExpired() {
        requestsExpired.incrementAndGet()
        dailyRequestsExpired.incrementAndGet()
    }

    /**
     * Get current statistics as a formatted map
     */
    fun getStatistics(): Map<String, Map<String, Int>> {
        val stats = HashMap<String, Map<String, Int>>()

        // All-time stats
        val allTimeStats = HashMap<String, Int>()
        allTimeStats["Sent"] = requestsSent.get()
        allTimeStats["Accepted"] = requestsAccepted.get()
        allTimeStats["Denied"] = requestsDenied.get()
        allTimeStats["Expired"] = requestsExpired.get()
        stats["All-Time"] = allTimeStats

        // Daily stats
        val dailyStats = HashMap<String, Int>()
        dailyStats["Sent"] = dailyRequestsSent.get()
        dailyStats["Accepted"] = dailyRequestsAccepted.get()
        dailyStats["Denied"] = dailyRequestsDenied.get()
        dailyStats["Expired"] = dailyRequestsExpired.get()
        stats["Daily"] = dailyStats

        return stats
    }

    /**
     * Calculate and get acceptance rate
     */
    fun getAcceptanceRate(): Int {
        val sent = requestsSent.get()
        return if (sent > 0) {
            (requestsAccepted.get().toDouble() / sent * 100).toInt()
        } else {
            0
        }
    }
}