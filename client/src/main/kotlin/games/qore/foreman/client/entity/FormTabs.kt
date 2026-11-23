package games.qore.foreman.client.entity

import games.qore.foreman.client.ui.kit.Slider
import games.qore.foreman.client.ui.kit.Style
import games.qore.foreman.client.ui.kit.Theme
import games.qore.foreman.client.ui.kit.Ui
import games.qore.foreman.client.util.bool
import games.qore.foreman.client.util.double
import games.qore.foreman.client.util.get
import games.qore.foreman.client.util.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.EditBox
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.DoubleTag
import net.minecraft.nbt.FloatTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtOps
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.Display
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Interaction
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.entity.decoration.painting.Painting
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.level.block.state.BlockState
import java.util.Locale

/**
 * A tab of values read from what the server says of the primary entity (its NBT, its name, health and attributes) and
 * written back as one `edit_entities` call (NBT removed then merged, and its other arguments): rows of text fields
 * (Enter applies the changed rows), switches and choices (applied at once). A change goes to the selected entities of
 * the primary's type, or of the tab's [kind] for rows every one of them has ([Row.common]: a display's view range), or
 * to the primary alone ([Row.alone]: a position). A number with a usual range is a [Slider] ([Row.slides]), applied as
 * soon as it is let go.
 */
abstract class NbtFormTab(panel: EntityPanel) : PanelTab(panel) {
    /** What a change writes: [removes] (NBT paths) removed first, then [merge] merged in; [args] and [attributes] besides. */
    class Change(
        val merge: CompoundTag = CompoundTag(), val removes: MutableList<String> = mutableListOf(),
        val args: MutableMap<String, JsonElement> = linkedMapOf(), val attributes: MutableMap<String, Double> = linkedMapOf(),
    ) {
        /** The compound at [path] in [merge], made when missing. */
        fun compound(vararg path: String): CompoundTag = path.fold(merge) { tag, key ->
            tag.getCompound(key).orElseGet { CompoundTag().also { tag.put(key, it) } }
        }

        val empty get() = merge.isEmpty && removes.isEmpty() && args.isEmpty() && attributes.isEmpty()
    }

    /** A value typed wrong: its message says what is expected. */
    class Bad(message: String) : Exception(message)

    /** A row of text fields: their values read from the entity, and written into a change; [slides]: each field's slider. */
    class Row(
        val label: String, val hints: List<String>, val common: Boolean = true, val alone: Boolean = false,
        val slides: List<Slide?> = emptyList(),
        val read: (EntityInfo) -> List<String>, val write: (List<String>, EntityInfo, Change) -> Unit,
    )

    /** A field's usual range, slid across (any value can still be typed), in whole steps for [whole] numbers. */
    class Slide(val min: Double, val max: Double, val whole: Boolean = false)

    /** A switch: on or off, and what turning it on or off writes. */
    class Toggle(val label: String, val tip: String? = null, val read: (EntityInfo) -> Boolean, val write: (Boolean, Change) -> Unit)

    /** One of [options], a click takes the next. */
    class Choice(val label: String, val options: List<String>, val common: Boolean, val read: (EntityInfo) -> String, val write: (String, Change) -> Unit)

    protected abstract val rows: List<Row>
    /** What every selected entity a common row goes to is. */
    protected abstract val kind: Class<out Entity>

    /** Its switches for [info] (some only where the entity has them). */
    protected open fun toggles(info: EntityInfo): List<Toggle> = emptyList()

    protected open fun choices(info: EntityInfo): List<Choice> = emptyList()

    private val boxes = mutableListOf<List<EditBox>>()
    /** Each box's slider, where its row gives one. */
    private val sliders = mutableListOf<List<Slider?>>()
    private val filled = mutableListOf<List<String>>()
    private var filledFrom: EntityInfo? = null

