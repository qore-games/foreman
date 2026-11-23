package games.qore.foreman.server.world

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import games.qore.foreman.server.mcp.ToolError
import io.papermc.paper.entity.EntitySerializationFlag
import io.papermc.paper.persistence.PersistentDataContainerView
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.World
import org.bukkit.attribute.Attribute
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Item
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import java.util.Locale
import java.util.UUID

/**
 * Any entity's scoreboard tags, persistent data (PDC), whole NBT, custom name and persistence, a display's view range, a
 * text display's text, a living entity's AI, health and attributes: read in a box or by uuid, filtered by type and by
 * what it shows, changed in bulk, removed and copied, each call one undo step. Main thread.
 */
object EntityEdits {
    /** An entity, and the furniture whose root display it is. */
    class Found(val entity: Entity, val furniture: Furniture.Placed?)

    /**
     * Which entities of a box are taken, null for any: their [types]; the item [model] or [item] type they show (item
     * displays, item frames, dropped items); the [block] type of block displays; furniture root displays only (true) or
     * none of them (false). Players only when [types] names them.
     */
    class Filter(val types: Set<EntityType>?, val model: String?, val item: Material?, val block: Material?, val furniture: Boolean?) {
        val any get() = types != null || model != null || item != null || block != null || furniture != null

        fun matches(e: Entity, root: Boolean): Boolean {
            if (types?.contains(e.type) == false || types == null && e is Player) return false
            if (furniture != null && furniture != root) return false
            if (block != null && (e as? BlockDisplay)?.block?.material != block) return false
            if (model == null && item == null) return true
            val stack = item(e) ?: return false
            if (item != null && stack.type != item) return false
            return model == null || modelOf(stack).let { if (':' in model) it == model else it.substringAfter(':') == model }
        }
    }

    /** An entity type by key (`minecraft:` may be left out). */
    fun type(raw: String): EntityType =
        NamespacedKey.fromString(raw.lowercase(Locale.ROOT))?.let { Registry.ENTITY_TYPE.get(it) } ?: throw ToolError("Unknown entity type '$raw'")

    /** An item or block type by key. */
    fun material(raw: String, block: Boolean): Material {
        val m = Material.matchMaterial(raw) ?: throw ToolError("Unknown ${if (block) "block" else "item"} '$raw'")
        if (block && !m.isBlock || !block && !m.isItem) throw ToolError("$raw is not ${if (block) "a block" else "an item"}")
        return m
    }

    /** The entities whose position is in [box] that [filter] takes, with the furniture of their displays, bottom up. */
    fun inBox(world: World, box: Box, filter: Filter): List<Found> {
        val bounds = BoundingBox(box.min.x().toDouble(), box.min.y().toDouble(), box.min.z().toDouble(), box.max.x() + 1.0, box.max.y() + 1.0, box.max.z() + 1.0)
        Chunks.keepLoaded(world, box)
        val entities = world.getNearbyEntities(bounds) { bounds.contains(it.location.toVector()) }
        val roots = if (entities.any { it is Display }) Furniture.inBox(world, box.grow(2, 2, 2)).mapNotNull { p -> p.entity?.let { it to p } }.toMap() else emptyMap()
        return entities.filter { filter.matches(it, it.uniqueId in roots) }
            .map { Found(it, roots[it.uniqueId]) }
            .sortedWith(compareBy({ it.entity.location.y }, { it.entity.location.x }, { it.entity.location.z }))
    }

    /** The loaded entity [id] of [world] (null: of any world, when only one has it), or what it became. */
    fun find(world: World?, id: UUID): Entity? = Edits.loaded(world, id) ?: Edits.loaded(world, Edits.current(id))

    /** [e] with the furniture whose root display it is. */
    fun found(e: Entity) = Found(e, (e as? Display)?.let(DisplayEdits::furnitureOf))

    // ---- Reading ------------------------------------------------------------------------------------------------------

    /** The item an entity shows: an item display's, an item frame's, a dropped item's; null for none. */
    fun item(e: Entity): ItemStack? = when (e) {
        is ItemDisplay -> e.itemStack
        is ItemFrame -> e.item
        is Item -> e.itemStack
        else -> null
    }?.takeUnless { it.type.isAir }

