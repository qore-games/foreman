package games.qore.foreman.server

import games.qore.foreman.api.ForemanTool
import games.qore.foreman.server.mcp.Tool
import org.bukkit.configuration.ConfigurationSection
import java.util.logging.Logger

/** What a server can offer, each on or off: everything on a dev server, only [production] ones on a live one. */
enum class Feature(val key: String, val about: String, val production: Boolean) {
    MCP("mcp", "The MCP server on mcp.host:mcp.port, with the server's token and players' tokens through the mod", false),
    RELAY("relay", "The mod's AI reaches the tools through the player's game connection, no port: for players with foreman.client, audited, rate limited", true),
    DIAGNOSTICS("diagnostics", "Read-only server tools: the log, plugins and their jars, status, the profiler, listeners", true),
    CONSOLE("console", "server_command (commands as the console or a player) and plugin reloads", false),
    WORLD("world", "The world tools that read it (with FastAsyncWorldEdit): context, scans, renders, checks", false),
    WORLD_EDIT("world-edit", "The world tools that change it: edits, terrain, buildings, assets, furniture", false),
    TESTING("testing", "Dupe and test tools, and the mod's network profiles and packet drops", false),
    TEST_PLAYERS("test-players", "Registered test players (test clients, bots) let through the whitelist, with every permission only when elevated", false),
    PLUGIN_TOOLS("plugin-tools", "Tools other plugins publish, and Foreman's tools for other plugins", false),
    LEARNING("learning", "The AI saving lessons and skills for every later session", false),
    ;

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key }
    }
}

/**
 * The features on: those of the `preset` (`dev`: all, `production`: [Feature.production]), then those `features:` turns
 * on or off. Tools of a feature that's off are never published ([allows]), whoever registers them.
 */
class Features(val preset: String, private val on: Set<Feature>, /** The ones `features:` set, over the preset. */ val overridden: Set<Feature>) {
    operator fun get(feature: Feature) = feature in on

    /** Whether [tool] may be published: every feature it needs is on. */
    fun allows(tool: Tool) = needs(tool).all { it in on }

    /** One line for the log: what's on, what's off. */
    fun describe() = listOf(
        Feature.entries.filter { it in on }.joinToString(", ") { label(it) }.ifEmpty { "nothing" } + " on",
        Feature.entries.filter { it !in on }.joinToString(", ") { label(it) }.takeIf { it.isNotEmpty() }?.let { "$it off" },
    ).filterNotNull().joinToString("; ")

    /** Its key, with ` (set)` when `features:` overrides the preset. */
    fun label(feature: Feature) = feature.key + if (feature in overridden) " (set)" else ""

    companion object {
        const val DEV = "dev"
        const val PRODUCTION = "production"

        fun load(config: ConfigurationSection, logger: Logger): Features {
            val preset = config.getString("preset", DEV)!!.trim().lowercase().let {
                if (it == DEV || it == PRODUCTION) it else DEV.also { _ -> logger.warning("Unknown preset '$it' (dev or production): dev") }
            }
            val on = Feature.entries.filter { preset == DEV || it.production }.toMutableSet()
            val overridden = mutableSetOf<Feature>()
            config.getConfigurationSection("features")?.getValues(false)?.forEach { (key, value) ->
                val feature = Feature.of(key) ?: return@forEach logger.warning("Unknown feature '$key' in features: (${Feature.entries.joinToString { it.key }})")
                if (value !is Boolean) return@forEach logger.warning("features.$key is true or false, not '$value'")
                if (value) on += feature else on -= feature
                overridden += feature
            }
            return Features(preset, on, overridden)
        }

        /** The features [tool] needs: by what it is when it's one of Foreman's, else another plugin's. */
        fun needs(tool: Tool): List<Feature> {
            val known = ForemanTool.of(tool.name) ?: return listOf(Feature.PLUGIN_TOOLS)
            return when {
                known.world -> if (tool.modifies) listOf(Feature.WORLD, Feature.WORLD_EDIT) else listOf(Feature.WORLD)
                known == ForemanTool.SERVER_COMMAND || known == ForemanTool.SERVER_RELOAD_PLUGIN -> listOf(Feature.CONSOLE)
                known == ForemanTool.TEST_PLAYERS -> listOf(Feature.TEST_PLAYERS)
                known.group == "Server" -> listOf(Feature.DIAGNOSTICS)
                known.group == "Testing" -> listOf(Feature.TESTING)
                known == ForemanTool.SAVE_SKILL || known.group == "Lessons" -> listOf(Feature.LEARNING)
                known.requires != null -> listOf(Feature.PLUGIN_TOOLS)
                else -> emptyList()
            }
        }
    }
}
