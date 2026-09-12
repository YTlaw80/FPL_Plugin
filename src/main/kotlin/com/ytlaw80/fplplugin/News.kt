package com.ytlaw80.fplplugin

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.time.Instant

data class NewsItem(
    val id: Int,
    val author: String,
    val title: String,
    val content: String,
    val timestamp: Long
)

class News(private val plugin: JavaPlugin, private val stocks: Stocks) : CommandExecutor, TabCompleter {

    private val newsFile: File = File(plugin.dataFolder, "news.yml")
    private val newsList: MutableList<NewsItem> = mutableListOf()
    private var lastId: Int = 0

    init {
        loadNews()
    }

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ): Boolean {
        return when (command.name.lowercase()) {
            "uploadnews" -> handleUploadNews(sender, args)
            "deletenews" -> handleDeleteNews(sender, args)
            "checknews" -> handleCheckNews(sender)
            else -> false
        }
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): MutableList<String> {
        if (command.name.equals("deletenews", ignoreCase = true) && sender is Player && args.size == 1) {
            val prefix = args[0]
            return newsList
                .filter { it.author == sender.name }
                .map { it.id.toString() }
                .filter { it.startsWith(prefix) }
                .distinct()
                .toMutableList()
        }
        return mutableListOf()
    }

    private fun handleUploadNews(sender: CommandSender, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(plugin.messages.text(sender, "common.players-only"))
            return true
        }

        if (args.size < 2) {
            sender.sendMessage(plugin.messages.text(sender, "news.upload-usage"))
            return true
        }

        val title = args[0]
        val content = args.drop(1).joinToString(" ")

        val id = ++lastId
        val item = NewsItem(
            id = id,
            author = sender.name,
            title = title,
            content = content,
            timestamp = Instant.now().epochSecond
        )
        newsList.add(item)
        saveNews()

        sender.sendMessage(plugin.messages.text(sender, "news.uploaded", id))

        Bukkit.getOnlinePlayers().forEach { p ->
            val newsChatMsg = plugin.messages.text(p, "news.notification", item.title.ifBlank { plugin.messages.text(p, "news.untitled") }, item.author)
            val newsActionBarMsg = LegacyComponentSerializer.legacySection().deserialize(newsChatMsg)
            when (stocks.getNewsNotificationMode(p.uniqueId)) {
                "chat" -> p.sendMessage(newsChatMsg)
                "actionbar" -> p.sendActionBar(newsActionBarMsg)
                else -> {}
            }
        }
        return true
    }

    private fun handleDeleteNews(sender: CommandSender, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(plugin.messages.text(sender, "common.players-only"))
            return true
        }

        if (args.size != 1) {
            sender.sendMessage(plugin.messages.text(sender, "news.delete-usage"))
            return true
        }

        val id = args[0].toIntOrNull()
        if (id == null) {
            sender.sendMessage(plugin.messages.text(sender, "news.invalid-id"))
            return true
        }

        val item = newsList.find { it.id == id }
        if (item == null) {
            sender.sendMessage(plugin.messages.text(sender, "news.not-found"))
            return true
        }

        if (item.author != sender.name) {
            sender.sendMessage(plugin.messages.text(sender, "news.not-author"))
            return true
        }

        newsList.remove(item)
        saveNews()
        sender.sendMessage(plugin.messages.text(sender, "news.deleted", id))
        return true
    }

    private fun handleCheckNews(sender: CommandSender): Boolean {
        if (newsList.isEmpty()) {
            sender.sendMessage(plugin.messages.text(sender, "news.empty"))
            return true
        }

        sender.sendMessage(plugin.messages.text(sender, "news.list-header"))
        newsList
            .sortedByDescending { it.id }
            .forEach { item ->
                sender.sendMessage(plugin.messages.text(sender, "news.list-item", item.id, item.title.ifBlank { plugin.messages.text(sender, "news.untitled") }, item.author))
                sender.sendMessage(plugin.messages.text(sender, "news.content", item.content))
            }
        return true
    }

    private fun loadNews() {
        if (!newsFile.exists()) {
            return
        }

        val config = YamlConfiguration.loadConfiguration(newsFile)
        lastId = config.getInt("lastId", 0)
        val section = config.getConfigurationSection("news") ?: return

        for (key in section.getKeys(false)) {
            val id = key.toIntOrNull() ?: continue
            val newsSec = section.getConfigurationSection(key) ?: continue

            val item = NewsItem(
                id = id,
                author = newsSec.getString("author") ?: "Unknown",
                title = newsSec.getString("title") ?: "",
                content = newsSec.getString("content") ?: "",
                timestamp = newsSec.getLong("timestamp", 0L)
            )
            newsList.add(item)
        }
    }

    fun saveNews() {
        val config = YamlConfiguration()
        config.set("lastId", lastId)

        for (item in newsList) {
            val path = "news.${item.id}"
            config.set("$path.author", item.author)
            config.set("$path.title", item.title)
            config.set("$path.content", item.content)
            config.set("$path.timestamp", item.timestamp)
        }

        if (!plugin.dataFolder.exists()) {
            plugin.dataFolder.mkdirs()
        }

        config.save(newsFile)
    }
}