    override fun build(x: Int, y: Int, w: Int, h: Int) {
        rows.forEachIndexed { r, row ->
            val n = row.hints.size
            val bw = (w - LABEL - (n - 1) * 4) / n
            boxes += row.hints.mapIndexed { i, hint -> panel.box(x + LABEL + i * (bw + 4), y + r * ROW, bw, hint) }
            sliders += row.hints.indices.map { i -> row.slides.getOrNull(i)?.let { Slider(it.min, it.max, Slider.step(it.min, it.max, it.whole), ::num) } }
            filled += List(n) { "" }
        }
    }

    /** The primary's info, the boxes filled from it when it changed. */
    private fun info(): EntityInfo? {
        val info = panel.primary?.let { EntityEditor.info(it.uuid) }
        if (info != null && info !== filledFrom) {
            filledFrom = info
            rows.forEachIndexed { r, row ->
                val values = runCatching { row.read(info) }.getOrElse { List(row.hints.size) { "" } }
                values.forEachIndexed { i, v -> boxes[r].getOrNull(i)?.let { it.value = v; it.moveCursorToStart(false) } }
                filled[r] = values
            }
        }
        return info
    }

    override fun draw(ui: Ui, x: Int, y: Int, w: Int, h: Int): Int {
        val info = info()
        val on = info != null && !EntityEditor.busy
        rows.forEachIndexed { r, row -> ui.text(ui.fit(row.label, LABEL - 4), x, y + r * ROW + 5, if (info != null) Theme.TEXT else Theme.FAINT) }
        boxes.flatten().forEach { it.active = info != null }
        sliders.forEachIndexed { r, row ->
            row.forEachIndexed { i, slider ->
                val box = boxes[r][i]
                slider?.draw(ui, box.x, box.y, box.width, box, true, none = rows[r].hints[i], h = box.height) { if (info != null) apply() }
            }
        }
        var yy = y + rows.size * ROW + 2
        if (info == null) {
            ui.text(ui.fit(EntityEditor.infoError ?: "Reading it from the server…", w), x, yy, if (EntityEditor.infoError != null) Theme.ERROR else Theme.FAINT)
            return yy + 12 - y
        }
        toggles(info).chunked(2).forEach { pair ->
            pair.forEachIndexed { i, t ->
                val state = runCatching { t.read(info) }.getOrDefault(false)
                ui.switch(x + i * (w / 2 + 6), yy + 1, w / 2 - 10, t.label, state, t.tip) { if (on) send(Change().also { t.write(!state, it) }, false, false, "${t.label} " + if (!state) "on" else "off") }
            }
            yy += ROW
        }
        for (c in choices(info)) {
            val value = runCatching { c.read(info) }.getOrDefault(c.options.first())
            ui.text(c.label, x, yy + 5, Theme.TEXT)
            ui.button(x + LABEL, yy, w - LABEL, 18, value, enabled = on, tooltip = c.options.joinToString(" · ") + " (click: the next)") {
                val next = c.options[(c.options.indexOf(value) + 1) % c.options.size]
                send(Change().also { c.write(next, it) }, !c.common, false, "${c.label} $next")
            }
            yy += ROW
        }
        if (rows.isNotEmpty()) ui.button(x, yy, w / 2 - 2, 18, "Apply (Enter)", Style.PRIMARY, on) { apply() }
        ui.button(x + w / 2 + 2, yy, w / 2 - 2, 18, "Undo", enabled = !EntityEditor.busy, tooltip = "Your last change on the server (${EntityEditor.keyName("display-undo")})") { panel.undo() }
        return yy + ROW - y
    }

    /** Writes the rows whose values were changed, as one change. */
    override fun apply() {
        val info = filledFrom ?: return
        val change = Change()
        var typed = false
        var alone = false
        rows.forEachIndexed { r, row ->
            val values = boxes[r].map { it.value.trim() }
            if (values == filled[r]) return@forEachIndexed
            try {
                row.write(values, info, change)
            } catch (e: Bad) {
                return panel.say("${row.label}: ${e.message}", true)
            }
            typed = typed || !row.common
            alone = alone || row.alone
        }
        if (change.empty) return panel.say("Nothing changed")
        send(change, typed, alone, "Saved")
    }

