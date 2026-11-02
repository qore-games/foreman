package games.qore.foreman.client.packets

import games.qore.foreman.client.packets.PacketReports.bytes
import games.qore.foreman.client.packets.PacketReports.count
import games.qore.foreman.client.packets.PacketReports.share
import net.minecraft.client.Minecraft
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.world.entity.Display
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import java.lang.reflect.Modifier
import java.util.Locale
import kotlin.math.sqrt

/**
 * The received traffic grouped by what likely sends it: the same kind of entity (a model's parts at one place, every
 * zombie…), the same boss bar or action bar text, a plugin channel, chunk updates. The client can't see which plugin
 * sent a packet; each suspect comes with what identifies it (item models, custom data keys, translation keys, texts,
 * names, channels) to search in the plugins' sources and configs, how often it is resent, and which entity fields
 * change.
 */
object PacketSuspects {
    /** What the client knows of an entity (read on the client thread). */
    class EntityInfo(val type: String, val kind: String, val label: String, val clues: List<String>, val fields: Map<Int, String>)

    class Suspect(
        val title: String, val where: String?, val count: Long, val wire: Long, val repeats: Long, val subjects: Int,
        val seconds: Double, val types: List<Triple<String, Long, Long>>, val cadence: String?, val fields: List<Pair<String, Long>>,
        val clues: List<String>, val hint: String?, val sample: String?,
        /** For the viewer: the packets to list when it is clicked. */
        val mainType: String, val subject: Long,
    )

    // ---- Entities ---------------------------------------------------------------------------------------------------

    /** On the client thread: what the entities with the most packets are. */
    fun capture(limit: Int = 500): Map<Int, EntityInfo> {
        val level = Minecraft.getInstance().level ?: return emptyMap()
        return PacketRecorder.entities(limit).mapNotNull { s ->
            val id = Subject.entityId(s.subject)
            level.getEntity(id)?.let { id to info(it) }
        }.toMap()
    }

