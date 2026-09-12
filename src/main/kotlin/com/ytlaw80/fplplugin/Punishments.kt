package com.ytlaw80.fplplugin

import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerGameModeChangeEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerLoginEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.UUID

data class Punishment(
    val uuid: UUID,
    var name: String,
    var level: Int,
    var untilEpochMs: Long
)

class Punishments(private val plugin: JavaPlugin) : Listener, CommandExecutor, TabCompleter {
    private val dataFile = File(plugin.dataFolder, "punishments.yml")
    private val punishments: MutableMap<UUID, Punishment> = mutableMapOf()
    private var exemptUsersRaw: MutableList<String> = mutableListOf()
    private var exemptUserUuids: Set<UUID> = emptySet()
    private var enforceTask: BukkitTask? = null

    init {
        if (!dataFile.exists() && plugin.getResource("punishments.yml") != null) {
            plugin.saveResource("punishments.yml", false)
        }
        load()
        startEnforceTask()
        applyAllOnline()
    }

    fun shutdown() {
        enforceTask?.cancel()
        enforceTask = null
        save()
    }

    private fun isActive(p: Punishment): Boolean = System.currentTimeMillis() < p.untilEpochMs

    private fun resolveExemptUsers() {
        val resolved = mutableSetOf<UUID>()
        exemptUsersRaw.forEach { raw ->
            val t = raw.trim()
            if (t.isEmpty()) return@forEach
            val uuid = runCatching { UUID.fromString(t) }.getOrNull()
                ?: Bukkit.getOfflinePlayer(t).uniqueId
            resolved.add(uuid)
        }
        exemptUserUuids = resolved
    }

    private fun isExempt(uuid: UUID): Boolean = exemptUserUuids.contains(uuid)

    private fun levelGameMode(level: Int): GameMode? = when (level) {
        2 -> GameMode.ADVENTURE
        3 -> GameMode.SPECTATOR
        else -> null
    }

