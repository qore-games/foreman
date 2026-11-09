package games.qore.foreman.server.tools

import games.qore.foreman.api.ForemanTool
import games.qore.foreman.server.Foreman
import games.qore.foreman.server.Msg
import games.qore.foreman.server.mcp.Args
import games.qore.foreman.server.mcp.Caller
import games.qore.foreman.server.mcp.Tool
import games.qore.foreman.server.mcp.ToolError
import games.qore.foreman.server.mcp.ToolResult
import games.qore.foreman.server.mcp.schema
import games.qore.foreman.server.world.Area
import games.qore.foreman.server.world.AreaLock
import games.qore.foreman.server.world.Edits
import games.qore.foreman.server.world.Locks
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit

/** Area locks ([Locks]): a builder keeps everyone else's Foreman edits out of the area they work in, until they unlock it. */
object LockTools {
    fun all(plugin: Foreman) = listOf(
        Tool(
            ForemanTool.LOCK_AREA,
            "Locks an area (one set_mark or claim made) to you: until you unlock it, everyone else's Foreman edits inside " +
                "it are refused, naming you (blocks, displays, furniture, asset copies, its points and areas), and on most " +
                "servers their own block placing and breaking there too. The console and players with foreman.lock.bypass " +
                "pass. Lock the area you build in while others build on the server; unlock_area when done. Returns the area.",
            schema {
                string("name", "Area name")
                string("world", "Its world (default: where an area of that name is)", required = false)
            },
        ) { args -> ToolResult.text(lock(plugin, area(plugin, args), args.caller ?: Caller.CONSOLE)) },
        Tool(
            ForemanTool.UNLOCK_AREA,
            "Unlocks an area you locked with lock_area: everyone's edits go in again. force unlocks one someone else holds " +
                "(foreman.admin; they are told).",
            schema {
                string("name", "Area name")
                string("world", "Its world (default: where an area of that name is)", required = false)
                bool("force", "Unlock it although someone else holds it (needs foreman.admin)", required = false)
            },
        ) { args ->
            val force = args.bool("force", false)
            if (force) ServerTools.admin(args)
            ToolResult.text(unlock(plugin, area(plugin, args), args.caller ?: Caller.CONSOLE, force))
        },
    )

    private fun area(plugin: Foreman, args: Args): Area {
        val name = args.string("name")
        val world = args.string("world", null)?.let { plugin.world(it).name }
        return (if (world != null) plugin.worlds.area(name, world) else plugin.worlds.area(name, plugin.world(null).name) ?: plugin.worlds.area(name))
            ?: throw ToolError("No area $name" + (world?.let { " in $it" } ?: "") + ": set_mark or claim makes one")
    }

    /** Locks [area] to [by]; refused while someone else holds it. What it says. */
    fun lock(plugin: Foreman, area: Area, by: Caller): String {
        var held: AreaLock? = null
        val locked = plugin.worlds.lock(area.name, area.world) { old ->
            if (old != null && old.uuid != by.id) throw ToolError("${area.name} is locked by ${old.describe()}: only they unlock it (or foreman.admin with unlock_area force)")
            held = old
            old ?: AreaLock.of(by)
        } ?: throw ToolError("No area ${area.name} in ${area.world}")
        if (held != null) return "${area.name} is already locked to you (locked ${Edits.ago(held!!.since)})"
        val blocks = plugin.config.getBoolean("locks.protect-blocks", true)
        return "Locked ${locked.name} (${locked.bounds.describe()} in ${locked.world}) to ${by.name}: others' Foreman edits inside it are refused" +
            (if (blocks) ", and their own block and entity changes there" else "") + " until you unlock it (unlock_area)."
    }

    /** Unlocks [area]: its holder, or anyone with [force] (checked by the caller). What it says. */
    fun unlock(plugin: Foreman, area: Area, by: Caller, force: Boolean): String {
        var held: AreaLock? = null
        plugin.worlds.lock(area.name, area.world) { old ->
            if (old != null && old.uuid != by.id && !force) throw ToolError("${area.name} is locked by ${old.describe()}: only they unlock it (foreman.admin can with force)")
            held = old
            null
        } ?: throw ToolError("No area ${area.name} in ${area.world}")
        val was = held ?: return "${area.name} isn't locked"
        if (was.uuid != by.id) Bukkit.getPlayer(was.uuid)?.let { holder ->
            Bukkit.getScheduler().runTask(plugin, Runnable { Msg.warning(holder, Component.text("${by.name} unlocked your area ${area.name}")) })
        }
        return "Unlocked ${area.name}" + if (was.uuid != by.id) " (it was ${was.name}'s)" else ""
    }
}