    /** A stack's `item_model`, else its material. */
    fun modelOf(stack: ItemStack): String {
        val meta = stack.itemMeta
        return if (meta != null && meta.hasItemModel()) meta.itemModel.toString() else stack.type.key.toString()
    }

    fun typeName(e: Entity): String = e.type.key.asMinimalString()

    /** Its JSON entry; with [nbt], its whole NBT as SNBT too. */
    fun json(f: Found, nbt: Boolean = false): JsonObject = JsonObject().apply {
        val e = f.entity
        val l = e.location
        addProperty("uuid", e.uniqueId.toString())
        addProperty("type", typeName(e))
        addProperty("world", e.world.name)
        add("position", JsonArray().apply { listOf(l.x, l.y, l.z).forEach { add(Math.round(it * 1e6) / 1e6) } })
        item(e)?.let { addProperty("model", modelOf(it)) }
        (e as? BlockDisplay)?.let { addProperty("block", it.block.asString) }
        (e as? Display)?.let { addProperty("view_range", it.viewRange) }
        add("tags", JsonArray().apply { e.scoreboardTags.sorted().forEach { add(it) } })
        add("data", data(e.persistentDataContainer))
        f.furniture?.let { p ->
            add("furniture", JsonObject().apply {
                addProperty("id", p.id)
                p.handle?.let { addProperty("handle", it.toString()) }
            })
        }
        if (!e.isPersistent) addProperty("persistent", false)
        e.customName()?.let { addProperty("custom_name", MINI.serialize(it)) }
        (e as? TextDisplay)?.let { addProperty("text", MINI.serialize(it.text())) }
        (e as? LivingEntity)?.let {
            addProperty("ai", it.hasAI())
            addProperty("health", it.health)
            addProperty("max_health", maxHealth(it))
        }
        if (nbt) addProperty("nbt", EntityNbt.read(e).toString())
    }

    /** One line for the AI; with [nbt], its whole NBT on the next. */
    fun describe(f: Found, nbt: Boolean = false): String {
        val e = f.entity
        val l = e.location
        fun n(x: Double) = "%.3f".format(Locale.ROOT, x).trimEnd('0').trimEnd('.').let { if (it == "-0") "0" else it }
        val data = e.persistentDataContainer
        return "${e.uniqueId} ${typeName(e)} at [${n(l.x)}, ${n(l.y)}, ${n(l.z)}]" +
            (e.customName()?.let { " named \"${MINI.serialize(it)}\"" } ?: "") +
            ((e as? LivingEntity)?.let { " health ${n(it.health)}/${n(maxHealth(it))}" } ?: "") +
            (item(e)?.let { " model ${modelOf(it)}" } ?: "") +
            ((e as? BlockDisplay)?.let { " block ${it.block.asString}" } ?: "") +
            ((e as? Display)?.let { " view_range ${n(it.viewRange.toDouble())}" } ?: "") +
            (if (e.scoreboardTags.isNotEmpty()) " tags [${e.scoreboardTags.sorted().joinToString(", ")}]" else "") +
            (if (!data.isEmpty) " data {${data.keys.sortedBy { it.toString() }.joinToString(", ") { k -> describe(k, value(data, k)) }}}" else "") +
            (f.furniture?.let { " — furniture ${it.id} rooted at [${it.x}, ${it.y}, ${it.z}]" } ?: "") +
            (if (!e.isPersistent) " (not saved)" else "") +
            (if (nbt) "\n  nbt ${EntityNbt.read(e)}" else "")
    }

    private val gson = Gson()
    private val MINI = MiniMessage.miniMessage()

    /**
     * [e]'s max health with its modifiers, read without making its attribute instance (`getAttribute` makes the
     * instances it's asked for, which the entity then saves: a read mustn't change what is saved).
     */
    @Suppress("DEPRECATION")
    private fun maxHealth(e: LivingEntity) = e.maxHealth

    private fun describe(key: NamespacedKey, v: JsonObject): String {
        val type = v["type"].asString
        val value = v["value"] ?: return "$key ($type)"
        return "$key: ${gson.toJson(value)} (" + (v["of"]?.let { "list of ${it.asString}" } ?: type) + ")"
    }

    // ---- Persistent data ----------------------------------------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    private fun <P, C> any(type: PersistentDataType<P, C>) = type as PersistentDataType<Any, Any>

