package games.qore.foreman.server.tools

import games.qore.foreman.api.ForemanTool
import games.qore.foreman.server.Foreman
import games.qore.foreman.server.mcp.Args
import games.qore.foreman.server.mcp.Tool
import games.qore.foreman.server.mcp.ToolError
import games.qore.foreman.server.mcp.ToolResult
import games.qore.foreman.server.mcp.schema
import games.qore.foreman.server.world.Items
import games.qore.foreman.server.world.Sync
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import java.util.concurrent.ConcurrentHashMap

/**
 * Finding item duplication in plugins: count an item everywhere ([ForemanTool.ITEM_LEDGER]), watch the inventory events
 * a test fires and who handles them ([ForemanTool.INVENTORY_WATCH]), and put tested inventories back
 * ([ForemanTool.TEST_RESTORE]). A dupe leaves more of an item than it started with; the ledger's before/after total
 * proves it, the watch points at the listener. Tests of any kind keep players as they found them
 * ([ForemanTool.TEST_PLAYER]) and see the server's view of them tick by tick ([ForemanTool.SERVER_TRACE]). All need
 * [PERMISSION]: lag and action bursts that trigger dupes are the [games.qore.foreman.server.bridge.Bridge] client's,
 * gated the same way.
 */
object TestTools {
    /** What a per-player token's player needs for the testing tools (never the normal player: it drives dupe attempts). */
    const val PERMISSION = "foreman.test"

    /** The last count per item key, so the ledger reports the change since you last ran it. */
    private val baseline = ConcurrentHashMap<String, Items.Tally>()
    /** Saved inventories by label, for test_restore. */
    private val saved = ConcurrentHashMap<String, Map<java.util.UUID, SavedInv>>()

    private class SavedInv(val contents: Array<ItemStack?>, val ender: Array<ItemStack?>)

    /** Players as tests found them, by label, for test_player. */
    private val kept = ConcurrentHashMap<String, Map<java.util.UUID, Kept>>()

    /** A player as a test found them. [invulnerable] is their own flag, before test_player protected them. */
    private class Kept(
        val location: Location, val gameMode: GameMode, val health: Double, val food: Int, val saturation: Float,
        val exhaustion: Float, val fire: Int, val air: Int, val effects: Collection<PotionEffect>, val level: Int,
        val exp: Float, val allowFlight: Boolean, val flying: Boolean, val invulnerable: Boolean, val inventory: SavedInv,
    )

