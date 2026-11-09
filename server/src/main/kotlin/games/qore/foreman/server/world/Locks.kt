package games.qore.foreman.server.world

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import games.qore.foreman.server.Foreman
import games.qore.foreman.server.Msg
import games.qore.foreman.server.api.WorldDataChangeEvent
import games.qore.foreman.server.mcp.Caller
import games.qore.foreman.server.mcp.ToolError
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Hanging
import org.bukkit.entity.Interaction
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.Cancellable
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.hanging.HangingBreakByEntityEvent
import org.bukkit.event.hanging.HangingPlaceEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerBucketFillEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.world.WorldLoadEvent
import org.bukkit.event.world.WorldUnloadEvent
import java.util.UUID

/** Who holds an area's lock: the player's [name] and [uuid] (the console's is zero), since when. */
data class AreaLock(val name: String, val uuid: UUID, val since: Long) {
    fun describe() = "$name (${Edits.ago(since)})"

    fun json() = JsonObject().apply {
        addProperty("name", name)
        addProperty("uuid", uuid.toString())
        addProperty("since", since)
    }

    companion object {
        fun of(caller: Caller) = AreaLock(caller.name, caller.id, System.currentTimeMillis())

        /** A stored lock (`locked_by`), null when there is none or it doesn't read. */
        fun of(e: JsonElement?): AreaLock? = (e as? JsonObject)?.let { o ->
            runCatching { AreaLock(o.get("name").asString, UUID.fromString(o.get("uuid").asString), o.get("since")?.asLong ?: 0L) }.getOrNull()
        }
    }
}

/**
 * Area locks, for several builders on one server: a player locks an area ([Area.lock]) and, until it is unlocked, the
 * Foreman edits of anyone else that reach into it are refused, naming who holds it: blocks (the tools' own checks
 * through [guard], and [verify] after any edit session), displays, furniture, asset copies, the points and areas in it.
 * The holder and players with [BYPASS] pass, the console and the server's token too. With `locks.protect-blocks`,
 * players' own block and entity changes inside are refused as well ([Protect]): a listener registered only while a lock
 * exists.
 */
object Locks : Listener {
    const val BYPASS = "foreman.lock.bypass"
    private lateinit var plugin: Foreman

    /** The locked areas of each loaded world: read on every guarded change, rebuilt when areas change. */
    @Volatile
    private var locked: Map<String, List<Area>> = emptyMap()
    private var protecting = false

    val any get() = locked.isNotEmpty()

    fun start(plugin: Foreman) {
        this.plugin = plugin
        Bukkit.getPluginManager().registerEvents(this, plugin)
        refresh()
    }

    @EventHandler
    fun onChange(event: WorldDataChangeEvent) {
        if (event.part == WorldDataChangeEvent.Part.AREAS) refresh()
    }

    // After WorldData has read or forgotten the world.
    @EventHandler
    fun onLoad(event: WorldLoadEvent) {
        Bukkit.getScheduler().runTask(plugin, Runnable { refresh() })
    }

    @EventHandler
    fun onUnload(event: WorldUnloadEvent) {
        Bukkit.getScheduler().runTask(plugin, Runnable { refresh() })
    }

    /** Reads the locked areas again; registers [Protect] while there are some and the config wants it. Main thread. */
    fun refresh() {
        locked = plugin.worlds.areas.filter { it.lock != null }.groupBy { it.world }
        val protect = locked.isNotEmpty() && plugin.config.getBoolean("locks.protect-blocks", true)
        if (protect == protecting) return
        protecting = protect
        if (protect) Bukkit.getPluginManager().registerEvents(Protect, plugin) else HandlerList.unregisterAll(Protect)
    }

    /** Whether [caller] passes every lock: the console (null too) and players with [BYPASS]. */
    fun passes(caller: Caller?): Boolean = caller == null || caller.isConsole || Bukkit.getPlayer(caller.id)?.hasPermission(BYPASS) == true

    /** The first area of [world] locked by someone other than [caller] for which [hits] holds, unless [caller] passes. */
    private fun blocking(world: String, caller: Caller?, hits: (Area) -> Boolean): Area? {
        val areas = locked[world] ?: return null
        val others = areas.filter { it.lock!!.uuid != caller?.id && hits(it) }
        return if (others.isEmpty() || passes(caller)) null else others.first()
    }

    /** Why a change into [area] is refused. */
    fun refusal(area: Area) =
        "${area.name} is locked by ${area.lock!!.describe()} (${area.bounds.describe()} in ${area.world}): only they change what is inside it. " +
            "Work outside it, or ask them to unlock it."

    /** Refuses (a [ToolError]) a change of [box] in [world] that reaches into an area locked by someone other than [caller]. */
    fun guard(world: String, box: Box, caller: Caller? = Caller.current()) {
        locks(world, box, caller)?.let { throw ToolError(refusal(it)) }
    }

