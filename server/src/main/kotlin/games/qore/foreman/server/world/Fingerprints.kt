package games.qore.foreman.server.world

import games.qore.foreman.server.Foreman
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.type.Chest
import org.bukkit.block.data.type.Fence
import org.bukkit.block.data.type.Fire
import org.bukkit.block.data.type.GlassPane
import org.bukkit.block.data.type.RedstoneWire
import org.bukkit.block.data.type.Tripwire
import org.bukkit.block.data.type.TripwireHook
import org.bukkit.block.data.type.Wall
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Hanging
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Marker
import org.bukkit.entity.TextDisplay
import org.joml.Matrix4f
import java.security.MessageDigest
import java.util.Locale

/**
 * What a paste left of an instance, as a hash ([Instance.fingerprint]), to tell the copies changed since: the blocks it
 * wrote (their states, less the properties that change by themselves, as players use things or as the blocks around
 * change: open, powered, a fence's connections, a stair's shape…) and their block data entries (the entities they name
 * aside: an undo puts furniture back as other entities), outside the boxes of the copies nested in it (a child's sync
 * doesn't make its parent look changed), and its own entities (tagged with it; mobs aside): type, position, yaw and
 * pitch (a fixed display's whole pose instead, however its turn is split between them and its transformation; only the
 * block of one that physics moves, which settles after the paste), what a display shows. Blocks it didn't write (where
 * its asset has air) aren't read. Main thread.
 */
object Fingerprints {
    /** Block state properties left out: they change without anyone editing the build, or with the blocks around it. */
    private val VOLATILE = setOf(
        "open", "powered", "lit", "occupied", "triggered", "enabled", "extended", "distance", "age", "moisture", "power",
        "stage", "snowy", "has_book", "signal_fire", "locked", "in_wall", "tilt", "hatch", "berries", "bites", "charges",
        "honey_level", "sculk_sensor_phase", "shape", "waterlogged", "instrument", "thickness", "bottom", "leaves", "side_chain",
    )

    /** The sides a block connects on to those around it: a fence's, a pane's, a wall's, a wire's (not a vine's: it hangs on them). */
    private val SIDES = setOf("north", "east", "south", "west", "up", "down")

    /** Blocks without a block data type of their own whose [SIDES] are connections to the blocks around them. */
    private val CONNECTING = setOf(Material.CHORUS_PLANT, Material.MUSHROOM_STEM, Material.BROWN_MUSHROOM_BLOCK, Material.RED_MUSHROOM_BLOCK)

    private val ATTACHED = setOf("attached")
    private val TRIPWIRE = SIDES + ATTACHED
    private val CHEST = setOf("type")

    enum class State { CLEAN, EDITED, UNKNOWN }

    /** The fingerprint of a copy moved while it was changed: it never matches again, until a sync or an attach. */
    const val CHANGED = "changed"

    /** [i]'s fingerprint as its world is now, null when it doesn't know the blocks it wrote. */
    fun of(world: World, i: Instance): String? {
        val written = i.written ?: return null
        val nested = Foreman.instance.worlds.descendants(world.name, i.id).map { it.box }
        fun own(x: Int, y: Int, z: Int) = nested.none { it.contains(x, y, z) }
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(s: String) = digest.update((s + "\n").toByteArray())

        val snapshots = Snapshots(world, i.box.min.x(), i.box.min.z(), i.box.max.x(), i.box.max.z()).load()
        val states = HashMap<BlockData, String>()
        written.blocks().filter { own(it.x(), it.y(), it.z()) }.forEach { p ->
            val data = snapshots.data(p.x(), p.y(), p.z())
            add(states.getOrPut(data) { stable(data) })
        }

        ChunkBlockData.read(world, i.box).filter { written.has(it.x, it.y, it.z) && own(it.x, it.y, it.z) }
            .map { "${it.namespace} ${it.x} ${it.y} ${it.z} ${canonical(RegionDiff.Nbt.read(it.data))}" }.sorted().forEach(::add)

        Instances.entities(world, i).filter { it !is LivingEntity || it is ArmorStand }.map(::entity).sorted().forEach(::add)
        return digest.digest().take(12).joinToString("") { "%02x".format(it) }
    }

    /** Whether [i] changed since its fingerprint was taken, here; [Instance.edited] (changed in its source) counts too. */
    fun state(world: World, i: Instance): State = state(i) { changed(world, i) }

    /** [i]'s [State], [changed] telling whether it changed where it is (as [Fingerprints.changed]): asked only when that decides it. */
    fun state(i: Instance, changed: () -> Boolean): State = when {
        i.edited -> State.EDITED
        i.fingerprint == null -> State.UNKNOWN
        changed() -> State.EDITED
        else -> State.CLEAN
    }