    fun all(plugin: Foreman) = listOf(
        Tool(
            ForemanTool.ITEM_LEDGER,
            "Counts an item everywhere it can be on the server — every online player's inventory, ender chest and cursor, " +
                "the containers and item frames, armor stands, dropped items and entities with inventories (mules, chest/" +
                "hopper minecarts) of the loaded world — and reports the total, a per-place breakdown, and the change since " +
                "you last ran it for this item. Run it before and after a dupe attempt: a bigger total is duplication, and " +
                "the breakdown says where the extra copies are. Counts the exact item (its components) by default.",
            schema {
                string("item", "What to count: `held` (the caller's held item, matched with its components) or a material like `DIAMOND`", required = false)
                bool("exact", "Match the full item incl. components (default true for `held`, false for a material)", required = false)
                string("world", "World to scan (default: the caller's, or the first online player's)", required = false)
                array("from", intItems(), "A corner of a box to limit the scan to, players included by where they stand (default: the whole loaded world)", required = false)
                array("to", intItems(), "The opposite corner of the box", required = false)
                bool("reset", "Forget the baseline for this item and start fresh (no change reported)", required = false)
            },
        ) { args ->
            requireTest(args)
            Sync.main { ledger(args) }
        },

        Tool(
            ForemanTool.INVENTORY_WATCH,
            "Records the inventory events fired over the next few seconds and whether each ended cancelled, with the plugins " +
                "whose listeners are in the chain for that event. Run it while you make a dupe attempt (clicks, drops, item " +
                "moves): it shows which action a plugin let through or handled wrong. Reports the handler plugins per event " +
                "type, then each event in order.",
            schema {
                int("seconds", "How long to record (default 8, max 60)", required = false)
                string("player", "Only this player's events (default: everyone)", required = false)
            },
        ) { args ->
            requireTest(args)
            watch(plugin, args)
        },

        Tool(
            ForemanTool.TEST_RESTORE,
            "Saves or restores players' inventories (contents, armor, offhand and ender chest), so each dupe test starts " +
                "from the same state. `save` with a label, run your test, then `restore` the label. By default it's the " +
                "caller; name players for more. Overwrites what's there now: for a test server.",
            schema {
                string("action", "`save` or `restore`")
                string("label", "A name for the saved state (default `test`)", required = false)
                strings("players", "Player names (default: the caller)", required = false)
            },
        ) { args ->
            requireTest(args)
            Sync.main { restore(args) }
        },

        Tool(
            ForemanTool.TEST_PLAYER,
            "Saves or restores players as a test found them: where they stand and look, game mode, health, hunger, " +
                "effects, experience, flight, fire and air, inventory and ender chest. `protect` with `save` keeps them " +
                "from any damage until the restore; a dead player is respawned first. For tests that move, hurt or kill " +
                "the player (the client's client_test `keep_player` uses it). By default it's the caller.",
            schema {
                string("action", "`save` or `restore`")
                string("label", "A name for the saved state (default `test`)", required = false)
                bool("protect", "With save: no damage until the restore (default false)", required = false)
                strings("players", "Player names (default: the caller)", required = false)
            },
        ) { args ->
            requireTest(args)
            Sync.main { keep(args) }
        },

        Tool(
            ForemanTool.SERVER_TRACE,
            "Records the server's view of a player, tick by tick: where it last heard they are and how far that moved " +
                "since the tick before, their on-ground flag (the client's), the keys they hold, pose, the nearest `limit` " +
                "entities of `entities` types within `radius` (box bottom..top, the player's feet against its top), and " +
                "the values plugins add (ForemanTools.registerTraceValues). Returns a summary line (as client_trace's, " +
                "plus each plugin number's range), then a row every `every` ticks. Alongside client_trace (a client_test " +
                "`parallel`) it shows what the server and the client each have, for movement and collision bugs.",
            schema {
                string("player", "Whom (default: the caller)", required = false)
                int("ticks", "How long (default 60, max ${ServerTrace.MAX_TICKS})", required = false)
                int("every", "A row every so many ticks (default 1)", required = false)
                string("entities", "Entity types to follow, comma-separated parts of their ids (shulker, item_display…)", required = false)
                number("radius", "Blocks around the player to find them (default 8)", required = false)
                int("limit", "The nearest ones followed (default 4)", required = false)
                bool("rows", "The rows after the summary line (default true; false: the summary only)", required = false)
            },
        ) { args ->
            requireTest(args)
            ServerTrace.trace(plugin, args)
        },
    )

    /** With a per-player token: the player needs [PERMISSION]. The main/server token passes (it's the console's). */
    fun requireTest(args: Args) {
        val caller = args.caller ?: return
        val allowed = Bukkit.getPlayer(caller.id)?.hasPermission(PERMISSION) ?: Bukkit.getOfflinePlayer(caller.id).isOp
        if (!allowed) throw ToolError("This needs $PERMISSION, which ${caller.name} doesn't have")
    }

    private fun ledger(args: Args): ToolResult {
        val template = template(args)
        val exact = args.bool("exact", (args.string("item", "held") ?: "held") == "held")
        val type = template.type
        val match: (ItemStack) -> Boolean = if (exact && template.hasItemMeta()) ({ it.isSimilar(template) }) else ({ it.type == type })

        val world = args.string("world", null)?.let { Bukkit.getWorld(it) ?: throw ToolError("No world '$it'") }
            ?: args.caller?.let { Bukkit.getPlayer(it.id)?.world } ?: Bukkit.getOnlinePlayers().firstOrNull()?.world
            ?: throw ToolError("No world to scan: name one, or have a player online")
        val box = box(args)
        val tally = Items.count(Items.Scope(world, box), match)

        val key = "${type.name}|$exact|${world.name}|${box?.joinToString(",") ?: ""}"
        val previous = if (args.bool("reset", false)) null else baseline[key]
        baseline[key] = tally

        val name = type.name.lowercase() + if (exact && template.hasItemMeta()) " (exact)" else ""
        return ToolResult.text(buildString {
            appendLine("$name in ${world.name}${box?.let { " box ${it[0]},${it[1]},${it[2]}..${it[3]},${it[4]},${it[5]}" } ?: ""}: ${tally.total}")
            appendLine("- players ${tally.players}, containers ${tally.containers}, entities ${tally.entities}, ground ${tally.ground}")
            if (previous != null) {
                val d = tally.total - previous.total
                appendLine(if (d == 0L) "No change since the last ledger." else "Change since the last ledger: ${if (d > 0) "+$d (duplication!)" else "$d"}")
                if (d != 0L) appendLine("  players ${delta(tally.players - previous.players)}, containers ${delta(tally.containers - previous.containers)}, entities ${delta(tally.entities - previous.entities)}, ground ${delta(tally.ground - previous.ground)}")
            } else appendLine("Baseline set. Run the test, then call item_ledger again to see the change.")
            if (tally.spots.isNotEmpty()) {
                appendLine("Where:")
                tally.spots.take(12).forEach { appendLine("  ${it.amount}× ${it.what} at ${it.where}") }
            }
        })
    }