    /** The area of [world] locked by someone other than [caller] that [box] reaches into, unless [caller] passes. */
    fun locks(world: String, box: Box, caller: Caller? = Caller.current()): Area? =
        blocking(world, caller) { a -> a.boxes.any { it.intersect(box) != null } }

    /** Refuses a change at [at] (an entity, a point) inside an area locked by someone other than [caller]. */
    fun guard(at: Location, caller: Caller? = Caller.current()) {
        locks(at, caller)?.let { throw ToolError(refusal(it)) }
    }

    /** The area locked by someone other than [caller] that [at] is in, unless [caller] passes. */
    fun locks(at: Location, caller: Caller?): Area? {
        val world = at.world?.name ?: return null
        return blocking(world, caller) { a -> a.boxes.any { it.contains(at.blockX, at.blockY, at.blockZ) } }
    }

    /**
     * Refuses a change of the area [name] in [world] whose boxes were or will be [boxes]: the locked area itself, or one
     * reaching into it (an area around it all stays free: a zone holding several builds).
     */
    fun guardArea(world: String, name: String, boxes: List<Box>, caller: Caller? = Caller.current()) {
        if (!any) return
        val bounds = boxes.reduceOrNull(Box::union)
        blocking(world, caller) { l ->
            l.name == name || bounds != null && !bounds.containsBox(l.bounds) && boxes.any { b -> l.boxes.any { it.intersect(b) != null } }
        }?.let { throw ToolError(refusal(it)) }
    }

    /** Refuses adding, moving or removing [points] of [world] when one is inside an area locked by someone else. */
    fun guardPoints(world: String, points: Collection<Point>, caller: Caller? = Caller.current()) {
        if (!any || points.isEmpty()) return
        blocking(world, caller) { l -> points.any { p -> l.boxes.any { p.inside(it) } } }?.let { throw ToolError(refusal(it)) }
    }

    /**
     * After an edit session ([Edits.scoped]), the backstop of the tools' own checks: when [change] changed a block (or an
     * entity) inside an area locked by someone other than its author, it is taken back and refused. Off the main thread
     * only: its blocks are read from its history, written by FastAsyncWorldEdit a few ms after.
     */
    fun verify(change: Change) {
        val areas = locked[change.world.name] ?: return
        if (Bukkit.isPrimaryThread() || passes(change.author)) return
        val others = areas.filter { it.lock!!.uuid != change.author.id }.ifEmpty { return }
        Edits.await(listOf(change))
        val box = change.box ?: return
        val near = others.filter { a -> a.boxes.any { it.intersect(box) != null } }.ifEmpty { return }
        val hit = near.firstOrNull { a -> Edits.reaches(change) { x, y, z -> a.boxes.any { it.contains(x, y, z) } } } ?: return
        Edits.revert(change)
        throw ToolError(refusal(hit) + " Nothing was changed.")
    }

    /** Players' own changes inside areas locked by someone else, refused: registered only while a lock exists. */
    private object Protect : Listener {
        /** When each player was last told, so that holding the button doesn't flood their chat. */
        private val told = HashMap<UUID, Long>()

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onBreak(event: BlockBreakEvent) = refuse(event, event.player, event.block.location)

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onPlace(event: BlockPlaceEvent) = refuse(event, event.player, event.block.location)

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onEmpty(event: PlayerBucketEmptyEvent) = refuse(event, event.player, event.block.location)

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onFill(event: PlayerBucketFillEvent) = refuse(event, event.player, event.block.location)

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onInteract(event: PlayerInteractEntityEvent) {
            if (guarded(event.rightClicked)) refuse(event, event.player, event.rightClicked.location)
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onInteractAt(event: PlayerInteractAtEntityEvent) {
            if (guarded(event.rightClicked)) refuse(event, event.player, event.rightClicked.location)
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onDamage(event: EntityDamageByEntityEvent) {
            val player = player(event.damager) ?: return
            if (guarded(event.entity)) refuse(event, player, event.entity.location)
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onHangingBreak(event: HangingBreakByEntityEvent) {
            val player = event.remover?.let(::player) ?: return
            refuse(event, player, event.entity.location)
        }

        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        fun onHangingPlace(event: HangingPlaceEvent) {
            refuse(event, event.player ?: return, event.block.getRelative(event.blockFace).location)
        }

        /** Displays, furniture hitboxes, item frames and paintings, armor stands: what builds are decorated with. */
        private fun guarded(entity: Entity) = entity is Display || entity is Interaction || entity is Hanging || entity is ArmorStand

        private fun player(entity: Entity): Player? = entity as? Player ?: (entity as? Projectile)?.shooter as? Player

        private fun refuse(event: Cancellable, player: Player, at: Location) {
            val area = locks(at, Caller.of(player)) ?: return
            event.isCancelled = true
            val now = System.currentTimeMillis()
            if (now - (told[player.uniqueId] ?: 0L) < 2000) return
            told[player.uniqueId] = now
            Msg.error(player, Component.text("${area.name} is locked by ${area.lock!!.name}: only they build in it"))
        }
    }
}