    /** The tag types a value is read as, in order (a boolean is a byte). */
    private val SCALARS = listOf(
        "byte" to any(PersistentDataType.BYTE), "short" to any(PersistentDataType.SHORT), "int" to any(PersistentDataType.INTEGER),
        "long" to any(PersistentDataType.LONG), "float" to any(PersistentDataType.FLOAT), "double" to any(PersistentDataType.DOUBLE),
        "string" to any(PersistentDataType.STRING), "byte_array" to any(PersistentDataType.BYTE_ARRAY),
        "int_array" to any(PersistentDataType.INTEGER_ARRAY), "long_array" to any(PersistentDataType.LONG_ARRAY),
        "container" to any(PersistentDataType.TAG_CONTAINER),
    )

    private val LISTS = PersistentDataType.LIST.let { l ->
        listOf(
            "byte" to any(l.bytes()), "short" to any(l.shorts()), "int" to any(l.integers()), "long" to any(l.longs()),
            "float" to any(l.floats()), "double" to any(l.doubles()), "string" to any(l.strings()), "byte_array" to any(l.byteArrays()),
            "int_array" to any(l.integerArrays()), "long_array" to any(l.longArrays()), "container" to any(l.dataContainers()),
        )
    }

    /** A container's values by key, each `{type, value}`: a container's value is its own values, a list says what `of`. */
    fun data(pdc: PersistentDataContainerView): JsonObject = JsonObject().apply {
        pdc.keys.sortedBy { it.toString() }.forEach { add(it.toString(), value(pdc, it)) }
    }

    private fun value(pdc: PersistentDataContainerView, key: NamespacedKey): JsonObject = JsonObject().apply {
        SCALARS.firstOrNull { pdc.has(key, it.second) }?.let { (name, type) ->
            addProperty("type", name)
            add("value", json(pdc.get(key, type)))
            return@apply
        }
        LISTS.firstOrNull { pdc.has(key, it.second) }?.let { (name, type) ->
            addProperty("type", "list")
            addProperty("of", name)
            add("value", json(pdc.get(key, type)))
            return@apply
        }
        addProperty("type", "unknown")
    }

    private fun json(v: Any?): JsonElement = when (v) {
        null -> JsonNull.INSTANCE
        is Double -> if (v.isFinite()) JsonPrimitive(v) else JsonPrimitive(v.toString())
        is Float -> if (v.isFinite()) JsonPrimitive(v) else JsonPrimitive(v.toString())
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is String -> JsonPrimitive(v)
        is ByteArray -> JsonArray().apply { v.forEach { add(it) } }
        is IntArray -> JsonArray().apply { v.forEach { add(it) } }
        is LongArray -> JsonArray().apply { v.forEach { add(it) } }
        is PersistentDataContainerView -> data(v)
        is List<*> -> JsonArray().apply { v.forEach { add(json(it)) } }
        else -> JsonPrimitive(v.toString())
    }

    /** The types a value can be set as. */
    val SETTABLE = listOf("byte", "boolean", "short", "int", "long", "float", "double", "string")

    /** A value to set under [key], as [type]. */
    class Value(val key: NamespacedKey, val type: PersistentDataType<Any, Any>, val value: Any)

    /** A persistent data key: `namespace:key`, the namespace given. */
    fun key(raw: String): NamespacedKey {
        if (':' !in raw) throw ToolError("key '$raw' is namespace:key")
        return NamespacedKey.fromString(raw) ?: throw ToolError("key '$raw' is namespace:key in lowercase letters, digits and _ - . /")
    }

