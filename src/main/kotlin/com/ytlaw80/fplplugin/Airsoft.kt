package com.ytlaw80.fplplugin

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.entity.Interaction
import org.bukkit.entity.Item
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerGameModeChangeEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitTask
import org.bukkit.scoreboard.Criteria
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Objective
import org.bukkit.scoreboard.Scoreboard
import org.bukkit.scoreboard.Team
import java.io.File
import java.util.Locale
import java.util.UUID

enum class AirsoftTeam(val id: String, val competitive: Boolean) {
    RED("red", true),
    BLUE("blue", true),
    GREEN("green", true),
    YELLOW("yellow", true),
    UNASSIGNED("unassigned", false),
    SPECTATOR("spectator", false);

    companion object {
        fun from(input: String): AirsoftTeam? = when (input.lowercase()) {
            "red", "r", "레드", "빨강" -> RED
            "blue", "b", "블루", "파랑" -> BLUE
            "green", "g", "그린", "초록" -> GREEN
            "yellow", "y", "옐로", "노랑" -> YELLOW
            "unassigned", "none", "미참가", "미참가자" -> UNASSIGNED
            "spectator", "spec", "관전자", "관전" -> SPECTATOR
            else -> null
        }
    }
}

enum class AirsoftMode { TDM, CTF }

private enum class AirsoftMapType(val id: String, val teams: List<AirsoftTeam>) {
    TWO_TEAMS("2teams", listOf(AirsoftTeam.RED, AirsoftTeam.BLUE)),
    FOUR_TEAMS("4teams", listOf(AirsoftTeam.RED, AirsoftTeam.BLUE, AirsoftTeam.GREEN, AirsoftTeam.YELLOW));

    companion object {
        fun from(input: String?): AirsoftMapType? = entries.firstOrNull { it.id.equals(input, true) }
    }
}

private data class AirsoftBoard(
    val scoreboard: Scoreboard,
    val teams: Map<AirsoftTeam, Team>,
    val tdmObjective: Objective,
    val ctfObjective: Objective
)

private data class AirsoftMap(
    val spawns: MutableMap<AirsoftTeam, Location> = mutableMapOf(),
    var flag: Location? = null,
    var type: AirsoftMapType = AirsoftMapType.FOUR_TEAMS
)

/**
 * Scoreboard based team deathmatch state.
 *
 * Airsoft weapons that eliminate players without a vanilla death can call
 * [recordElimination] directly. Vanilla player deaths are handled as a fallback.
 */
class Airsoft(private val plugin: JavaPlugin) : CommandExecutor, TabCompleter, Listener {
    private val dataFile = File(plugin.dataFolder, "airsoft.yml")
    private val flagKey = NamespacedKey(plugin, "ctf_flag")
    private val interactionKey = NamespacedKey(plugin, "ctf_flag_interaction")
    private val boards = mutableMapOf<UUID, AirsoftBoard>()
    private val participants = mutableMapOf<UUID, AirsoftTeam>()
    private val previousScoreboards = mutableMapOf<UUID, Scoreboard>()
    private val previousGameModes = mutableMapOf<UUID, GameMode>()
    private val matchGameModes = mutableMapOf<UUID, GameMode>()
    private val scores = AirsoftTeam.entries.associateWith { 0 }.toMutableMap()
    private val maps = linkedMapOf(DEFAULT_MAP to AirsoftMap())
    var activeMapName: String = DEFAULT_MAP
        private set
    private val activeMap: AirsoftMap
        get() = maps.getValue(activeMapName)
    private val spawnLocations: MutableMap<AirsoftTeam, Location>
        get() = activeMap.spawns
    private var flagLocation: Location?
        get() = activeMap.flag
        set(value) { activeMap.flag = value }
    private var flagInteraction: Interaction? = null
    private var flagCarrier: UUID? = null
    private var droppedFlag: Item? = null
    private var flagReturnTask: BukkitTask? = null

    var mode: AirsoftMode = AirsoftMode.TDM
        private set

    var isEnabled: Boolean = true
        private set

    var isRunning: Boolean = false
        private set
    var targetScore: Int = DEFAULT_TARGET_SCORE
        private set

    init {
        loadData()
        targetScore = defaultTargetScore()
        if (isEnabled) restoreFlagAtBase()
        Bukkit.getOnlinePlayers().forEach { player ->
            removeFlagFromInventory(player)
            if (isEnabled) moveToTeam(player, AirsoftTeam.UNASSIGNED)
        }
        updateScoreboard()
    }

    fun teamOf(player: Player): AirsoftTeam? = participants[player.uniqueId]

    fun scoreOf(team: AirsoftTeam): Int = scores.getValue(team)

    fun spawnOf(team: AirsoftTeam): Location? = if (team in competitiveTeams()) spawnLocations[team]?.clone() else null

    fun teleportToTeamSpawn(player: Player): Boolean {
        val team = teamOf(player)?.takeIf { it.competitive } ?: return false
        val spawn = spawnOf(team) ?: return false
        return player.teleport(spawn)
    }

    /** Returns true only when this elimination was accepted and scored. */
    fun recordElimination(killer: Player, victim: Player): Boolean {
        if (!isRunning || mode != AirsoftMode.TDM || killer.uniqueId == victim.uniqueId) return false
        val killerTeam = teamOf(killer) ?: return false
        val victimTeam = teamOf(victim) ?: return false
        if (killerTeam !in competitiveTeams() || victimTeam !in competitiveTeams() || killerTeam == victimTeam) return false

        val newScore = scoreOf(killerTeam) + 1
        scores[killerTeam] = newScore
        updateScoreboard()
        broadcast("airsoft.elimination", killer.name, victim.name, teamName(killerTeam), newScore, targetScore)

        if (newScore >= targetScore) finish(killerTeam)
        return true
    }

