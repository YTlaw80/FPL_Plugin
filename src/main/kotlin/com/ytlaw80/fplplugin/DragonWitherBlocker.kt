package com.ytlaw80.fplplugin

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.EntityType
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntitySpawnEvent
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

/**
 * [block_entity.yml]에 적힌 엔티티 타입은 스폰을 취소합니다.
 *
 * yml 형식(예시):
 * blocked_entities:
 *   - ENDER_DRAGON
 *   - WITHER
 */
class DragonWitherBlocker(private val plugin: JavaPlugin) : Listener {

    private val blockFile = File(plugin.dataFolder, "block_entity.yml")
    private var blockedTypes: Set<EntityType> = emptySet()

    init {
        reload()
    }

    fun reload() {
        if (!blockFile.exists() && plugin.getResource("block_entity.yml") != null) {
            plugin.saveResource("block_entity.yml", false)
        }

        val yaml = YamlConfiguration.loadConfiguration(blockFile)

        val rawList = when {
            yaml.contains("blocked_entities") -> yaml.getStringList("blocked_entities")
            yaml.contains("blockedEntities") -> yaml.getStringList("blockedEntities")
            yaml.contains("entities") -> yaml.getStringList("entities")
            yaml.contains("blocked") -> yaml.getStringList("blocked")
            else -> emptyList()
        }

        val parsed = rawList.mapNotNull { raw -> parseEntityType(raw) }.toSet()

        if (parsed.isEmpty() && rawList.isNotEmpty()) {
            plugin.logger.warning("block_entity.yml에 유효한 EntityType이 없습니다. entries=${rawList.joinToString(", ")}")
        }

        blockedTypes = parsed
    }

    private fun parseEntityType(raw: String): EntityType? {
        val normalized = raw.trim()
            .replace("minecraft:", "", ignoreCase = true)
            .replace("-", "_")
            .replace(' ', '_')
            .uppercase()
        return runCatching { EntityType.valueOf(normalized) }.getOrNull()
    }

    @EventHandler(ignoreCancelled = true)
    fun onEntitySpawn(event: EntitySpawnEvent) {
        if (blockedTypes.contains(event.entityType)) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onCreatureSpawn(event: CreatureSpawnEvent) {
        if (blockedTypes.contains(event.entityType)) event.isCancelled = true
    }
}