    /** Sends [change] to the selected entities of the primary's type ([typed]), of the tab's kind, or to the primary ([alone]). */
    private fun send(change: Change, typed: Boolean, alone: Boolean, what: String) {
        val primary = panel.primary ?: return
        val targets = if (alone) listOf(primary) else EntityEditor.selectedEntities().filter { if (typed) it.type == primary.type else kind.isInstance(it) }
        panel.say("")
        EntityEditor.editEntities(targets.map { it.uuid }, what, buildJsonObject {
            if (change.removes.isNotEmpty()) put("remove_nbt", JsonArray(change.removes.map(::JsonPrimitive)))
            if (!change.merge.isEmpty) put("merge_nbt", change.merge.toString())
            change.args.forEach { (k, v) -> put(k, v) }
            if (change.attributes.isNotEmpty()) put("set_attributes", JsonObject(change.attributes.mapValues { JsonPrimitive(it.value) }))
        }) { error -> panel.say(error ?: "$what: ${count(targets.size)}", error != null) }
    }

    companion object {
        const val LABEL = 84
        const val ROW = 22

        fun count(n: Int) = if (n == 1) "1 entity" else "$n entities"

        fun num(v: Double) = "%.4f".format(Locale.ROOT, v).trimEnd('0').trimEnd('.').let { if (it == "-0") "0" else it }

        fun number(text: String, what: String): Double =
            text.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() } ?: throw Bad("$what is a number, not '$text'")

        fun positive(text: String, what: String): Float = number(text, what).takeIf { it >= 0 }?.toFloat() ?: throw Bad("$what is 0 or more")

        fun whole(text: String, range: IntRange, what: String): Int = text.toIntOrNull()?.takeIf { it in range } ?: throw Bad("$what is a whole number from ${range.first} to ${range.last}")

        fun id(text: String): Identifier = Identifier.tryParse(text) ?: throw Bad("'$text' is not an id (namespace:path)")

        /** A colour typed `#RRGGBB`, or `#AARRGGBB` with [alpha]. */
        fun color(text: String, alpha: Boolean = false): Int {
            val hex = text.removePrefix("#")
            val n = hex.takeIf { it.length == 6 || alpha && it.length == 8 }?.toLongOrNull(16) ?: throw Bad(if (alpha) "a colour is #AARRGGBB or #RRGGBB" else "a colour is #RRGGBB")
            return (if (hex.length == 6 && alpha) n or 0xFF000000 else n).toInt()
        }

        /** The entity's position, [x, y, z]: `Pos`. */
        fun position() = Row("Position", listOf("x", "y", "z"), alone = true, read = { i ->
            val pos = i.nbt.getListOrEmpty("Pos")
            (0..2).map { num(pos.getDoubleOr(it, 0.0)) }
        }) { v, _, c ->
            c.merge.put("Pos", ListTag().apply { v.forEachIndexed { i, s -> add(DoubleTag.valueOf(number(s, "xyz"[i].toString()))) } })
        }

        /** The item an entity holds under [key] (`Item`, `item`), by id; empty takes it away. */
        fun item(key: String) = Row("Item", listOf("namespace:id"), read = { i -> listOf(i.nbt.getCompound(key).flatMap { it.getString("id") }.orElse("")) }) { v, i, c ->
            if (v[0].isEmpty()) c.removes += key
            else {
                c.compound(key).putString("id", id(v[0]).toString())
                if (!i.nbt.contains(key)) c.compound(key).putInt("count", 1)
            }
        }

