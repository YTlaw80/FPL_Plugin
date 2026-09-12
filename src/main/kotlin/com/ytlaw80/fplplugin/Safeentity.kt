package com.ytlaw80.fplplugin

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin

class SafeEntity(private val plugin: JavaPlugin) : CommandExecutor, TabCompleter {

    private val safeEntityManager: SafeEntityManager = SafeEntityManager(plugin)

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ): Boolean {
        return when (command.name.lowercase()) {
            "addsafeentity" -> handleAddSafeEntity(sender, args)
            "checksafeentity" -> handleCheckSafeEntity(sender, args)
            "clearentity" -> handleClearEntity(sender)
            "delsafeentity" -> handleDelSafeEntity(sender, args)
            else -> false
        }
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): MutableList<String> {
        if (!sender.isOp || args.size != 1) return mutableListOf()

        val entityTypes = when (command.name.lowercase()) {
            "addsafeentity", "checksafeentity" -> EntityType.entries.toList()
            "delsafeentity" -> safeEntityManager.getSafeEntities().toList()
            else -> return mutableListOf()
        }
        val prefix = args[0]
        return entityTypes
            .map { it.name }
            .filter { it.startsWith(prefix, ignoreCase = true) }
            .sorted()
            .toMutableList()
    }

    private fun handleAddSafeEntity(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.isOp) {
            sender.sendMessage(plugin.messages.text(sender, "common.op-only"))
            return true
        }

        if (args.isEmpty()) {
            sender.sendMessage(plugin.messages.text(sender, "safe-entity.add-usage"))
            return true
        }

        val input = args[0].trim().uppercase()
        val entityType = try {
            EntityType.valueOf(input)
        } catch (e: IllegalArgumentException) {
            sender.sendMessage(plugin.messages.text(sender, "safe-entity.unknown-type", args[0]))
            return true
        }

        val added = safeEntityManager.addSafeEntity(entityType)
        if (added) {
            sender.sendMessage(plugin.messages.text(sender, "safe-entity.added", entityType.name))
        } else {
            sender.sendMessage(plugin.messages.text(sender, "safe-entity.already-safe", entityType.name))
        }

        return true
    }

    private fun handleCheckSafeEntity(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.isOp) {
            sender.sendMessage(plugin.messages.text(sender, "common.op-only"))
            return true
        }

        val safeEntityTypes = safeEntityManager.getSafeEntities()

        if (args.isEmpty()) {
            if (safeEntityTypes.isEmpty()) {
                sender.sendMessage(plugin.messages.text(sender, "safe-entity.empty"))
                return true
            }
            sender.sendMessage(plugin.messages.text(sender, "safe-entity.header", safeEntityTypes.size))
            safeEntityTypes.sortedBy { it.name }.forEach {
                sender.sendMessage(plugin.messages.text(sender, "safe-entity.list-item", it.name))
            }
            return true
        }

        val input = args[0].trim().uppercase()
        val entityType = try {
            EntityType.valueOf(input)
        } catch (e: IllegalArgumentException) {
            sender.sendMessage(plugin.messages.text(sender, "safe-entity.unknown-type", args[0]))
            return true
        }

        if (safeEntityManager.isSafe(entityType)) {
            sender.sendMessage(plugin.messages.text(sender, "safe-entity.is-safe", entityType.name))
        } else {
            sender.sendMessage(plugin.messages.text(sender, "safe-entity.not-safe", entityType.name))
        }

        return true
    }

    private fun handleClearEntity(sender: CommandSender): Boolean {
        if (!sender.isOp) {
            sender.sendMessage(plugin.messages.text(sender, "common.op-only"))
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

        sender.sendMessage(plugin.messages.text(sender, "safe-entity.cleared", killedCount))
        return true
    }

    private fun handleDelSafeEntity(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.isOp) {
            sender.sendMessage(plugin.messages.text(sender, "common.op-only"))
            return true
        }

        if (args.isEmpty()) {
            sender.sendMessage(plugin.messages.text(sender, "safe-entity.delete-usage"))
            return true
        }

        val input = args[0].trim().uppercase()
        val entityType = try {
            EntityType.valueOf(input)
        } catch (e: IllegalArgumentException) {
            sender.sendMessage(plugin.messages.text(sender, "safe-entity.unknown-type", args[0]))
            return true
        }

        val removed = safeEntityManager.removeSafeEntity(entityType)
        if (removed) {
            sender.sendMessage(plugin.messages.text(sender, "safe-entity.removed", entityType.name))
        } else {
            sender.sendMessage(plugin.messages.text(sender, "safe-entity.not-listed", entityType.name))
        }

        return true
    }
}
