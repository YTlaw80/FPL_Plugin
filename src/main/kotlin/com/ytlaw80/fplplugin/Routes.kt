package com.ytlaw80.fplplugin

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

data class Station(
    val name: String,
    val color: String,      // 역 색깔 (Minecraft § 코드)
    val line: String,       // 노선(선) 이름
    val district: String
)

class Routes(private val plugin: JavaPlugin) : CommandExecutor, TabCompleter {

    private val routesFile = File(plugin.dataFolder, "routes.json").also { f ->
        if (!f.exists() && plugin.getResource("routes.json") != null) {
            plugin.saveResource("routes.json", false)
        }
    }

    private val gson = Gson()
    private var stations: List<Station> = emptyList()

    init {
        load()
    }

    fun load() {
        stations = runCatching {
            if (!routesFile.exists()) return@runCatching emptyList<Station>()
            val json = routesFile.readText(Charsets.UTF_8)
            val type = object : TypeToken<List<StationJson>>() {}.type
            val list = gson.fromJson<List<StationJson>>(json, type) ?: emptyList()
            list.map { j ->
                // 구 형식(color에 "§a2호선"처럼 같이 적힌 경우) 호환
                val (colorVal, lineVal) = if (j.line.isNotBlank()) {
                    j.color.ifBlank { "§f" } to j.line
                } else {
                    val c = j.color
                    val code = if (c.startsWith("§") && c.length >= 2) c.take(2) else "§f"
                    val rest = c.drop(code.length).trim().ifBlank { "-" }
                    code to rest
                }
                Station(
                    name = j.name.ifBlank { "" },
                    color = colorVal,
                    line = lineVal,
                    district = j.district.ifBlank { "-" }
                )
            }
        }.getOrElse {
            plugin.logger.warning("routes.json 파싱 실패: ${it.message}")
            emptyList()
        }
    }