        /** A byte flag of the NBT, absent when off. */
        fun flag(label: String, key: String, inverted: Boolean = false, tip: String? = null) = Toggle(label, tip, { it.nbt.getBooleanOr(key, false) != inverted }) { on, c ->
            c.merge.putBoolean(key, on != inverted)
        }
    }
}

/** What every entity has: its custom name (MiniMessage), its place (a display's is in Transform), its flags; Duplicate and Delete. */
class EntityTab(panel: EntityPanel) : NbtFormTab(panel) {
    private val shows = panel.primary
    override val kind = Entity::class.java

    override fun draw(ui: Ui, x: Int, y: Int, w: Int, h: Int): Int {
        val used = super.draw(ui, x, y, w, h)
        val on = !EntityEditor.busy && EntityEditor.selectedEntities().isNotEmpty()
        ui.button(x, y + used, w / 2 - 2, 18, "Duplicate", enabled = on, tooltip = "A copy of each selected entity in place, selected (${EntityEditor.keyName("display-duplicate")})") { EntityEditor.duplicate() }
        ui.button(x + w / 2 + 2, y + used, w / 2 - 2, 18, "Delete", Style.DANGER, on, tooltip = "Remove the selected entities: undo brings them back (${EntityEditor.keyName("display-delete")})") { EntityEditor.delete() }
        return used + ROW
    }

    override val rows = buildList {
        add(Row("Name", listOf("MiniMessage, empty: none"), read = { listOf(it.json["custom_name"].str().orEmpty()) }) { v, _, c -> c.args["custom_name"] = JsonPrimitive(v[0]) })
        if (shows !is Display) add(position())
    }

    override fun toggles(info: EntityInfo) = listOf(
        flag("Name shown", "CustomNameVisible", tip = "Its name above it, even when not looked at"), flag("Glowing", "Glowing"),
        flag("Silent", "Silent"), flag("Gravity", "NoGravity", inverted = true),
        flag("Invulnerable", "Invulnerable"),
        Toggle("Saved", "Saved with the world: off, it is gone when its chunk unloads", { it.json["persistent"].bool() != false }) { on, c -> c.args["persistent"] = JsonPrimitive(on) },
    )
}

/** A display's look: view range, brightness, shadow, glow, billboard; an item display's item, a block display's block. */
class DisplayTab(panel: EntityPanel) : NbtFormTab(panel) {
    private val shows = panel.primary
    override val kind = Display::class.java

