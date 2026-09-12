package com.ytlaw80.fplplugin

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Save before changing the active preference, so a failed write leaves the old selection intact. */
internal class LanguagePreferences(private val file: File) {
    private val selections = mutableMapOf<UUID, String>()

    init {
        if (file.exists()) {
            val yaml = YamlConfiguration().apply { load(file) }
            yaml.getConfigurationSection("players")?.getKeys(false)?.forEach { id ->
                val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: return@forEach
                yaml.getString("players.$id")?.let { selections[uuid] = it }
            }
        }
    }

    operator fun get(uuid: UUID): String? = selections[uuid]

    fun save(uuid: UUID, language: String) {
        val yaml = YamlConfiguration()
        (selections + (uuid to language)).forEach { (id, code) -> yaml.set("players.$id", code) }
        file.parentFile.mkdirs()
        val temporary = Files.createTempFile(file.parentFile.toPath(), "player_languages-", ".tmp")
        try {
            yaml.save(temporary.toFile())
            try {
                Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            selections[uuid] = language
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

/** Only substitute template tokens, never recursively substitute text supplied as an argument. */
internal object MessageTemplate {
    fun resolve(
        selected: String,
        defaultLanguage: String,
        key: String,
        catalogs: Map<String, Map<String, String>>,
        bundled: Map<String, Map<String, String>>,
        vararg args: Any?
    ): String {
        val template = catalogs[selected]?.get(key) ?: bundled[selected]?.get(key)
            ?: catalogs[defaultLanguage]?.get(key) ?: bundled[defaultLanguage]?.get(key)
            ?: bundled["ko_kr"]?.get(key) ?: key
        return render(template, *args)
    }

    private val placeholder = Regex("\\{(\\d+)\\}")
    fun render(template: String, vararg args: Any?): String = placeholder.replace(template) { match ->
        val index = match.groupValues[1].toIntOrNull()
        if (index != null && index in args.indices) args[index]?.toString().orEmpty() else match.value
    }
}

