package com.ytlaw80.fplplugin

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class Clearentity(
    private val plugin: org.bukkit.plugin.java.JavaPlugin,
    private val safeEntityManager: SafeEntityManager
) : CommandExecutor {

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ): Boolean {
        if (!sender.isOp) {
            sender.sendMessage("§c이 명령어는 OP(서버 운영자)만 사용할 수 있습니다.")
            return true
        }

        val safeEntityTypes = safeEntityManager.getSafeEntities()
        var killedCount = 0

        for (world in plugin.server.worlds) {
            for (entity in world.entities) {
                if (entity is Player) continue
                if (entity.type !in safeEntityTypes) {
                    entity.remove()
                    killedCount++
                }
            }
        }

        sender.sendMessage("§a안전 목록(safe_entity.yml)에 없는 엔티티 §e${killedCount}§a마리를 제거했습니다.")
        return true
    }
}