    override val rows = buildList {
        add(Row("View range", listOf("1 is 64 blocks"), slides = listOf(Slide(0.0, 4.0)), read = { listOf(num(it.nbt.getFloatOr("view_range", 1f).toDouble())) }) { v, _, c ->
            c.merge.putFloat("view_range", positive(v[0], "It"))
        })
        add(Row("Brightness", listOf("block 0-15", "sky 0-15"), slides = listOf(Slide(0.0, 15.0, true), Slide(0.0, 15.0, true)), read = { i ->
            i.nbt.getCompound("brightness").map { listOf(it.getIntOr("block", 0).toString(), it.getIntOr("sky", 0).toString()) }.orElse(listOf("", ""))
        }) { v, _, c ->
            // one left empty takes the other's (a slider moved alone)
            if (v.all { it.isEmpty() }) c.removes += "brightness"
            else c.merge.put("brightness", CompoundTag().apply { putInt("block", light(v[0].ifEmpty { v[1] })); putInt("sky", light(v[1].ifEmpty { v[0] })) })
        })
        add(Row("Shadow", listOf("radius", "strength"), slides = listOf(Slide(0.0, 4.0), Slide(0.0, 1.0)), read = { listOf(num(it.nbt.getFloatOr("shadow_radius", 0f).toDouble()), num(it.nbt.getFloatOr("shadow_strength", 1f).toDouble())) }) { v, _, c ->
            c.merge.putFloat("shadow_radius", positive(v[0], "The radius"))
            c.merge.putFloat("shadow_strength", positive(v[1], "The strength"))
        })
        add(Row("Glow color", listOf("#RRGGBB"), read = { i -> listOf(i.nbt.getIntOr("glow_color_override", -1).let { if (it == -1) "" else "#%06X".format(it and 0xFFFFFF) }) }) { v, _, c ->
            c.merge.putInt("glow_color_override", if (v[0].isEmpty()) -1 else color(v[0]))
        })
        when (shows) {
            is Display.ItemDisplay -> {
                add(Row("Item", listOf("namespace:id"), common = false, read = { i -> listOf(i.nbt.getCompound("item").flatMap { it.getString("id") }.orElse("")) }) { v, i, c ->
                    if (v[0].isEmpty()) c.removes += "item"
                    else {
                        c.compound("item").putString("id", id(v[0]).toString())
                        if (!i.nbt.contains("item")) c.compound("item").putInt("count", 1)
                    }
                })
                add(Row("Model", listOf("item_model"), common = false, read = { i ->
                    listOf(i.nbt.getCompound("item").flatMap { it.getCompound("components") }.flatMap { it.getString(ITEM_MODEL) }.orElse(""))
                }) { v, i, c ->
                    if (v[0].isEmpty()) c.removes += "item.components.\"$ITEM_MODEL\""
                    else if (!i.nbt.contains("item") && !c.merge.contains("item")) throw Bad("give it an item first")
                    else c.compound("item", "components").putString(ITEM_MODEL, id(v[0]).toString())
                })
            }
            is Display.BlockDisplay -> add(Row("Block", listOf("oak_stairs[facing=east]"), common = false, read = { i ->
                listOf(i.nbt.get("block_state")?.let { BlockState.CODEC.parse(NbtOps.INSTANCE, it).result().orElse(null) }?.let(BlockStateParser::serialize).orEmpty())
            }) { v, _, c ->
                val state = runCatching { BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, v[0].ifEmpty { "air" }, false).blockState() }.getOrElse { throw Bad(it.message ?: "not a block") }
                c.removes += "block_state"
                c.merge.put("block_state", BlockState.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow())
            })
            else -> {}
        }
    }

    override fun toggles(info: EntityInfo) = listOf(flag("Glowing", "Glowing"))

    override fun choices(info: EntityInfo) = buildList {
        add(Choice("Billboard", listOf("fixed", "vertical", "horizontal", "center"), true, { it.nbt.getStringOr("billboard", "fixed") }) { v, c -> c.merge.putString("billboard", v) })
        if (shows is Display.ItemDisplay) {
            add(Choice("Shown as", ItemDisplayContext.entries.map { it.serializedName }, false, { it.nbt.getStringOr("item_display", "none") }) { v, c -> c.merge.putString("item_display", v) })
        }
    }

    private companion object {
        const val ITEM_MODEL = "minecraft:item_model"

        fun light(text: String): Int = text.toIntOrNull()?.takeIf { it in 0..15 } ?: throw Bad("block and sky are 0 to 15 (both empty: the world's light)")
    }
}

/** A text display's text (MiniMessage), line width, background, alignment, see-through, shadow, default background. */
class TextTab(panel: EntityPanel) : NbtFormTab(panel) {
    override val kind = Display.TextDisplay::class.java

    override val rows = listOf(
        Row("Text", listOf("MiniMessage, <newline>"), read = { listOf(it.json["text"].str().orEmpty()) }) { v, _, c -> c.args["text"] = JsonPrimitive(v[0]) },
        Row("Line width", listOf("pixels (200)"), slides = listOf(Slide(1.0, 400.0, true)), read = { listOf(it.nbt.getIntOr("line_width", 200).toString()) }) { v, _, c ->
            c.merge.putInt("line_width", whole(v[0], 1..100_000, "The line width"))
        },
        Row("Background", listOf("#AARRGGBB"), read = { listOf("#%08X".format(it.nbt.getIntOr("background", 0x40000000))) }) { v, _, c ->
            c.merge.putInt("background", color(v[0], alpha = true))
        },
    )

