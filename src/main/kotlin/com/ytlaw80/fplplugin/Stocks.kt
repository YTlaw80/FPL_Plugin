package com.ytlaw80.fplplugin

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
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
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.random.Random

data class Company(
    val name: String,
    var stars: Double,
    var basePrice: Double,
    var currentPrice: Double,
    /** 자동 시세 반영 직전 가격 (/주식 에서 직전 대비 표시용) */
    var previousPrice: Double
)

class Stocks(private val plugin: JavaPlugin) : CommandExecutor, TabCompleter {

    private val dataFile = File(plugin.dataFolder, "stocks.yml")

    private val companies: MutableMap<String, Company> = mutableMapOf()
    private val balances: MutableMap<UUID, Double> = mutableMapOf()
    private val holdings: MutableMap<UUID, MutableMap<String, Int>> = mutableMapOf()
    /** 주식 알림을 끈 플레이어 UUID (시세, 별점 변경, 상장폐지 공지) */
    private val notificationMuted: MutableSet<UUID> = mutableSetOf()

    private var startMoney: Double = 1000.0

    private var priceTickTask: BukkitTask? = null
    private val PRICE_TICK_INTERVAL_TICKS = 6000L // 5분 = 6000틱

    /** 다음 시세 반영까지 남은 게임 틱 (표시용, 매 틱 감소) */
    private var ticksUntilPriceUpdate: Long = PRICE_TICK_INTERVAL_TICKS

    init {
        load()
        startPriceTick()
    }

