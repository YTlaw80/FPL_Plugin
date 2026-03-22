package com.ytlaw80.fplplugin

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

data class HelpEntry(val command: String, val desc: String, val usage: String)

class Help(private val plugin: org.bukkit.plugin.java.JavaPlugin) : CommandExecutor, TabCompleter {

    private val entries = listOf(
        HelpEntry("uploadnews", "뉴스 업로드", "/uploadnews <제목> <내용...>"),
        HelpEntry("deletenews", "자신이 올린 뉴스 삭제", "/deletenews <뉴스ID>"),
        HelpEntry("checknews", "뉴스 목록 확인", "/checknews"),
        HelpEntry("buy", "주식 구매", "/buy <회사이름> <수량>"),
        HelpEntry("sell", "주식 판매", "/sell <회사이름> <수량>"),
        HelpEntry("checkstats", "주식회사 정보·시세 확인", "/checkstats [회사이름]"),
        HelpEntry("money", "내 지갑·보유 주식 확인", "/money"),
        HelpEntry("wealthrank", "총 자산 순위", "/wealthrank"),
        HelpEntry("transfer", "다른 플레이어에게 송금", "/transfer <플레이어> <금액>"),
        HelpEntry("notifysettings", "주식·뉴스 알림 설정", "/notifysettings"),
        HelpEntry("makecompany", "주식회사 생성 (권한)", "/makecompany <회사이름> <기본가격>"),
        HelpEntry("deletecompany", "상장 폐지 (권한)", "/deletecompany <회사이름>"),
        HelpEntry("setstars", "별점 조정 (권한)", "/setstars <회사이름> <별점>"),
        HelpEntry("setstartmoney", "기본 돈 설정 (권한)", "/setstartmoney <금액>"),
        HelpEntry("setmoney", "플레이어 돈 설정 (권한)", "/setmoney <플레이어> <금액>"),
        HelpEntry("setcompanydesc", "회사 설명 (OP)", "/setcompanydesc <회사이름> <설명...>"),
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

        sender.sendMessage("§6===== §eFPL 플러그인 도움말 §7($page/$totalPages) §6=====")
        pageEntries.forEach { entry ->
            val line = Component.text("  /", NamedTextColor.DARK_GRAY)
                .append(Component.text(entry.command, NamedTextColor.YELLOW)
                    .clickEvent(ClickEvent.suggestCommand(entry.usage))
                    .hoverEvent(HoverEvent.showText(Component.text(entry.usage, NamedTextColor.GRAY))))
                .append(Component.text(" §7- ", NamedTextColor.GRAY))
                .append(Component.text(entry.desc, NamedTextColor.WHITE))
            sender.sendMessage(line)
        }

        if (totalPages > 1) {
            val prevComp = if (page > 1) {
                Component.text("[이전 <] ", NamedTextColor.WHITE)
                    .clickEvent(ClickEvent.runCommand("/help ${page - 1}"))
                    .hoverEvent(HoverEvent.showText(Component.text("이전 페이지")))
            } else {
                Component.text("[이전 <] ", NamedTextColor.DARK_GRAY)
            }
            val pageComp = Component.text("$page / $totalPages 페이지 ", NamedTextColor.GRAY)
            val nextComp = if (page < totalPages) {
                Component.text("[다음 >]", NamedTextColor.WHITE)
                    .clickEvent(ClickEvent.runCommand("/help ${page + 1}"))
                    .hoverEvent(HoverEvent.showText(Component.text("다음 페이지")))
            } else {
                Component.text("[다음 >]", NamedTextColor.DARK_GRAY)
            }
            sender.sendMessage(Component.empty().append(prevComp).append(pageComp).append(nextComp))
        }
    }
}
