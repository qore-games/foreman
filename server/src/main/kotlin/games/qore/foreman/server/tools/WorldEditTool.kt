package games.qore.foreman.server.tools

import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.regions.selector.CuboidRegionSelector
import games.qore.foreman.api.ForemanTool
import games.qore.foreman.server.Foreman
import games.qore.foreman.server.mcp.Tool
import games.qore.foreman.server.mcp.ToolError
import games.qore.foreman.server.mcp.ToolResult
import games.qore.foreman.server.mcp.schema
import games.qore.foreman.server.world.Box
import games.qore.foreman.server.world.Change
import games.qore.foreman.server.world.Edits
import games.qore.foreman.server.world.Sync
import org.bukkit.Bukkit
import org.bukkit.command.PluginIdentifiableCommand

/**
 * Any WorldEdit or FastAsyncWorldEdit command, run by the console on a box: the box is its selection (and the session's
 * world), what it printed comes back, and the box is saved first so `undo` puts it back. The worldedit skill teaches
 * the commands, patterns and masks: one call does what would take many edit ops.
 */
object WorldEditTool {
    /** One command at a time: they share the console's WorldEdit session (its selection). */
    private val lock = Any()

    /**
     * The plugins whose commands it runs. It runs them as the console: any other command would give a player with only
     * `foreman.client` the console's rights (server_command needs `foreman.admin`).
     */
    private val owners = setOf("FastAsyncWorldEdit", "WorldEdit", "WorldGuard")

    /**
     * The WorldEdit commands a player with `foreman.client` may run: they change or measure the selection only. The
     * others (WorldGuard's, schematics, uploads, settings) need `foreman.admin`, like server_command.
     */
    private val editing = setOf(
        "set", "replace", "rep", "re", "overlay", "walls", "faces", "outline", "center", "middle", "smooth", "naturalize",
        "hollow", "deform", "setbiome", "regen", "stack", "move", "line", "curve", "forest", "flora", "fill", "fillr",
        "drain", "fixwater", "fixlava", "removeabove", "removebelow", "removenear", "replacenear", "snow", "thaw", "green",
        "extinguish", "ex", "count", "distr", "size", "calc", "calculate", "generate", "gen", "g", "generatebiome",
        "genbiome", "gb", "lay", "fixblocks",
    )

    /** Commands that change no block (measures, WorldGuard's regions): no snapshot, no undo step. */
    private val keepsBlocks = setOf("count", "distr", "size", "calc", "calculate", "rg", "region", "regions")

    fun all(plugin: Foreman) = listOf(
        Tool(
            ForemanTool.WORLDEDIT,
            "Runs a WorldEdit/FastAsyncWorldEdit command on a box, its selection: //replace stone 50%cobblestone,50%stone, " +
                "//set, //overlay grass_block, //smooth 4, //naturalize, //walls, //hollow, //deform, //setbiome, //regen, " +
                "//replace with masks… One call instead of many edit ops. Region commands act on the box; give a box " +
                "that holds everything the command may change (it is saved first: `undo` puts it back). Returns what " +
                "the command printed. Read the worldedit skill first for patterns, masks and the commands.",
            schema {
                string("command", "The command as a player types it, e.g. \"//replace stone,andesite 70%grass_block,30%moss_block\"")
                vec("from", "A corner of the box (the selection's first position)")
                vec("to", "The opposite corner")
                string("world", "World name (default: the default world)", required = false)
                string("label", "Short description for the undo history", required = false)
                int("wait_ms", "How long to collect its output after it ran (default 2500: FastAsyncWorldEdit finishes off the main thread)", required = false)
            },
            modifies = true,
        ) { args ->
            val world = plugin.world(args.string("world", null))
            val box = Box.of(args.vec("from"), args.vec("to")).requireVolume(plugin.limits.maxEditBlocks.toLong(), "worldedit")
            // From the console, `//replace` is typed `/replace`.
            val command = args.string("command").trim().let { if (it.startsWith("//")) it.substring(1) else it.removePrefix("/") }
            if (command.isBlank()) throw ToolError("Empty command")
            val label = args.string("label", null) ?: "worldedit ${command.take(60)}"
            val owner = Sync.main { (Bukkit.getCommandMap().getCommand(command.substringBefore(' ')) as? PluginIdentifiableCommand)?.plugin?.name }
            if (owner !in owners) throw ToolError("worldedit runs WorldEdit and WorldGuard commands only (/${command.substringBefore(' ')} is ${owner?.let { "$it's" } ?: "not one of theirs"}): use server_command for others")
            val name = command.substringBefore(' ').trimStart('/').substringAfter(':').lowercase()
            if (name !in editing) ServerTools.admin(args)
            val changes = name !in keepsBlocks
            synchronized(lock) {
                val change = Sync.main {
                    val weWorld = BukkitAdapter.adapt(world)
                    val session = WorldEdit.getInstance().sessionManager.get(BukkitAdapter.adapt(Bukkit.getConsoleSender()))
                    session.setWorldOverride(weWorld)
                    session.setRegionSelector(weWorld, CuboidRegionSelector(weWorld, box.min, box.max))
                    if (changes) Change(label, world).also { Edits.snapshot(it, box) } else null
                }
                val ran = ServerTools.run(command, null, args.int("wait_ms", if (changes) 2500 else 300).coerceIn(0, 60_000).toLong(), null, Bukkit.getConsoleSender())
                change?.let(Edits::record)
                val report = ServerTools.report(command, null, ran)
                if (report.isError || change == null) report
                else ToolResult.text("${(report.content.first().get("text").asString)}\nOn ${box.describe()} in ${world.name}; `undo` puts the box back.")
            }
        },
    )
}