    fun info(e: Entity): EntityInfo {
        val type = PacketInspector.short(EntityType.getKey(e.type))
        val p = e.blockPosition()
        val clues = ArrayList<String>()
        val content = when (e) {
            is Display.ItemDisplay -> e.itemStack.takeUnless { it.isEmpty }?.let { stack ->
                val item = BuiltInRegistries.ITEM.getKey(stack.item).toString()
                val model = stack.get(net.minecraft.core.component.DataComponents.ITEM_MODEL)?.toString()
                (model ?: item).also { clues += it }
            }
            is Display.BlockDisplay -> BuiltInRegistries.BLOCK.getKey(e.blockState.block).toString().also { clues += it }
            is Display.TextDisplay -> PacketInspector.readable(e.text.string).trim().take(40).takeIf { it.isNotEmpty() }?.also { clues += "\"$it\"" }
            else -> null
        }
        val name = if (e.hasCustomName()) e.customName?.string?.let { PacketInspector.readable(it).trim() }?.takeIf { it.isNotEmpty() } else null
        name?.let { clues += "\"${it.take(40)}\"" }
        val root = e.rootVehicle
        // A model's parts stand together (display entities at one place) or ride one entity; mobs group by type.
        val kind = when {
            e is Display -> "$type at ${p.x} ${p.y} ${p.z}"
            root !== e -> "$type riding ${EntityType.getKey(root.type).path} #${root.id}"
            else -> type + (name?.let { " \"${it.replace(numbers, "#").take(30)}\"" } ?: "")
        }
        val away = Minecraft.getInstance().player?.let { String.format(Locale.ROOT, ", %.0f blocks away", sqrt(it.distanceToSqr(e))) } ?: ""
        val label = type + (content?.let { " ($it)" } ?: "") + (name?.let { " \"${it.take(30)}\"" } ?: "") + " at ${p.x} ${p.y} ${p.z}$away"
        return EntityInfo(type, kind, label, clues, dataNames.get(e.javaClass))
    }

    /** Entity data ids → field names (`DATA_LEFT_ROTATION_ID` → `left_rotation`), from the entity's class. */
    private val dataNames = object : ClassValue<Map<Int, String>>() {
        override fun computeValue(type: Class<*>): Map<Int, String> = generateSequence(type) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .filter { Modifier.isStatic(it.modifiers) && EntityDataAccessor::class.java.isAssignableFrom(it.type) && it.trySetAccessible() }
            .mapNotNull { f -> (runCatching { f.get(null) }.getOrNull() as? EntityDataAccessor<*>)?.let { it.id() to f.name.removePrefix("DATA_").removeSuffix("_ID").lowercase() } }
            .toMap()
    }

    // ---- Analysis ---------------------------------------------------------------------------------------------------

    private class Group(val title: String, val info: EntityInfo?) {
        var count = 0L
        var wire = 0L
        var repeats = 0L
        var first = Long.MAX_VALUE
        var last = 0L
        val types = HashMap<String, LongArray>()
        val subjects = HashSet<Long>()
        /** Per subject and type: when it was last seen, for the rhythm. */
        val seen = HashMap<Long, Long>()
        /** Intervals in ticks: 0 (same tick) to 40, 41 longer. */
        val ticks = IntArray(42)
        val fields = HashMap<Int, Long>()
        val clues = LinkedHashSet<String>()
        var sample: String? = null
    }

    private val dataIds = Regex("#(\\d+)=")
    private val ids = Regex("(?<![\\w.:/-])([a-z0-9_.-]*[a-z][a-z0-9_.-]*):([a-z0-9_./-]*[a-z][a-z0-9_./-]*)")
    private val keys = Regex("key=([\\w.-]+)")
    private val texts = Regex("\"([^\"]{4,60})\"")
    private val glyphRuns = Regex("⟨\\d+ glyphs?⟩")
    private val numbers = Regex("\\d+")

    /** The suspects of the kept received packets, the most bytes first. [infos] from [capture]. */
    fun analyze(infos: Map<Int, EntityInfo>, limit: Int = 30): List<Suspect> {
        val groups = HashMap<String, Group>()
        var total = 0L
        var first = Long.MAX_VALUE
        var last = 0L
        for (e in PacketRecorder.events()) {
            if (e.outbound) continue
            total += e.wire
            first = minOf(first, e.t)
            last = maxOf(last, e.t)
            val group = when {
                Subject.isEntity(e.subject) -> {
                    val id = Subject.entityId(e.subject)
                    val info = infos[id]
                    // Not in the client's world: removed since, or never spawned for this player (packets for nothing).
                    val kind = info?.kind ?: PacketRecorder.entityType(id)?.let { "$it (gone)" } ?: "entities the client doesn't have"
                    groups.getOrPut("E|$kind") { Group(kind, info) }.also { g -> if (info != null && g.clues.size < 12) g.clues += info.clues }
                }
                Subject.isChunk(e.subject) -> groups.getOrPut("C|${e.type}") { Group("${e.type} (chunks)", null) }
                else -> {
                    val norm = e.detail?.replace(numbers, "#")?.take(80).orEmpty()
                    groups.getOrPut("T|${e.type}|$norm") { Group(e.type + (e.detail?.let { d -> texts.find(d)?.groupValues?.get(1)?.let { " \"${it.take(40)}\"" } } ?: ""), null) }
                }
            }
            add(group, e)
        }
        val seconds = ((last - first) / 1e9).coerceAtLeast(1.0)
        return groups.values.sortedByDescending { it.wire }.take(limit).map { suspect(it, total, seconds) }
    }

    private fun add(g: Group, e: PacketRecorder.Event) {
        g.count++
        g.wire += e.wire
        if (e.repeat) g.repeats++
        g.first = minOf(g.first, e.t)
        g.last = maxOf(g.last, e.t)
        val t = g.types.getOrPut(e.type) { LongArray(2) }
        t[0]++
        t[1] += e.wire.toLong()
        if (e.subject != Subject.NONE && g.subjects.size < 10_000) g.subjects += e.subject
        val key = e.subject * 31 + e.type.hashCode()
        g.seen.put(key, e.t)?.let { previous -> g.ticks[((e.t - previous + 25_000_000) / 50_000_000).toInt().coerceIn(0, 41)]++ }
        val detail = e.detail ?: return
        if (g.sample == null) g.sample = detail
        if (e.type == "set_entity_data") dataIds.findAll(detail).forEach { m -> g.fields.merge(m.groupValues[1].toInt(), 1L, Long::plus) }
        if (g.clues.size < 12) {
            ids.findAll(detail).forEach { m -> if (m.groupValues[1] != "minecraft") g.clues += m.value }
            keys.findAll(detail).forEach { m -> g.clues += m.groupValues[1] }
            texts.findAll(detail).forEach { m ->
                m.groupValues[1].replace(glyphRuns, "").trim().takeIf { s -> s.count { it.isLetter() } >= 3 }?.let { g.clues += "\"$it\"" }
            }
        }
    }

    private fun suspect(g: Group, total: Long, seconds: Double): Suspect {
        val types = g.types.entries.map { Triple(it.key, it.value[0], it.value[1]) }.sortedByDescending { it.third }
        val names = g.info?.fields.orEmpty()
        val fields = g.fields.entries.sortedByDescending { it.value }.map { (id, n) -> (names[id] ?: "#$id") to n }
        val cadence = cadence(g.ticks)
        val clues = (g.info?.clues.orEmpty() + g.clues).distinct().take(8)
        val where = g.info?.label?.substringAfterLast(" at ", "")?.takeIf { it.isNotEmpty() }?.let { "at $it" }
        val title = (if (g.subjects.size > 1 && g.info != null) "${g.subjects.size} × " else "") + g.title
        return Suspect(title, where, g.count, g.wire, g.repeats, g.subjects.size, seconds, types, cadence, fields, clues,
            hint(g, types, fields, cadence), g.sample, types.first().first, if (g.subjects.size == 1) g.subjects.first() else Subject.NONE)
    }

    /** The usual time between two packets of one subject: "every tick", "every 20 ticks (1 s)"… */
    private fun cadence(ticks: IntArray): String? {
        if (ticks.sum() < 5) return null
        val best = (1..41).maxBy { ticks[it] }
        if (ticks[0] > ticks[best]) return "several per tick"
        return when (best) {
            1 -> "every tick"
            41 -> "2 s+ apart"
            else -> "every $best ticks" + if (best % 20 == 0) " (${best / 20} s)" else ""
        }
    }

    /** What usually helps, for the patterns that are easy to tell. */
    private fun hint(g: Group, types: List<Triple<String, Long, Long>>, fields: List<Pair<String, Long>>, cadence: String?): String? {
        val share = g.repeats.toDouble() / g.count.coerceAtLeast(1)
        val animated = fields.any { (name, _) -> listOf("rotation", "translation", "scale", "interpolation").any { it in name } }
        val moved = types.any { (name, _, _) -> name.startsWith("move_entity") || name == "entity_position_sync" || name == "rotate_head" || name == "teleport_entity" }
        return when {
            share >= 0.8 -> "${(share * 100).toInt()}% are identical to one sent just before: send it only when it changes."
            animated && cadence == "every tick" -> "Transformations sent every tick: send keyframes less often with an interpolation duration (the client animates in between)."
            moved && cadence == "every tick" && g.subjects.size > 3 -> "${g.subjects.size} entities moved every tick: fewer parts, a shorter tracking range or a longer update interval."
            else -> null
        }
    }

    // ---- Text -------------------------------------------------------------------------------------------------------

    fun text(s: Suspect, rank: Int, totalWire: Long): String = buildString {
        append("$rank. ${s.title}")
        s.where?.let { if (!s.title.contains(" at ")) append(" — $it") }
        appendLine()
        append("   ${count(s.count)} packets (${String.format(Locale.ROOT, "%.1f", s.count / s.seconds)}/s), ${bytes(s.wire)} (${bytes((s.wire / s.seconds).toLong())}/s, ")
        append(share(s.wire, totalWire)).append(" of received)")
        if (s.repeats > 0) append(", ${(s.repeats * 100 / s.count.coerceAtLeast(1))}% repeats")
        if (s.subjects > 1) append(", ${s.subjects} subjects")
        appendLine()
        append("   ").append(s.types.take(4).joinToString(", ") { (name, n, _) -> "$name ${count(n)}" })
        s.cadence?.let { append(" · ").append(it).append(if (s.subjects > 1) " each" else "") }
        appendLine()
        if (s.fields.isNotEmpty()) appendLine("   fields sent: " + s.fields.take(6).joinToString(", ") { (name, n) -> "$name ×${count(n)}" })
        if (s.clues.isNotEmpty()) appendLine("   search for: " + s.clues.joinToString(", "))
        s.hint?.let { appendLine("   hint: $it") }
        s.sample?.let { appendLine("   sample: $it") }
    }.trimEnd()

    fun report(list: List<Suspect>, totalWire: Long): String =
        if (list.isEmpty()) "No received packet kept." else list.withIndex().joinToString("\n") { (i, s) -> text(s, i + 1, totalWire) }
}