    private fun watch(plugin: Foreman, args: Args): ToolResult {
        val seconds = args.int("seconds", 8).coerceIn(1, 60)
        val only = args.string("player", null)?.lowercase()
        val log = java.util.concurrent.CopyOnWriteArrayList<String>()
        val start = System.currentTimeMillis()
        fun at() = "%.1fs".format((System.currentTimeMillis() - start) / 1000.0)

        val listener = object : Listener {
            private fun note(who: String?, line: String, cancelled: Boolean) {
                if (only != null && who?.lowercase() != only) return
                log += "[${at()}] $line${if (cancelled) " → CANCELLED" else ""}"
            }

            @EventHandler(priority = EventPriority.MONITOR)
            fun onClick(e: InventoryClickEvent) = note(e.whoClicked.name,
                "CLICK ${e.whoClicked.name} ${e.action} ${e.click} slot=${e.slot}/${e.rawSlot} ${item(e.currentItem)} cursor=${item(e.cursor)}", e.isCancelled)

            @EventHandler(priority = EventPriority.MONITOR)
            fun onDrag(e: InventoryDragEvent) = note(e.whoClicked.name,
                "DRAG ${e.whoClicked.name} slots=${e.rawSlots} ${item(e.oldCursor)}", e.isCancelled)

            @EventHandler(priority = EventPriority.MONITOR)
            fun onMove(e: InventoryMoveItemEvent) = note(null,
                "MOVE ${holder(e.source)} → ${holder(e.destination)} ${item(e.item)}", e.isCancelled)

            @EventHandler(priority = EventPriority.MONITOR)
            fun onPickup(e: EntityPickupItemEvent) = note((e.entity as? Player)?.name,
                "PICKUP ${e.entity.name} ${item(e.item.itemStack)}", e.isCancelled)

            @EventHandler(priority = EventPriority.MONITOR)
            fun onDrop(e: PlayerDropItemEvent) = note(e.player.name,
                "DROP ${e.player.name} ${item(e.itemDrop.itemStack)}", e.isCancelled)
        }

        Sync.main { Bukkit.getPluginManager().registerEvents(listener, plugin) }
        try {
            Thread.sleep(seconds * 1000L)
        } finally {
            Sync.main { HandlerList.unregisterAll(listener) }
        }

        return ToolResult.text(buildString {
            appendLine("Handlers (plugins whose listeners run for each event):")
            appendLine("  InventoryClickEvent: ${handlers(InventoryClickEvent.getHandlerList())}")
            appendLine("  InventoryDragEvent: ${handlers(InventoryDragEvent.getHandlerList())}")
            appendLine("  InventoryMoveItemEvent: ${handlers(InventoryMoveItemEvent.getHandlerList())}")
            appendLine("  EntityPickupItemEvent: ${handlers(EntityPickupItemEvent.getHandlerList())}")
            appendLine("  PlayerDropItemEvent: ${handlers(PlayerDropItemEvent.getHandlerList())}")
            appendLine()
            if (log.isEmpty()) appendLine("No inventory events in ${seconds}s.")
            else {
                appendLine("${log.size} events:")
                log.forEach { appendLine("  $it") }
            }
        })
    }

    private fun restore(args: Args): ToolResult {
        val label = args.string("label", "test") ?: "test"
        val players = players(args)
        return when (args.string("action")) {
            "save" -> {
                // Deep copy: a shared ItemStack is mutated in place when its stack grows, which would change the save.
                saved[label] = players.associate { it.uniqueId to SavedInv(deep(it.inventory.contents), deep(it.enderChest.contents)) }
                ToolResult.text("Saved ${players.joinToString { it.name }} as `$label`. Restore it after the test.")
            }
            "restore" -> {
                val snap = saved[label] ?: throw ToolError("No saved state `$label`. Save one first.")
                var n = 0
                for (player in players) snap[player.uniqueId]?.let {
                    player.inventory.contents = deep(it.contents)
                    player.enderChest.contents = deep(it.ender)
                    n++
                }
                ToolResult.text("Restored $n player(s) from `$label`.")
            }
            else -> throw ToolError("action is `save` or `restore`")
        }
    }