    /** Whether [i] was changed where it is since its fingerprint was taken (false when it has none). */
    fun changed(world: World, i: Instance): Boolean = i.fingerprint != null && (i.fingerprint == CHANGED || of(world, i) != i.fingerprint)

    /**
     * What a sync of [root] would undo: [root] if it [state] edited ([rootChanged]: whether it [changed] where it is,
     * known already), and the copies nested in it changed where they are (those under a detached one aside: a sync keeps
     * them; one that came changed from its source comes so again).
     */
    fun edited(world: World, root: Instance, rootChanged: Boolean): List<Instance> {
        val nested = Foreman.instance.worlds.descendants(world.name, root.id)
        val detached = nested.filter { it.detached }.map { it.id }
        return listOf(root).filter { state(it) { rootChanged } == State.EDITED } +
            nested.filter { i -> detached.none { i.id == it || i.isUnder(it) } && changed(world, i) }
    }

    /** [instances] with their fingerprints taken now, [edited] for those that came changed from their source. */
    fun taken(world: World, instances: List<Instance>, edited: Set<String> = emptySet()): List<Instance> =
        instances.map { it.copy(fingerprint = of(world, it), edited = it.id in edited) }

    /** [data] as text, less its [VOLATILE] properties and those the blocks around it set ([around]). */
    private fun stable(data: BlockData): String {
        val text = data.getAsString(false)
        val open = text.indexOf('[')
        if (open < 0) return text
        val set = around(data)
        val kept = text.substring(open + 1, text.length - 1).split(',').filter { p -> p.substringBefore('=').let { it !in VOLATILE && it !in set } }
        return text.substring(0, open) + kept.joinToString(",", "[", "]")
    }

    /** The properties of [data] that depend on its kind and the blocks around it: its connections, a chest's half. */
    private fun around(data: BlockData): Set<String> = when {
        data is Fence || data is GlassPane || data is Wall || data is RedstoneWire || data is Fire || data.material in CONNECTING -> SIDES
        data is Tripwire -> TRIPWIRE
        data is TripwireHook -> ATTACHED
        data is Chest -> CHEST
        else -> emptySet()
    }

    /** Whether physics moves [e] by itself (a boat, a minecart, an armor stand with gravity): it settles after a paste. */
    private fun settles(e: Entity) = e.hasGravity() && e !is Display && e !is Hanging && e !is Interaction && e !is Marker

    private fun entity(e: Entity): String {
        val l = e.location
        val out = StringBuilder(e.type.key.toString())
        if (settles(e)) return out.append(' ').append(l.blockX).append(' ').append(l.blockY).append(' ').append(l.blockZ).toString()
        out.append(" %.3f %.3f %.3f".format(Locale.ROOT, l.x, l.y, l.z))
        if (e is Display && e.billboard == Display.Billboard.FIXED) {
            val t = e.transformation
            val pose = Matrix4f().rotate(DisplayEdits.rotation(l.yaw, l.pitch)).translate(t.translation).rotate(t.leftRotation).scale(t.scale).rotate(t.rightRotation)
            FloatArray(16).also { pose.get(it) }.forEach { out.append(' ').append(Math.round(it * 1000) / 1000.0 + 0.0) }
        } else {
            out.append(" %.2f %.2f".format(Locale.ROOT, Placement.normalize(l.yaw), l.pitch))
            if (e is Display) {
                val t = e.transformation
                listOf(t.translation.x, t.translation.y, t.translation.z, t.leftRotation.x, t.leftRotation.y, t.leftRotation.z, t.leftRotation.w,
                    t.scale.x, t.scale.y, t.scale.z, t.rightRotation.x, t.rightRotation.y, t.rightRotation.z, t.rightRotation.w,
                ).forEach { out.append(' ').append(Math.round(it * 1000) / 1000.0 + 0.0) }
                out.append(' ').append(e.billboard)
            }
        }
        when (e) {
            is ItemDisplay -> out.append(' ').append(e.itemStack.type.key).append(' ').append(e.itemStack.itemMeta?.takeIf { it.hasItemModel() }?.itemModel ?: "")
            is BlockDisplay -> out.append(' ').append(e.block.getAsString(false))
            is TextDisplay -> out.append(' ').append(PlainTextComponentSerializer.plainText().serialize(e.text()))
        }
        return out.toString()
    }

    /** An NBT tree ([RegionDiff.Nbt]) as text that is the same for the same data, the UUIDs it holds (entities') aside. */
    private fun canonical(v: Any?): String = when (v) {
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") { "${it.key}:${canonical(it.value)}" }
        is List<*> -> v.joinToString(",", "[", "]") { canonical(it) }
        is String -> if (v.length == 36 && runCatching { java.util.UUID.fromString(v) }.isSuccess) "uuid" else v
        is ByteArray -> v.contentToString()
        is IntArray -> if (v.size == 4) "uuid" else v.contentToString()
        is LongArray -> v.contentToString()
        else -> v.toString()
    }
}
