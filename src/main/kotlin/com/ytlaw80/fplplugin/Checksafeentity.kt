package com.ytlaw80.fplplugin

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.EntityType

class Checksafeentity(
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

        if (args.isEmpty()) {
            if (safeEntityTypes.isEmpty()) {
                sender.sendMessage("§c안전 목록이 비어있거나 로드에 실패했습니다.")
                return true
            }
            sender.sendMessage("§a[안전 엔티티 목록] §7(${safeEntityTypes.size}개)")
            safeEntityTypes.sortedBy { it.name }.forEach {
                sender.sendMessage("§7- §f${it.name}")
            }
            return true
        }

        val input = args[0].trim().uppercase()
        val entityType = try {
            EntityType.valueOf(input)
        } catch (e: IllegalArgumentException) {
            sender.sendMessage("§c알 수 없는 엔티티 타입입니다: ${args[0]}")
            return true
        }

        if (safeEntityManager.isSafe(entityType)) {
            sender.sendMessage("§a${entityType.name} 은(는) 안전 목록에 §l있습니다§r§a.")
        } else {
            sender.sendMessage("§c${entityType.name} 은(는) 안전 목록에 §l없습니다§r§c.")
        }

        return true
    }
}