    fun shutdown() {
        isRunning = false
        restoreMatchGameModes()
        resetFlag()
        hideFlagAtBase()
        participants.keys.toList().forEach { removeParticipant(it, true) }
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            sendHelp(sender)
            return true
        }

        when (args[0].lowercase()) {
            "on" -> { setEnabled(sender, true); return true }
            "off" -> { setEnabled(sender, false); return true }
        }
        if (!isEnabled) {
            sender.sendMessage(plugin.messages.text(sender, "airsoft.disabled"))
            return true
        }

        when (args[0].lowercase()) {
            "join", "참가" -> join(sender, args.getOrNull(1))
            "leave", "퇴장" -> leave(sender)
            "start", "시작" -> start(sender, args.getOrNull(1))
            "stop", "중지" -> stop(sender)
            "switchteam", "팀변경" -> switchTeam(sender, args.getOrNull(1), args.getOrNull(2))
            "spawn", "스폰" -> setSpawn(sender, args)
            "setflag", "깃발설정" -> setFlag(sender, args)
            "mode", "모드" -> setMode(sender, args.getOrNull(1))
            "map", "맵" -> manageMap(sender, args)
            "score", "점수", "status", "상태" -> sendStatus(sender)
            else -> sendHelp(sender)
        }
        return true
    }

    private fun setEnabled(sender: CommandSender, enabled: Boolean) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(plugin.messages.text(sender, "common.no-permission"))
            return
        }
        if (isEnabled == enabled) {
            sender.sendMessage(plugin.messages.text(sender, if (enabled) "airsoft.already-enabled" else "airsoft.already-disabled"))
            return
        }

        if (enabled) {
            isEnabled = true
            restoreFlagAtBase()
            Bukkit.getOnlinePlayers().forEach { player ->
                removeFlagFromInventory(player)
                moveToTeam(player, AirsoftTeam.UNASSIGNED)
            }
            updateScoreboard()
        } else {
            isRunning = false
            restoreMatchGameModes()
            resetFlag()
            hideFlagAtBase()
            isEnabled = false
            participants.keys.toList().forEach { removeParticipant(it, true) }
        }
        saveData()
        sender.sendMessage(plugin.messages.text(sender, if (enabled) "airsoft.enabled" else "airsoft.disabled-confirmation"))
    }

    private fun join(sender: CommandSender, requestedTeam: String?) {
        val player = sender as? Player ?: run {
            sender.sendMessage(plugin.messages.text(sender, "common.players-only"))
            return
        }
        if ((plugin as? FPLPlugin)?.tagGame?.isParticipant(player) == true) {
            player.sendMessage(plugin.messages.text(player, "tag.leave-first"))
            return
        }
        val team = when {
            requestedTeam == null -> smallerTeam()
            else -> AirsoftTeam.from(requestedTeam) ?: run {
                player.sendMessage(plugin.messages.text(player, "airsoft.unknown-team"))
                return
            }
        }
        if (team.competitive && team !in competitiveTeams()) {
            player.sendMessage(plugin.messages.text(player, "airsoft.team-not-in-map", activeMap.type.id))
            return
        }
        if (isRunning && team.competitive) {
            player.sendMessage(plugin.messages.text(player, "airsoft.already-running"))
            return
        }
        moveToTeam(player, team)
        broadcast("airsoft.joined", player.name, teamName(team))
    }

    private fun leave(sender: CommandSender) {
        val player = sender as? Player ?: run {
            sender.sendMessage(plugin.messages.text(sender, "common.players-only"))
            return
        }
        if (teamOf(player) == null || teamOf(player) == AirsoftTeam.UNASSIGNED) {
            player.sendMessage(plugin.messages.text(player, "airsoft.not-participating"))
            return
        }
        val name = player.name
        moveToTeam(player, AirsoftTeam.UNASSIGNED)
        broadcast("airsoft.left", name)
        checkForfeit()
    }

    private fun start(sender: CommandSender, targetArgument: String?) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(plugin.messages.text(sender, "common.no-permission"))
            return
        }
        if (isRunning) {
            sender.sendMessage(plugin.messages.text(sender, "airsoft.already-running"))
            return
        }
        val parsedTarget = targetArgument?.toIntOrNull()
        if (targetArgument != null && parsedTarget == null) {
            sender.sendMessage(plugin.messages.text(sender, "airsoft.invalid-target", MAX_TARGET_SCORE))
            return
        }
        val requestedTarget = parsedTarget ?: defaultTargetScore()
        if (requestedTarget !in 1..MAX_TARGET_SCORE) {
            sender.sendMessage(plugin.messages.text(sender, "airsoft.invalid-target", MAX_TARGET_SCORE))
            return
        }
        if (competitiveTeams().any { teamSize(it) == 0 }) {
            sender.sendMessage(plugin.messages.text(sender, "airsoft.need-map-teams", activeMap.type.id, competitiveTeams().joinToString(", ") { it.name }))
            return
        }
        if (mode == AirsoftMode.CTF && flagLocation == null) {
            sender.sendMessage(plugin.messages.text(sender, "airsoft.flag-not-set"))
            return
        }
        if (mode == AirsoftMode.CTF && flagLocation?.block?.type != Material.WHITE_BANNER) {
            sender.sendMessage(plugin.messages.text(sender, "airsoft.flag-unavailable"))
            return
        }
        if (mode == AirsoftMode.CTF) {
            val missingSpawn = competitiveTeams().firstOrNull { teamSize(it) > 0 && spawnOf(it) == null }
            if (missingSpawn != null) {
                sender.sendMessage(plugin.messages.text(sender, "airsoft.ctf-spawn-missing", teamName(missingSpawn)))
                return
            }
        }

        targetScore = requestedTarget
        scores.keys.forEach { scores[it] = 0 }
        isRunning = true
        participants.forEach { (id, team) ->
            if (team.competitive) Bukkit.getPlayer(id)?.let(::setMatchGameMode)
        }
        if (mode == AirsoftMode.CTF) resetFlag()
        updateScoreboard()
        participants.keys.mapNotNull(Bukkit::getPlayer).forEach { teleportToTeamSpawn(it) }
        if (mode == AirsoftMode.CTF) broadcast("airsoft.ctf-started", targetScore)
        else broadcast("airsoft.started", targetScore)
    }

    private fun stop(sender: CommandSender) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(plugin.messages.text(sender, "common.no-permission"))
            return
        }
        if (!isRunning) {
            sender.sendMessage(plugin.messages.text(sender, "airsoft.not-running"))
            return
        }
        isRunning = false
        restoreMatchGameModes()
        resetFlag()
        broadcast("airsoft.stopped")
        updateScoreboard()
    }

    private fun switchTeam(sender: CommandSender, playerName: String?, teamArgument: String?) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(plugin.messages.text(sender, "common.no-permission"))
            return
        }
        if (playerName == null || teamArgument == null) {
            sender.sendMessage(plugin.messages.text(sender, "airsoft.switchteam-usage"))
            return
        }
        val target = Bukkit.getPlayerExact(playerName)
        if (target == null) {
            sender.sendMessage(plugin.messages.text(sender, "airsoft.player-not-found", playerName))
            return
        }
        val oldTeam = teamOf(target) ?: AirsoftTeam.UNASSIGNED
        val newTeam = AirsoftTeam.from(teamArgument)
        if (newTeam == null) {
            sender.sendMessage(plugin.messages.text(sender, "airsoft.unknown-team"))
            return
        }
        if (newTeam != AirsoftTeam.UNASSIGNED && (plugin as? FPLPlugin)?.tagGame?.isParticipant(target) == true) {
            sender.sendMessage(plugin.messages.text(sender, "tag.leave-first"))
            return
        }
        if (newTeam.competitive && newTeam !in competitiveTeams()) {
            sender.sendMessage(plugin.messages.text(sender, "airsoft.team-not-in-map", activeMap.type.id))
            return
        }
        if (oldTeam == newTeam) {
            sender.sendMessage(plugin.messages.text(sender, "airsoft.already-on-team", target.name, teamName(newTeam)))
            return
        }

        moveToTeam(target, newTeam)
        broadcast("airsoft.team-switched", target.name, teamName(oldTeam), teamName(newTeam))
        if (isRunning && newTeam.competitive) teleportToTeamSpawn(target)
        checkForfeit()
    }

    private fun setSpawn(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(plugin.messages.text(sender, "common.no-permission"))
            return
        }
        val player = sender as? Player ?: run {
            sender.sendMessage(plugin.messages.text(sender, "common.players-only"))
            return
        }
        if (args.size != 5) {
            sender.sendMessage(plugin.messages.text(sender, "airsoft.spawn-usage"))
            return
        }
        val team = AirsoftTeam.from(args[1])
        if (team == null || team !in competitiveTeams()) {
            sender.sendMessage(plugin.messages.text(sender, "airsoft.spawn-team-invalid", competitiveTeams().joinToString(", ") { it.id }))
            return
        }
        val origin = player.location
        val x = parseCoordinate(args[2], origin.x)
        val y = parseCoordinate(args[3], origin.y)
        val z = parseCoordinate(args[4], origin.z)
        if (x == null || y == null || z == null || !x.isFinite() || !y.isFinite() || !z.isFinite()) {
            sender.sendMessage(plugin.messages.text(sender, "airsoft.invalid-coordinates"))
            return
        }

        val location = Location(player.world, x, y, z, origin.yaw, origin.pitch)
        spawnLocations[team] = location
        saveData()
        sender.sendMessage(
            plugin.messages.text(
                sender,
                "airsoft.spawn-set",
                teamName(team),
                player.world.name,
                formatCoordinate(x),
                formatCoordinate(y),
                formatCoordinate(z),
                activeMapName
            )
        )
    }

    private fun parseCoordinate(input: String, origin: Double): Double? {
        if (!input.startsWith("~")) return input.toDoubleOrNull()
        val offset = input.drop(1)
        return if (offset.isEmpty()) origin else offset.toDoubleOrNull()?.let(origin::plus)
    }

    private fun formatCoordinate(value: Double): String = "%.2f".format(java.util.Locale.US, value)

    private fun setMode(sender: CommandSender, argument: String?) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(plugin.messages.text(sender, "common.no-permission"))
            return
        }
        if (isRunning) {
            sender.sendMessage(plugin.messages.text(sender, "airsoft.already-running"))
            return
        }
        val selected = when (argument?.lowercase()) {
            "tdm" -> AirsoftMode.TDM
            "ctf" -> AirsoftMode.CTF
            else -> {
                sender.sendMessage(plugin.messages.text(sender, "airsoft.mode-usage"))
                return
            }
        }
        mode = selected
        targetScore = defaultTargetScore()
        scores.keys.forEach { scores[it] = 0 }
        saveData()
        updateScoreboard()
        sender.sendMessage(plugin.messages.text(sender, "airsoft.mode-set", selected.name))
    }

    private fun manageMap(sender: CommandSender, args: Array<out String>) {
        if (args.size == 1 || (args.size == 2 && args[1].equals("list", true))) {
            val names = maps.entries.sortedBy { it.key }.joinToString(", ") { (name, map) -> "$name (${map.type.id})" }
            sender.sendMessage(plugin.messages.text(sender, "airsoft.map-list", activeMapName, names))
            return
        }
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(plugin.messages.text(sender, "common.no-permission"))
            return
        }
        if (args.size !in 2..3) {
            sender.sendMessage(plugin.messages.text(sender, "airsoft.map-usage"))
            return
        }
        if (isRunning) {
            sender.sendMessage(plugin.messages.text(sender, "airsoft.already-running"))
            return
        }
        val name = args[1].lowercase(Locale.ROOT)
        if (!MAP_NAME_PATTERN.matches(name) || name == "list") {
            sender.sendMessage(plugin.messages.text(sender, "airsoft.map-invalid"))
            return
        }
        val type = if (args.size == 3) AirsoftMapType.from(args[2]) else maps[name]?.type ?: AirsoftMapType.FOUR_TEAMS
        if (type == null) {
            sender.sendMessage(plugin.messages.text(sender, "airsoft.map-usage"))
            return
        }
        val changed = name != activeMapName || type != activeMap.type
        if (name != activeMapName) {
            resetFlag()
            hideFlagAtBase()
            maps.putIfAbsent(name, AirsoftMap())
            activeMapName = name
            restoreFlagAtBase()
        }
        activeMap.type = type
        if (changed) {
            participants.keys.mapNotNull(Bukkit::getPlayer).forEach { player ->
                if (teamOf(player)?.competitive == true && teamOf(player) !in competitiveTeams()) {
                    moveToTeam(player, AirsoftTeam.UNASSIGNED)
                    player.sendMessage(plugin.messages.text(player, "airsoft.team-removed-from-map", activeMapName, type.id))
                }
            }
            scores.keys.forEach { scores[it] = 0 }
            updateScoreboard()
            saveData()
        }
        sender.sendMessage(plugin.messages.text(sender, "airsoft.map-selected", activeMapName, type.id))
    }

    private fun setFlag(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(plugin.messages.text(sender, "common.no-permission"))
            return
        }
        val player = sender as? Player ?: run {
            sender.sendMessage(plugin.messages.text(sender, "common.players-only"))
            return
        }
        if (args.size != 4) {
            sender.sendMessage(plugin.messages.text(sender, "airsoft.setflag-usage"))
            return
        }
        if (isRunning) {
            sender.sendMessage(plugin.messages.text(sender, "airsoft.already-running"))
            return
        }
        val origin = player.location
        val x = parseCoordinate(args[1], origin.x)
        val y = parseCoordinate(args[2], origin.y)
        val z = parseCoordinate(args[3], origin.z)
        if (x == null || y == null || z == null || !x.isFinite() || !y.isFinite() || !z.isFinite()) {
            sender.sendMessage(plugin.messages.text(sender, "airsoft.invalid-coordinates"))
            return
        }
        val newLocation = Location(player.world, x, y, z).block.location
        val newBlock = newLocation.block
        val otherMap = maps.entries.firstOrNull { (name, map) -> name != activeMapName && map.flag == newLocation }
        if (otherMap != null) {
            sender.sendMessage(plugin.messages.text(sender, "airsoft.flag-used-by-other-map", otherMap.key))
            return
        }
        if (!newBlock.type.isAir && newLocation != flagLocation) {
            sender.sendMessage(plugin.messages.text(sender, "airsoft.flag-space-occupied"))
            return
        }
        if (!newBlock.getRelative(0, -1, 0).type.isSolid) {
            sender.sendMessage(plugin.messages.text(sender, "airsoft.flag-needs-support"))
            return
        }
        resetFlag()
        val oldLocation = flagLocation
        flagInteraction?.remove()
        flagInteraction = null
        if (oldLocation != null && oldLocation != newLocation && oldLocation.block.type == Material.WHITE_BANNER) {
            oldLocation.block.type = Material.AIR
        }
        flagLocation = newLocation
        restoreFlagAtBase()
        saveData()
        sender.sendMessage(plugin.messages.text(sender, "airsoft.flag-set", player.world.name, newLocation.blockX, newLocation.blockY, newLocation.blockZ, activeMapName))
    }

    private fun flagItem(): ItemStack = ItemStack(Material.WHITE_BANNER).apply {
        editMeta { meta ->
            meta.displayName(Component.text("CTF Flag", NamedTextColor.GOLD))
            meta.persistentDataContainer.set(flagKey, PersistentDataType.BYTE, 1.toByte())
        }
    }

    private fun isFlag(item: ItemStack?): Boolean = item?.itemMeta?.persistentDataContainer
        ?.has(flagKey, PersistentDataType.BYTE) == true

    private fun removeFlagFromInventory(player: Player) {
        player.inventory.contents.forEachIndexed { index, item ->
            if (isFlag(item)) player.inventory.setItem(index, null)
        }
        if (isFlag(player.inventory.itemInOffHand)) player.inventory.setItemInOffHand(ItemStack(Material.AIR))
    }

    private fun restoreFlagAtBase() {
        val base = flagLocation ?: return
        if (base.block.type.isAir) base.block.type = Material.WHITE_BANNER
        if (base.block.type != Material.WHITE_BANNER) return
        val center = base.clone().add(0.5, 0.0, 0.5)
        base.world.getNearbyEntities(center, 1.0, 2.0, 1.0)
            .filterIsInstance<Interaction>()
            .filter { it.persistentDataContainer.has(interactionKey, PersistentDataType.BYTE) }
            .forEach { it.remove() }
        flagInteraction = base.world.spawn(center, Interaction::class.java) { interaction ->
            interaction.interactionWidth = 1.4f
            interaction.interactionHeight = 2.2f
            interaction.isResponsive = true
            interaction.persistentDataContainer.set(interactionKey, PersistentDataType.BYTE, 1.toByte())
        }
    }

    private fun hideFlagAtBase() {
        flagInteraction?.remove()
        flagInteraction = null
        flagLocation?.block?.takeIf { it.type == Material.WHITE_BANNER }?.let { it.type = Material.AIR }
    }

    private fun takeFlag(player: Player) {
        if (!isRunning || mode != AirsoftMode.CTF || flagCarrier != null || droppedFlag != null) return
        if (teamOf(player) !in competitiveTeams() || player.inventory.firstEmpty() == -1) {
            player.sendMessage(plugin.messages.text(player, "airsoft.flag-inventory-full"))
            return
        }
        hideFlagAtBase()
        flagCarrier = player.uniqueId
        player.inventory.addItem(flagItem())
        broadcast("airsoft.flag-taken", player.name)
    }

    private fun dropFlag(location: Location) {
        val carrierId = flagCarrier ?: return
        Bukkit.getPlayer(carrierId)?.let(::removeFlagFromInventory)
        flagCarrier = null
        val item = location.world.dropItemNaturally(location, flagItem())
        item.pickupDelay = 0
        droppedFlag = item
        broadcast("airsoft.flag-dropped")
        flagReturnTask?.cancel()
        flagReturnTask = Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (droppedFlag?.uniqueId == item.uniqueId) {
                resetFlag()
                broadcast("airsoft.flag-returned")
            }
        }, 100L)
    }

    private fun resetFlag() {
        flagReturnTask?.cancel()
        flagReturnTask = null
        flagCarrier?.let { Bukkit.getPlayer(it)?.let(::removeFlagFromInventory) }
        flagCarrier = null
        droppedFlag?.remove()
        droppedFlag = null
        restoreFlagAtBase()
    }

    private fun loadData() {
        if (!dataFile.exists()) return
        val yaml = YamlConfiguration.loadConfiguration(dataFile)
        isEnabled = yaml.getBoolean("enabled", true)
        mode = runCatching { AirsoftMode.valueOf(yaml.getString("mode", "TDM")!!.uppercase()) }.getOrDefault(AirsoftMode.TDM)
        val mapSection = yaml.getConfigurationSection("maps")
        if (mapSection == null) {
            flagLocation = yaml.getLocation("flag")
            allCompetitiveTeams().forEach { team ->
                yaml.getLocation("spawns.${team.id}")?.let { spawnLocations[team] = it }
            }
            if (yaml.contains("flag") || yaml.contains("spawns")) saveData()
            return
        }
        mapSection.getKeys(false).forEach { name ->
            if (!MAP_NAME_PATTERN.matches(name) || name == "list") return@forEach
            val map = AirsoftMap(
                flag = yaml.getLocation("maps.$name.flag"),
                type = AirsoftMapType.from(yaml.getString("maps.$name.type")) ?: AirsoftMapType.FOUR_TEAMS
            )
            allCompetitiveTeams().forEach { team ->
                yaml.getLocation("maps.$name.spawns.${team.id}")?.let { map.spawns[team] = it }
            }
            maps[name] = map
        }
        activeMapName = yaml.getString("active-map")?.takeIf { maps.containsKey(it) } ?: DEFAULT_MAP
    }

    private fun saveData() {
        val yaml = YamlConfiguration()
        yaml.set("enabled", isEnabled)
        yaml.set("mode", mode.name.lowercase())
        yaml.set("active-map", activeMapName)
        maps.forEach { (name, map) ->
            yaml.createSection("maps.$name")
            yaml.set("maps.$name.type", map.type.id)
            yaml.set("maps.$name.flag", map.flag)
            allCompetitiveTeams().forEach { team -> yaml.set("maps.$name.spawns.${team.id}", map.spawns[team]) }
        }
        if (!plugin.dataFolder.exists()) plugin.dataFolder.mkdirs()
        yaml.save(dataFile)
    }

    private fun finish(winner: AirsoftTeam) {
        isRunning = false
        restoreMatchGameModes()
        if (mode == AirsoftMode.CTF) {
            resetFlag()
            broadcast("airsoft.ctf-winner", teamName(winner))
        } else {
            if (activeMap.type == AirsoftMapType.TWO_TEAMS) {
                broadcast("airsoft.winner-two-teams", teamName(winner), scoreOf(AirsoftTeam.RED), scoreOf(AirsoftTeam.BLUE))
            } else {
                broadcast(
                    "airsoft.winner",
                    teamName(winner),
                    scoreOf(AirsoftTeam.RED),
                    scoreOf(AirsoftTeam.BLUE),
                    scoreOf(AirsoftTeam.GREEN),
                    scoreOf(AirsoftTeam.YELLOW)
                )
            }
        }
        updateScoreboard()
    }

    private fun checkForfeit() {
        if (!isRunning) return
        val remaining = competitiveTeams().filter { teamSize(it) > 0 }
        if (remaining.size == 1) finish(remaining.single())
    }

    private fun moveToTeam(player: Player, newTeam: AirsoftTeam) {
        val oldTeam = participants[player.uniqueId]
        if (oldTeam == null) previousScoreboards[player.uniqueId] = player.scoreboard
        if (oldTeam != newTeam && flagCarrier == player.uniqueId) dropFlag(player.location)

        if (oldTeam?.competitive == true && !newTeam.competitive) restoreMatchGameMode(player)
        participants[player.uniqueId] = newTeam
        if (oldTeam == AirsoftTeam.SPECTATOR && newTeam != AirsoftTeam.SPECTATOR) {
            player.gameMode = previousGameModes.remove(player.uniqueId) ?: GameMode.SURVIVAL
        }
        if (oldTeam != AirsoftTeam.SPECTATOR && newTeam == AirsoftTeam.SPECTATOR) {
            previousGameModes[player.uniqueId] = player.gameMode
        }
        if (isRunning && newTeam.competitive && oldTeam?.competitive != true) setMatchGameMode(player)

        boards.values.forEach { board ->
            board.teams.values.forEach { it.removeEntry(player.name) }
            board.teams.getValue(newTeam).addEntry(player.name)
        }
        val personalBoard = boards.getOrPut(player.uniqueId) { createBoard(player) }
        player.scoreboard = personalBoard.scoreboard
        if (newTeam == AirsoftTeam.SPECTATOR) player.gameMode = GameMode.SPECTATOR
        updateScoreboard()
        (plugin as? FPLPlugin)?.refreshTagNameTags()
    }

    private fun setMatchGameMode(player: Player) {
        matchGameModes.putIfAbsent(player.uniqueId, player.gameMode)
        player.gameMode = GameMode.ADVENTURE
    }

    private fun restoreMatchGameMode(player: Player) {
        matchGameModes.remove(player.uniqueId)?.let { player.gameMode = it }
    }

    private fun restoreMatchGameModes() {
        matchGameModes.forEach { (id, previous) -> Bukkit.getPlayer(id)?.gameMode = previous }
        matchGameModes.clear()
    }

    private fun removeParticipant(uuid: UUID, restoreScoreboard: Boolean, knownPlayer: Player? = null) {
        val player = knownPlayer ?: Bukkit.getPlayer(uuid)
        val oldTeam = participants.remove(uuid)
        player?.name?.let { name ->
            boards.values.forEach { board -> board.teams.values.forEach { it.removeEntry(name) } }
        }
        boards.remove(uuid)
        if (player != null) restoreMatchGameMode(player) else matchGameModes.remove(uuid)
        if (oldTeam == AirsoftTeam.SPECTATOR && player != null) {
            player.gameMode = previousGameModes[uuid] ?: GameMode.SURVIVAL
        }
        previousGameModes.remove(uuid)
        val oldScoreboard = previousScoreboards.remove(uuid)
        if (restoreScoreboard && player != null && oldScoreboard != null) player.scoreboard = oldScoreboard
        updateScoreboard()
    }

    private fun smallerTeam(): AirsoftTeam {
        return competitiveTeams().minBy(::teamSize)
    }

    private fun defaultTargetScore(): Int = if (mode == AirsoftMode.CTF) DEFAULT_CTF_TARGET_SCORE else DEFAULT_TARGET_SCORE

    private fun competitiveTeams(): List<AirsoftTeam> = activeMap.type.teams

    private fun allCompetitiveTeams(): List<AirsoftTeam> = AirsoftMapType.FOUR_TEAMS.teams

    private fun teamSize(team: AirsoftTeam): Int = participants.values.count { it == team }

    private fun teamName(team: AirsoftTeam): String = team.name

    private fun createBoard(player: Player): AirsoftBoard {
        val scoreboard = Bukkit.getScoreboardManager().newScoreboard
        val colors = mapOf(
            AirsoftTeam.RED to NamedTextColor.RED,
            AirsoftTeam.BLUE to NamedTextColor.BLUE,
            AirsoftTeam.GREEN to NamedTextColor.GREEN,
            AirsoftTeam.YELLOW to NamedTextColor.YELLOW,
            AirsoftTeam.UNASSIGNED to NamedTextColor.GRAY,
            AirsoftTeam.SPECTATOR to NamedTextColor.GRAY
        )
        val teams = AirsoftTeam.entries.associateWith { team ->
            val id = when (team) {
                AirsoftTeam.UNASSIGNED -> "airsoft_none"
                AirsoftTeam.SPECTATOR -> "airsoft_spec"
                else -> "airsoft_${team.id}"
            }
            val prefixText = when (team) {
                AirsoftTeam.UNASSIGNED -> "[미참가] "
                AirsoftTeam.SPECTATOR -> "[관전자] "
                else -> "[${team.name}] "
            }
            scoreboard.registerNewTeam(id).apply {
                color(colors.getValue(team))
                prefix(Component.text(prefixText, colors.getValue(team)))
                setAllowFriendlyFire(false)
                setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.FOR_OTHER_TEAMS)
            }
        }
        participants.forEach { (uuid, team) ->
            Bukkit.getPlayer(uuid)?.let { teams.getValue(team).addEntry(it.name) }
        }
        val tdm = scoreboard.registerNewObjective(
            "airsoft_tdm", Criteria.DUMMY,
            Component.text(plugin.messages.text(player, "airsoft.scoreboard-kills"), NamedTextColor.GOLD)
        )
        val ctf = scoreboard.registerNewObjective(
            "airsoft_ctf", Criteria.DUMMY,
            Component.text(plugin.messages.text(player, "airsoft.scoreboard-score"), NamedTextColor.GOLD)
        )
        return AirsoftBoard(scoreboard, teams, tdm, ctf)
    }

    fun refreshScoreboard(player: Player) {
        if (!isEnabled) return
        val board = boards[player.uniqueId] ?: return
        board.tdmObjective.displayName(Component.text(plugin.messages.text(player, "airsoft.scoreboard-kills"), NamedTextColor.GOLD))
        board.ctfObjective.displayName(Component.text(plugin.messages.text(player, "airsoft.scoreboard-score"), NamedTextColor.GOLD))
    }

    fun refreshScoreboards() {
        boards.keys.mapNotNull(Bukkit::getPlayer).forEach(::refreshScoreboard)
    }

    private fun updateScoreboard() {
        if (!isEnabled) return
        boards.values.forEach { board ->
            val activeObjective = if (mode == AirsoftMode.CTF) board.ctfObjective else board.tdmObjective
            activeObjective.displaySlot = DisplaySlot.SIDEBAR
            listOf(board.tdmObjective, board.ctfObjective).forEach { objective ->
                objective.getScore("§7목표 점수").score = targetScore
                objective.getScore("§cRED").score = scoreOf(AirsoftTeam.RED)
                objective.getScore("§9BLUE").score = scoreOf(AirsoftTeam.BLUE)
                if (activeMap.type == AirsoftMapType.FOUR_TEAMS) {
                    objective.getScore("§aGREEN").score = scoreOf(AirsoftTeam.GREEN)
                    objective.getScore("§eYELLOW").score = scoreOf(AirsoftTeam.YELLOW)
                } else {
                    board.scoreboard.resetScores("§aGREEN")
                    board.scoreboard.resetScores("§eYELLOW")
                }
                objective.getScore("§7관전자").score = teamSize(AirsoftTeam.SPECTATOR)
                objective.getScore("§8미참가자").score = teamSize(AirsoftTeam.UNASSIGNED)
            }
        }
    }

    private fun sendStatus(sender: CommandSender) {
        sender.sendMessage(plugin.messages.text(sender, "airsoft.status-header"))
        sender.sendMessage(plugin.messages.text(sender, "airsoft.status-map", activeMapName, activeMap.type.id))
        sender.sendMessage(plugin.messages.text(sender, "airsoft.status-mode", mode.name))
        sender.sendMessage(plugin.messages.text(sender, "airsoft.status-state", if (isRunning) "RUNNING" else "WAITING"))
        sender.sendMessage(plugin.messages.text(sender, "airsoft.status-red", scoreOf(AirsoftTeam.RED), teamSize(AirsoftTeam.RED)))
        sender.sendMessage(plugin.messages.text(sender, "airsoft.status-blue", scoreOf(AirsoftTeam.BLUE), teamSize(AirsoftTeam.BLUE)))
        if (activeMap.type == AirsoftMapType.FOUR_TEAMS) {
            sender.sendMessage(plugin.messages.text(sender, "airsoft.status-green", scoreOf(AirsoftTeam.GREEN), teamSize(AirsoftTeam.GREEN)))
            sender.sendMessage(plugin.messages.text(sender, "airsoft.status-yellow", scoreOf(AirsoftTeam.YELLOW), teamSize(AirsoftTeam.YELLOW)))
        }
        sender.sendMessage(plugin.messages.text(sender, "airsoft.status-spectator", teamSize(AirsoftTeam.SPECTATOR)))
        sender.sendMessage(plugin.messages.text(sender, "airsoft.status-unassigned", teamSize(AirsoftTeam.UNASSIGNED)))
        sender.sendMessage(plugin.messages.text(sender, "airsoft.status-target", targetScore))
    }

    private fun sendHelp(sender: CommandSender) {
        sender.sendMessage(plugin.messages.text(sender, "airsoft.help-header"))
        if (sender.hasPermission(ADMIN_PERMISSION)) sender.sendMessage(plugin.messages.text(sender, "airsoft.help-toggle"))
        if (!isEnabled) {
            sender.sendMessage(plugin.messages.text(sender, "airsoft.disabled"))
            return
        }
        sender.sendMessage(plugin.messages.text(sender, "airsoft.help-join"))
        sender.sendMessage(plugin.messages.text(sender, "airsoft.help-leave"))
        sender.sendMessage(plugin.messages.text(sender, "airsoft.help-score"))
        sender.sendMessage(plugin.messages.text(sender, "airsoft.help-map-list"))
        if (sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(plugin.messages.text(sender, "airsoft.help-map"))
            sender.sendMessage(plugin.messages.text(sender, "airsoft.help-start"))
            sender.sendMessage(plugin.messages.text(sender, "airsoft.help-stop"))
            sender.sendMessage(plugin.messages.text(sender, "airsoft.help-switchteam"))
            sender.sendMessage(plugin.messages.text(sender, "airsoft.help-spawn"))
            sender.sendMessage(plugin.messages.text(sender, "airsoft.help-setflag"))
            sender.sendMessage(plugin.messages.text(sender, "airsoft.help-mode"))
        }
    }

    private fun broadcast(key: String, vararg args: Any?) {
        participants.keys.mapNotNull(Bukkit::getPlayer).forEach { player ->
            player.sendMessage(plugin.messages.text(player, key, *args))
        }
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        removeFlagFromInventory(event.player)
        if (isEnabled) moveToTeam(event.player, AirsoftTeam.UNASSIGNED)
    }

    @EventHandler
    fun onGameModeChange(event: PlayerGameModeChangeEvent) {
        if (isEnabled && teamOf(event.player) == AirsoftTeam.SPECTATOR && event.newGameMode != GameMode.SPECTATOR) {
            event.isCancelled = true
            event.player.sendMessage(plugin.messages.text(event.player, "airsoft.spectator-mode-locked"))
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlayerMove(event: PlayerMoveEvent) {
        if (!isRunning || mode != AirsoftMode.CTF || flagCarrier != event.player.uniqueId) return
        val team = teamOf(event.player)?.takeIf { it in competitiveTeams() } ?: return
        val spawn = spawnOf(team) ?: return
        val destination = event.to ?: return
        if (destination.world != spawn.world || destination.distanceSquared(spawn) > CAPTURE_RADIUS_SQUARED) return
        if (event.player.inventory.contents.none(::isFlag)) {
            resetFlag()
            return
        }

        val newScore = scoreOf(team) + 1
        scores[team] = newScore
        updateScoreboard()
        broadcast("airsoft.ctf-captured", event.player.name, teamName(team), newScore, targetScore)
        if (newScore >= targetScore) finish(team) else resetFlag()
    }

    @EventHandler
    fun onPlayerDeath(event: PlayerDeathEvent) {
        if (flagCarrier == event.player.uniqueId) {
            event.drops.removeIf(::isFlag)
            dropFlag(event.player.location)
        }
        val killer = event.player.killer ?: return
        recordElimination(killer, event.player)
    }

    @EventHandler
    fun onFlagInteraction(event: PlayerInteractEntityEvent) {
        if (event.rightClicked.uniqueId != flagInteraction?.uniqueId) return
        event.isCancelled = true
        takeFlag(event.player)
    }

    @EventHandler
    fun onFlagAttack(event: EntityDamageByEntityEvent) {
        if (event.entity.uniqueId != flagInteraction?.uniqueId) return
        event.isCancelled = true
        (event.damager as? Player)?.let(::takeFlag)
    }

    @EventHandler
    fun onFlagPickup(event: EntityPickupItemEvent) {
        if (event.item.uniqueId != droppedFlag?.uniqueId) return
        event.isCancelled = true
        val player = event.entity as? Player ?: return
        if (!isRunning || mode != AirsoftMode.CTF || teamOf(player) !in competitiveTeams()) return
        if (player.inventory.firstEmpty() == -1) {
            player.sendMessage(plugin.messages.text(player, "airsoft.flag-inventory-full"))
            return
        }
        flagReturnTask?.cancel()
        flagReturnTask = null
        droppedFlag?.remove()
        droppedFlag = null
        flagCarrier = player.uniqueId
        player.inventory.addItem(flagItem())
        broadcast("airsoft.flag-taken", player.name)
    }

    @EventHandler
    fun onFlagDrop(event: PlayerDropItemEvent) {
        if (isFlag(event.itemDrop.itemStack) && flagCarrier == event.player.uniqueId) event.isCancelled = true
    }

    @EventHandler
    fun onFlagInventoryClick(event: InventoryClickEvent) {
        val hotbarItem = event.hotbarButton.takeIf { it >= 0 }?.let { event.whoClicked.inventory.getItem(it) }
        if (isFlag(event.currentItem) || isFlag(event.cursor) || isFlag(hotbarItem)) event.isCancelled = true
    }

    @EventHandler
    fun onFlagInventoryDrag(event: InventoryDragEvent) {
        if (isFlag(event.oldCursor)) event.isCancelled = true
    }

    @EventHandler
    fun onFlagHandSwap(event: PlayerSwapHandItemsEvent) {
        if (isFlag(event.mainHandItem) || isFlag(event.offHandItem)) event.isCancelled = true
    }

    @EventHandler
    fun onFlagBreak(event: BlockBreakEvent) {
        if (event.block.type == Material.WHITE_BANNER && maps.values.any { it.flag == event.block.location }) event.isCancelled = true
    }

    @EventHandler
    fun onPlayerRespawn(event: PlayerRespawnEvent) {
        if (!isRunning) return
        val team = teamOf(event.player)?.takeIf { it in competitiveTeams() } ?: return
        spawnOf(team)?.let { event.respawnLocation = it }
        val playerId = event.player.uniqueId
        Bukkit.getScheduler().runTask(plugin, Runnable {
            val player = Bukkit.getPlayer(playerId) ?: return@Runnable
            if (!isEnabled || !isRunning || teamOf(player) != team || player.isDead) return@Runnable
            if (matchGameModes.containsKey(playerId)) player.gameMode = GameMode.ADVENTURE
            player.addPotionEffect(PotionEffect(PotionEffectType.RESISTANCE, 60, 4, false, false, true))
        })
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val oldTeam = teamOf(event.player) ?: return
        val name = event.player.name
        if (flagCarrier == event.player.uniqueId) dropFlag(event.player.location)
        removeParticipant(event.player.uniqueId, false, event.player)
        if (oldTeam != AirsoftTeam.UNASSIGNED) broadcast("airsoft.left", name)
        checkForfeit()
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): MutableList<String> {
        val options = when (args.size) {
            1 -> buildList {
                if (isEnabled) addAll(listOf("join", "leave", "score", "map"))
                if (sender.hasPermission(ADMIN_PERMISSION)) {
                    addAll(listOf("on", "off"))
                    if (isEnabled) addAll(listOf("start", "stop", "switchteam", "spawn", "setflag", "mode"))
                }
            }
            2 -> if (!isEnabled) emptyList() else when (args[0].lowercase()) {
                "join", "참가" -> competitiveTeams().map { it.id } + listOf(AirsoftTeam.UNASSIGNED.id, AirsoftTeam.SPECTATOR.id)
                "start", "시작" -> if (sender.hasPermission(ADMIN_PERMISSION)) listOf("10", "20", "30", "50") else emptyList()
                "switchteam", "팀변경" -> if (sender.hasPermission(ADMIN_PERMISSION)) {
                    participants.keys.mapNotNull(Bukkit::getPlayer).map { it.name }.sorted()
                } else emptyList()
                "spawn", "스폰" -> if (sender.hasPermission(ADMIN_PERMISSION)) competitiveTeams().map { it.id } else emptyList()
                "mode", "모드" -> if (sender.hasPermission(ADMIN_PERMISSION)) listOf("tdm", "ctf") else emptyList()
                "setflag", "깃발설정" -> if (sender.hasPermission(ADMIN_PERMISSION)) listOf("~") else emptyList()
                "map", "맵" -> if (sender.hasPermission(ADMIN_PERMISSION)) listOf("list") + maps.keys.sorted() else listOf("list")
                else -> emptyList()
            }
            3 -> if (!isEnabled) emptyList() else if (args[0].equals("switchteam", true) || args[0] == "팀변경") {
                if (sender.hasPermission(ADMIN_PERMISSION)) competitiveTeams().map { it.id } + listOf(AirsoftTeam.UNASSIGNED.id, AirsoftTeam.SPECTATOR.id) else emptyList()
            } else if (args[0].equals("map", true) || args[0] == "맵") {
                if (sender.hasPermission(ADMIN_PERMISSION) && !args[1].equals("list", true)) AirsoftMapType.entries.map { it.id } else emptyList()
            } else if (args[0].equals("spawn", true) || args[0] == "스폰" || args[0].equals("setflag", true) || args[0] == "깃발설정") {
                if (sender.hasPermission(ADMIN_PERMISSION)) listOf("~") else emptyList()
            } else emptyList()
            4, 5 -> if (!isEnabled) emptyList() else if (args[0].equals("spawn", true) || args[0] == "스폰" || args[0].equals("setflag", true) || args[0] == "깃발설정") {
                if (sender.hasPermission(ADMIN_PERMISSION)) listOf("~") else emptyList()
            } else emptyList()
            else -> emptyList()
        }
        return options.filter { it.startsWith(args.last(), ignoreCase = true) }.toMutableList()
    }

    companion object {
        private const val ADMIN_PERMISSION = "fpl.airsoft.admin"
        private const val DEFAULT_MAP = "default"
        private val MAP_NAME_PATTERN = Regex("[a-z0-9_-]{1,32}")
        private const val DEFAULT_TARGET_SCORE = 20
        private const val DEFAULT_CTF_TARGET_SCORE = 3
        private const val MAX_TARGET_SCORE = 999
        private const val CAPTURE_RADIUS_SQUARED = 4.0
    }
}
