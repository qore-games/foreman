package games.qore.foreman.server.bridge

import com.sk89q.worldedit.math.BlockVector3
import games.qore.foreman.server.Foreman
import games.qore.foreman.server.mcp.Args
import games.qore.foreman.server.mcp.ToolResult
import games.qore.foreman.server.world.Box
import games.qore.foreman.server.world.Edits

/**
 * What the bridge learns from a finished tool call: the box an edit changed and its label, read from the call's
 * arguments and result text (check issues come straight from QualityTools.check).
 */
object ToolEvents {
    private val BOX = Regex("""\[(-?\d+), (-?\d+), (-?\d+)] to \[(-?\d+), (-?\d+), (-?\d+)]""")

    fun text(result: ToolResult) = result.content.mapNotNull { it.get("text")?.asString }.joinToString("\n")

    /** The box a modifying call changed: its `mark`, its from/to, or the first box in its result. */
    fun changedBox(plugin: Foreman, args: Args, result: ToolResult): Box? {
        args.string("mark", null)?.let { name -> plugin.worlds.area(name) }?.let { return it.bounds }
        if (args.has("from") && args.has("to")) runCatching { return Box.of(args.vec("from"), args.vec("to")) }
        return box(text(result))
    }

    /** The label of the change the call recorded (the newest undo step, when it is that recent), else the result's first line. */
    fun label(result: ToolResult, ms: Long): String {
        val recent = Edits.recent.firstOrNull()?.takeIf { System.currentTimeMillis() - it.time <= ms + 1000 }
        return recent?.label ?: text(result).lineSequence().firstOrNull().orEmpty().take(120)
    }

    private fun box(text: String): Box? = BOX.find(text)?.groupValues?.let { g ->
        Box.of(BlockVector3.at(g[1].toInt(), g[2].toInt(), g[3].toInt()), BlockVector3.at(g[4].toInt(), g[5].toInt(), g[6].toInt()))
    }
}