    override fun toggles(info: EntityInfo) = listOf(
        flag("See-through", "see_through", tip = "Seen through blocks"), flag("Shadow", "shadow", tip = "The text's shadow"),
        flag("Default bg", "default_background", tip = "The chat's background instead of its own"),
    )

    override fun choices(info: EntityInfo) = listOf(
        Choice("Alignment", listOf("center", "left", "right"), true, { it.nbt.getStringOr("alignment", "center") }) { v, c -> c.merge.putString("alignment", v) },
    )
}

/** A living entity's health, max health and scale (attribute base values), its AI, and its persistence and age where it has them. */
class MobTab(panel: EntityPanel) : NbtFormTab(panel) {
    override val kind = LivingEntity::class.java

    override val rows = listOf(
        Row("Health", listOf("health"), read = { listOf(num(it.json["health"].double() ?: 0.0)) }) { v, _, c ->
            c.args["health"] = JsonPrimitive(number(v[0], "It").takeIf { it > 0 } ?: throw Bad("it is above 0"))
        },
        Row("Max health", listOf("base value"), slides = listOf(Slide(1.0, 100.0)), read = { listOf(num(base(it, MAX_HEALTH) ?: it.json["max_health"].double() ?: 0.0)) }) { v, _, c -> c.attributes[MAX_HEALTH] = number(v[0], "It") },
        Row("Scale", listOf("1 is its size"), slides = listOf(Slide(0.1, 4.0)), read = { listOf(num(base(it, SCALE) ?: 1.0)) }) { v, _, c -> c.attributes[SCALE] = number(v[0], "It") },
    )

    override fun toggles(info: EntityInfo) = buildList {
        add(Toggle("AI", "Off: it stands still, doesn't look around nor fall", { it.json["ai"].bool() != false }) { on, c -> c.args["ai"] = JsonPrimitive(on) })
        if (info.nbt.contains("PersistenceRequired")) add(flag("Persistent", "PersistenceRequired", tip = "PersistenceRequired: it never despawns"))
        when {
            info.nbt.contains("IsBaby") -> add(flag("Baby", "IsBaby"))
            info.nbt.contains("Age") -> add(Toggle("Baby", "A baby grows up unless its age is locked", { it.nbt.getIntOr("Age", 0) < 0 }) { on, c -> c.merge.putInt("Age", if (on) -24000 else 0) })
        }
    }

    private companion object {
        const val MAX_HEALTH = "minecraft:max_health"
        const val SCALE = "minecraft:scale"

        /**
         * An attribute's base value from the entity's NBT; null when it has none of its own there (its type's default:
         * the max health it has, a scale of 1).
         */
        fun base(info: EntityInfo, key: String): Double? = info.nbt.getListOrEmpty("attributes")
            .firstNotNullOfOrNull { (it as? CompoundTag)?.takeIf { a -> a.getStringOr("id", "") == key }?.getDoubleOr("base", 0.0) }
    }
}

/** An item frame's item and rotation, whether the frame shows and is fixed. */
class FrameTab(panel: EntityPanel) : NbtFormTab(panel) {
    override val kind = ItemFrame::class.java

    override val rows = listOf(item("Item"))

    override fun toggles(info: EntityInfo) = listOf(flag("Visible", "Invisible", inverted = true, tip = "The frame (its item shows either way)"), flag("Fixed", "Fixed", tip = "Can't be broken, nor its item turned or taken"))

    override fun choices(info: EntityInfo) = listOf(
        Choice("Rotation", (0..7).map { "${it * 45}°" }, true, { "${it.nbt.getByteOr("ItemRotation", 0) * 45}°" }) { v, c -> c.merge.putByte("ItemRotation", (v.removeSuffix("°").toInt() / 45).toByte()) },
    )
}