    private fun keep(args: Args): ToolResult {
        val label = args.string("label", "test") ?: "test"
        val players = players(args)
        return when (args.string("action")) {
            "save" -> {
                val protect = args.bool("protect", false)
                kept[label] = players.associate { p ->
                    p.uniqueId to Kept(
                        p.location, p.gameMode, p.health, p.foodLevel, p.saturation, p.exhaustion, p.fireTicks, p.remainingAir,
                        p.activePotionEffects.toList(), p.level, p.exp, p.allowFlight, p.isFlying, p.isMarkedInvulnerable,
                        SavedInv(deep(p.inventory.contents), deep(p.enderChest.contents)),
                    ).also { if (protect) p.setInvulnerable(true) }
                }
                ToolResult.text("Saved ${players.joinToString { it.name }} as `$label`${if (protect) ", unhurt until the restore" else ""}.")
            }
            "restore" -> {
                val snap = kept[label] ?: throw ToolError("No saved player state `$label`. Save one first.")
                val done = players.mapNotNull { p -> snap[p.uniqueId]?.let { putBack(p, it); p.name } }
                ToolResult.text(if (done.isEmpty()) "Nothing saved as `$label` for ${players.joinToString { it.name }}" else "Restored ${done.joinToString()} from `$label`.")
            }
            else -> throw ToolError("action is `save` or `restore`")
        }
    }

    /** Puts [p] back as [k] says. */
    private fun putBack(p: Player, k: Kept) {
        if (p.isDead) p.spigot().respawn()
        p.gameMode = k.gameMode
        p.teleport(k.location)
        p.velocity = org.bukkit.util.Vector()
        p.fallDistance = 0f
        p.health = k.health.coerceAtMost(p.getAttribute(Attribute.MAX_HEALTH)?.value ?: k.health)
        p.foodLevel = k.food
        p.saturation = k.saturation
        p.exhaustion = k.exhaustion
        p.fireTicks = k.fire
        p.remainingAir = k.air
        p.activePotionEffects.forEach { p.removePotionEffect(it.type) }
        p.addPotionEffects(k.effects)
        p.level = k.level
        p.exp = k.exp
        p.allowFlight = k.allowFlight
        p.isFlying = k.flying && k.allowFlight
        p.setInvulnerable(k.invulnerable)
        p.inventory.contents = deep(k.inventory.contents)
        p.enderChest.contents = deep(k.inventory.ender)
    }

    /** The players named in `players`, else the caller. */
    private fun players(args: Args): List<Player> = args.strings("players").ifEmpty {
        listOfNotNull(args.caller?.let { Bukkit.getPlayer(it.id)?.name })
    }.map { Bukkit.getPlayerExact(it) ?: throw ToolError("$it isn't online") }.ifEmpty { throw ToolError("No players: name some, or call as a player in game") }

    private fun template(args: Args): ItemStack = when (val item = args.string("item", "held") ?: "held") {
        "held" -> args.caller?.let { Bukkit.getPlayer(it.id)?.inventory?.itemInMainHand?.takeIf { s -> !s.type.isAir } }
            ?: throw ToolError("Hold an item, or name a material")
        else -> ItemStack(Material.matchMaterial(item) ?: throw ToolError("Unknown item '$item': hold one and pass `held`, or give a material"))
    }

    private fun box(args: Args): IntArray? {
        if (!args.has("from") || !args.has("to")) return null
        val a = args.array("from").map { it.asInt }
        val b = args.array("to").map { it.asInt }
        if (a.size != 3 || b.size != 3) throw ToolError("from and to are [x, y, z]")
        return intArrayOf(minOf(a[0], b[0]), minOf(a[1], b[1]), minOf(a[2], b[2]), maxOf(a[0], b[0]), maxOf(a[1], b[1]), maxOf(a[2], b[2]))
    }

    private fun deep(contents: Array<ItemStack?>) = Array(contents.size) { contents[it]?.clone() }

    private fun item(stack: ItemStack?) = if (stack == null || stack.type.isAir) "empty" else "${stack.type.name.lowercase()} x${stack.amount}"
    private fun holder(inv: org.bukkit.inventory.Inventory) = inv.holder?.let { it::class.java.simpleName } ?: inv.type.name.lowercase()
    private fun handlers(list: HandlerList) = list.registeredListeners.map { it.plugin.name }.distinct().joinToString().ifEmpty { "none" }
    private fun delta(n: Long) = if (n > 0) "+$n" else "$n"

    private fun intItems() = com.google.gson.JsonObject().apply { addProperty("type", "integer") }
}