    /** [raw] under [key] as [type] (one of [SETTABLE]). */
    fun value(key: String, type: String, raw: JsonElement?): Value {
        val k = key(key)
        val p = raw?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: throw ToolError("$key: value is a ${type.ifEmpty { "number or string" }}")
        fun whole(min: Long, max: Long): Long {
            val n = runCatching { p.asBigDecimal.longValueExact() }.getOrNull()?.takeIf { p.isNumber && it in min..max }
            return n ?: throw ToolError("$key: $type is a whole number from $min to $max, got $p")
        }
        fun decimal(): Double = runCatching { p.asDouble }.getOrNull()?.takeIf { p.isNumber && it.isFinite() } ?: throw ToolError("$key: $type is a number, got $p")
        return when (type) {
            "byte" -> Value(k, any(PersistentDataType.BYTE), whole(Byte.MIN_VALUE.toLong(), Byte.MAX_VALUE.toLong()).toByte())
            "boolean" -> Value(k, any(PersistentDataType.BOOLEAN), when {
                p.isBoolean -> p.asBoolean
                p.isNumber -> whole(0, 1) == 1L
                else -> throw ToolError("$key: boolean is true or false, got $p")
            })
            "short" -> Value(k, any(PersistentDataType.SHORT), whole(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toShort())
            "int" -> Value(k, any(PersistentDataType.INTEGER), whole(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt())
            "long" -> Value(k, any(PersistentDataType.LONG), whole(Long.MIN_VALUE, Long.MAX_VALUE))
            "float" -> Value(k, any(PersistentDataType.FLOAT), decimal().toFloat())
            "double" -> Value(k, any(PersistentDataType.DOUBLE), decimal())
            "string" -> Value(k, any(PersistentDataType.STRING), p.asString)
            else -> throw ToolError("$key: type is ${SETTABLE.joinToString(", ")}")
        }
    }

    // ---- Editing ------------------------------------------------------------------------------------------------------

    /**
     * What an edit changes on each entity: null or empty for what stays. Its NBT: [removeNbt] paths removed, then
     * [mergeNbt] merged in, as `/data remove` and `/data merge` do. [name] and [text] are MiniMessage (an empty name
     * takes it away), [attributes] base values.
     */
    class Request(
        val viewRange: Float? = null,
        val addTags: List<String> = emptyList(), val removeTags: List<String> = emptyList(),
        val setData: List<Value> = emptyList(), val removeData: List<NamespacedKey> = emptyList(),
        val mergeNbt: EntityNbt.Compound? = null, val removeNbt: List<EntityNbt.Path> = emptyList(),
        val name: String? = null, val text: String? = null, val persistent: Boolean? = null,
        val attributes: Map<Attribute, Double> = emptyMap(), val health: Double? = null, val ai: Boolean? = null,
    ) {
        val nbt get() = mergeNbt != null || removeNbt.isNotEmpty()
        val empty get() = viewRange == null && addTags.isEmpty() && removeTags.isEmpty() && setData.isEmpty() && removeData.isEmpty() && !nbt &&
            name == null && text == null && persistent == null && attributes.isEmpty() && health == null && ai == null
    }

    /**
     * What an edit changed on an entity: its view range before and after, the tags and keys it added, set or removed,
     * the top-level NBT keys whose value changed, the other properties it changed ([changed]: custom_name, text,
     * persistent, ai, an attribute's key, health).
     */
    class Edited(
        val id: UUID, val viewRange: Pair<Float, Float>?, val tagsAdded: List<String>, val tagsRemoved: List<String>,
        val dataSet: List<String>, val dataRemoved: List<String>, val nbt: List<String>, val changed: List<String>,
    )

    /** A property set through the server's API: its name, how to set it, how to put it back on the entity then. */
    private class Prop(val name: String, val set: () -> Unit, val restore: (Entity) -> Unit)

    private const val MAX_TAGS = 1024
    /** NBT keys a removal can't take: an entity without them would lose itself or go to 0, 0, 0. */
    private val KEPT = listOf("UUID", "Pos")
    /** The world an entity's NBT names (Bukkit's): NBT never moves one to another world. */
    private val WORLD = listOf("WorldUUIDMost", "WorldUUIDLeast")

    /**
     * Applies [r] to [e], recorded in [change] for undo; null when it changed nothing (it already was so). Its NBT is
     * written first, then the rest over it. Undo puts each thing back (its persistent data as the whole container was);
     * after an NBT change, its whole NBT as it was too.
     */
    fun edit(e: Entity, r: Request, change: Change): Edited? {
        val display = e as? Display
        if (r.viewRange != null && display == null) {
            throw ToolError("not a display (${typeName(e)}): view_range is a display's (other entities are seen as far as the server's entity-tracking-range, spigot.yml)")
        }
        val from = e.location
        val before = if (r.nbt) EntityNbt.read(e) else null
        val after = before?.let { nbtEdited(e, it, r) }
        val nbtKeys = if (before == null || after == null) emptyList() else (before.keys + after.keys).filter { before[it] != after[it] }.sorted()
        val tags = e.scoreboardTags
        if (tags.size - r.removeTags.count { it in tags } + r.addTags.count { it !in tags } > MAX_TAGS) throw ToolError("it would have more than $MAX_TAGS tags")
        val props = props(e, r)
        if (before != null && after != null && nbtKeys.isNotEmpty()) {
            EntityNbt.write(e, after)
            // Its Pos may have taken it somewhere locked.
            if (e.location != from) runCatching { Locks.guard(e.location) }.onFailure {
                EntityNbt.write(e, before)
                throw it
            }
        }

        val added = r.addTags.filter { it !in e.scoreboardTags }
        val removed = r.removeTags.filter { it in e.scoreboardTags }
        val pdc = e.persistentDataContainer
        val set = r.setData.filter { v -> !pdc.has(v.key, v.type) || pdc.get(v.key, v.type) != v.value }
        val dropped = r.removeData.filter { pdc.has(it) }
        val oldRange = display?.viewRange
        val range = r.viewRange?.takeIf { it != oldRange }
        if (range == null && added.isEmpty() && removed.isEmpty() && set.isEmpty() && dropped.isEmpty() && nbtKeys.isEmpty() && props.isEmpty()) return null

        val pdcBefore = if (set.isNotEmpty() || dropped.isNotEmpty()) pdc.serializeToBytes() else null
        if (range != null) display!!.viewRange = range
        removed.forEach { e.removeScoreboardTag(it) }
        added.forEach { e.addScoreboardTag(it) }
        set.forEach { pdc.set(it.key, it.type, it.value) }
        dropped.forEach { pdc.remove(it) }
        props.forEach { it.set() }

        val id = e.uniqueId
        val at = e.location
        change.touch(from)
        change.touch(at)
        change.restores += Runnable {
            val now = Edits.entity(id, at) ?: return@Runnable
            if (range != null) (now as? Display)?.viewRange = oldRange!!
            added.forEach { now.removeScoreboardTag(it) }
            removed.forEach { now.addScoreboardTag(it) }
            pdcBefore?.let { now.persistentDataContainer.readFromBytes(it, true) }
            props.forEach { it.restore(now) }
            if (nbtKeys.isNotEmpty()) EntityNbt.write(now, before!!)
        }
        return Edited(id, range?.let { oldRange!! to it }, added, removed, set.map { it.key.toString() }, dropped.map { it.toString() }, nbtKeys, props.map { it.name })
    }

    /** [r]'s name, text, persistence, AI, attributes and health that would change [e], checked before anything changes. */
    private fun props(e: Entity, r: Request): List<Prop> {
        val props = mutableListOf<Prop>()
        r.name?.let { raw ->
            val now = raw.takeIf { it.isNotEmpty() }?.let(MINI::deserialize)
            val old = e.customName()
            if (now != old) props += Prop("custom_name", { e.customName(now) }) { it.customName(old) }
        }
        r.text?.let { raw ->
            val t = e as? TextDisplay ?: throw ToolError("not a text display (${typeName(e)}): text is a text display's")
            val now = MINI.deserialize(raw)
            val old = t.text()
            if (now != old) props += Prop("text", { t.text(now) }) { (it as? TextDisplay)?.text(old) }
        }
        r.persistent?.takeIf { it != e.isPersistent }?.let { p -> props += Prop("persistent", { e.isPersistent = p }) { it.isPersistent = !p } }
        if (r.attributes.isEmpty() && r.health == null && r.ai == null) return props
        val living = e as? LivingEntity ?: throw ToolError("not a living entity (${typeName(e)}): AI, attributes and health are a living entity's")
        r.ai?.takeIf { it != living.hasAI() }?.let { ai -> props += Prop("ai", { living.setAI(ai) }) { (it as? LivingEntity)?.setAI(!ai) } }
        for ((a, base) in r.attributes) {
            val old = living.getAttribute(a)?.baseValue ?: throw ToolError("it has no ${a.key} attribute")
            if (old != base) props += Prop(a.key.toString(), { living.getAttribute(a)?.baseValue = base }) { (it as? LivingEntity)?.getAttribute(a)?.baseValue = old }
        }
        r.health?.let { h ->
            val max = r.attributes[Attribute.MAX_HEALTH]?.let { base -> living.getAttribute(Attribute.MAX_HEALTH)!!.let { m -> base + m.value - m.baseValue } } ?: maxHealth(living)
            if (h > max) throw ToolError("health $h is above its max_health $max: raise max_health with it")
            val old = living.health
            if (old != h) props += Prop("health", { living.health = h }) { now -> (now as? LivingEntity)?.let { it.health = old.coerceAtMost(maxHealth(it)) } }
        }
        return props
    }

    /**
     * [before] with [r]'s removals and merge. Refuses what NBT mustn't do: change the uuid or the type, move the entity
     * to another world, take away its uuid or position.
     */
    private fun nbtEdited(e: Entity, before: EntityNbt.Compound, r: Request): EntityNbt.Compound {
        val after = before.copy()
        r.removeNbt.forEach { EntityNbt.remove(after, it) }
        r.mergeNbt?.let { merge ->
            merge["id"]?.let { id ->
                val type = id.trim('"', '\'').let { if (':' in it) it else "minecraft:$it" }
                if (type != e.type.key.toString()) throw ToolError("NBT can't change an entity's type (it is ${e.type.key}, the merge says $type): spawn another entity")
            }
            EntityNbt.merge(after, merge.copy().also { it.remove("id") })
        }
        KEPT.firstOrNull { before[it] != null && after[it] == null }?.let { throw ToolError("$it can't be removed from an entity's NBT") }
        if (after["UUID"] != before["UUID"]) throw ToolError("NBT can't change an entity's UUID (the server keeps it): leave UUID out of the merge")
        WORLD.firstOrNull { after[it] != null && after[it] != before[it] }?.let { throw ToolError("NBT can't move an entity to another world ($it): Pos moves it within its world") }
        return after
    }

    // ---- Removing and copying -------------------------------------------------------------------------------------------

    /**
     * Removes [f], recorded in [change] so undo brings it back as it was (its whole data), with its uuid where it is
     * free: a furniture's root display through its plugin, any other entity but a player. What rides it stays.
     */
    fun remove(f: Found, change: Change) {
        val e = f.entity
        if (e is Player) throw ToolError("a player can't be removed")
        if (e is Display && f.furniture != null) return DisplayEdits.remove(DisplayEdits.Found(e, f.furniture), change)
        val data = Bukkit.getUnsafe().serializeEntity(e, EntitySerializationFlag.FORCE, EntitySerializationFlag.MISC)
        val id = e.uniqueId
        val at = e.location
        e.remove()
        change.touch(at)
        change.restores += Runnable {
            at.world.getChunkAt(at)
            val back = Bukkit.getUnsafe().deserializeEntity(data, at.world, true)
            if (back.spawnAt(at)) return@Runnable
            // Its uuid was taken meanwhile: back under a new one, which older changes follow.
            val fresh = Bukkit.getUnsafe().deserializeEntity(data, at.world, false)
            if (fresh.spawnAt(at)) Edits.replaced(Edits.current(id), fresh.uniqueId)
        }
    }

    /**
     * A copy of [f] with all its data and a new uuid, moved by [offset], recorded in [change] (undo removes it). Not a
     * player's, nor a furniture's (its plugin places another one).
     */
    fun copy(f: Found, offset: Vector, change: Change): Entity {
        val e = f.entity
        if (e is Player) throw ToolError("a player can't be copied")
        f.furniture?.let { throw ToolError("it is a furniture's root display (${it.id}): place another one with place_furniture") }
        val at = e.location.add(offset)
        Locks.guard(at)
        val copy = e.copy(at)
        change.touch(at)
        change.spawnedEntities += copy.uniqueId to copy.location
        return copy
    }

    fun json(e: Edited): JsonObject = JsonObject().apply {
        addProperty("uuid", e.id.toString())
        e.viewRange?.let { (old, now) -> add("view_range", JsonArray().apply { add(old); add(now) }) }
        fun list(name: String, values: List<String>) {
            if (values.isNotEmpty()) add(name, JsonArray().apply { values.forEach { add(it) } })
        }
        list("tags_added", e.tagsAdded)
        list("tags_removed", e.tagsRemoved)
        list("data_set", e.dataSet)
        list("data_removed", e.dataRemoved)
        list("nbt", e.nbt)
        list("changed", e.changed)
    }
}
