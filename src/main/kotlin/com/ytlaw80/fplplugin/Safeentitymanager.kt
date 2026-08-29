package com.ytlaw80.fplplugin

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.EntityType
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

/**
 * safe_entity.yml (플러그인 데이터 폴더 기준)의 읽기/쓰기를 전담하는 매니저.
 * Clearentity, Checksafeentity, Addsafeentity 가 공통으로 사용한다.
 */
class SafeEntityManager(private val plugin: JavaPlugin) {

    private val fileName = "safe_entity.yml"
    private val configKey = "safe-entities"

    private val file: File
        get() = File(plugin.dataFolder, fileName)

    /** 데이터 폴더에 파일이 없으면 jar 내부 기본값을 최초 1회 복사 */
    private fun ensureFileExists() {
        if (!file.exists()) {
            plugin.dataFolder.mkdirs()
            plugin.saveResource(fileName, false)
        }
    }

    private fun loadConfig(): YamlConfiguration {
        ensureFileExists()
        return YamlConfiguration.loadConfiguration(file)
    }

    /** 현재 안전 목록을 EntityType 집합으로 반환. 매번 파일에서 새로 읽어와 최신 상태를 보장. */
    fun getSafeEntities(): Set<EntityType> {
        val config = loadConfig()
        val names = config.getStringList(configKey)

        return names.mapNotNull { name ->
            try {
                EntityType.valueOf(name.trim().uppercase())
            } catch (e: IllegalArgumentException) {
                plugin.logger.warning("$fileName 에 알 수 없는 엔티티 타입이 있습니다: $name")
                null
            }
        }.toSet()
    }

    fun isSafe(entityType: EntityType): Boolean {
        return getSafeEntities().contains(entityType)
    }

    /**
     * 안전 목록에 엔티티 타입 추가.
     * @return true = 추가됨, false = 이미 존재해서 변경 없음
     */
    fun addSafeEntity(entityType: EntityType): Boolean {
        val config = loadConfig()
        val currentList = config.getStringList(configKey).toMutableList()

        val alreadySafe = currentList.any { it.trim().uppercase() == entityType.name }
        if (alreadySafe) return false

        currentList.add(entityType.name)
        config.set(configKey, currentList)
        config.save(file)
        return true
    }

    /**
     * 안전 목록에서 엔티티 타입 제거.
     * @return true = 제거됨, false = 원래 없었음
     */
    fun removeSafeEntity(entityType: EntityType): Boolean {
        val config = loadConfig()
        val currentList = config.getStringList(configKey).toMutableList()

        val removed = currentList.removeIf { it.trim().uppercase() == entityType.name }
        if (!removed) return false

        config.set(configKey, currentList)
        config.save(file)
        return true
    }
}