/** A dropped item's item, count and pickup delay, and whether it never despawns. */
class ItemTab(panel: EntityPanel) : NbtFormTab(panel) {
    override val kind = ItemEntity::class.java

    override val rows = listOf(
        item("Item"),
        Row("Count", listOf("1-99"), slides = listOf(Slide(1.0, 99.0, true)), read = { i -> listOf(i.nbt.getCompound("Item").map { it.getIntOr("count", 1).toString() }.orElse("")) }) { v, i, c ->
            if (!i.nbt.contains("Item") && !c.merge.contains("Item")) throw Bad("give it an item first")
            c.compound("Item").putInt("count", whole(v[0], 1..99, "It"))
        },
        Row("Pickup delay", listOf("ticks, 32767: never"), read = { listOf(it.nbt.getShortOr("PickupDelay", 0).toString()) }) { v, _, c ->
            c.merge.putShort("PickupDelay", whole(v[0], 0..Short.MAX_VALUE.toInt(), "It").toShort())
        },
    )

    override fun toggles(info: EntityInfo) = listOf(
        Toggle("Never despawns", "Its age held where items never despawn", { it.nbt.getShortOr("Age", 0) == Short.MIN_VALUE }) { on, c -> c.merge.putShort("Age", if (on) Short.MIN_VALUE else 0) },
    )
}

/** A painting's variant. */
class PaintingTab(panel: EntityPanel) : NbtFormTab(panel) {
    override val kind = Painting::class.java

    override val rows = listOf(
        Row("Variant", listOf("minecraft:kebab"), read = { listOf(it.nbt.getStringOr("variant", "")) }) { v, _, c ->
            val variant = id(v[0])
            val known = Minecraft.getInstance().level?.registryAccess()?.lookupOrThrow(Registries.PAINTING_VARIANT)?.listElementIds()?.anyMatch { it.identifier() == variant }
            if (known == false) throw Bad("$variant is not a painting variant of this server")
            c.merge.putString("variant", variant.toString())
        },
    )
}

/** An interaction's size and whether it answers clicks. */
class InteractionTab(panel: EntityPanel) : NbtFormTab(panel) {
    override val kind = Interaction::class.java

    override val rows = listOf(
        Row("Size", listOf("width", "height"), slides = listOf(Slide(0.0, 4.0), Slide(0.0, 4.0)), read = { listOf(num(it.nbt.getFloatOr("width", 1f).toDouble()), num(it.nbt.getFloatOr("height", 1f).toDouble())) }) { v, _, c ->
            c.merge.putFloat("width", positive(v[0], "The width"))
            c.merge.putFloat("height", positive(v[1], "The height"))
        },
    )

    override fun toggles(info: EntityInfo) = listOf(flag("Responds", "response", tip = "Answers clicks (a swing or a use sound)"))
}

/** An armor stand's yaw and flags. */
class ArmorStandTab(panel: EntityPanel) : NbtFormTab(panel) {
    override val kind = ArmorStand::class.java

    override val rows = listOf(
        Row("Yaw", listOf("degrees"), alone = true, slides = listOf(Slide(-180.0, 180.0, true)), read = { listOf(num(it.nbt.getListOrEmpty("Rotation").getFloatOr(0, 0f).toDouble())) }) { v, i, c ->
            val pitch = i.nbt.getListOrEmpty("Rotation").getFloatOr(1, 0f)
            c.merge.put("Rotation", ListTag().apply { add(FloatTag.valueOf(number(v[0], "The yaw").toFloat())); add(FloatTag.valueOf(pitch)) })
        },
    )

    override fun toggles(info: EntityInfo) = listOf(
        flag("Invisible", "Invisible"), flag("Small", "Small"), flag("Arms", "ShowArms"), flag("Base plate", "NoBasePlate", inverted = true),
        flag("Marker", "Marker", tip = "No hitbox: can't be hit nor clicked"),
    )
}