    fun cancelPriceTick() {
        priceTickTask?.cancel()
        priceTickTask = null
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
            "money" -> handleMoney(sender, args)
            "wealthrank" -> handleWealthRank(sender, args)
            "transfer" -> handleTransfer(sender, args)
            "stocknotify" -> handleStockNotify(sender, args)
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

            "stocknotify" -> {
                if (args.size == 1) {
                    val prefix = args[0].lowercase()
                    listOf("켜기", "끄기").filter { it.startsWith(prefix) }.toMutableList()
                } else mutableListOf()
            }

            "setmoney", "transfer" -> {
                if (args.size == 1) {
                    val prefix = args[0].lowercase()
                    val names = Bukkit.getOnlinePlayers()
                        .map { it.name }
                        .filter { it.lowercase().startsWith(prefix) }
                    val filtered = if (lower == "transfer") names.filter { it != sender.name } else names
                    filtered.sorted().toMutableList()
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
        broadcastStockNotification("§6[주식] §e${company.name}§f 의 별점이 §e${starsDisplay}★§f 로 변경되었습니다.")
        sender.sendMessage("§a별점을 변경했습니다.")
        return true
    }

    private fun handleCheckStats(sender: CommandSender, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            sendCompanyList(sender, 1)
            return true
        }

        val firstArg = args[0]
        // 숫자이면서 회사 이름이 아닌 경우 → 페이지 번호
        val pageNum = firstArg.toIntOrNull()
        val sortedCompanies = companies.values.sortedBy { it.name }.toList()
        val totalPages = if (sortedCompanies.size <= COMPANIES_PER_PAGE) 1
            else (sortedCompanies.size + COMPANIES_PER_PAGE - 1) / COMPANIES_PER_PAGE

        if (pageNum != null && pageNum in 1..totalPages && !companies.containsKey(firstArg)) {
            sendCompanyList(sender, pageNum)
            return true
        }

        val company = companies[firstArg]
        if (company == null) {
            sender.sendMessage("§c해당 이름의 회사를 찾을 수 없습니다.")
            return true
        }

        val price = currentPrice(company)
        sender.sendMessage("§6===== ${company.name} 정보 =====")
        sender.sendMessage("§7다음 주가 반영까지: §e${formatTicksAsTimeLeft(ticksUntilPriceUpdate)}")
        sender.sendMessage("§7별점: §e${formatStars(company.stars)}★")
        sender.sendMessage("§7기본 가격: §a${formatMoney(company.basePrice)}")
        sender.sendMessage("§7직전 시세: §f${formatMoney(roundPrice(company.previousPrice))}")
        sender.sendMessage("§7현재 가격: §a${formatMoney(price)} §7| ${formatPriceDiffFromPrevious(company)}")
        return true
    }

    private fun sendCompanyList(sender: CommandSender, page: Int) {
        sender.sendMessage("§7다음 주가 반영까지: §e${formatTicksAsTimeLeft(ticksUntilPriceUpdate)}")
        if (companies.isEmpty()) {
            sender.sendMessage("§7등록된 주식회사가 없습니다.")
            return
        }

        val sortedCompanies = companies.values.sortedBy { it.name }.toList()
        val totalPages = if (sortedCompanies.size <= COMPANIES_PER_PAGE) 1
            else (sortedCompanies.size + COMPANIES_PER_PAGE - 1) / COMPANIES_PER_PAGE
        val pageIndex = (page - 1).coerceIn(0, totalPages - 1)
        val from = pageIndex * COMPANIES_PER_PAGE
        val to = minOf(from + COMPANIES_PER_PAGE, sortedCompanies.size)
        val pageCompanies = sortedCompanies.subList(from, to)

        sender.sendMessage("§6===== 주식회사 목록 §7($page/$totalPages 페이지) §6=====")
        pageCompanies.forEach { company ->
            val price = currentPrice(company)
            val starsDisplay = formatStars(company.stars)
            val diffPart = formatPriceDiffFromPrevious(company)
            val line = Component.text("  ", NamedTextColor.DARK_GRAY)
                .append(Component.text(company.name, NamedTextColor.YELLOW)
                    .clickEvent(ClickEvent.runCommand("/checkstats ${company.name}"))
                    .hoverEvent(HoverEvent.showText(Component.text("클릭: ${company.name} 정보 보기"))))
                .append(Component.text(" - 별점: ", NamedTextColor.GRAY))
                .append(Component.text("${starsDisplay}★", NamedTextColor.YELLOW))
                .append(Component.text(" 현재가: ", NamedTextColor.GRAY))
                .append(Component.text(formatMoney(price), NamedTextColor.GREEN))
                .append(LegacyComponentSerializer.legacySection().deserialize(" | $diffPart"))
            sender.sendMessage(line)
        }

        if (totalPages > 1) {
            val prevComp = if (page > 1) {
                Component.text("[이전 <] ", NamedTextColor.GRAY)
                    .clickEvent(ClickEvent.runCommand("/checkstats ${page - 1}"))
                    .hoverEvent(HoverEvent.showText(Component.text("이전 페이지 보기")))
            } else {
                Component.text("[이전 <] ", NamedTextColor.DARK_GRAY)
            }
            val pageComp = Component.text("$page / $totalPages 페이지 ", NamedTextColor.GRAY)
            val nextComp = if (page < totalPages) {
                Component.text("[다음 >]", NamedTextColor.GRAY)
                    .clickEvent(ClickEvent.runCommand("/checkstats ${page + 1}"))
                    .hoverEvent(HoverEvent.showText(Component.text("다음 페이지 보기")))
            } else {
                Component.text("[다음 >]", NamedTextColor.DARK_GRAY)
            }
            sender.sendMessage(Component.empty().append(prevComp).append(pageComp).append(nextComp))
        }
    }

    companion object {
        private const val COMPANIES_PER_PAGE = 5
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

        val initial = basePrice * getStarMultiplier(2.5)
        val company = Company(
            name = name,
            stars = 2.5,
            basePrice = basePrice,
            currentPrice = initial,
            previousPrice = initial
        )
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

    private fun handleTransfer(sender: CommandSender, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("§c플레이어만 이 명령어를 사용할 수 있습니다.")
            return true
        }
        if (args.size != 2) {
            sender.sendMessage("§c사용법: /송금 <플레이어> <금액>")
            return true
        }

        val targetName = args[0]
        val amount = args[1].toDoubleOrNull()
        if (amount == null || amount <= 0) {
            sender.sendMessage("§c유효한 금액을 입력하세요. (0보다 커야 합니다)")
            return true
        }

        if (targetName.equals(sender.name, ignoreCase = true)) {
            sender.sendMessage("§c자기 자신에게는 송금할 수 없습니다.")
            return true
        }

        val target = Bukkit.getPlayer(targetName)
        if (target == null) {
            sender.sendMessage("§c접속 중인 플레이어를 찾을 수 없습니다.")
            return true
        }
        if (target == sender) {
            sender.sendMessage("§c자기 자신에게는 송금할 수 없습니다.")
            return true
        }

        val senderUuid = sender.uniqueId
        val targetUuid = target.uniqueId
        val senderBal = getBalance(senderUuid)
        if (senderBal < amount) {
            sender.sendMessage("§c잔액이 부족합니다. (보유: ${formatMoney(senderBal)}, 필요: ${formatMoney(amount)})")
            return true
        }

        setBalance(senderUuid, senderBal - amount)
        val targetBal = getBalance(targetUuid)
        setBalance(targetUuid, targetBal + amount)
        save()

        sender.sendMessage("§a${target.name} §7님에게 §a${formatMoney(amount)}§7 을(를) 송금했습니다. §8(잔액: ${formatMoney(senderBal - amount)})")
        target.sendMessage("§6[주식] §e${sender.name}§f 님으로부터 §a${formatMoney(amount)}§f 을(를) 받았습니다. §8(잔액: ${formatMoney(targetBal + amount)})")
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

        broadcastStockNotification("§6[주식] §c§l상장 폐지 §f- §e${company.name}§f 이(가) 시장에서 퇴출되었습니다.")
        sender.sendMessage("§a${company.name} 상장이 폐지되었습니다. 보유자에게 현재가로 환급했습니다.")
        return true
    }

    private fun handleStockNotify(sender: CommandSender, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("§c플레이어만 이 명령어를 사용할 수 있습니다.")
            return true
        }
        val uuid = sender.uniqueId
        val muted = uuid in notificationMuted
        when (args.getOrNull(0)?.lowercase()) {
            "끄기", "off", "false" -> {
                notificationMuted.add(uuid)
                save()
                sender.sendMessage("§7주식 알림이 §c꺼짐§7 입니다. (시세, 별점 변경, 상장폐지 공지 비표시)")
            }
            "켜기", "on", "true" -> {
                notificationMuted.remove(uuid)
                save()
                sender.sendMessage("§7주식 알림이 §a켜짐§7 입니다.")
            }
            null, "" -> {
                if (muted) {
                    sender.sendMessage("§7주식 알림: §c꺼짐§7 — §e/주식알림 켜기§7 로 켤 수 있습니다.")
                } else {
                    sender.sendMessage("§7주식 알림: §a켜짐§7 — §e/주식알림 끄기§7 로 끌 수 있습니다.")
                }
            }
            else -> {
                sender.sendMessage("§c사용법: /주식알림 [켜기|끄기]")
            }
        }
        return true
    }

