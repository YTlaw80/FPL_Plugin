package com.ytlaw80.fplplugin

import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

data class Company(
    val name: String,
    var stars: Double,
    var basePrice: Double
)

class Stocks(private val plugin: JavaPlugin) : CommandExecutor, TabCompleter {

    private val dataFile = File(plugin.dataFolder, "stocks.yml")

    private val companies: MutableMap<String, Company> = mutableMapOf()
    private val balances: MutableMap<UUID, Double> = mutableMapOf()
    private val holdings: MutableMap<UUID, MutableMap<String, Int>> = mutableMapOf()

    private var startMoney: Double = 1000.0

    init {
        load()
    }

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ): Boolean {
        return when (command.name.lowercase()) {
            "buy" -> handleBuy(sender, args)
            "sell" -> handleSell(sender, args)
            "setstars" -> handleSetStars(sender, args)
            "checkstats" -> handleCheckStats(sender, args)
            "makecompany" -> handleMakeCompany(sender, args)
            "setstartmoney" -> handleSetStartMoney(sender, args)
            "setmoney" -> handleSetMoney(sender, args)
            "deletecompany" -> handleDeleteCompany(sender, args)
            else -> false
        }
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): MutableList<String> {
        val lower = command.name.lowercase()
        return when (lower) {
            "buy", "sell", "setstars", "checkstats", "deletecompany" -> {
                if (args.size == 1) {
                    val prefix = args[0].lowercase()
                    companies.keys
                        .filter { it.lowercase().startsWith(prefix) }
                        .sorted()
                        .toMutableList()
                } else mutableListOf()
            }

            "setmoney" -> {
                if (args.size == 1) {
                    val prefix = args[0].lowercase()
                    Bukkit.getOnlinePlayers()
                        .map { it.name }
                        .filter { it.lowercase().startsWith(prefix) }
                        .sorted()
                        .toMutableList()
                } else mutableListOf()
            }

            else -> mutableListOf()
        }
    }

    // region Commands

    private fun handleBuy(sender: CommandSender, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("§c플레이어만 이 명령어를 사용할 수 있습니다.")
            return true
        }

        if (args.size != 2) {
            sender.sendMessage("§c사용법: /buy <회사이름> <수량>")
            return true
        }

        val companyName = args[0]
        val amount = args[1].toIntOrNull()
        if (amount == null || amount <= 0) {
            sender.sendMessage("§c유효한 수량을 입력하세요.")
            return true
        }

        val company = companies[companyName]
        if (company == null) {
            sender.sendMessage("§c해당 이름의 회사를 찾을 수 없습니다.")
            return true
        }

        val pricePerStock = currentPrice(company)
        val totalPrice = pricePerStock * amount

        val bal = getBalance(sender.uniqueId)
        if (bal < totalPrice) {
            sender.sendMessage("§c잔액이 부족합니다. (보유: ${formatMoney(bal)}, 필요: ${formatMoney(totalPrice)})")
            return true
        }

        setBalance(sender.uniqueId, bal - totalPrice)
        val playerHoldings = holdings.getOrPut(sender.uniqueId) { mutableMapOf() }
        playerHoldings[companyName] = (playerHoldings[companyName] ?: 0) + amount

        save()

        sender.sendMessage("§a${companyName} 주식 ${amount}개를 구매했습니다. (개당 ${formatMoney(pricePerStock)}, 총 ${formatMoney(totalPrice)})")
        return true
    }

    private fun handleSell(sender: CommandSender, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("§c플레이어만 이 명령어를 사용할 수 있습니다.")
            return true
        }

        if (args.size != 2) {
            sender.sendMessage("§c사용법: /sell <회사이름> <수량>")
            return true
        }

        val companyName = args[0]
        val amount = args[1].toIntOrNull()
        if (amount == null || amount <= 0) {
            sender.sendMessage("§c유효한 수량을 입력하세요.")
            return true
        }

        val company = companies[companyName]
        if (company == null) {
            sender.sendMessage("§c해당 이름의 회사를 찾을 수 없습니다.")
            return true
        }

        val playerHoldings = holdings.getOrPut(sender.uniqueId) { mutableMapOf() }
        val owned = playerHoldings[companyName] ?: 0
        if (owned < amount) {
            sender.sendMessage("§c해당 회사의 주식을 충분히 보유하고 있지 않습니다. (보유: $owned)")
            return true
        }

        val pricePerStock = currentPrice(company)
        val totalPrice = pricePerStock * amount

        playerHoldings[companyName] = owned - amount
        if (playerHoldings[companyName] == 0) {
            playerHoldings.remove(companyName)
        }

        val bal = getBalance(sender.uniqueId)
        setBalance(sender.uniqueId, bal + totalPrice)

        save()

        sender.sendMessage("§a${companyName} 주식 ${amount}개를 판매했습니다. (개당 ${formatMoney(pricePerStock)}, 총 ${formatMoney(totalPrice)})")
        return true
    }

    private fun handleSetStars(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("fpl.stocks.admin")) {
            sender.sendMessage("§c이 명령어를 사용할 권한이 없습니다.")
            return true
        }

        if (args.size != 2) {
            sender.sendMessage("§c사용법: /setstars <회사이름> <별점(1~5, 소수 가능)>")
            return true
        }

        val companyName = args[0]
        val stars = args[1].toDoubleOrNull()
        if (stars == null || stars !in 1.0..5.0) {
            sender.sendMessage("§c별점은 1~5 사이의 값이어야 합니다. (예: 3.5)")
            return true
        }

        val company = companies[companyName]
        if (company == null) {
            sender.sendMessage("§c해당 이름의 회사를 찾을 수 없습니다.")
            return true
        }

        company.stars = stars
        save()

        val starsDisplay = if (stars == stars.toLong().toDouble()) "${stars.toLong()}" else String.format("%.1f", stars)
        Bukkit.broadcastMessage("§6[주식] §e${company.name}§f 의 별점이 §e${starsDisplay}★§f 로 변경되었습니다.")
        sender.sendMessage("§a별점을 변경했습니다.")
        return true
    }

    private fun handleCheckStats(sender: CommandSender, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            if (companies.isEmpty()) {
                sender.sendMessage("§7등록된 주식회사가 없습니다.")
                return true
            }

            sender.sendMessage("§6===== 주식회사 목록 =====")
            companies.values.sortedBy { it.name }.forEach { company ->
                val price = currentPrice(company)
                val starsDisplay = formatStars(company.stars)
                sender.sendMessage("§e${company.name} §7- 별점: §e${starsDisplay}★ §7현재가: §a${formatMoney(price)}")
            }
            return true
        }

        val companyName = args[0]
        val company = companies[companyName]
        if (company == null) {
            sender.sendMessage("§c해당 이름의 회사를 찾을 수 없습니다.")
            return true
        }

        val price = currentPrice(company)
        sender.sendMessage("§6===== ${company.name} 정보 =====")
        sender.sendMessage("§7별점: §e${formatStars(company.stars)}★")
        sender.sendMessage("§7기본 가격: §a${formatMoney(company.basePrice)}")
        sender.sendMessage("§7현재 가격: §a${formatMoney(price)}")
        return true
    }

    private fun handleMakeCompany(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("fpl.stocks.admin")) {
            sender.sendMessage("§c이 명령어를 사용할 권한이 없습니다.")
            return true
        }

        if (args.size != 2) {
            sender.sendMessage("§c사용법: /makecompany <회사이름> <기본가격>")
            return true
        }

        val name = args[0]
        val basePrice = args[1].toDoubleOrNull()
        if (basePrice == null || basePrice <= 0) {
            sender.sendMessage("§c유효한 기본 가격을 입력하세요.")
            return true
        }

        if (companies.containsKey(name)) {
            sender.sendMessage("§c이미 존재하는 회사 이름입니다.")
            return true
        }

        val company = Company(name = name, stars = 2.5, basePrice = basePrice)
        companies[name] = company
        save()

        sender.sendMessage("§a새로운 주식회사 §e$name§a 가 생성되었습니다. (기본 가격: ${formatMoney(basePrice)}, 초기 별점: 2.5★)")
        return true
    }

    private fun handleSetStartMoney(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("fpl.stocks.admin")) {
            sender.sendMessage("§c이 명령어를 사용할 권한이 없습니다.")
            return true
        }

        if (args.size != 1) {
            sender.sendMessage("§c사용법: /setstartmoney <금액>")
            return true
        }

        val value = args[0].toDoubleOrNull()
        if (value == null || value < 0) {
            sender.sendMessage("§c유효한 금액을 입력하세요.")
            return true
        }

        startMoney = value
        save()
        sender.sendMessage("§a기본 돈이 ${formatMoney(startMoney)} 으로 설정되었습니다.")
        return true
    }

    private fun handleSetMoney(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("fpl.stocks.admin")) {
            sender.sendMessage("§c이 명령어를 사용할 권한이 없습니다.")
            return true
        }

        if (args.size != 2) {
            sender.sendMessage("§c사용법: /setmoney <플레이어> <금액>")
            return true
        }

        val targetName = args[0]
        val value = args[1].toDoubleOrNull()
        if (value == null || value < 0) {
            sender.sendMessage("§c유효한 금액을 입력하세요.")
            return true
        }

        val offline = Bukkit.getOfflinePlayer(targetName)
        val uuid = offline.uniqueId

        setBalance(uuid, value)
        save()

        sender.sendMessage("§a$targetName 님의 돈이 ${formatMoney(value)} 으로 설정되었습니다.")
        Bukkit.getPlayerExact(targetName)?.sendMessage("§6[주식] §a당신의 돈이 관리자에 의해 ${formatMoney(value)} 으로 설정되었습니다.")
        return true
    }

    private fun handleDeleteCompany(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("fpl.stocks.admin")) {
            sender.sendMessage("§c이 명령어를 사용할 권한이 없습니다.")
            return true
        }

        if (args.size != 1) {
            sender.sendMessage("§c사용법: /deletecompany <회사이름>")
            return true
        }

        val companyName = args[0]
        val company = companies[companyName]
        if (company == null) {
            sender.sendMessage("§c해당 이름의 회사를 찾을 수 없습니다.")
            return true
        }

        val pricePerStock = currentPrice(company)

        // 보유 중인 모든 플레이어에게 현재가로 환급
        for ((uuid, playerHoldings) in holdings) {
            val amount = playerHoldings[companyName] ?: 0
            if (amount > 0) {
                val refund = pricePerStock * amount
                val bal = getBalance(uuid)
                setBalance(uuid, bal + refund)
                playerHoldings.remove(companyName)
                Bukkit.getPlayer(uuid)?.sendMessage("§6[주식] §e${company.name}§f 상장 폐지로 보유 주식 §7${amount}주§f가 현재가 §a${formatMoney(refund)}§f 로 환급되었습니다.")
            }
        }

        companies.remove(companyName)
        save()

        Bukkit.broadcastMessage("§6[주식] §c§l상장 폐지 §f- §e${company.name}§f 이(가) 시장에서 퇴출되었습니다.")
        sender.sendMessage("§a${company.name} 상장이 폐지되었습니다. 보유자에게 현재가로 환급했습니다.")
        return true
    }

    // endregion

    // region Data helpers

    private fun load() {
        if (!dataFile.exists()) {
            return
        }

        val config = YamlConfiguration.loadConfiguration(dataFile)

        startMoney = config.getDouble("startMoney", 1000.0)

        val companiesSection = config.getConfigurationSection("companies")
        if (companiesSection != null) {
            for (name in companiesSection.getKeys(false)) {
                val sec = companiesSection.getConfigurationSection(name) ?: continue
                val stars = sec.getDouble("stars", 2.5)
                val basePrice = sec.getDouble("basePrice", 100.0)
                companies[name] = Company(name, stars.coerceIn(1.0, 5.0), basePrice)
            }
        }

        val balancesSection = config.getConfigurationSection("balances")
        if (balancesSection != null) {
            for (id in balancesSection.getKeys(false)) {
                val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: continue
                val value = balancesSection.getDouble(id, startMoney)
                balances[uuid] = value
            }
        }

        val holdingsSection = config.getConfigurationSection("holdings")
        if (holdingsSection != null) {
            for (id in holdingsSection.getKeys(false)) {
                val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: continue
                val playerSec = holdingsSection.getConfigurationSection(id) ?: continue
                val map = mutableMapOf<String, Int>()
                for (company in playerSec.getKeys(false)) {
                    val amount = playerSec.getInt(company, 0)
                    if (amount > 0) {
                        map[company] = amount
                    }
                }
                holdings[uuid] = map
            }
        }
    }

    fun save() {
        val config = YamlConfiguration()

        config.set("startMoney", startMoney)

        for ((name, company) in companies) {
            val path = "companies.$name"
            config.set("$path.stars", company.stars)
            config.set("$path.basePrice", company.basePrice)
        }

        for ((uuid, bal) in balances) {
            config.set("balances.${uuid}", bal)
        }

        for ((uuid, map) in holdings) {
            for ((company, amount) in map) {
                config.set("holdings.${uuid}.$company", amount)
            }
        }

        if (!plugin.dataFolder.exists()) {
            plugin.dataFolder.mkdirs()
        }

        config.save(dataFile)
    }

    private fun getBalance(uuid: UUID): Double {
        return balances.getOrPut(uuid) { startMoney }
    }

    private fun setBalance(uuid: UUID, value: Double) {
        balances[uuid] = value
    }

    private fun currentPrice(company: Company): Double {
        val s = company.stars.coerceIn(1.0, 5.0)
        // 1→0.5, 2→0.8, 3→1.0, 4→1.3, 5→1.7 구간별 선형 보간
        val multiplier = when {
            s <= 2.0 -> 0.5 + 0.3 * (s - 1.0)
            s <= 3.0 -> 0.8 + 0.2 * (s - 2.0)
            s <= 4.0 -> 1.0 + 0.3 * (s - 3.0)
            else -> 1.3 + 0.4 * (s - 4.0)
        }
        return (company.basePrice * multiplier * 100.0).roundToInt() / 100.0
    }

    private fun formatStars(stars: Double): String {
        return if (stars == stars.toLong().toDouble()) "${stars.toLong()}" else String.format("%.1f", stars)
    }

    private fun formatMoney(value: Double): String {
        return String.format("%,.2f", value)
    }

    // endregion
}

