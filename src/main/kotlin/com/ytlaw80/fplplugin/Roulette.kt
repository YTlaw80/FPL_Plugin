package com.ytlaw80.fplplugin

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.io.File
import java.time.Duration
import java.util.UUID
import kotlin.random.Random

data class RouletteBet(
    val amount: Double
)

class Roulette(private val plugin: JavaPlugin, private val stocks: Stocks) : CommandExecutor, TabCompleter {

    private val dataFile = File(plugin.dataFolder, "roulette.yml")
    private var items: MutableList<String> = mutableListOf()
    private val running: MutableMap<UUID, BukkitTask> = mutableMapOf()
    private val bets: MutableMap<UUID, RouletteBet> = mutableMapOf()

    init {
        if (!dataFile.exists() && plugin.getResource("roulette.yml") != null) {
            plugin.saveResource("roulette.yml", false)
        }
        load()
    }

    fun shutdown() {
        running.values.forEach { it.cancel() }
        running.clear()
        bets.clear()
        save()
    }

    private fun load() {
        if (!dataFile.exists()) {
            items = mutableListOf()
            return
        }
        val yaml = YamlConfiguration.loadConfiguration(dataFile)
        items = yaml.getStringList("items")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toMutableList()
    }

    private fun save() {
        val yaml = YamlConfiguration()
        yaml.set("items", items.distinct())
        if (!plugin.dataFolder.exists()) plugin.dataFolder.mkdirs()
        yaml.save(dataFile)
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("§c플레이어만 이 명령어를 사용할 수 있습니다.")
            return true
        }

        if (args.isEmpty()) {
            sendHelp(sender)
            return true
        }

