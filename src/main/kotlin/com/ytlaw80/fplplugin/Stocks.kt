package com.ytlaw80.fplplugin

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
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

data class NotificationPref(
    var stock: Boolean = true,
    /** "chat" | "actionbar" | "off" — 기본값 채팅 */
    var newsMode: String = "chat"
)

data class Company(
    val name: String,
    var stars: Double,
    var basePrice: Double,
    var currentPrice: Double,
    /** 자동 시세 반영 직전 가격 (/주식 에서 직전 대비 표시용) */
    var previousPrice: Double,
    /** 회사 소개 (OP가 /setcompanydesc 로 설정) */
    var description: String = ""
)

class Stocks(private val plugin: JavaPlugin) : CommandExecutor, TabCompleter {

    private val dataFile = File(plugin.dataFolder, "stocks.yml")

    private val companies: MutableMap<String, Company> = mutableMapOf()
    private val balances: MutableMap<UUID, Double> = mutableMapOf()
    private val holdings: MutableMap<UUID, MutableMap<String, Int>> = mutableMapOf()
    /** 알림 설정 (주식: 시세/별점/상장폐지, 뉴스: 업로드 공지) */
    private val notificationPrefs: MutableMap<UUID, NotificationPref> = mutableMapOf()
    /** 도박 성공확률 보정값 (기본 50%에 더해짐) */
    private val gambleAdjust: MutableMap<UUID, Double> = mutableMapOf()

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
            "gamble" -> handleGamble(sender, args)
            "notifysettings", "stocknotify" -> handleNotifySettings(sender, args)
            "setcompanydesc" -> handleSetCompanyDesc(sender, args)
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

            "setcompanydesc" -> {
                if (args.size == 1) {
                    val prefix = args[0].lowercase()
                    if (!sender.isOp) mutableListOf() else companies.keys
                        .filter { it.lowercase().startsWith(prefix) }
                        .sorted()
                        .toMutableList()
                } else mutableListOf()
            }

            "notifysettings", "stocknotify" -> {
                if (args.size <= 2) {
                    val subPrefix = if (args.size >= 2) args[1].lowercase() else args.getOrNull(0)?.lowercase() ?: ""
                    val korean = plugin.messages.language(sender) == "ko_kr"
                    when (args.getOrNull(0)?.lowercase()) {
                        "stock", "주식" -> (listOf("on", "off") + if (korean) listOf("켜기", "끄기") else emptyList()).filter { it.startsWith(subPrefix) }
                        "news", "뉴스" -> (listOf("chat", "actionbar", "off") + if (korean) listOf("채팅", "액션바", "끄기") else emptyList()).filter { it.startsWith(subPrefix) }
                        else -> (listOf("stock", "news") + if (korean) listOf("주식", "뉴스") else emptyList()).filter { it.startsWith(subPrefix) }
                    }.toMutableList()
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

            "gamble" -> {
                if (args.size == 1) {
                    listOf("100", "500", "1000", "5000", "10000")
                        .filter { it.startsWith(args[0]) }
                        .toMutableList()
                } else mutableListOf()
            }

            else -> mutableListOf()
        }
    }

    // region Commands

    private fun handleBuy(sender: CommandSender, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(plugin.messages.text(sender, "common.players-only"))
            return true
        }

        if (args.size != 2) {
            sender.sendMessage(plugin.messages.text(sender, "stocks.buy-usage"))
            return true
        }

        val companyName = args[0]
        val amount = args[1].toIntOrNull()
        if (amount == null || amount <= 0) {
            sender.sendMessage(plugin.messages.text(sender, "stocks.invalid-quantity"))
            return true
        }

        val company = companies[companyName]
        if (company == null) {
            sender.sendMessage(plugin.messages.text(sender, "stocks.company-not-found"))
            return true
        }

        val pricePerStock = currentPrice(company)
        val totalPrice = pricePerStock * amount

        val bal = getBalance(sender.uniqueId)
        if (bal < totalPrice) {
            sender.sendMessage(plugin.messages.text(sender, "common.insufficient-balance", formatMoney(bal), formatMoney(totalPrice)))
            return true
        }

        setBalance(sender.uniqueId, bal - totalPrice)
        val playerHoldings = holdings.getOrPut(sender.uniqueId) { mutableMapOf() }
        playerHoldings[companyName] = (playerHoldings[companyName] ?: 0) + amount

