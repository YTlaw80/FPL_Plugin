package com.ytlaw80.fplplugin

import org.bukkit.plugin.java.JavaPlugin

class FPLPlugin : JavaPlugin() {

    private lateinit var news: News
    private lateinit var stocks: Stocks
    private lateinit var help: Help

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