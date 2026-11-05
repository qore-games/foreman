package games.qore.foreman.server.tools

import games.qore.foreman.api.ForemanTool
import games.qore.foreman.server.Foreman
import games.qore.foreman.server.admin.TestPlayers
import games.qore.foreman.server.mcp.Tool
import games.qore.foreman.server.mcp.ToolError
import games.qore.foreman.server.mcp.ToolResult
import games.qore.foreman.server.mcp.schema
import org.bukkit.Bukkit

/** [ForemanTool.TEST_PLAYERS]: test players registered, elevated and demoted ([TestPlayers]). Needs foreman.admin: it can give every permission. */
object TestPlayerTools {
    fun all(plugin: Foreman) = listOf(
        Tool(
            ForemanTool.TEST_PLAYERS,
            "Test players: names (test clients, bots) let through the whitelist and plain players otherwise, to test what " +
                "a real player can and can't do. `add` registers them (`elevated: true`: every permission at once), " +
                "`elevate` gives registered ones every permission (for a command a plain player can't run, a setup " +
                "step), `demote` takes it back, `remove` unregisters them, `list` (the default) says who is and who's " +
                "online. They count only from this machine unless the server allows otherwise.",
            schema {
                enum("action", listOf("list", "add", "remove", "elevate", "demote"), "What to do (default list)", required = false)
                strings("names", "Player names, for add, remove, elevate and demote", required = false)
                bool("elevated", "add: with every permission at once (default false: a plain player)", required = false)
            },
        ) { args ->
            ServerTools.admin(args)
            val players = plugin.testPlayers ?: throw ToolError("Test players are off on this server (features.test-players)")
            val names = args.strings("names").map { it.trim() }.filter { it.isNotEmpty() }
            val action = args.string("action", "list")!!
            if (action != "list" && names.isEmpty()) throw ToolError("Give names")
            val done = when (action) {
                "add" -> players.add(names, args.bool("elevated", false))
                "remove" -> players.remove(names)
                "elevate" -> players.elevate(names, true)
                "demote" -> players.elevate(names, false)
                "list" -> emptyList()
                else -> throw ToolError("Unknown action '$action': list, add, remove, elevate or demote")
            }
            val missed = names.filter { n -> done.none { it.equals(n, true) } }
            val head = if (action == "list") "" else "$action: ${done.joinToString().ifEmpty { "nobody" }}" +
                (if (missed.isNotEmpty()) " (not test players: ${missed.joinToString()})" else "") + "\n"
            ToolResult.text(head + describe(players))
        },
    )

    /** Who is a test player, online or not, plain or elevated, and whether the whitelist is on. */
    fun describe(players: TestPlayers): String {
        val list = players.list()
        if (list.isEmpty()) return "No test players." + if (Bukkit.hasWhitelist()) " The whitelist is on." else ""
        return "${list.size} test player(s)" + (if (Bukkit.hasWhitelist()) ", let through the whitelist" else "") + ":\n" + list.joinToString("\n") { e ->
            val online = Bukkit.getPlayerExact(e.name)
            "  ${e.name}: " + (if (e.elevated) "elevated" else "plain") + ", " + when {
                online == null -> "offline"
                !players.counts(online) -> "online from another machine: not counted as a test player"
                players.took(online) == false -> "online, but its permissions don't take: the server's permission plugin ignores Bukkit permission attachments"
                else -> "online"
            }
        }
    }
}