    private fun handleMoney(sender: CommandSender, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("§c플레이어만 이 명령어를 사용할 수 있습니다.")
            return true
        }
        if (args.isNotEmpty()) {
            sender.sendMessage("§c사용법: /money")
            return true
        }

        val uuid = sender.uniqueId
        val cash = getBalance(uuid)
        val playerHoldings = holdings[uuid]

        sender.sendMessage("§6===== §e내 지갑 §6=====")
        sender.sendMessage("§7현금: §a${formatMoney(cash)}")

        if (playerHoldings == null || playerHoldings.isEmpty()) {
            sender.sendMessage("§7보유 주식: §f없음")
            sender.sendMessage("§7총 자산: §a${formatMoney(cash)}")
            return true
        }

        sender.sendMessage("§7보유 주식:")
        var stockTotal = 0.0
        for ((companyName, qty) in playerHoldings.entries.sortedBy { it.key }) {
            val company = companies[companyName]
            if (company == null) {
                sender.sendMessage(" §7- §e$companyName §7${qty}주 §c(회사 정보 없음)")
                continue
            }
            val unit = currentPrice(company)
            val sub = unit * qty
            stockTotal += sub
            sender.sendMessage(" §7- §e$companyName §7${qty}주 §8× §f${formatMoney(unit)} §7→ §a${formatMoney(sub)}")
        }