        // 구매량이 많으면 가격 상승 (수요 증가)
        applyBuyPriceImpact(company, amount)

        save()

        sender.sendMessage(plugin.messages.text(sender, "stocks.bought", companyName, amount, formatMoney(pricePerStock), formatMoney(totalPrice)))
        return true
    }

    private fun handleSell(sender: CommandSender, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(plugin.messages.text(sender, "common.players-only"))
            return true
        }

        if (args.size != 2) {
            sender.sendMessage(plugin.messages.text(sender, "stocks.sell-usage"))
            return true
        }

        val companyName = args[0]
        val amount = args[1].toIntOrNull()
        if (amount == null || amount <= 0) {
            sender.sendMessage(plugin.messages.text(sender, "stocks.invalid-quantity"))
            return true
        }

        val company = companies[companyName]
        if (company == null) {
            sender.sendMessage(plugin.messages.text(sender, "stocks.company-not-found"))
            return true
        }

        val playerHoldings = holdings.getOrPut(sender.uniqueId) { mutableMapOf() }
        val owned = playerHoldings[companyName] ?: 0
        if (owned < amount) {
            sender.sendMessage(plugin.messages.text(sender, "stocks.insufficient-shares", owned))
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

        // 판매량이 많으면 가격 하락 (공급 증가)
        applySellPriceImpact(company, amount)

        save()

        sender.sendMessage(plugin.messages.text(sender, "stocks.sold", companyName, amount, formatMoney(pricePerStock), formatMoney(totalPrice)))
        return true
    }

    private fun handleSetStars(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("fpl.stocks.admin")) {
            sender.sendMessage(plugin.messages.text(sender, "common.command-no-permission"))
            return true
        }

        if (args.size != 2) {
            sender.sendMessage(plugin.messages.text(sender, "stocks.rating-usage"))
            return true
        }

        val companyName = args[0]
        val stars = args[1].toDoubleOrNull()
        if (stars == null || stars !in 1.0..5.0) {
            sender.sendMessage(plugin.messages.text(sender, "stocks.invalid-rating"))
            return true
        }

        val company = companies[companyName]
        if (company == null) {
            sender.sendMessage(plugin.messages.text(sender, "stocks.company-not-found"))
            return true
        }

        company.stars = stars
        save()

        val starsDisplay = if (stars == stars.toLong().toDouble()) "${stars.toLong()}" else String.format("%.1f", stars)
        broadcastStockNotification { p -> plugin.messages.text(p, "stocks.rating-notification", company.name, starsDisplay) }
        sender.sendMessage(plugin.messages.text(sender, "stocks.rating-changed"))
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
            sender.sendMessage(plugin.messages.text(sender, "stocks.company-not-found"))
            return true
        }

        val price = currentPrice(company)
        sender.sendMessage(plugin.messages.text(sender, "stocks.company-header", company.name))
        sender.sendMessage(plugin.messages.text(sender, "stocks.next-update", formatTicksAsTimeLeft(sender, ticksUntilPriceUpdate)))
        sender.sendMessage(plugin.messages.text(sender, "stocks.rating", formatStars(company.stars)))
        if (company.description.isNotBlank()) {
            sender.sendMessage(plugin.messages.text(sender, "stocks.description", company.description))
        }
        sender.sendMessage(plugin.messages.text(sender, "stocks.base-price", formatMoney(company.basePrice)))
        sender.sendMessage(plugin.messages.text(sender, "stocks.previous-price", formatMoney(roundPrice(company.previousPrice))))
        sender.sendMessage(plugin.messages.text(sender, "stocks.current-price", formatMoney(price), formatPriceDiffFromPrevious(sender, company)))
        return true
    }

    private fun sendCompanyList(sender: CommandSender, page: Int) {
        sender.sendMessage(plugin.messages.text(sender, "stocks.next-update", formatTicksAsTimeLeft(sender, ticksUntilPriceUpdate)))
        if (companies.isEmpty()) {
            sender.sendMessage(plugin.messages.text(sender, "stocks.empty"))
            return
        }

        val sortedCompanies = companies.values.sortedBy { it.name }.toList()
        val totalPages = if (sortedCompanies.size <= COMPANIES_PER_PAGE) 1
            else (sortedCompanies.size + COMPANIES_PER_PAGE - 1) / COMPANIES_PER_PAGE
        val pageIndex = (page - 1).coerceIn(0, totalPages - 1)
        val from = pageIndex * COMPANIES_PER_PAGE
        val to = minOf(from + COMPANIES_PER_PAGE, sortedCompanies.size)
        val pageCompanies = sortedCompanies.subList(from, to)

        sender.sendMessage(plugin.messages.text(sender, "stocks.list-header", page, totalPages))
        pageCompanies.forEach { company ->
            val price = currentPrice(company)
            val starsDisplay = formatStars(company.stars)
            val diffPart = formatPriceDiffFromPrevious(sender, company)
            val hoverText = buildHoverForCompany(sender, company)
            val line = messageComponent(plugin.messages.text(sender, "common.indent"), NamedTextColor.DARK_GRAY)
                .append(Component.text(company.name, NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.runCommand("/checkstats ${company.name}"))
                    .hoverEvent(HoverEvent.showText(hoverText)))
                .append(messageComponent(plugin.messages.text(sender, "stocks.rating-badge", starsDisplay), NamedTextColor.YELLOW))
                .append(messageComponent(plugin.messages.text(sender, "stocks.price-label"), NamedTextColor.GRAY))
                .append(Component.text(formatMoney(price), NamedTextColor.GREEN))
                .append(messageComponent(plugin.messages.text(sender, "stocks.price-diff-separator", diffPart)))
            sender.sendMessage(line)
        }

        if (totalPages > 1) {
            val prevComp = if (page > 1) {
                messageComponent(plugin.messages.text(sender, "common.previous"), NamedTextColor.WHITE)
                    .clickEvent(ClickEvent.runCommand("/checkstats ${page - 1}"))
                    .hoverEvent(HoverEvent.showText(messageComponent(plugin.messages.text(sender, "stocks.previous-hover"))))
            } else {
                messageComponent(plugin.messages.text(sender, "common.previous"), NamedTextColor.DARK_GRAY)
            }
            val pageComp = messageComponent(plugin.messages.text(sender, "common.page", page, totalPages), NamedTextColor.GRAY)
            val nextComp = if (page < totalPages) {
                messageComponent(plugin.messages.text(sender, "common.next"), NamedTextColor.WHITE)
                    .clickEvent(ClickEvent.runCommand("/checkstats ${page + 1}"))
                    .hoverEvent(HoverEvent.showText(messageComponent(plugin.messages.text(sender, "stocks.next-hover"))))
            } else {
                messageComponent(plugin.messages.text(sender, "common.next"), NamedTextColor.DARK_GRAY)
            }
            sender.sendMessage(Component.empty().append(prevComp).append(pageComp).append(nextComp))
        }
    }

    private fun buildHoverForCompany(sender: CommandSender, company: Company): Component {
        var c = messageComponent(plugin.messages.text(sender, "stocks.details-hover"), NamedTextColor.GRAY)
        if (company.description.isNotBlank()) {
            val desc = if (company.description.length > 200) "${company.description.take(200)}…" else company.description
            c = c.append(Component.newline()).append(Component.text(desc, NamedTextColor.WHITE))
        }
        return c
    }

    private fun handleSetCompanyDesc(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.isOp) {
            sender.sendMessage(plugin.messages.text(sender, "common.op-only"))
            return true
        }
        if (args.size < 2) {
            sender.sendMessage(plugin.messages.text(sender, "stocks.description-usage"))
            sender.sendMessage(plugin.messages.text(sender, "stocks.description-clear-usage"))
            return true
        }
        val companyName = args[0]
        val company = companies[companyName]
        if (company == null) {
            sender.sendMessage(plugin.messages.text(sender, "stocks.company-not-found"))
            return true
        }
        var desc = args.drop(1).joinToString(" ")
        if (desc == "-") {
            desc = ""
        }
        company.description = desc
        save()
        if (desc.isEmpty()) {
            sender.sendMessage(plugin.messages.text(sender, "stocks.description-cleared", company.name))
        } else {
            sender.sendMessage(plugin.messages.text(sender, "stocks.description-set", company.name))
        }
        return true
    }

    /** 구매 시 가격 상승 (주당 0.08%, 거래당 최대 5%) */
    private fun applyBuyPriceImpact(company: Company, amount: Int) {
        val impact = (amount * PRICE_IMPACT_PER_SHARE).coerceAtMost(MAX_TRADE_IMPACT)
        company.currentPrice = clampPrice(company, company.currentPrice * (1.0 + impact))
    }

    /** 판매 시 가격 하락 (주당 0.08%, 거래당 최대 5%) */
    private fun applySellPriceImpact(company: Company, amount: Int) {
        val impact = (amount * PRICE_IMPACT_PER_SHARE).coerceAtMost(MAX_TRADE_IMPACT)
        company.currentPrice = clampPrice(company, company.currentPrice * (1.0 - impact))
    }

    private fun clampPrice(company: Company, price: Double): Double {
        val minP = company.basePrice * 0.1
        val maxP = company.basePrice * 5.0
        return price.coerceIn(minP, maxP)
    }

    companion object {
        private const val COMPANIES_PER_PAGE = 5
        private const val PRICE_IMPACT_PER_SHARE = 0.0008  // 주당 0.08% (100주 ≈ 8% 변동)
        private const val MAX_TRADE_IMPACT = 0.05          // 거래당 최대 5%
    }

    private fun handleMakeCompany(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("fpl.stocks.admin")) {
            sender.sendMessage(plugin.messages.text(sender, "common.command-no-permission"))
            return true
        }

        if (args.size != 2) {
            sender.sendMessage(plugin.messages.text(sender, "stocks.create-usage"))
            return true
        }

        val name = args[0]
        val basePrice = args[1].toDoubleOrNull()
        if (basePrice == null || basePrice <= 0) {
            sender.sendMessage(plugin.messages.text(sender, "stocks.invalid-base-price"))
            return true
        }

        if (companies.containsKey(name)) {
            sender.sendMessage(plugin.messages.text(sender, "stocks.company-exists"))
            return true
        }

        val initial = basePrice * getStarMultiplier(2.5)
        val company = Company(
            name = name,
            stars = 2.5,
            basePrice = basePrice,
            currentPrice = initial,
            previousPrice = initial,
            description = ""
        )
        companies[name] = company
        save()

        sender.sendMessage(plugin.messages.text(sender, "stocks.created", name, formatMoney(basePrice)))
        return true
    }

    private fun handleSetStartMoney(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("fpl.stocks.admin")) {
            sender.sendMessage(plugin.messages.text(sender, "common.command-no-permission"))
            return true
        }

        if (args.size != 1) {
            sender.sendMessage(plugin.messages.text(sender, "stocks.start-money-usage"))
            return true
        }

        val value = args[0].toDoubleOrNull()
        if (value == null || value < 0) {
            sender.sendMessage(plugin.messages.text(sender, "stocks.invalid-amount"))
            return true
        }

        startMoney = value
        save()
        sender.sendMessage(plugin.messages.text(sender, "stocks.start-money-set", formatMoney(startMoney)))
        return true
    }

    private fun handleSetMoney(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("fpl.stocks.admin")) {
            sender.sendMessage(plugin.messages.text(sender, "common.command-no-permission"))
            return true
        }

        if (args.size != 2) {
            sender.sendMessage(plugin.messages.text(sender, "stocks.set-money-usage"))
            return true
        }

        val targetName = args[0]
        val value = args[1].toDoubleOrNull()
        if (value == null || value < 0) {
            sender.sendMessage(plugin.messages.text(sender, "stocks.invalid-amount"))
            return true
        }

        val offline = Bukkit.getOfflinePlayer(targetName)
        val uuid = offline.uniqueId

        setBalance(uuid, value)
        save()

        sender.sendMessage(plugin.messages.text(sender, "stocks.money-set", targetName, formatMoney(value)))
        Bukkit.getPlayerExact(targetName)?.let { recipient -> recipient.sendMessage(plugin.messages.text(recipient, "stocks.money-set-notification", formatMoney(value))) }
        return true
    }

    private fun handleTransfer(sender: CommandSender, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(plugin.messages.text(sender, "common.players-only"))
            return true
        }
        if (args.size != 2) {
            sender.sendMessage(plugin.messages.text(sender, "stocks.transfer-usage"))
            return true
        }

        val targetName = args[0]
        val amount = args[1].toDoubleOrNull()
        if (amount == null || amount <= 0) {
            sender.sendMessage(plugin.messages.text(sender, "stocks.positive-amount"))
            return true
        }

        if (targetName.equals(sender.name, ignoreCase = true)) {
            sender.sendMessage(plugin.messages.text(sender, "stocks.self-transfer"))
            return true
        }

        val target = Bukkit.getPlayer(targetName)
        if (target == null) {
            sender.sendMessage(plugin.messages.text(sender, "stocks.player-not-found"))
            return true
        }
        if (target == sender) {
            sender.sendMessage(plugin.messages.text(sender, "stocks.self-transfer"))
            return true
        }

        val senderUuid = sender.uniqueId
        val targetUuid = target.uniqueId
        val senderBal = getBalance(senderUuid)
        if (senderBal < amount) {
            sender.sendMessage(plugin.messages.text(sender, "common.insufficient-balance", formatMoney(senderBal), formatMoney(amount)))
            return true
        }

        setBalance(senderUuid, senderBal - amount)
        val targetBal = getBalance(targetUuid)
        setBalance(targetUuid, targetBal + amount)
        save()

        sender.sendMessage(plugin.messages.text(sender, "stocks.transferred", target.name, formatMoney(amount), formatMoney(senderBal - amount)))
        target.sendMessage(plugin.messages.text(target, "stocks.transfer-received", sender.name, formatMoney(amount), formatMoney(targetBal + amount)))
        return true
    }

    private fun handleGamble(sender: CommandSender, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(plugin.messages.text(sender, "common.players-only"))
            return true
        }
        if (args.size != 1) {
            sender.sendMessage(plugin.messages.text(sender, "stocks.gamble-usage"))
            sender.sendMessage(plugin.messages.text(sender, "stocks.gamble-chance"))
            return true
        }

        val bet = args[0].toDoubleOrNull()
        if (bet == null || bet <= 0) {
            sender.sendMessage(plugin.messages.text(sender, "stocks.positive-amount"))
            return true
        }

        val uuid = sender.uniqueId
        val bal = getBalance(uuid)
        val minBet = bal * 0.05
        if (bet < minBet) {
            sender.sendMessage(plugin.messages.text(sender, "stocks.minimum-bet", formatMoney(minBet)))
            return true
        }
        if (bal < bet) {
            sender.sendMessage(plugin.messages.text(sender, "common.insufficient-balance", formatMoney(bal), formatMoney(bet)))
            return true
        }

        val adjust = gambleAdjust[uuid] ?: 0.0
        val chanceBefore = clampChance(0.5 + adjust)
        val success = Random.nextDouble() < chanceBefore

        if (success) {
            // 성공: 배팅금만큼 이득 (순익 +bet)
            setBalance(uuid, bal + bet)
            gambleAdjust[uuid] = (chanceBefore - 0.02) - 0.5
            save()
            val chanceAfter = clampChance(0.5 + (gambleAdjust[uuid] ?: 0.0))
            sender.sendMessage(
                plugin.messages.text(sender, "stocks.gamble-won", formatMoney(bet), formatPercent(chanceBefore), formatPercent(chanceAfter))
            )
        } else {
            // 실패: 배팅금만큼 손해 (순익 -bet)
            setBalance(uuid, bal - bet)
            gambleAdjust[uuid] = (chanceBefore + 0.001) - 0.5
            save()
            val chanceAfter = clampChance(0.5 + (gambleAdjust[uuid] ?: 0.0))
            sender.sendMessage(
                plugin.messages.text(sender, "stocks.gamble-lost", formatMoney(bet), formatPercent(chanceBefore), formatPercent(chanceAfter))
            )
        }
        return true
    }

    private fun handleDeleteCompany(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("fpl.stocks.admin")) {
            sender.sendMessage(plugin.messages.text(sender, "common.command-no-permission"))
            return true
        }

        if (args.size != 1) {
            sender.sendMessage(plugin.messages.text(sender, "stocks.delete-usage"))
            return true
        }

        val companyName = args[0]
        val company = companies[companyName]
        if (company == null) {
            sender.sendMessage(plugin.messages.text(sender, "stocks.company-not-found"))
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
                Bukkit.getPlayer(uuid)?.let { recipient -> recipient.sendMessage(plugin.messages.text(recipient, "stocks.delist-refund", company.name, amount, formatMoney(refund))) }
            }
        }

        companies.remove(companyName)
        save()

        broadcastStockNotification { p -> plugin.messages.text(p, "stocks.delist-notification", company.name) }
        sender.sendMessage(plugin.messages.text(sender, "stocks.delisted", company.name))
        return true
    }

    private fun getNotificationPref(uuid: UUID): NotificationPref {
        return notificationPrefs.getOrPut(uuid) { NotificationPref() }
    }

    /** 뉴스 알림 모드: "chat" | "actionbar" | "off" (News에서 호출) */
    fun getNewsNotificationMode(uuid: UUID): String =
        getNotificationPref(uuid).newsMode.let { if (it in listOf("chat", "actionbar", "off")) it else "chat" }

    private fun wantsStockNotification(uuid: UUID): Boolean = getNotificationPref(uuid).stock

    private fun handleNotifySettings(sender: CommandSender, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(plugin.messages.text(sender, "common.players-only"))
            return true
        }
        val uuid = sender.uniqueId
        val pref = getNotificationPref(uuid)

        when {
            args.size >= 2 -> {
                val type = args[0].lowercase()
                val on = args[1].lowercase() in listOf("on", "켜기", "true")
                when (type) {
                    "stock", "주식" -> {
                        pref.stock = on
                        save()
                        sender.sendMessage(plugin.messages.text(sender, "notifications.stock-status", if (on) plugin.messages.text(sender, "notifications.stock-enabled") else plugin.messages.text(sender, "notifications.stock-disabled")))
                    }
                    "news", "뉴스" -> {
                        val mode = when (args[1].lowercase()) {
                            "chat", "채팅" -> "chat"
                            "actionbar", "액션바" -> "actionbar"
                            "off", "끄기" -> "off"
                            else -> pref.newsMode
                        }
                        pref.newsMode = mode
                        save()
                        val modeStr = when (mode) {
                            "chat" -> plugin.messages.text(sender, "notifications.chat-colored")
                            "actionbar" -> plugin.messages.text(sender, "notifications.actionbar-colored")
                            else -> plugin.messages.text(sender, "notifications.off-colored")
                        }
                        sender.sendMessage(plugin.messages.text(sender, "notifications.news-status", modeStr))
                    }
                    else -> sendNotifySettingsUi(sender)
                }
            }
            else -> sendNotifySettingsUi(sender)
        }
        return true
    }

    private fun sendNotifySettingsUi(sender: Player) {
        val uuid = sender.uniqueId
        val pref = getNotificationPref(uuid)
        sender.sendMessage(plugin.messages.text(sender, "notifications.header"))
        sender.sendMessage(plugin.messages.text(sender, "notifications.toggle-hint"))

        val stockLine = messageComponent(plugin.messages.text(sender, "notifications.stock-label"), NamedTextColor.GRAY)
            .append(if (pref.stock) {
                messageComponent(plugin.messages.text(sender, "notifications.on-button"), NamedTextColor.GREEN)
                    .clickEvent(ClickEvent.runCommand("/notifysettings stock off"))
                    .hoverEvent(HoverEvent.showText(messageComponent(plugin.messages.text(sender, "notifications.disable-hover"))))
            } else {
                messageComponent(plugin.messages.text(sender, "notifications.off-button"), NamedTextColor.RED)
                    .clickEvent(ClickEvent.runCommand("/notifysettings stock on"))
                    .hoverEvent(HoverEvent.showText(messageComponent(plugin.messages.text(sender, "notifications.enable-hover"))))
            })
        sender.sendMessage(stockLine)

        val newsLine = messageComponent(plugin.messages.text(sender, "notifications.news-label"), NamedTextColor.GRAY)
            .append(buildNewsModeButton(sender, plugin.messages.text(sender, "notifications.chat"), "chat", pref.newsMode))
            .append(messageComponent(plugin.messages.text(sender, "common.space"), NamedTextColor.DARK_GRAY))
            .append(buildNewsModeButton(sender, plugin.messages.text(sender, "notifications.actionbar"), "actionbar", pref.newsMode))
            .append(messageComponent(plugin.messages.text(sender, "common.space"), NamedTextColor.DARK_GRAY))
            .append(buildNewsModeButton(sender, plugin.messages.text(sender, "notifications.off-label"), "off", pref.newsMode))
        sender.sendMessage(newsLine)
    }

    private fun buildNewsModeButton(sender: CommandSender, label: String, mode: String, current: String): Component {
        val isSelected = current == mode
        return messageComponent(plugin.messages.text(sender, "notifications.mode-button", label), if (isSelected) NamedTextColor.GREEN else NamedTextColor.GRAY)
            .clickEvent(ClickEvent.runCommand("/notifysettings news $mode"))
            .hoverEvent(HoverEvent.showText(messageComponent(if (isSelected) plugin.messages.text(sender, "notifications.selected-hover") else plugin.messages.text(sender, "notifications.select-hover", label))))
    }

    private fun handleMoney(sender: CommandSender, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(plugin.messages.text(sender, "common.players-only"))
            return true
        }
        if (args.isNotEmpty()) {
            sender.sendMessage(plugin.messages.text(sender, "stocks.money-usage"))
            return true
        }

        val uuid = sender.uniqueId
        val cash = getBalance(uuid)
        val playerHoldings = holdings[uuid]

        sender.sendMessage(plugin.messages.text(sender, "stocks.wallet-header"))
        sender.sendMessage(plugin.messages.text(sender, "stocks.cash", formatMoney(cash)))

        if (playerHoldings == null || playerHoldings.isEmpty()) {
            sender.sendMessage(plugin.messages.text(sender, "stocks.no-holdings"))
            sender.sendMessage(plugin.messages.text(sender, "stocks.total-assets", formatMoney(cash)))
            return true
        }

        sender.sendMessage(plugin.messages.text(sender, "stocks.holdings-header"))
        var stockTotal = 0.0
        for ((companyName, qty) in playerHoldings.entries.sortedBy { it.key }) {
            val company = companies[companyName]
            if (company == null) {
                sender.sendMessage(plugin.messages.text(sender, "stocks.unknown-holding", companyName, qty))
                continue
            }
            val unit = currentPrice(company)
            val sub = unit * qty
            stockTotal += sub
            sender.sendMessage(plugin.messages.text(sender, "stocks.holding", companyName, qty, formatMoney(unit), formatMoney(sub)))
        }

        sender.sendMessage(plugin.messages.text(sender, "stocks.stock-value", formatMoney(stockTotal)))
        val grand = cash + stockTotal
        sender.sendMessage(plugin.messages.text(sender, "stocks.grand-total", formatMoney(grand)))
        return true
    }

    private fun handleWealthRank(sender: CommandSender, args: Array<out String>): Boolean {
        if (args.isNotEmpty()) {
            sender.sendMessage(plugin.messages.text(sender, "stocks.rank-usage"))
            return true
        }

        val uuids = (balances.keys + holdings.keys).toSet()
        val rows = uuids.map { uuid ->
            val cash = balances[uuid] ?: startMoney
            val stock = computeStockValue(uuid)
            Triple(uuid, cash + stock, cash to stock)
        }.sortedByDescending { it.second }

        sender.sendMessage(plugin.messages.text(sender, "stocks.rank-header"))
        if (rows.isEmpty()) {
            sender.sendMessage(plugin.messages.text(sender, "stocks.no-players"))
            return true
        }

        sender.sendMessage(plugin.messages.text(sender, "stocks.player-count", rows.size))
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
            val selfMark = if (isSelf) plugin.messages.text(sender, "stocks.self-marker") else ""
            sender.sendMessage(
                plugin.messages.text(sender, "stocks.rank-row", rankPrefix, rank, name, selfMark, formatMoney(total), formatMoney(cash), formatMoney(stock))
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
                val description = sec.getString("description") ?: ""
                companies[name] = Company(name, stars, basePrice, currentPrice, previousPrice, description)
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

        val prefsSec = config.getConfigurationSection("notificationPrefs")
        if (prefsSec != null) {
            for (id in prefsSec.getKeys(false)) {
                val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: continue
                val sec = prefsSec.getConfigurationSection(id) ?: continue
                val newsVal = sec.get("news")
                val newsMode = when {
                    newsVal is Boolean -> if (newsVal) "chat" else "off"
                    newsVal is String && newsVal in listOf("chat", "actionbar", "off") -> newsVal
                    else -> "chat"
                }
                notificationPrefs[uuid] = NotificationPref(
                    stock = sec.getBoolean("stock", true),
                    newsMode = newsMode
                )
            }
        }
        val mutedList = config.getStringList("notificationMuted")
        if (mutedList.isNotEmpty()) {
            mutedList.forEach { id ->
                runCatching { UUID.fromString(id) }.getOrNull()?.let { uuid ->
                    notificationPrefs.getOrPut(uuid) { NotificationPref() }.stock = false
                }
            }
        }

        val gambleSec = config.getConfigurationSection("gambleAdjust")
        if (gambleSec != null) {
            for (id in gambleSec.getKeys(false)) {
                val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: continue
                gambleAdjust[uuid] = gambleSec.getDouble(id, 0.0)
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
            config.set("$path.currentPrice", company.currentPrice)
            config.set("$path.previousPrice", company.previousPrice)
            config.set("$path.description", company.description)
        }

        for ((uuid, bal) in balances) {
            config.set("balances.${uuid}", bal)
        }

        for ((uuid, map) in holdings) {
            for ((company, amount) in map) {
                config.set("holdings.${uuid}.$company", amount)
            }
        }

        if (notificationPrefs.isNotEmpty()) {
            val prefsSec = config.createSection("notificationPrefs")
            for ((uuid, pref) in notificationPrefs) {
                val sec = prefsSec.createSection(uuid.toString())
                sec.set("stock", pref.stock)
                sec.set("news", pref.newsMode)
            }
        }

        if (gambleAdjust.isNotEmpty()) {
            val gSec = config.createSection("gambleAdjust")
            for ((uuid, adj) in gambleAdjust) {
                gSec.set(uuid.toString(), adj)
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

    /** 외부 시스템(룰렛 등)에서 조회용으로 사용 */
    fun getCashBalance(uuid: UUID): Double = getBalance(uuid)

    /** 외부 시스템(룰렛 등)에서 출금 시도 */
    fun withdrawCash(uuid: UUID, amount: Double): Boolean {
        if (amount <= 0) return false
        val bal = getBalance(uuid)
        if (bal < amount) return false
        setBalance(uuid, bal - amount)
        save()
        return true
    }

    /** 외부 시스템(룰렛 등)에서 입금 */
    fun depositCash(uuid: UUID, amount: Double) {
        if (amount <= 0) return
        val bal = getBalance(uuid)
        setBalance(uuid, bal + amount)
        save()
    }

    private fun roundPrice(value: Double): Double {
        return (value * 100.0).roundToInt() / 100.0
    }

    private fun currentPrice(company: Company): Double {
        return roundPrice(company.currentPrice)
    }

    /** 직전 자동 시세 대비 차액·등락률 (색상 코드 포함) */
    private fun formatPriceDiffFromPrevious(sender: CommandSender, company: Company): String {
        val cur = currentPrice(company)
        val prev = roundPrice(company.previousPrice)
        val diff = cur - prev
        if (kotlin.math.abs(diff) < 0.005) {
            return plugin.messages.text(sender, "stocks.no-price-change")
        }
        val pct = if (prev > 1e-9) (diff / prev) * 100.0 else 0.0
        val color = if (diff >= 0) "§a" else "§c"
        val moneyPart = if (diff >= 0) "+${formatMoney(diff)}" else formatMoney(diff)
        val pctPart = String.format("%+.2f", pct)
        return plugin.messages.text(sender, "stocks.price-change", color, pctPart, moneyPart)
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
                if (wantsStockNotification(p.uniqueId)) {
                    p.sendMessage(plugin.messages.text(p, "stocks.price-notification-header"))
                    changes.forEach { (name, pct) ->
                        val color = if (pct >= 0) "§a" else "§c"
                        p.sendMessage(plugin.messages.text(p, "stocks.price-notification-row", name, color, String.format("%+.1f", pct)))
                    }
                }
            }
        }
    }

    private fun formatStars(stars: Double): String {
        return if (stars == stars.toLong().toDouble()) "${stars.toLong()}" else String.format("%.1f", stars)
    }

    /** 게임 틱을 분·초 문자열로 (20틱 ≈ 1초) */
    private fun formatTicksAsTimeLeft(sender: CommandSender, ticks: Long): String {
        if (ticks <= 0) return plugin.messages.text(sender, "time.soon")
        val totalSec = ticks / 20
        val m = totalSec / 60
        val s = totalSec % 60
        return when {
            m > 0 -> plugin.messages.text(sender, "time.minutes-seconds", m, s)
            else -> plugin.messages.text(sender, "time.seconds", s)
        }
    }

    private fun formatMoney(value: Double): String {
        return String.format("%,.2f", value)
    }

    private fun clampChance(value: Double): Double = value.coerceIn(0.01, 0.99)

    private fun formatPercent(chance: Double): String = String.format("%.1f%%", chance * 100.0)

    /** 알림 끈 플레이어 제외하고 공지 발송 (시세, 별점 변경, 상장폐지) */
    private fun broadcastStockNotification(message: (Player) -> String) {
        Bukkit.getOnlinePlayers().forEach { p ->
            if (wantsStockNotification(p.uniqueId)) {
                p.sendMessage(message(p))
            }
        }
    }

    // endregion
}

