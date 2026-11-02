package games.qore.foreman.server.bridge

import games.qore.foreman.server.Secrets
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-player MCP tokens, given to the player's foreman client in the bridge hello so the AI running on their
 * computer can use the MCP server as them. Persisted in tokens.yml; revoking one makes the next hello issue a new one.
 */
class Tokens(private val file: File) {
    data class Owner(val uuid: UUID, val name: String)

    private val byPlayer = ConcurrentHashMap<UUID, Pair<String, String>>()
    private val byToken = ConcurrentHashMap<String, Owner>()

    fun load() {
        if (!file.exists()) return
        val yaml = YamlConfiguration.loadConfiguration(file)
        yaml.getKeys(false).forEach { key ->
            val uuid = runCatching { UUID.fromString(key) }.getOrNull() ?: return@forEach
            val token = yaml.getString("$key.token") ?: return@forEach
            val name = yaml.getString("$key.name") ?: key
            byPlayer[uuid] = name to token
            byToken[token] = Owner(uuid, name)
        }
    }

    /** The player's token, issued now if they have none. */
    fun issue(player: Player): String = synchronized(this) {
        byPlayer[player.uniqueId]?.let { (name, token) ->
            if (name != player.name) put(player.uniqueId, player.name, token)
            return token
        }
        val token = Secrets.token("fmp_")
        put(player.uniqueId, player.name, token)
        token
    }

    private fun put(uuid: UUID, name: String, token: String) {
        byPlayer[uuid]?.let { byToken.remove(it.second) }
        byPlayer[uuid] = name to token
        byToken[token] = Owner(uuid, name)
        save()
    }

    /** Who [token] belongs to (any thread). */
    fun owner(token: String): Owner? = byToken[token]

    /** Revokes [uuid]'s token, if they have one. */
    fun revoke(uuid: UUID) = synchronized(this) {
        byPlayer.remove(uuid)?.let { byToken.remove(it.second); save() }
    }

    /** Revokes the token of the player named [name] (case-insensitive). */
    fun revoke(name: String): Owner? = synchronized(this) {
        val (uuid, entry) = byPlayer.entries.firstOrNull { it.value.first.equals(name, ignoreCase = true) } ?: return null
        byPlayer.remove(uuid)
        byToken.remove(entry.second)
        save()
        Owner(uuid, entry.first)
    }

    fun names(): List<String> = byPlayer.values.map { it.first }.sorted()

    private fun save() {
        val yaml = YamlConfiguration()
        byPlayer.forEach { (uuid, entry) ->
            yaml.set("$uuid.name", entry.first)
            yaml.set("$uuid.token", entry.second)
        }
        Secrets.write(file, yaml.saveToString())
    }
}
