package com.ytlaw80.fplplugin

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.Locale
import java.util.jar.JarFile

/** Translations are resolved when sending, so broadcasts use each recipient's preference. */
class Languages(private val plugin: JavaPlugin) : CommandExecutor, TabCompleter {
    private val directory = File(plugin.dataFolder, "languages")
    private val preferences = LanguagePreferences(File(plugin.dataFolder, "player_languages.yml"))
    private var catalogs: Map<String, Map<String, String>> = emptyMap()
    private var bundled: Map<String, Map<String, String>> = emptyMap()
    private var defaultLanguage = "ko_kr"

    init {
        reload()
    }

    /** Discover packaged files as well as files added to the running server's language directory. */
    private fun bundledFiles(): List<String> {
        val source = File(plugin.javaClass.protectionDomain.codeSource.location.toURI())
        val names = if (source.isDirectory) {
            File(source, "languages").listFiles()?.map { "languages/${it.name}" }.orEmpty()
        } else {
            JarFile(source).use { jar -> jar.entries().asSequence().map { it.name }.toList() }
        }
        return (names + listOf("languages/ko_kr.yml", "languages/en_us.yml"))
            .filter { it.matches(Regex("languages/[a-z]{2,8}(?:_[a-z0-9]{2,8})?\\.yml")) }
            .distinct().sorted()
    }

    private fun strings(yaml: YamlConfiguration): Map<String, String> =
        yaml.getKeys(true).filter { yaml.isString(it) }.associateWith { yaml.getString(it)!! }

    fun reload() {
        directory.mkdirs()
        val newBundled = bundledFiles().associate { path ->
            val yaml = YamlConfiguration()
            plugin.getResource(path)?.bufferedReader(Charsets.UTF_8)?.use { yaml.load(it) }
                ?: error("Missing language resource: $path")
            if (!File(plugin.dataFolder, path).exists()) plugin.saveResource(path, false)
            File(path).nameWithoutExtension to strings(yaml)
        }
        val newCatalogs = directory.listFiles().orEmpty()
            .filter { it.isFile && it.name.matches(Regex("[a-z]{2,8}(?:_[a-z0-9]{2,8})?\\.yml")) }
            .associate { file ->
                file.nameWithoutExtension to strings(YamlConfiguration().apply { load(file) })
            }
        plugin.reloadConfig()
        val requested = normalize(plugin.config.getString("language.default", "ko_kr")!!)
        val newDefault = requested.takeIf { it in newCatalogs } ?: "ko_kr"
        if (requested != newDefault) plugin.logger.warning("Unknown default language '$requested'; using ko_kr.")
        // Publish only after every file has loaded successfully. A failed reload keeps the old messages.
        bundled = newBundled
        catalogs = newCatalogs
        defaultLanguage = newDefault
    }

    fun language(sender: CommandSender): String =
        (sender as? Player)?.uniqueId?.let { preferences[it] }?.takeIf { it in catalogs } ?: defaultLanguage

    fun text(sender: CommandSender, key: String, vararg args: Any?): String {
        val selected = language(sender)
        return MessageTemplate.resolve(selected, defaultLanguage, key, catalogs, bundled, *args)
    }

    private fun normalize(code: String): String = when (val value = code.lowercase(Locale.ROOT).replace('-', '_')) {
        "ko" -> "ko_kr"
        "en" -> "en_us"
        else -> value
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            sender.sendMessage(text(sender, "language.current", language(sender)))
            catalogs.keys.sorted().forEach { code ->
                val name = catalogs[code]?.get("language.name") ?: code
                sender.sendMessage(messageComponent(text(sender, "language.option", name, code))
                    .clickEvent(ClickEvent.runCommand("/language $code")))
            }
            sender.sendMessage(text(sender, "language.usage"))
            return true
        }
        if (args.size != 1) {
            sender.sendMessage(text(sender, "language.usage"))
            return true
        }
        if (args[0].equals("reload", ignoreCase = true)) {
            if (!sender.hasPermission("fpl.language.reload")) {
                sender.sendMessage(text(sender, "common.no-permission"))
                return true
            }
            try {
                reload()
                sender.sendMessage(text(sender, "language.reloaded"))
            } catch (e: Exception) {
                plugin.logger.warning("Could not reload language files: ${e.message}")
                sender.sendMessage(text(sender, "language.reload-failed"))
            }
            return true
        }
        if (sender !is Player) {
            sender.sendMessage(text(sender, "common.players-only"))
            return true
        }
        val selected = normalize(args[0])
        if (selected !in catalogs) {
            sender.sendMessage(text(sender, "language.unknown", args[0], catalogs.keys.sorted().joinToString(", ")))
            return true
        }
        try {
            preferences.save(sender.uniqueId, selected)
            sender.sendMessage(text(sender, "language.changed", catalogs[selected]?.get("language.name") ?: selected))
        } catch (e: Exception) {
            plugin.logger.warning("Could not save player language: ${e.message}")
            sender.sendMessage(text(sender, "language.save-failed"))
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): MutableList<String> {
        if (args.size != 1) return mutableListOf()
        val options = catalogs.keys + if (sender.hasPermission("fpl.language.reload")) setOf("reload") else emptySet()
        return options.filter { it.startsWith(args[0], ignoreCase = true) }.sorted().toMutableList()
    }
}

internal val JavaPlugin.messages: Languages
    get() = (this as FPLPlugin).languages

/** Preserve legacy colors in translated text and existing click/hover component structure. */
internal fun messageComponent(text: String, color: TextColor? = null): Component {
    val component = LegacyComponentSerializer.legacySection().deserialize(text)
    return if (color == null) component else component.colorIfAbsent(color)
}