    private fun startEnforceTask() {
        enforceTask?.cancel()
        enforceTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            cleanupExpired()
            applyAllOnline()
        }, 20L, 20L)
    }

    private fun cleanupExpired() {
        val now = System.currentTimeMillis()
        val expired = punishments.values
            .filter { it.untilEpochMs <= now || isExempt(it.uuid) }
            .map { it.uuid }
        if (expired.isEmpty()) return
        expired.forEach { punishments.remove(it) }
        save()
    }

    private fun applyAllOnline() {
        Bukkit.getOnlinePlayers().forEach { applyToPlayer(it) }
    }

    private fun applyToPlayer(player: Player) {
        if (isExempt(player.uniqueId)) {
            punishments.remove(player.uniqueId)
            return
        }
        val punishment = punishments[player.uniqueId] ?: return
        if (!isActive(punishment)) return

        punishment.name = player.name
        if (player.isOp) player.isOp = false

        when (punishment.level) {
            2, 3 -> {
                val gm = levelGameMode(punishment.level) ?: return
                if (player.gameMode != gm) {
                    player.gameMode = gm
                }
            }
            4 -> {
                player.kickPlayer(plugin.messages.text(player, "punishments.kicked", formatLeft(player, punishment.untilEpochMs)))
            }
        }
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("fpl.punish.admin")) {
            sender.sendMessage(plugin.messages.text(sender, "common.no-permission"))
            return true
        }

        return when (command.name.lowercase()) {
            "punish" -> handlePunish(sender, args)
            "unpunish" -> handleUnpunish(sender, args)
            else -> false
        }
    }

    private fun handlePunish(sender: CommandSender, args: Array<out String>): Boolean {
        if (args.size < 3) {
            sender.sendMessage(plugin.messages.text(sender, "punishments.punish-usage"))
            sender.sendMessage(plugin.messages.text(sender, "punishments.duration-example"))
            return true
        }
        val targetName = args[0]
        val level = args[1].toIntOrNull()
        if (level == null || level !in 1..4) {
            sender.sendMessage(plugin.messages.text(sender, "punishments.invalid-level"))
            return true
        }
        val spec = args.drop(2).joinToString(" ")
        val until = parseUntil(spec)
        if (until == null || until <= System.currentTimeMillis()) {
            sender.sendMessage(plugin.messages.text(sender, "punishments.invalid-duration"))
            return true
        }

        val offline = Bukkit.getOfflinePlayer(targetName)
        val uuid = offline.uniqueId
        if (isExempt(uuid)) {
            sender.sendMessage(plugin.messages.text(sender, "punishments.exempt", targetName))
            return true
        }
        val p = Punishment(uuid, targetName, level, until)
        punishments[uuid] = p
        save()

        Bukkit.getPlayer(uuid)?.let { online ->
            applyToPlayer(online)
            online.sendMessage(plugin.messages.text(online, "punishments.received", level, formatLeft(online, until)))
        }

        sender.sendMessage(plugin.messages.text(sender, "punishments.applied", targetName, level, formatLeft(sender, until)))
        return true
    }

    private fun handleUnpunish(sender: CommandSender, args: Array<out String>): Boolean {
        if (args.size != 1) {
            sender.sendMessage(plugin.messages.text(sender, "punishments.unpunish-usage"))
            return true
        }
        val targetName = args[0]
        val offline = Bukkit.getOfflinePlayer(targetName)
        val uuid = offline.uniqueId

        val removed = punishments.remove(uuid)
        save()

        if (removed == null) {
            sender.sendMessage(plugin.messages.text(sender, "punishments.not-found"))
        } else {
            sender.sendMessage(plugin.messages.text(sender, "punishments.removed", targetName))
            Bukkit.getPlayer(uuid)?.let { recipient -> recipient.sendMessage(plugin.messages.text(recipient, "punishments.released")) }
        }
        return true
    }

    private fun parseUntil(spec: String): Long? {
        val trimmed = spec.trim()
        val now = System.currentTimeMillis()

        val rel = Regex("""^(\d+)\s*(s|m|h|d|w|초|분|시간|일)$""").matchEntire(trimmed)
        if (rel != null) {
            val n = rel.groupValues[1].toLongOrNull() ?: return null
            val u = rel.groupValues[2]
            val millis = when (u) {
                "s", "초" -> n * 1000L
                "m", "분" -> n * 60_000L
                "h", "시간" -> n * 3_600_000L
                "d", "일" -> n * 86_400_000L
                "w" -> n * 604_800_000L
                else -> return null
            }
            return now + millis
        }

        return parseAbsoluteDateTime(trimmed)
    }

    private fun parseAbsoluteDateTime(raw: String): Long? {
        val zone = ZoneId.systemDefault()
        val patterns = listOf("yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm")
        for (p in patterns) {
            try {
                val dt = LocalDateTime.parse(raw, DateTimeFormatter.ofPattern(p))
                return dt.atZone(zone).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
            }
        }
        return runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
    }

    private fun formatLeft(sender: CommandSender, untilEpochMs: Long): String {
        val leftSec = ((untilEpochMs - System.currentTimeMillis()) / 1000L).coerceAtLeast(0)
        val d = leftSec / 86400
        val h = (leftSec % 86400) / 3600
        val m = (leftSec % 3600) / 60
        val s = leftSec % 60
        return when {
            d > 0 -> plugin.messages.text(sender, "time.days-hours", d, h)
            h > 0 -> plugin.messages.text(sender, "time.hours-minutes", h, m)
            m > 0 -> plugin.messages.text(sender, "time.minutes-seconds", m, s)
            else -> plugin.messages.text(sender, "time.seconds", s)
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    fun onGameModeChange(event: PlayerGameModeChangeEvent) {
        if (isExempt(event.player.uniqueId)) return
        val p = punishments[event.player.uniqueId] ?: return
        if (!isActive(p)) return
        val forced = levelGameMode(p.level) ?: return
        if (event.newGameMode != forced) {
            event.isCancelled = true
            Bukkit.getScheduler().runTask(plugin, Runnable { applyToPlayer(event.player) })
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    fun onCommandTry(event: PlayerCommandPreprocessEvent) {
        if (isExempt(event.player.uniqueId)) return
        val p = punishments[event.player.uniqueId] ?: return
        if (!isActive(p)) return
        if (p.level !in setOf(2, 3)) return
        val msg = event.message.lowercase()
        if (msg.startsWith("/gamemode") || msg.startsWith("/gm ")) {
            event.isCancelled = true
            event.player.sendMessage(plugin.messages.text(event.player, "punishments.gamemode-denied"))
            Bukkit.getScheduler().runTask(plugin, Runnable { applyToPlayer(event.player) })
        }
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        cleanupExpired()
        if (isExempt(event.player.uniqueId)) return
        applyToPlayer(event.player)
    }

    @EventHandler
    fun onLogin(event: PlayerLoginEvent) {
        if (isExempt(event.player.uniqueId)) return
        val p = punishments[event.player.uniqueId] ?: return
        if (!isActive(p)) return
        if (p.level == 4) {
            event.disallow(
                PlayerLoginEvent.Result.KICK_BANNED,
                plugin.messages.text(event.player, "punishments.login-denied", formatLeft(event.player, p.untilEpochMs))
            )
        }
    }

    private fun load() {
        if (!dataFile.exists()) return
        val yaml = YamlConfiguration.loadConfiguration(dataFile)
        exemptUsersRaw = yaml.getStringList("exempt_users").toMutableList()
        resolveExemptUsers()

        val sec = yaml.getConfigurationSection("punishments")
        if (sec != null) {
            for (id in sec.getKeys(false)) {
                val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: continue
                if (isExempt(uuid)) continue
                val psec = sec.getConfigurationSection(id) ?: continue
                val name = psec.getString("name") ?: "unknown"
                val level = psec.getInt("level", 1).coerceIn(1, 4)
                val until = psec.getLong("untilEpochMs", 0L)
                punishments[uuid] = Punishment(uuid, name, level, until)
            }
        }
        cleanupExpired()
    }

    private fun save() {
        val yaml = YamlConfiguration()
        yaml.set("exempt_users", exemptUsersRaw.distinct())
        val sec = yaml.createSection("punishments")
        for ((uuid, p) in punishments) {
            if (isExempt(uuid)) continue
            val psec = sec.createSection(uuid.toString())
            psec.set("name", p.name)
            psec.set("level", p.level)
            psec.set("untilEpochMs", p.untilEpochMs)
        }
        if (!plugin.dataFolder.exists()) plugin.dataFolder.mkdirs()
        yaml.save(dataFile)
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): MutableList<String> {
        if (!sender.hasPermission("fpl.punish.admin")) return mutableListOf()
        return when (command.name.lowercase()) {
            "punish" -> when (args.size) {
                1 -> Bukkit.getOnlinePlayers().map { it.name }
                    .filter { it.lowercase().startsWith(args[0].lowercase()) }
                    .sorted().toMutableList()
                2 -> listOf("1", "2", "3", "4").filter { it.startsWith(args[1]) }.toMutableList()
                3 -> listOf("30m", "12h", "7d", "2026-04-10 18:00")
                    .filter { it.startsWith(args[2]) }.toMutableList()
                else -> mutableListOf()
            }
            "unpunish" -> if (args.size == 1) {
                punishments.values.map { it.name }.distinct()
                    .filter { it.lowercase().startsWith(args[0].lowercase()) }
                    .sorted().toMutableList()
            } else mutableListOf()
            else -> mutableListOf()
        }
    }
}

