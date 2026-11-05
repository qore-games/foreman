package games.qore.foreman.server

import games.qore.foreman.server.mcp.ToolError
import games.qore.foreman.server.tools.AssetTools
import games.qore.foreman.server.tools.ServerTools
import games.qore.foreman.server.tools.TestPlayerTools
import games.qore.foreman.server.world.Edits
import games.qore.foreman.server.world.WorldEditSelections
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.Player

/**
 * `/foreman mark <name> [note…]` (WorldEdit selection, or where you stand), `unmark <name>`, `marks`,
 * `undo [count]`, `asset save <name> [tags…]` (WorldEdit selection), `token revoke <player>`, `tools [filter]`, `skills`,
 * `world show|export|import [world]` (the world's profile and areas as YAML in plugins/Foreman/worlds), `status`.
 * All but `token`, `tools`, `skills` and `status` are about the world: they need FastAsyncWorldEdit and the world feature.
 */
class ForemanCommand(private val plugin: Foreman) : TabExecutor {
    private val worldCommands = setOf("mark", "unmark", "marks", "undo", "asset", "world")

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.getOrNull(0) in worldCommands && !plugin.worldTools) {
            Msg.error(sender, Component.text(
                if (plugin.features[Feature.WORLD]) "/foreman ${args[0]} needs FastAsyncWorldEdit: the world tools are off without it"
                else "/foreman ${args[0]} needs the world tools, off on this server (features.world)",
            ))
            return true
        }
        try {
            when (args.getOrNull(0)) {
                "mark" -> {
                    val player = sender as? Player ?: return usage(sender)
                    val name = args.getOrNull(1) ?: return usage(sender)
                    val box = WorldEditSelections.of(player) ?: WorldEditSelections.here(player)
                    val mark = plugin.worlds.mark(name, player.world.name, box, args.drop(2).joinToString(" "))
                    Msg.success(sender, Component.text("Marked ${mark.describe()}"))
                }

                "unmark" -> {
                    val name = args.getOrNull(1) ?: return usage(sender)
                    if (plugin.worlds.remove(name)) Msg.success(sender, Component.text("Removed mark $name"))
                    else Msg.error(sender, Component.text("No mark $name"))
                }

                "marks" -> {
                    val marks = plugin.worlds.areas
                    if (marks.isEmpty()) Msg.info(sender, Component.text("No areas"))
                    marks.forEach { Msg.info(sender, Component.text(it.describe())) }
                }

                "undo" -> {
                    val count = args.getOrNull(1)?.toIntOrNull() ?: 1
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
                        val undone = Edits.undo(count)
                        Bukkit.getScheduler().runTask(plugin, Runnable {
                            if (undone.isEmpty()) Msg.warning(sender, Component.text("Nothing to undo"))
                            else Msg.success(sender, Component.text("Undid: ${undone.joinToString(", ")}"))
                        })
                    })
                }

                "asset" -> {
                    val player = sender as? Player ?: return usage(sender)
                    if (args.getOrNull(1) != "save") return usage(sender)
                    val name = args.getOrNull(2) ?: return usage(sender)
                    val box = WorldEditSelections.of(player) ?: run {
                        Msg.error(sender, Component.text("Select the build with WorldEdit first"))
                        return true
                    }
                    AssetTools.saveBox(plugin, player.world, box, name, "", args.drop(3))
                    Msg.success(sender, Component.text("Saved asset $name (${box.sizeX}x${box.sizeY}x${box.sizeZ}). Describe it in assets/$name.yml"))
                }

                "token" -> {
                    if (args.getOrNull(1) != "revoke") return usage(sender)
                    val name = args.getOrNull(2) ?: return usage(sender)
                    if (plugin.bridge?.revoke(name) == true) Msg.success(sender, Component.text("Revoked $name's MCP token (a new one is issued on their next join)"))
                    else Msg.error(sender, Component.text("$name has no MCP token"))
                }

                "skills" -> {
                    val skills = plugin.skills.active()
                    Msg.info(sender, Component.text("${skills.size} skills (read by the AI when a request matches; add yours in plugins/Foreman/skills/)"))
                    skills.forEach { s ->
                        sender.sendMessage(Component.text("${s.name} ", net.kyori.adventure.text.format.NamedTextColor.YELLOW)
                            .append(Component.text("(${s.source}) ${s.description}", net.kyori.adventure.text.format.NamedTextColor.GRAY)))
                    }
                }

                "tools" -> {
                    // Every MCP tool by provider: Foreman's own and other plugins'.
                    val filter = args.getOrNull(1)?.lowercase()
                    val tools = plugin.tools.all().filter { filter == null || it.provider.lowercase().contains(filter) || it.name.contains(filter) }
                    Msg.info(sender, Component.text("${tools.size} MCP tools" + if (filter != null) " matching '$filter'" else ""))
                    tools.groupBy { it.provider }.forEach { (provider, list) ->
                        sender.sendMessage(Component.text("$provider (${list.size}): ", net.kyori.adventure.text.format.NamedTextColor.YELLOW)
                            .append(Component.join(net.kyori.adventure.text.JoinConfiguration.commas(true), list.map { t ->
                                Component.text(t.name, if (t.modifies) net.kyori.adventure.text.format.NamedTextColor.GOLD else net.kyori.adventure.text.format.NamedTextColor.GRAY)
                                    .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text(t.description.take(300))))
                            })))
                    }
                }

                "world" -> {
                    val world = args.getOrNull(2) ?: (sender as? Player)?.world?.name ?: plugin.world(null).name
                    when (args.getOrNull(1)) {
                        "export" -> Msg.success(sender, Component.text("Wrote ${plugin.worlds.export(world).path}"))
                        "import" -> {
                            val (profile, areas, points) = plugin.worlds.import(world)
                            Msg.success(sender, Component.text("Loaded ${plugin.worlds.file(world).path} into $world: $profile profile fields, $areas areas, $points points"))
                        }
                        "show" -> {
                            Msg.info(sender, Component.text("Profile of $world:"))
                            sender.sendMessage(Component.text(plugin.worlds.profile(world).describe()))
                            Msg.info(sender, Component.text("${plugin.worlds.areas(world).size} areas, ${plugin.worlds.areas(world).count { it.theme.isNotEmpty() }} zones, ${plugin.worlds.points(world).size} points"))
                        }
                        else -> return usage(sender)
                    }
                }

                "status" -> status(sender)

                "testplayer" -> testPlayer(sender, args.drop(1))

                else -> return usage(sender)
            }
        } catch (e: ToolError) {
            Msg.error(sender, Component.text(e.message ?: "Error"))
        }
        return true
    }

    /** What the server exposes: its features (hover: what each is), the MCP server, the tools published, the world's state. */
    private fun status(sender: CommandSender) {
        val features = plugin.features
        Msg.info(sender, Component.text("Foreman ${plugin.pluginMeta.version}, preset ${features.preset}. Features:"))
        sender.sendMessage(Component.join(JoinConfiguration.commas(true), Feature.entries.map { f ->
            Component.text(features.label(f), if (features[f]) NamedTextColor.GREEN else NamedTextColor.DARK_GRAY)
                .hoverEvent(HoverEvent.showText(Component.text((if (features[f]) "On. " else "Off. ") + f.about)))
        }))
        val mcp = plugin.mcp
        sender.sendMessage(Component.text(
            if (mcp == null) "MCP: off, no port open and no token: nothing reaches the tools"
            else "MCP: listening on ${plugin.config.getString("mcp.host", "127.0.0.1")}:${mcp.port} (${mcp.url}), ${plugin.bridge?.tokens?.names()?.size ?: 0} player tokens",
            NamedTextColor.GRAY,
        ))
        sender.sendMessage(Component.text("${plugin.tools.size} tools published (/foreman tools); " +
            if (plugin.worldTools) "${plugin.assets.list().size} assets, ${plugin.worlds.areas.size} areas, ${Edits.recent.size} undoable changes"
            else if (features[Feature.WORLD]) "world tools off (no FastAsyncWorldEdit)" else "world tools off", NamedTextColor.GRAY))
    }

    /** `/foreman testplayer list | add <name> [elevated] | remove | elevate | demote <name>`: changes need foreman.admin. */
    private fun testPlayer(sender: CommandSender, args: List<String>) {
        val players = plugin.testPlayers ?: return Msg.error(sender, Component.text("Test players are off on this server (features.test-players)"))
        val action = args.getOrNull(0) ?: "list"
        val name = args.getOrNull(1)
        if (action != "list" && (name == null || !sender.hasPermission(ServerTools.PERMISSION))) {
            return Msg.error(sender, Component.text(if (name == null) "/foreman testplayer list | add <name> [elevated] | remove | elevate | demote <name>" else "This needs ${ServerTools.PERMISSION}"))
        }
        val done = when (action) {
            "add" -> players.add(listOf(name!!), args.getOrNull(2) == "elevated")
            "remove" -> players.remove(listOf(name!!))
            "elevate" -> players.elevate(listOf(name!!), true)
            "demote" -> players.elevate(listOf(name!!), false)
            "list" -> emptyList()
            else -> return Msg.error(sender, Component.text("/foreman testplayer list | add <name> [elevated] | remove | elevate | demote <name>"))
        }
        if (action != "list") {
            if (done.isEmpty()) Msg.error(sender, Component.text("$name isn't a test player")) else Msg.success(sender, Component.text("$action: $name"))
        }
        TestPlayerTools.describe(players).lines().forEach { sender.sendMessage(Component.text(it, NamedTextColor.GRAY)) }
    }

    private fun usage(sender: CommandSender): Boolean {
        Msg.info(sender, Component.text(
            "/foreman mark <name> [note] | unmark <name> | marks | undo [count] | asset save <name> [tags…] | token revoke <player> | tools [filter] | skills | world show|export|import [world] | testplayer … | status"
        ))
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<out String>): List<String> =
        when (args.size) {
            1 -> listOf("mark", "unmark", "marks", "undo", "asset", "token", "tools", "skills", "world", "testplayer", "status").filter { it.startsWith(args[0]) }
            2 -> when (args[0]) {
                "unmark" -> if (plugin.worldTools) plugin.worlds.areas.map { it.name }.filter { it.startsWith(args[1]) } else emptyList()
                "asset" -> listOf("save")
                "token" -> listOf("revoke")
                "world" -> listOf("show", "export", "import").filter { it.startsWith(args[1]) }
                "testplayer" -> listOf("list", "add", "remove", "elevate", "demote").filter { it.startsWith(args[1]) }
                else -> emptyList()
            }
            3 -> when (args[0]) {
                "testplayer" -> (if (args[1] == "add") Bukkit.getOnlinePlayers().map { it.name } else plugin.testPlayers?.list()?.map { it.name }.orEmpty())
                    .filter { it.startsWith(args[2], ignoreCase = true) }
                "token" -> plugin.bridge?.tokens?.names().orEmpty().filter { it.startsWith(args[2], ignoreCase = true) }
                "world" -> org.bukkit.Bukkit.getWorlds().map { it.name }.filter { it.startsWith(args[2]) }
                else -> emptyList()
            }
            else -> emptyList()
        }
}
