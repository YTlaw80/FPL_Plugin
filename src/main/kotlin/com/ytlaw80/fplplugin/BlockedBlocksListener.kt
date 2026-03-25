package com.ytlaw80.fplplugin

import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.block.BlockPhysicsEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.BlockSpreadEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.io.File
import java.util.ArrayDeque

class BlockedBlocksListener(private val plugin: JavaPlugin) : Listener {

    private val blockFile = File(plugin.dataFolder, "block_entity.yml")
    private var blockedMaterials: Set<Material> = emptySet()

    private val removalQueue: ArrayDeque<Chunk> = ArrayDeque()
    private var removalTask: BukkitTask? = null

    init {
        reloadAndEnsureDefaults()
        scheduleInitialRemovalAroundOnlinePlayers()
    }

    fun reloadAndEnsureDefaults() {
        ensureDefaultsFromResource()
        blockedMaterials = loadBlockedMaterials()
    }

    private fun ensureDefaultsFromResource() {
        val resourceStream = plugin.getResource("block_entity.yml") ?: return
        if (!blockFile.exists()) {
            plugin.saveResource("block_entity.yml", false)
            return
        }

        val yaml = YamlConfiguration.loadConfiguration(blockFile)
        val defaults = YamlConfiguration.loadConfiguration(resourceStream.reader(Charsets.UTF_8))

        var changed = false
        // blocked_blocks는 기존 항목이 있더라도 "병합"해서 신규 기본값이 누락되지 않게 처리
        val defaultBlocks = defaults.getStringList("blocked_blocks")
        if (defaultBlocks.isNotEmpty()) {
            val currentBlocks = yaml.getStringList("blocked_blocks")
            val union = (currentBlocks + defaultBlocks).distinct()
            if (union != currentBlocks) {
                yaml.set("blocked_blocks", union)
                changed = true
            }
        }

        if (changed) {
            yaml.save(blockFile)
        }
    }

    private fun loadBlockedMaterials(): Set<Material> {
        val yaml = YamlConfiguration.loadConfiguration(blockFile)

        val rawList = when {
            yaml.contains("blocked_blocks") -> yaml.getStringList("blocked_blocks")
            yaml.contains("blockedBlocks") -> yaml.getStringList("blockedBlocks")
            yaml.contains("blocks") -> yaml.getStringList("blocks")
            else -> emptyList()
        }

        val parsed = rawList.mapNotNull { parseMaterialType(it) }.toSet()

        if (parsed.isEmpty() && rawList.isNotEmpty()) {
            plugin.logger.warning("block_entity.yml에 유효한 Material이 없습니다. entries=${rawList.joinToString(", ")}")
        }

        // SOUL_SAND는 "제거(파괴)"하면 안 된다는 요구사항이 있어, 차단 대상에서 제외
        return parsed - Material.SOUL_SAND
    }

    private fun parseMaterialType(raw: String): Material? {
        val normalized = raw.trim()
            .replace("minecraft:", "", ignoreCase = true)
            .replace("-", "_")
            .replace(' ', '_')
            .uppercase()
        return runCatching { Material.valueOf(normalized) }.getOrNull()
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    fun onBlockPlace(event: BlockPlaceEvent) {
        val type = event.blockPlaced.type
        if (blockedMaterials.contains(type)) {
            event.isCancelled = true
            // 간혹 취소만으로도 한 틱 뒤에 블록이 남는 케이스를 대비해 즉시 제거
            val b = event.blockPlaced
            Bukkit.getScheduler().runTask(plugin, Runnable {
                if (b.type == type) {
                    b.setType(Material.AIR, false)
                }
            })
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    fun onBlockPhysics(event: BlockPhysicsEvent) {
        val type = event.block.type
        if (blockedMaterials.contains(type)) {
            event.isCancelled = true
            event.block.setType(Material.AIR, false)
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    fun onBlockFromTo(event: BlockFromToEvent) {
        val toType = event.toBlock.type
        if (blockedMaterials.contains(toType)) {
            event.isCancelled = true
            event.toBlock.setType(Material.AIR, false)
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    fun onBlockSpread(event: BlockSpreadEvent) {
        val newType = event.newState.type
        if (blockedMaterials.contains(newType)) {
            event.isCancelled = true
            event.newState.block.setType(Material.AIR, false)
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    fun onEntityChangeBlock(event: EntityChangeBlockEvent) {
        val type = event.block.type
        if (blockedMaterials.contains(type)) {
            event.isCancelled = true
            event.block.setType(Material.AIR, false)
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onChunkLoad(event: ChunkLoadEvent) {
        val chunk = event.chunk
        if (blockedMaterials.isEmpty()) return
        removeBlockedBlocksFromChunk(chunk)
    }

    private fun scheduleInitialRemovalAroundOnlinePlayers() {
        if (blockedMaterials.isEmpty()) return

        val radiusChunks = 2

        // 플레이어 주변 청크만 우선 제거(서버 전체 청크를 한 번에 훑는 건 과부하 방지)
        Bukkit.getOnlinePlayers().forEach { p ->
            val w = p.world
            val cx = p.chunk.x
            val cz = p.chunk.z
            for (dx in -radiusChunks..radiusChunks) {
                for (dz in -radiusChunks..radiusChunks) {
                    removalQueue.add(w.getChunkAt(cx + dx, cz + dz))
                }
            }
        }

        // 월드 스폰 주변도 한 번 제거
        Bukkit.getWorlds().forEach { w ->
            removalQueue.add(w.spawnLocation.chunk)
        }

        if (removalTask != null) return
        removalTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            if (blockedMaterials.isEmpty()) return@Runnable

            val batchSize = 1
            repeat(batchSize) {
                val chunk = removalQueue.pollFirst() ?: run {
                    removalTask?.cancel()
                    removalTask = null
                    return@repeat
                }
                removeBlockedBlocksFromChunk(chunk)
            }
        }, 1L, 1L)
    }

    private fun removeBlockedBlocksFromChunk(chunk: Chunk) {
        val world = chunk.world
        val maxY = world.maxHeight

        for (x in 0 until 16) {
            for (z in 0 until 16) {
                for (y in 0 until maxY) {
                    val b: Block = chunk.getBlock(x, y, z)
                    if (blockedMaterials.contains(b.type)) {
                        // physics 없이 제거
                        b.setType(Material.AIR, false)
                    }
                }
            }
        }
    }
}

