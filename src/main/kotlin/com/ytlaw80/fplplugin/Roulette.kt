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
            sender.sendMessage(plugin.messages.text(sender, "common.players-only"))
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
                    sender.sendMessage(plugin.messages.text(sender, "roulette.add-usage"))
                    return true
                }
                items.add(text)
                save()
                sender.sendMessage(plugin.messages.text(sender, "roulette.added", text))
                true
            }
            "remove", "삭제" -> {
                val text = args.drop(1).joinToString(" ").trim()
                if (text.isBlank()) {
                    sender.sendMessage(plugin.messages.text(sender, "roulette.remove-usage"))
                    return true
                }
                val removed = items.removeIf { it.equals(text, ignoreCase = true) }
                if (removed) {
                    save()
                    sender.sendMessage(plugin.messages.text(sender, "roulette.removed", text))
                } else {
                    sender.sendMessage(plugin.messages.text(sender, "roulette.not-found", text))
                }
                true
            }
            "list", "목록" -> {
                if (items.isEmpty()) {
                    sender.sendMessage(plugin.messages.text(sender, "roulette.empty"))
                    return true
                }
                sender.sendMessage(plugin.messages.text(sender, "roulette.list-header", items.size))
                items.forEachIndexed { i, s ->
                    sender.sendMessage(plugin.messages.text(sender, "roulette.list-item", i + 1, s))
                }
                true
            }
            "reload", "리로드" -> {
                if (!sender.hasPermission("fpl.roulette.admin")) {
                    sender.sendMessage(plugin.messages.text(sender, "common.no-permission"))
                    return true
                }
                load()
                sender.sendMessage(plugin.messages.text(sender, "roulette.reloaded", items.size))
                true
            }
            else -> {
                sendHelp(sender)
                true
            }
        }
    }

    private fun sendHelp(p: Player) {
        p.sendMessage(plugin.messages.text(p, "roulette.help-header"))
        p.sendMessage(plugin.messages.text(p, "roulette.help-start"))
        p.sendMessage(plugin.messages.text(p, "roulette.help-bet"))
        p.sendMessage(plugin.messages.text(p, "roulette.help-payout"))
        p.sendMessage(plugin.messages.text(p, "roulette.help-stop"))
        p.sendMessage(plugin.messages.text(p, "roulette.help-list"))
        p.sendMessage(plugin.messages.text(p, "roulette.help-add"))
        p.sendMessage(plugin.messages.text(p, "roulette.help-remove"))
    }

    private fun stop(p: Player) {
        running.remove(p.uniqueId)?.cancel()
        val bet = bets.remove(p.uniqueId)
        if (bet != null) {
            stocks.depositCash(p.uniqueId, bet.amount)
            p.sendMessage(plugin.messages.text(p, "roulette.refunded", formatMoney(bet.amount)))
        }
        p.clearTitle()
        p.sendActionBar(Component.empty())
        p.sendMessage(plugin.messages.text(p, "roulette.stopped"))
    }

    private fun start(p: Player, args: Array<out String>) {
        if (items.size < 3) {
            p.sendMessage(plugin.messages.text(p, "roulette.insufficient-items"))
            return
        }
        if (running.containsKey(p.uniqueId)) {
            p.sendMessage(plugin.messages.text(p, "roulette.already-running"))
            return
        }

        // /roulette start <금액>
        if (args.size >= 2) {
            val amount = args[1].toDoubleOrNull()
            if (amount == null || amount <= 0) {
                p.sendMessage(plugin.messages.text(p, "roulette.invalid-bet"))
                return
            }
            if (!stocks.withdrawCash(p.uniqueId, amount)) {
                val bal = stocks.getCashBalance(p.uniqueId)
                p.sendMessage(plugin.messages.text(p, "common.insufficient-balance", formatMoney(bal), formatMoney(amount)))
                return
            }
            bets[p.uniqueId] = RouletteBet(amount)
            p.sendMessage(plugin.messages.text(p, "roulette.bet-placed", formatMoney(amount)))
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
                val title = messageComponent(plugin.messages.text(p, "roulette.result-title", result), NamedTextColor.GOLD)
                val sub = messageComponent(plugin.messages.text(p, "roulette.triple", lastTriple.first, lastTriple.second, lastTriple.third), NamedTextColor.YELLOW)
                p.showTitle(Title.title(title, sub, Title.Times.times(Duration.ofMillis(100), Duration.ofSeconds(2), Duration.ofMillis(300))))
                p.sendActionBar(sub)
                p.sendMessage(plugin.messages.text(p, "roulette.result", result))

                val bet = bets.remove(p.uniqueId)
                if (bet != null) {
                    val (multiplier, label) = payoutMultiplier(p, lastTriple)
                    val payout = bet.amount * multiplier
                    if (payout > 0) {
                        stocks.depositCash(p.uniqueId, payout)
                    }
                    val diff = payout - bet.amount
                    val diffStr = if (diff >= 0) plugin.messages.text(p, "roulette.gain", formatMoney(diff)) else plugin.messages.text(p, "roulette.loss", formatMoney(-diff))
                    p.sendMessage(plugin.messages.text(p, "roulette.payout", label, formatMoney(payout), diffStr))
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
        val text = plugin.messages.text(p, "roulette.triple", triple.first, triple.second, triple.third)
        val title = messageComponent(plugin.messages.text(p, "roulette.spin-title", triple.second), NamedTextColor.YELLOW)
        val sub = messageComponent(text, NamedTextColor.GRAY)
        p.showTitle(Title.title(title, sub, Title.Times.times(Duration.ZERO, Duration.ofMillis(300), Duration.ZERO)))
        p.sendActionBar(messageComponent(text, NamedTextColor.YELLOW))
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
    private fun payoutMultiplier(p: Player, triple: Triple<String, String, String>): Pair<Double, String> {
        val (a, b, c) = triple
        return when {
            a.equals(b, ignoreCase = true) && b.equals(c, ignoreCase = true) -> 3.0 to plugin.messages.text(p, "roulette.three-matching")
            a.equals(b, ignoreCase = true) || b.equals(c, ignoreCase = true) || a.equals(c, ignoreCase = true) -> 1.25 to plugin.messages.text(p, "roulette.two-matching")
            else -> 0.0 to plugin.messages.text(p, "roulette.all-different")
        }
    }
}