        sender.sendMessage("§7주식 평가액 합계: §a${formatMoney(stockTotal)}")
        val grand = cash + stockTotal
        sender.sendMessage("§6총 자산 §7(현금+주식): §a§l${formatMoney(grand)}")
        return true
    }

    private fun handleWealthRank(sender: CommandSender, args: Array<out String>): Boolean {
        if (args.isNotEmpty()) {
            sender.sendMessage("§c사용법: /순위")
            return true
        }

        val uuids = (balances.keys + holdings.keys).toSet()
        val rows = uuids.map { uuid ->
            val cash = balances[uuid] ?: startMoney
            val stock = computeStockValue(uuid)
            Triple(uuid, cash + stock, cash to stock)
        }.sortedByDescending { it.second }

        sender.sendMessage("§6===== §e총 자산 순위 §7(현금+주식) §6=====")
        if (rows.isEmpty()) {
            sender.sendMessage("§7아직 기록된 플레이어가 없습니다.")
            return true
        }

        sender.sendMessage("§7총 §f${rows.size}§7명")
        rows.forEachIndexed { index, (uuid, total, cashStock) ->
            val rank = index + 1
            val (cash, stock) = cashStock
            val name = Bukkit.getOfflinePlayer(uuid).name
                ?: "§7${uuid.toString().substring(0, 8)}…"
            val isSelf = sender is Player && sender.uniqueId == uuid
            val rankPrefix = when (rank) {
                1 -> "§6§l"
                2 -> "§7§l"
                3 -> "§c§l"
                else -> "§e"
            }
            val selfMark = if (isSelf) " §6«나»" else ""
            sender.sendMessage(
                "$rankPrefix${rank}위 §f$name$selfMark §7- §a${formatMoney(total)} §8(현금 ${formatMoney(cash)} · 주식 ${formatMoney(stock)})"
            )
        }
        return true
    }

    /** 순위 계산용: 맵에 없으면 기본 시작 자금 (getOrPut 사용 안 함) */
    private fun computeStockValue(uuid: UUID): Double {
        val map = holdings[uuid] ?: return 0.0
        var sum = 0.0
        for ((companyName, qty) in map) {
            val c = companies[companyName] ?: continue
            sum += currentPrice(c) * qty
        }
        return sum
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
                val stars = sec.getDouble("stars", 2.5).coerceIn(1.0, 5.0)
                val basePrice = sec.getDouble("basePrice", 100.0)
                val currentPrice = sec.getDouble("currentPrice", basePrice * getStarMultiplier(stars))
                val previousPrice = sec.getDouble("previousPrice", currentPrice)
                companies[name] = Company(name, stars, basePrice, currentPrice, previousPrice)
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

        val mutedList = config.getStringList("notificationMuted")
        notificationMuted.clear()
        mutedList.forEach { id ->
            runCatching { UUID.fromString(id) }.getOrNull()?.let { notificationMuted.add(it) }
        }
    }

    fun save() {
        val config = YamlConfiguration()

        config.set("startMoney", startMoney)

        for ((name, company) in companies) {
            val path = "companies.$name"
            config.set("$path.stars", company.stars)
            config.set("$path.basePrice", company.basePrice)
            config.set("$path.currentPrice", company.currentPrice)
            config.set("$path.previousPrice", company.previousPrice)
        }

        for ((uuid, bal) in balances) {
            config.set("balances.${uuid}", bal)
        }

        for ((uuid, map) in holdings) {
            for ((company, amount) in map) {
                config.set("holdings.${uuid}.$company", amount)
            }
        }

        config.set("notificationMuted", notificationMuted.map { it.toString() })

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

    private fun roundPrice(value: Double): Double {
        return (value * 100.0).roundToInt() / 100.0
    }

    private fun currentPrice(company: Company): Double {
        return roundPrice(company.currentPrice)
    }

    /** 직전 자동 시세 대비 차액·등락률 (색상 코드 포함) */
    private fun formatPriceDiffFromPrevious(company: Company): String {
        val cur = currentPrice(company)
        val prev = roundPrice(company.previousPrice)
        val diff = cur - prev
        if (kotlin.math.abs(diff) < 0.005) {
            return "§7직전 대비 §f변동 없음"
        }
        val pct = if (prev > 1e-9) (diff / prev) * 100.0 else 0.0
        val color = if (diff >= 0) "§a" else "§c"
        val moneyPart = if (diff >= 0) "+${formatMoney(diff)}" else formatMoney(diff)
        val pctPart = String.format("%+.2f", pct)
        return "§7직전 대비 $color$moneyPart §7($pctPart%)"
    }

    /** 별점에 따른 초기 배율 (1→0.5, 2→0.8, 3→1.0, 4→1.3, 5→1.7) */
    private fun getStarMultiplier(stars: Double): Double {
        val s = stars.coerceIn(1.0, 5.0)
        return when {
            s <= 2.0 -> 0.5 + 0.3 * (s - 1.0)
            s <= 3.0 -> 0.8 + 0.2 * (s - 2.0)
            s <= 4.0 -> 1.0 + 0.3 * (s - 3.0)
            else -> 1.3 + 0.4 * (s - 4.0)
        }
    }

    private fun startPriceTick() {
        priceTickTask?.cancel()
        ticksUntilPriceUpdate = PRICE_TICK_INTERVAL_TICKS
        // 매 틱 카운트다운 → 정확한 '남은 시간' 표시, 6000틱마다 시세 갱신
        priceTickTask = Bukkit.getScheduler().runTaskTimer(
            plugin,
            Runnable {
                ticksUntilPriceUpdate--
                if (ticksUntilPriceUpdate <= 0) {
                    runPriceTick()
                    ticksUntilPriceUpdate = PRICE_TICK_INTERVAL_TICKS
                }
            },
            1L,
            1L
        )
    }

    private fun runPriceTick() {
        if (companies.isEmpty()) return

        val changes = mutableListOf<Pair<String, Double>>()

        for (company in companies.values) {
            val s = company.stars.coerceIn(1.0, 5.0)
            // 상승 확률: 1★ 30% → 5★ 70% (별점 높을수록 상승 선호)
            val upProbability = 0.3 + 0.1 * (s - 1.0)
            // 변동폭: 1★ 0.3%~1% → 5★ 1.5%~4% (별점 높을수록 변동성 큼)
            val baseVolatility = 0.003 + 0.003 * (s - 1.0)
            val maxVolatility = 0.01 + 0.0075 * (s - 1.0)
            val volatility = baseVolatility + Random.nextDouble() * (maxVolatility - baseVolatility)
            val direction = if (Random.nextDouble() < upProbability) 1.0 else -1.0
            val change = direction * volatility * (0.5 + Random.nextDouble() * 0.5)

            val oldPrice = company.currentPrice
            var newPrice = oldPrice * (1.0 + change)
            val minPrice = company.basePrice * 0.1
            val maxPrice = company.basePrice * 5.0
            newPrice = newPrice.coerceIn(minPrice, maxPrice)
            company.previousPrice = oldPrice
            company.currentPrice = newPrice

            val changePercent = ((newPrice - oldPrice) / oldPrice) * 100
            if (kotlin.math.abs(changePercent) >= 0.1) {
                changes.add(company.name to changePercent)
            }
        }

        save()

        if (changes.isNotEmpty()) {
            Bukkit.getOnlinePlayers().forEach { p ->
                if (p.uniqueId !in notificationMuted) {
                    p.sendMessage("§6[주식 시세]")
                    changes.forEach { (name, pct) ->
                        val color = if (pct >= 0) "§a" else "§c"
                        p.sendMessage(" §7- §e$name§f $color${String.format("%+.1f", pct)}%")
                    }
                }
            }
        }
    }

    private fun formatStars(stars: Double): String {
        return if (stars == stars.toLong().toDouble()) "${stars.toLong()}" else String.format("%.1f", stars)
    }

    /** 게임 틱을 분·초 문자열로 (20틱 ≈ 1초) */
    private fun formatTicksAsTimeLeft(ticks: Long): String {
        if (ticks <= 0) return "곧 반영"
        val totalSec = ticks / 20
        val m = totalSec / 60
        val s = totalSec % 60
        return when {
            m > 0 -> "${m}분 ${s}초"
            else -> "${s}초"
        }
    }

    private fun formatMoney(value: Double): String {
        return String.format("%,.2f", value)
    }

    /** 알림 끈 플레이어 제외하고 공지 발송 (시세, 별점 변경, 상장폐지) */
    private fun broadcastStockNotification(message: String) {
        Bukkit.getOnlinePlayers().forEach { p ->
            if (p.uniqueId !in notificationMuted) {
                p.sendMessage(message)
            }
        }
    }

    // endregion
}