    private data class StationJson(
        val name: String = "",
        val color: String = "",   // 역 색깔 (§ 코드)
        val line: String = "",    // 노선 이름
        val district: String = ""
    )

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isNotEmpty() && args[0].lowercase() == "reload" && sender.hasPermission("fpl.routes.reload")) {
            load()
            sender.sendMessage(plugin.messages.text(sender, "routes.reloaded"))
            return true
        }

        val (searchQuery, page) = parseRoutesArgs(args)
        val filtered = filterStations(searchQuery)
        sendRoutesPage(sender, filtered, page, searchQuery)
        return true
    }

    /**
     * 인자 파싱:
     * - 없음 → 전체, 1페이지
     * - 숫자 하나만 → 전체 목록의 해당 페이지
     * - 그 외 → 마지막 토큰이 숫자면 검색어 + 페이지, 아니면 검색어만(1페이지)
     */
    private fun parseRoutesArgs(args: Array<out String>): Pair<String?, Int> {
        if (args.isEmpty()) return null to 1

        if (args.size == 1) {
            val n = args[0].toIntOrNull()
            if (n != null && n > 0) return null to n
            return args[0].trim().takeIf { it.isNotEmpty() } to 1
        }

        val last = args.last()
        val lastNum = last.toIntOrNull()
        return if (lastNum != null && lastNum > 0) {
            val searchParts = args.dropLast(1)
            val q = searchParts.joinToString(" ").trim().takeIf { it.isNotEmpty() }
            q to lastNum
        } else {
            args.joinToString(" ").trim().takeIf { it.isNotEmpty() } to 1
        }
    }

    private fun filterStations(query: String?): List<Station> {
        if (query.isNullOrBlank()) return stations
        val q = query.lowercase()
        return stations.filter { stationMatches(it, q) }
    }

    private fun stationMatches(station: Station, qLower: String): Boolean {
        if (station.name.lowercase().contains(qLower)) return true
        return station.line.lowercase().contains(qLower)
    }

    private fun sendRoutesPage(sender: CommandSender, list: List<Station>, page: Int, searchQuery: String?) {
        val perPage = 5
        val totalPages = maxOf(1, (list.size + perPage - 1) / perPage)
        val pageIndex = (page - 1).coerceIn(0, totalPages - 1)
        val from = pageIndex * perPage
        val to = minOf(from + perPage, list.size)

        val searchLabel = if (!searchQuery.isNullOrBlank()) {
            plugin.messages.text(sender, "routes.search-label", searchQuery)
        } else {
            ""
        }
        sender.sendMessage(plugin.messages.text(sender, "routes.header", searchLabel, page, totalPages))

        if (stations.isEmpty()) {
            sender.sendMessage(plugin.messages.text(sender, "routes.empty"))
            sender.sendMessage(plugin.messages.text(sender, "routes.create-file"))
            sender.sendMessage(plugin.messages.text(sender, "routes.format"))
            return
        }

        if (list.isEmpty()) {
            sender.sendMessage(plugin.messages.text(sender, "routes.no-results"))
            sender.sendMessage(plugin.messages.text(sender, "routes.search-example"))
            return
        }

        list.subList(from, to).forEach { station ->
            // 1호선 연두색(§a), 2호선 §6 고정 표기, 그 외는 역 색깔 사용
            val lineColor = when (station.line) {
                "1호선" -> "§a"
                "2호선" -> "§6"
                else -> station.color
            }
            val lineComp = if (lineColor.contains("§")) {
                LegacyComponentSerializer.legacySection().deserialize(lineColor + station.line)
            } else {
                Component.text(station.line, NamedTextColor.WHITE)
            }
            // 역 이름에 역 색깔 적용
            val nameComp = if (station.color.contains("§")) {
                LegacyComponentSerializer.legacySection().deserialize(station.color + station.name.ifBlank { plugin.messages.text(sender, "routes.unnamed") })
            } else {
                Component.text(station.name.ifBlank { plugin.messages.text(sender, "routes.unnamed") }, NamedTextColor.YELLOW)
            }
            val line = messageComponent(plugin.messages.text(sender, "common.indent"), NamedTextColor.DARK_GRAY)
                .append(nameComp
                    .hoverEvent(HoverEvent.showText(
                        messageComponent(plugin.messages.text(sender, "routes.station-label"), NamedTextColor.GRAY).append(nameComp)
                            .append(messageComponent(plugin.messages.text(sender, "routes.line-label"), NamedTextColor.GRAY)).append(lineComp)
                            .append(messageComponent(plugin.messages.text(sender, "routes.district-label"), NamedTextColor.GRAY)).append(Component.text(station.district, NamedTextColor.WHITE))
                    )))
                .append(messageComponent(plugin.messages.text(sender, "common.column-separator"), NamedTextColor.GRAY))
                .append(lineComp)
                .append(messageComponent(plugin.messages.text(sender, "common.column-separator"), NamedTextColor.GRAY))
                .append(messageComponent(plugin.messages.text(sender, "common.column-separator"), NamedTextColor.GRAY))
                .append(Component.text(station.district, NamedTextColor.GRAY))
            sender.sendMessage(line)
        }

        if (totalPages > 1) {
            val prevCmd = buildRoutesCommand(page - 1, searchQuery)
            val nextCmd = buildRoutesCommand(page + 1, searchQuery)
            val prevComp = if (page > 1) {
                messageComponent(plugin.messages.text(sender, "routes.previous"), NamedTextColor.GRAY)
                    .clickEvent(ClickEvent.runCommand(prevCmd))
                    .hoverEvent(HoverEvent.showText(messageComponent(plugin.messages.text(sender, "common.previous-hover"))))
            } else {
                messageComponent(plugin.messages.text(sender, "routes.previous"), NamedTextColor.DARK_GRAY)
            }
            val pageComp = messageComponent(plugin.messages.text(sender, "common.page", page, totalPages), NamedTextColor.GRAY)
            val nextComp = if (page < totalPages) {
                messageComponent(plugin.messages.text(sender, "common.next"), NamedTextColor.GRAY)
                    .clickEvent(ClickEvent.runCommand(nextCmd))
                    .hoverEvent(HoverEvent.showText(messageComponent(plugin.messages.text(sender, "common.next-hover"))))
            } else {
                messageComponent(plugin.messages.text(sender, "common.next"), NamedTextColor.DARK_GRAY)
            }
            sender.sendMessage(Component.empty().append(prevComp).append(pageComp).append(nextComp))
        }
    }

    /** 검색어가 있으면 `/routes ...검색어 페이지`, 없으면 `/routes 페이지` */
    private fun buildRoutesCommand(page: Int, searchQuery: String?): String {
        val q = searchQuery?.trim()?.takeIf { it.isNotEmpty() }
        return buildString {
            append("/routes")
            if (q != null) {
                append(" ")
                append(q)
            }
            append(" ")
            append(page)
        }
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): MutableList<String> {
        val prefix = args.lastOrNull()?.lowercase() ?: ""
        if (args.size == 1) {
            val out = mutableListOf<String>()
            if (sender.hasPermission("fpl.routes.reload") && "reload".startsWith(prefix)) {
                out.add("reload")
            }
            stations.forEach { s ->
                if (s.name.lowercase().startsWith(prefix)) out.add(s.name)
                if (s.line.isNotBlank() && s.line.lowercase().startsWith(prefix)) out.add(s.line)
            }
            val totalPages = maxOf(1, (stations.size + 4) / 5)
            (1..totalPages).map { it.toString() }.filter { it.startsWith(prefix) }.forEach { out.add(it) }
            return out.distinct().sorted().toMutableList()
        }
        val searchSoFar = args.dropLast(1).joinToString(" ").trim()
        val filtered = filterStations(searchSoFar.takeIf { it.isNotEmpty() })
        val pages = maxOf(1, (filtered.size + 4) / 5)
        return (1..pages).map { it.toString() }.filter { it.startsWith(prefix) }.toMutableList()
    }
}
