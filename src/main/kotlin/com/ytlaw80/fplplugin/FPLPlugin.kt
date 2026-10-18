package com.ytlaw80.fplplugin

import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.entity.Player

class FPLPlugin : JavaPlugin() {

    lateinit var languages: Languages
        private set

    private lateinit var safeEntity: SafeEntity
    private lateinit var news: News
    private lateinit var stocks: Stocks
    private lateinit var help: Help
    private lateinit var routes: Routes
    private lateinit var blockedEntitiesListener: BlockedEntitiesListener
    private lateinit var blockedBlocksListener: BlockedBlocksListener
    private var worldEditBlockGuard: WorldEditBlockGuard? = null
    private lateinit var punishments: Punishments
    private lateinit var roulette: Roulette
    lateinit var airsoft: Airsoft
        private set
    lateinit var tagGame: TagGame
        private set

    fun refreshAirsoftScoreboards(player: Player? = null) {
        if (!this::airsoft.isInitialized) return
        if (player == null) airsoft.refreshScoreboards() else airsoft.refreshScoreboard(player)
    }

    fun refreshTagNameTags() {
        if (this::tagGame.isInitialized) tagGame.refreshNameTags()
    }

    override fun onEnable() {
        if (!dataFolder.exists()) {
            dataFolder.mkdirs()
        }

        saveDefaultConfig()
        languages = Languages(this)
        getCommand("language")?.let {
            it.setExecutor(languages)
            it.tabCompleter = languages
        } ?: logger.warning("Command /language is not defined in plugin.yml.")

        help = Help(this)
        getCommand("help")?.let {
            it.setExecutor(help)
            it.tabCompleter = help
        } ?: logger.warning("명령어 /help 가 plugin.yml 에 정의되어 있지 않습니다.")

        stocks = Stocks(this)
            listOf("buy", "sell", "setstars", "checkstats", "makecompany", "deletecompany", "setstartmoney", "setmoney", "money", "wealthrank", "transfer", "gamble", "notifysettings", "setcompanydesc").forEach { cmd ->
            getCommand(cmd)?.let {
                it.setExecutor(stocks)
                it.tabCompleter = stocks
            } ?: logger.warning("명령어 /$cmd 가 plugin.yml 에 정의되어 있지 않습니다.")
        }

        safeEntity = SafeEntity(this)
        listOf("clearentity", "checksafeentity", "addsafeentity", "delsafeentity").forEach { cmd ->
            getCommand(cmd)?.let {
                it.setExecutor(safeEntity)
                it.tabCompleter = safeEntity
            } ?: logger.warning("명령어 /$cmd 가 plugin.yml 에 정의되어 있지 않습니다.")
        }

        news = News(this, stocks)

        listOf("uploadnews", "deletenews", "checknews").forEach { cmd ->
            getCommand(cmd)?.let {
                it.setExecutor(news)
                it.tabCompleter = news
            } ?: logger.warning("명령어 /$cmd 가 plugin.yml 에 정의되어 있지 않습니다.")
        }

        routes = Routes(this)
        getCommand("routes")?.let {
            it.setExecutor(routes)
            it.tabCompleter = routes
        } ?: logger.warning("명령어 /routes 가 plugin.yml 에 정의되어 있지 않습니다.")

        // block_entity.yml 에 등록된 엔티티 스폰을 차단
        blockedEntitiesListener = BlockedEntitiesListener(this)
        server.pluginManager.registerEvents(blockedEntitiesListener, this)

        // block_entity.yml 에 등록된 블록 배치/스폰을 차단 + 이미 존재하는 블록 제거
        blockedBlocksListener = BlockedBlocksListener(this)
        server.pluginManager.registerEvents(blockedBlocksListener, this)
        if (server.pluginManager.isPluginEnabled("WorldEdit")) {
            worldEditBlockGuard = WorldEditBlockGuard(blockedBlocksListener).also { it.register() }
        }

        punishments = Punishments(this)
        server.pluginManager.registerEvents(punishments, this)
        listOf("punish", "unpunish").forEach { cmd ->
            getCommand(cmd)?.let {
                it.setExecutor(punishments)
                it.tabCompleter = punishments
            } ?: logger.warning("명령어 /$cmd 가 plugin.yml 에 정의되어 있지 않습니다.")
        }

        roulette = Roulette(this, stocks)
        getCommand("roulette")?.let {
            it.setExecutor(roulette)
            it.tabCompleter = roulette
        } ?: logger.warning("명령어 /roulette 가 plugin.yml 에 정의되어 있지 않습니다.")

        airsoft = Airsoft(this)
        server.pluginManager.registerEvents(airsoft, this)
        getCommand("airsoft")?.let {
            it.setExecutor(airsoft)
            it.tabCompleter = airsoft
        } ?: logger.warning("명령어 /airsoft 가 plugin.yml 에 정의되어 있지 않습니다.")

        tagGame = TagGame(this)
        server.pluginManager.registerEvents(tagGame, this)
        getCommand("tag")?.let {
            it.setExecutor(tagGame)
            it.tabCompleter = tagGame
        } ?: logger.warning("명령어 /tag 가 plugin.yml 에 정의되어 있지 않습니다.")

        logger.info("FPLPlugin 활성화됨")
    }

    override fun onDisable() {
        worldEditBlockGuard?.unregister()
        if (this::news.isInitialized) {
            news.saveNews()
        }
        if (this::stocks.isInitialized) {
            stocks.cancelPriceTick()
            stocks.save()
        }
        if (this::punishments.isInitialized) {
            punishments.shutdown()
        }
        if (this::roulette.isInitialized) {
            roulette.shutdown()
        }
        if (this::airsoft.isInitialized) {
            airsoft.shutdown()
        }
        if (this::tagGame.isInitialized) {
            tagGame.shutdown()
        }
        logger.info("FPLPlugin 비활성화됨")
    }
}
