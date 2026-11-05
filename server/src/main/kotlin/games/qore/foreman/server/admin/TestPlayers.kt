package games.qore.foreman.server.admin

import com.destroystokyo.paper.profile.PlayerProfile
import games.qore.foreman.server.Foreman
import games.qore.foreman.server.world.Sync
import io.papermc.paper.ban.BanListType
import io.papermc.paper.connection.PlayerConfigurationConnection
import io.papermc.paper.connection.PlayerLoginConnection
import io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent
import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.permissions.PermissionAttachment
import java.io.File
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Test players: names a dev host or an admin registered (test clients, bots). They get through the whitelist like the
 * players on it, and are plain players otherwise, to test what a real player can and can't do; [elevate]d, they have
 * every permission for as long as it lasts (a permission attachment: nothing is written to ops.json). Kept in
 * `test-players.yml`. A name counts from this machine only unless `test-players.local-only` is false: on an
 * offline-mode server anyone may join with it.
 */
class TestPlayers(private val plugin: Foreman) : Listener {
    class Entry(val name: String, var elevated: Boolean, val added: Long)

    private val file = File(plugin.dataFolder, "test-players.yml")
    private val entries = ConcurrentHashMap<String, Entry>()
    private val attachments = ConcurrentHashMap<UUID, PermissionAttachment>()
    private val localOnly = plugin.config.getBoolean("test-players.local-only", true)
    /** What an elevated player gets besides every registered permission: `*` for plugins that check it literally. */
    private val grant = plugin.config.getStringList("test-players.grant").ifEmpty { listOf("*") }

    fun start() {
        val yaml = YamlConfiguration.loadConfiguration(file)
        yaml.getConfigurationSection("players")?.getKeys(false)?.forEach { key ->
            val s = yaml.getConfigurationSection("players.$key") ?: return@forEach
            entries[key] = Entry(s.getString("name") ?: key, s.getBoolean("elevated"), s.getLong("added"))
        }
        Bukkit.getPluginManager().registerEvents(this, plugin)
        Bukkit.getOnlinePlayers().forEach(::apply)
    }

    fun stop() {
        HandlerList.unregisterAll(this)
        attachments.keys.toList().forEach { id -> Bukkit.getPlayer(id)?.let(::detach) }
    }

    fun list(): List<Entry> = entries.values.sortedBy { it.name.lowercase() }

    operator fun get(name: String): Entry? = entries[name.lowercase()]

    /** Registers [names] (plain, or [elevated]); those online have it at once. */
    fun add(names: List<String>, elevated: Boolean) = change(names) { name ->
        entries.getOrPut(name.lowercase()) { Entry(name, elevated, System.currentTimeMillis()) }.also { it.elevated = elevated }
    }

    fun remove(names: List<String>) = change(names) { entries.remove(it.lowercase()) }

    /** Elevates or demotes registered [names]: the names it changed. */
    fun elevate(names: List<String>, on: Boolean) = change(names) { name -> entries[name.lowercase()]?.also { it.elevated = on } }

    /** Applies [edit] to each name, saves, and updates the players online (on the server thread): the names it applied to. */
    private fun change(names: List<String>, edit: (String) -> Entry?): List<String> {
        val done = names.filter { edit(it) != null }
        save()
        Sync.main { names.mapNotNull { Bukkit.getPlayerExact(it) }.forEach(::apply) }
        return done
    }

    /**
     * Whether [player]'s elevation took: false when the server's permission plugin answers from its own data only and
     * ignores Bukkit's permission attachments (null: not elevated).
     */
    fun took(player: Player): Boolean? = if (attachments.containsKey(player.uniqueId)) player.hasPermission(grant.first()) else null

    /** Whether [player] is a test player now: registered, and from this machine when only local ones count. */
    fun counts(player: Player) = get(player.name) != null && local(player.address?.address)

    private fun local(address: InetAddress?) = !localOnly || address?.isLoopbackAddress == true

    /** Lets a registered test player through the whitelist, and only the whitelist (a ban still holds). */
    @EventHandler
    fun onValidate(event: PlayerConnectionValidateLoginEvent) {
        if (event.isAllowed || !Bukkit.hasWhitelist()) return
        val connection = event.connection
        val profile: PlayerProfile = when (connection) {
            is PlayerLoginConnection -> connection.authenticatedProfile ?: connection.unsafeProfile
            is PlayerConfigurationConnection -> connection.profile
            else -> null
        } ?: return
        val name = profile.name ?: return
        if (get(name) == null || !local(connection.clientAddress?.address)) return
        if (Bukkit.getWhitelistedPlayers().any { it.name.equals(name, true) }) return
        if (Bukkit.getBanList(BanListType.PROFILE).isBanned(profile)) return
        if (connection.clientAddress?.address?.let { Bukkit.getBanList(BanListType.IP).isBanned(it) } == true) return
        event.allow()
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) = apply(event.player)

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        attachments.remove(event.player.uniqueId)
    }

    /** Gives [player] every permission while they're an elevated test player, takes it back otherwise. */
    private fun apply(player: Player) {
        val elevated = counts(player) && get(player.name)?.elevated == true
        if (elevated == attachments.containsKey(player.uniqueId)) return
        if (!elevated) return detach(player)
        val attachment = player.addAttachment(plugin)
        (Bukkit.getPluginManager().permissions.map { it.name } + grant).forEach { attachment.setPermission(it, true) }
        attachments[player.uniqueId] = attachment
        player.updateCommands()
    }

    private fun detach(player: Player) {
        attachments.remove(player.uniqueId)?.let { runCatching { player.removeAttachment(it) } }
        player.updateCommands()
    }

    private fun save() {
        val yaml = YamlConfiguration()
        entries.forEach { (key, e) ->
            yaml.set("players.$key.name", e.name)
            yaml.set("players.$key.elevated", e.elevated)
            yaml.set("players.$key.added", e.added)
        }
        runCatching { yaml.save(file) }.onFailure { plugin.logger.warning("Could not save ${file.name}: $it") }
    }
}
