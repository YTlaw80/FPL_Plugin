package com.ytlaw80.fplplugin

import org.bukkit.plugin.java.JavaPlugin

class FPLPlugin : JavaPlugin() {

    private lateinit var news: News
    private lateinit var stocks: Stocks
    private lateinit var help: Help
    private lateinit var routes: Routes
    private lateinit var blockedEntitiesListener: BlockedEntitiesListener
    private lateinit var blockedBlocksListener: BlockedBlocksListener

    override fun onEnable() {
        if (!dataFolder.exists()) {
            dataFolder.mkdirs()
        }

        help = Help(this)
        getCommand("help")?.let {
            it.setExecutor(help)
            it.tabCompleter = help
        } ?: logger.warning("명령어 /help 가 plugin.yml 에 정의되어 있지 않습니다.")

        stocks = Stocks(this)
            listOf("buy", "sell", "setstars", "checkstats", "makecompany", "deletecompany", "setstartmoney", "setmoney", "money", "wealthrank", "transfer", "notifysettings", "setcompanydesc").forEach { cmd ->
            getCommand(cmd)?.let {
                it.setExecutor(stocks)
                it.tabCompleter = stocks
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

        logger.info("FPLPlugin 활성화됨")
    }

    override fun onDisable() {
        if (this::news.isInitialized) {
            news.saveNews()
        }
        if (this::stocks.isInitialized) {
            stocks.cancelPriceTick()
            stocks.save()
        }
        logger.info("FPLPlugin 비활성화됨")
    }
}