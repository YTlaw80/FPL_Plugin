package com.ytlaw80.fplplugin

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.EntityType

class Delsafeentity(
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

        if (args.isEmpty()) {
            sender.sendMessage("§c사용법: /${label} <엔티티타입>")
            return true
        }

        val input = args[0].trim().uppercase()
        val entityType = try {
            EntityType.valueOf(input)
        } catch (e: IllegalArgumentException) {
            sender.sendMessage("§c알 수 없는 엔티티 타입입니다: ${args[0]}")
            return true
        }

        val removed = safeEntityManager.removeSafeEntity(entityType)
        if (removed) {
            sender.sendMessage("§a${entityType.name} 을(를) 안전 목록에서 제거했습니다.")
        } else {
            sender.sendMessage("§e${entityType.name} 은(는) 원래 안전 목록에 없었습니다.")
        }

        return true
    }
}