package com.ytlaw80.fplplugin

import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.junit.Test
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** Runs without a Minecraft server. */
class LanguageChecks {
    @Test
    fun verifyLanguages() {
        val catalogs = listOf("ko_kr", "en_us").associateWith { code ->
            val yaml = YamlConfiguration()
            checkNotNull(javaClass.getResourceAsStream("/languages/$code.yml"))
                .bufferedReader(Charsets.UTF_8).use { yaml.load(it) }
            yaml.getKeys(true).filter { yaml.isString(it) }.associateWith { yaml.getString(it)!! }
        }
        val korean = catalogs.getValue("ko_kr")
        val english = catalogs.getValue("en_us")
        check(korean.keys == english.keys) { "Language files have different keys" }
        val token = Regex("\\{(\\d+)\\}")
        korean.forEach { (key, value) ->
            check(token.findAll(value).map { it.value }.toSet() == token.findAll(english.getValue(key)).map { it.value }.toSet()) {
                "Placeholder mismatch: $key"
            }
        }
        val source = File(System.getProperty("fpl.sourceDir")).walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        val references = Regex("(?:plugin\\.messages\\.)?text\\([^,\\n]+, \"([a-z][a-z.-]+)\"")
            .findAll(source).map { it.groupValues[1] }.toSet() +
            Regex("\"(help\\.[a-z]+\\.(?:description|usage))\"").findAll(source).map { it.groupValues[1] }.toSet()
        check(references.all { it in korean }) { "Missing keys: ${references - korean.keys}" }

        // Reordered/repeated arguments, literal user content, and missing/large tokens.
        check(MessageTemplate.render("{1} / {0} / {1}", "Alice", 42) == "42 / Alice / 42")
        check(MessageTemplate.render("{0} {1}", "User {1} $5 \\ text", "Bob") == "User {1} $5 \\ text Bob")
        check(MessageTemplate.render("{2} {99999999999999999999}", "Alice") == "{2} {99999999999999999999}")

        // Two recipients receive different languages from the same notification key.
        val koMessage = MessageTemplate.resolve("ko_kr", "ko_kr", "news.notification", catalogs, catalogs, "News {0}", "Alice")
        val enMessage = MessageTemplate.resolve("en_us", "ko_kr", "news.notification", catalogs, catalogs, "News {0}", "Alice")
        check(koMessage.contains("[뉴스]") && enMessage.contains("[News]"))
        check(koMessage.contains("News {0}") && enMessage.contains("News {0}"))
        val overrides = mapOf("en_us" to mapOf("news.notification" to "Custom {1}: {0}"), "ja_jp" to emptyMap(),
            "ko_kr" to mapOf("news.empty" to "Default override"))
        check(MessageTemplate.resolve("en_us", "ko_kr", "news.notification", overrides, catalogs, "Title", "Alice") == "Custom Alice: Title")
        check(MessageTemplate.resolve("en_us", "ko_kr", "news.empty", overrides, catalogs) == english["news.empty"])
        check(MessageTemplate.resolve("ja_jp", "ko_kr", "news.empty", overrides, catalogs) == "Default override")
        check(MessageTemplate.resolve("ja_jp", "en_us", "news.empty", overrides, catalogs) == english["news.empty"])
        check(MessageTemplate.resolve("ja_jp", "xx", "news.empty", emptyMap(), catalogs) == korean["news.empty"])
        check(MessageTemplate.resolve("xx", "xx", "missing.key", emptyMap(), catalogs) == "missing.key")

        val temporary = Files.createTempDirectory("fpl-language-checks").toFile()
        try {
            val file = File(temporary, "player_languages.yml")
            val first = UUID.randomUUID()
            val second = UUID.randomUUID()
            val store = LanguagePreferences(file)
            check(store[first] == null)
            store.save(first, "en_us")
            store.save(second, "ko_kr")
            val restarted = LanguagePreferences(file)
            check(restarted[first] == "en_us" && restarted[second] == "ko_kr")
            restarted.save(first, "ja_jp")
            check(LanguagePreferences(file)[first] == "ja_jp")
            // A failed write must not claim to have changed the active selection.
            check(file.delete())
            check(file.mkdir())
            File(file, "occupied").writeText("block replacement")
            check(runCatching { restarted.save(first, "en_us") }.isFailure)
            check(restarted[first] == "ja_jp")
            check(temporary.listFiles()!!.none { it.name.endsWith(".tmp") })
        } finally {
            temporary.deleteRecursively()
        }

        val click = ClickEvent.runCommand("/language en_us")
        val component = messageComponent("§aEnglish", NamedTextColor.GRAY)
            .clickEvent(click).hoverEvent(HoverEvent.showText(messageComponent("§eSelect")))
        check(component.clickEvent() == click && component.hoverEvent() != null)
        check(LegacyComponentSerializer.legacySection().serialize(component) == "§aEnglish")
        check(messageComponent("Plain", NamedTextColor.GRAY).color() == NamedTextColor.GRAY)
        println("Language checks passed: ${korean.size} keys, placeholders, fallback, per-recipient text, persistence, and components.")
    }
}
