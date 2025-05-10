package com.relaxlikes.yoTPA

import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.plugin.Plugin

class FixedMetadataValue(plugin: Plugin, private val value: Any?) : FixedMetadataValue(plugin, value) {
    override fun value(): Any? {
        return value
    }
}