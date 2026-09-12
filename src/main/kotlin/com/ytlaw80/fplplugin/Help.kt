package com.ytlaw80.fplplugin

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

data class HelpEntry(val command: String, val desc: String, val usage: String)

class Help(private val plugin: org.bukkit.plugin.java.JavaPlugin) : CommandExecutor, TabCompleter {

    private val entries = listOf(
        HelpEntry("uploadnews", "help.uploadnews.description", "help.uploadnews.usage"),
        HelpEntry("deletenews", "help.deletenews.description", "help.deletenews.usage"),
        HelpEntry("checknews", "help.checknews.description", "help.checknews.usage"),
        HelpEntry("buy", "help.buy.description", "help.buy.usage"),
        HelpEntry("sell", "help.sell.description", "help.sell.usage"),
        HelpEntry("checkstats", "help.checkstats.description", "help.checkstats.usage"),
        HelpEntry("money", "help.money.description", "help.money.usage"),
        HelpEntry("wealthrank", "help.wealthrank.description", "help.wealthrank.usage"),
        HelpEntry("transfer", "help.transfer.description", "help.transfer.usage"),
        HelpEntry("notifysettings", "help.notifysettings.description", "help.notifysettings.usage"),
        HelpEntry("routes", "help.routes.description", "help.routes.usage"),
        HelpEntry("makecompany", "help.makecompany.description", "help.makecompany.usage"),
        HelpEntry("deletecompany", "help.deletecompany.description", "help.deletecompany.usage"),
        HelpEntry("setstars", "help.setstars.description", "help.setstars.usage"),
        HelpEntry("setstartmoney", "help.setstartmoney.description", "help.setstartmoney.usage"),
        HelpEntry("setmoney", "help.setmoney.description", "help.setmoney.usage"),
        HelpEntry("setcompanydesc", "help.setcompanydesc.description", "help.setcompanydesc.usage"),
        HelpEntry("language", "help.language.description", "help.language.usage"),
    )

    private val perPage = 5
    private val totalPages = (entries.size + perPage - 1) / perPage

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val page = when {
            args.isEmpty() -> 1
            else -> args[0].toIntOrNull()?.coerceIn(1, totalPages) ?: 1
        }
        sendHelpPage(sender, page)
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): MutableList<String> {
        if (args.size != 1) return mutableListOf()
        val prefix = args[0]
        return (1..totalPages).map { it.toString() }.filter { it.startsWith(prefix) }.toMutableList()
    }

    private fun sendHelpPage(sender: CommandSender, page: Int) {
        val pageIndex = (page - 1).coerceIn(0, totalPages - 1)
        val from = pageIndex * perPage
        val to = minOf(from + perPage, entries.size)
        val pageEntries = entries.subList(from, to)

        sender.sendMessage(plugin.messages.text(sender, "help.header", page, totalPages))
        pageEntries.forEach { entry ->
            val line = messageComponent(plugin.messages.text(sender, "common.command-prefix"), NamedTextColor.DARK_GRAY)
                .append(Component.text(entry.command, NamedTextColor.YELLOW)
                    .clickEvent(ClickEvent.suggestCommand(plugin.messages.text(sender, entry.usage)))
                    .hoverEvent(HoverEvent.showText(messageComponent(plugin.messages.text(sender, entry.usage), NamedTextColor.GRAY))))
                .append(messageComponent(plugin.messages.text(sender, "common.description-separator"), NamedTextColor.GRAY))
                .append(messageComponent(plugin.messages.text(sender, entry.desc), NamedTextColor.WHITE))
            sender.sendMessage(line)
        }

        if (totalPages > 1) {
            val prevComp = if (page > 1) {
                messageComponent(plugin.messages.text(sender, "common.previous"), NamedTextColor.WHITE)
                    .clickEvent(ClickEvent.runCommand("/help ${page - 1}"))
                    .hoverEvent(HoverEvent.showText(messageComponent(plugin.messages.text(sender, "common.previous-hover"))))
            } else {
                messageComponent(plugin.messages.text(sender, "common.previous"), NamedTextColor.DARK_GRAY)
            }
            val pageComp = messageComponent(plugin.messages.text(sender, "common.page", page, totalPages), NamedTextColor.GRAY)
            val nextComp = if (page < totalPages) {
                messageComponent(plugin.messages.text(sender, "common.next"), NamedTextColor.WHITE)
                    .clickEvent(ClickEvent.runCommand("/help ${page + 1}"))
                    .hoverEvent(HoverEvent.showText(messageComponent(plugin.messages.text(sender, "common.next-hover"))))
            } else {
                messageComponent(plugin.messages.text(sender, "common.next"), NamedTextColor.DARK_GRAY)
            }
            sender.sendMessage(Component.empty().append(prevComp).append(pageComp).append(nextComp))
        }
    }
}