        return when (args[0].lowercase()) {
            "start", "시작" -> {
                start(sender, args)
                true
            }
            "stop", "중지" -> {
                stop(sender)
                true
            }
            "add", "추가" -> {
                val text = args.drop(1).joinToString(" ").trim()
                if (text.isBlank()) {
                    sender.sendMessage("§c사용법: /roulette add <문자열...>")
                    return true
                }
                items.add(text)
                save()
                sender.sendMessage("§a룰렛 항목 추가: §f$text")
                true
            }
            "remove", "삭제" -> {
                val text = args.drop(1).joinToString(" ").trim()
                if (text.isBlank()) {
                    sender.sendMessage("§c사용법: /roulette remove <문자열...>")
                    return true
                }
                val removed = items.removeIf { it.equals(text, ignoreCase = true) }
                if (removed) {
                    save()
                    sender.sendMessage("§a룰렛 항목 삭제: §f$text")
                } else {
                    sender.sendMessage("§7해당 항목을 찾을 수 없습니다: §f$text")
                }
                true
            }
            "list", "목록" -> {
                if (items.isEmpty()) {
                    sender.sendMessage("§7룰렛 항목이 없습니다. §f/roulette add <문자열> §7로 추가하세요.")
                    return true
                }
                sender.sendMessage("§6===== §e룰렛 항목 (${items.size}개) §6=====")
                items.forEachIndexed { i, s ->
                    sender.sendMessage("§7${i + 1}. §f$s")
                }
                true
            }
            "reload", "리로드" -> {
                if (!sender.hasPermission("fpl.roulette.admin")) {
                    sender.sendMessage("§c권한이 없습니다.")
                    return true
                }
                load()
                sender.sendMessage("§aroulette.yml 을 다시 불러왔습니다. (${items.size}개)")
                true
            }
            else -> {
                sendHelp(sender)
                true
            }
        }
    }

    private fun sendHelp(p: Player) {
        p.sendMessage("§6===== §e룰렛 §6=====")
        p.sendMessage("§7/roulette start §f- 일반 룰렛 시작")
        p.sendMessage("§7/roulette start <금액> §f- 배팅 룰렛 시작")
        p.sendMessage("§7배당: 모두 다름 -100% / 2개 동일 +25% / 3개 동일 +200%")
        p.sendMessage("§7/roulette stop §f- 룰렛 중지")
        p.sendMessage("§7/roulette list §f- 항목 목록")
        p.sendMessage("§7/roulette add <문자열...> §f- 항목 추가")
        p.sendMessage("§7/roulette remove <문자열...> §f- 항목 삭제")
    }

    private fun stop(p: Player) {
        running.remove(p.uniqueId)?.cancel()
        val bet = bets.remove(p.uniqueId)
        if (bet != null) {
            stocks.depositCash(p.uniqueId, bet.amount)
            p.sendMessage("§7룰렛 중지로 배팅금 §f${formatMoney(bet.amount)}§7 이 환불되었습니다.")
        }
        p.clearTitle()
        p.sendActionBar(Component.empty())
        p.sendMessage("§7룰렛을 중지했습니다.")
    }

    private fun start(p: Player, args: Array<out String>) {
        if (items.size < 3) {
            p.sendMessage("§c룰렛 항목이 부족합니다. §f/roulette add <문자열> §c로 추가하세요.")
            return
        }
        if (running.containsKey(p.uniqueId)) {
            p.sendMessage("§7이미 룰렛이 진행 중입니다. §f/roulette stop §7으로 중지할 수 있습니다.")
            return
        }

        // /roulette start <금액>
        if (args.size >= 2) {
            val amount = args[1].toDoubleOrNull()
            if (amount == null || amount <= 0) {
                p.sendMessage("§c배팅 금액은 0보다 커야 합니다.")
                return
            }
            if (!stocks.withdrawCash(p.uniqueId, amount)) {
                val bal = stocks.getCashBalance(p.uniqueId)
                p.sendMessage("§c잔액이 부족합니다. (보유: ${formatMoney(bal)}, 필요: ${formatMoney(amount)})")
                return
            }
            bets[p.uniqueId] = RouletteBet(amount)
            p.sendMessage("§6[룰렛] §7배팅 완료: §f${formatMoney(amount)}")
        }

        val spinTicks = 60L // 3초 (20t/s)
        val stepTicks = 2L  // 0.1초마다 갱신
        var t = 0L
        var lastTriple = Triple(items.random(), items.random(), items.random())

        val task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            if (!p.isOnline) {
                running.remove(p.uniqueId)?.cancel()
                return@Runnable
            }

            t += stepTicks
            lastTriple = rollTriple()
            show(p, lastTriple)

            if (t >= spinTicks) {
                running.remove(p.uniqueId)?.cancel()
                val result = lastTriple.second
                val title = Component.text(result, NamedTextColor.GOLD)
                val sub = Component.text("${lastTriple.first} | ${lastTriple.second} | ${lastTriple.third}", NamedTextColor.YELLOW)
                p.showTitle(Title.title(title, sub, Title.Times.times(Duration.ofMillis(100), Duration.ofSeconds(2), Duration.ofMillis(300))))
                p.sendActionBar(sub)
                p.sendMessage("§6[룰렛] §e결과: §f$result")

                val bet = bets.remove(p.uniqueId)
                if (bet != null) {
                    val (multiplier, label) = payoutMultiplier(lastTriple)
                    val payout = bet.amount * multiplier
                    if (payout > 0) {
                        stocks.depositCash(p.uniqueId, payout)
                    }
                    val diff = payout - bet.amount
                    val diffStr = if (diff >= 0) "§a+${formatMoney(diff)}" else "§c-${formatMoney(-diff)}"
                    p.sendMessage("§6[룰렛 배팅] §e$label §7지급: §f${formatMoney(payout)} §8(변동 $diffStr§8)")
                }
            }
        }, 0L, stepTicks)

        running[p.uniqueId] = task
    }

    private fun rollTriple(): Triple<String, String, String> {
        if (items.size == 1) {
            val s = items[0]
            return Triple(s, s, s)
        }
        val a = items[Random.nextInt(items.size)]
        val b = items[Random.nextInt(items.size)]
        val c = items[Random.nextInt(items.size)]
        return Triple(a, b, c)
    }

    private fun show(p: Player, triple: Triple<String, String, String>) {
        val text = "${triple.first} | ${triple.second} | ${triple.third}"
        val title = Component.text(triple.second, NamedTextColor.YELLOW)
        val sub = Component.text(text, NamedTextColor.GRAY)
        p.showTitle(Title.title(title, sub, Title.Times.times(Duration.ZERO, Duration.ofMillis(300), Duration.ZERO)))
        p.sendActionBar(Component.text(text, NamedTextColor.YELLOW))
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): MutableList<String> {
        if (args.size == 1) {
            val prefix = args[0].lowercase()
            return listOf("start", "stop", "list", "add", "remove", "reload")
                .filter { it.startsWith(prefix) }
                .toMutableList()
        }
        if (args.size == 2 && args[0].equals("start", ignoreCase = true)) {
            return listOf("100", "500", "1000", "5000", "10000")
                .filter { it.startsWith(args[1]) }
                .toMutableList()
        }
        return mutableListOf()
    }

    private fun formatMoney(value: Double): String = String.format("%,.2f", value)

    /**
     * 배당 규칙:
     * - 모두 다름: -100% (지급 0)
     * - 2개 동일: +25% (지급 1.25x)
     * - 3개 동일: +200% (지급 3.00x)
     */
    private fun payoutMultiplier(triple: Triple<String, String, String>): Pair<Double, String> {
        val (a, b, c) = triple
        return when {
            a.equals(b, ignoreCase = true) && b.equals(c, ignoreCase = true) -> 3.0 to "3개 동일 (+200%)"
            a.equals(b, ignoreCase = true) || b.equals(c, ignoreCase = true) || a.equals(c, ignoreCase = true) -> 1.25 to "2개 동일 (+25%)"
            else -> 0.0 to "모두 다름 (-100%)"
        }
    }
}

