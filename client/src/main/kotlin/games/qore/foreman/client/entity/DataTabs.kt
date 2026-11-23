package games.qore.foreman.client.entity

import com.mojang.brigadier.exceptions.CommandSyntaxException
import games.qore.foreman.client.ui.kit.Style
import games.qore.foreman.client.ui.kit.Theme
import games.qore.foreman.client.ui.kit.Ui
import games.qore.foreman.client.util.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.MultiLineEditBox
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.TagParser
import net.minecraft.network.chat.Component

/** What every tab of the server's data shows while it reads it, or why it couldn't; null once it came. */
private fun waiting(ui: Ui, info: EntityInfo?, x: Int, y: Int, w: Int): Boolean {
    if (info != null) return false
    ui.text(ui.fit(EntityEditor.infoError ?: "Reading it from the server…", w), x, y, if (EntityEditor.infoError != null) Theme.ERROR else Theme.FAINT)
    return true
}

private fun count(n: Int) = if (n == 1) "1 entity" else "$n entities"

/** A line of a tab's list (tags, data keys). */
private const val LINE = 18

/** Scoreboard tags: the primary's, added and removed on every selected entity. */
class TagsTab(panel: EntityPanel) : PanelTab(panel) {
    private lateinit var input: EditBox

    override fun build(x: Int, y: Int, w: Int, h: Int) {
        input = panel.box(x, y + 12, w - 54, "A new tag", 256)
    }

    override fun draw(ui: Ui, x: Int, y: Int, w: Int, h: Int): Int {
        val e = panel.primary ?: return 0
        val info = EntityEditor.info(e.uuid)
        val n = EntityEditor.selectedEntities().size
        ui.text(if (n > 1) "Scoreboard tags · added and removed on all $n" else "Scoreboard tags", x, y, Theme.FAINT)
        ui.button(x + w - 50, y + 12, 50, 18, "Add", Style.PRIMARY, info != null && !EntityEditor.busy) { apply() }
        input.active = info != null
        if (waiting(ui, info, x, y + 38, w)) return 50
        val tags = info!!.tags.sorted()
        if (tags.isEmpty()) {
            ui.text("No tag", x, y + 38, Theme.FAINT)
            return 50
        }
        val height = minOf(tags.size * LINE, h - 36)
        ui.scroll("tags", x - 4, y + 36, w + 8, height) { top ->
            tags.forEachIndexed { i, tag ->
                val ty = top + i * LINE
                if (ui.hovered(x - 4, ty, w + 8, LINE)) ui.rect(x - 4, ty, w + 8, LINE, Theme.PANEL_HOVER)
                ui.text(ui.fit(tag, w - 20), x, ty + 5, Theme.TEXT)
                ui.button(x + w - 16, ty + 2, 16, 14, "×", Style.GHOST, !EntityEditor.busy, tooltip = "Remove $tag") { change("remove_tags", tag) }
            }
            tags.size * LINE
        }
        return height + 36
    }

    override fun apply() {
        val tag = input.value.trim()
        if (tag.isEmpty() || tag.any(Char::isWhitespace)) return panel.say("A tag is a word, without spaces", true)
        change("add_tags", tag) { input.value = "" }
    }

    /** Adds or removes [tag] on every selected entity. */
    private fun change(how: String, tag: String, saved: () -> Unit = {}) {
        val uuids = EntityEditor.selectedEntities().map { it.uuid }
        val what = if (how == "add_tags") "Tag $tag added" else "Tag $tag removed"
        EntityEditor.editEntities(uuids, what, buildJsonObject { put(how, JsonArray(listOf(JsonPrimitive(tag)))) }) { error ->
            panel.say(error ?: "$what: ${count(uuids.size)}", error != null)
            if (error == null) saved()
        }
    }
}

/**
 * Persistent data (the PDC plugins keep on entities): the primary's keys with their types and values; a click puts one
 * in the editor below the list, which sets a key (a new one too) on every selected entity, × removes one.
 */
class DataTab(panel: EntityPanel) : PanelTab(panel) {
    private lateinit var key: EditBox
    private lateinit var value: EditBox
    private var type = "string"

    override fun build(x: Int, y: Int, w: Int, h: Int) {
        key = panel.box(x, y + 12, w, "namespace:key", 256)
        value = panel.box(x + 64, y + 34, w - 64 - 46, "value", 32767)
    }

    override fun draw(ui: Ui, x: Int, y: Int, w: Int, h: Int): Int {
        val e = panel.primary ?: return 0
        val info = EntityEditor.info(e.uuid)
        val n = EntityEditor.selectedEntities().size
        ui.text(if (n > 1) "Persistent data · set and removed on all $n" else "Persistent data", x, y, Theme.FAINT)
        val on = info != null && !EntityEditor.busy
        ui.button(x, y + 34, 60, 18, type, enabled = info != null, tooltip = TYPES.joinToString(" · ") + " (click: the next)") { type = TYPES[(TYPES.indexOf(type) + 1) % TYPES.size] }
        ui.button(x + w - 42, y + 34, 42, 18, "Set", Style.PRIMARY, on, tooltip = "Set this key on the selected entities (Enter)") { apply() }
        key.active = info != null
        value.active = info != null
        if (waiting(ui, info, x, y + 60, w)) return 72
        val entries = info!!.data.entries.sortedBy { it.key }
        if (entries.isEmpty()) {
            ui.text("No persistent data", x, y + 60, Theme.FAINT)
            return 72
        }
        val height = minOf(entries.size * LINE, h - 58)
        ui.scroll("data", x - 4, y + 58, w + 8, height) { top ->
            entries.forEachIndexed { i, (k, v) -> row(ui, x, top + i * LINE, w, k, v) }
            entries.size * LINE
        }
        return height + 58
    }

    private fun row(ui: Ui, x: Int, y: Int, w: Int, k: String, v: JsonElement) {
        val type = v.typeName()
        val shown = (v as? JsonObject)?.get("value")?.let(::text) ?: "?"
        if (ui.hovered(x - 4, y, w + 8, LINE)) ui.rect(x - 4, y, w + 8, LINE, Theme.PANEL_HOVER)
        val chip = ui.font.width(type) + 8
        val keyW = minOf(ui.font.width(k), (w - chip - 26) / 2)
        ui.text(ui.fit(k, keyW), x, y + 5, Theme.TEXT)
        ui.text(ui.fit(shown, w - keyW - chip - 30), x + keyW + 6, y + 5, Theme.MUTED)
        ui.chip(x + w - chip - 20, y + 3, type)
        ui.hit(x - 4, y, w - 16, LINE, "$k ($type): $shown\nClick: edit it below") { pick(k, type, shown) }
        ui.button(x + w - 16, y + 2, 16, 14, "×", Style.GHOST, !EntityEditor.busy, tooltip = "Remove $k") { remove(k) }
    }

    /** A key put in the editor; a container, a list or an array is set through the NBT tab. */
    private fun pick(k: String, type: String, shown: String) {
        key.value = k
        if (type in TYPES) {
            this.type = type
            value.value = shown
            panel.say("")
        } else panel.say("A $type is edited in the NBT tab (BukkitValues)", true)
    }

    override fun apply() {
        val k = key.value.trim()
        if (':' !in k) return panel.say("A key is namespace:key", true)
        val raw = value.value
        val v = when (type) {
            "string" -> JsonPrimitive(raw)
            "boolean" -> when (raw.trim().lowercase()) {
                "true", "1" -> JsonPrimitive(true)
                "false", "0" -> JsonPrimitive(false)
                else -> return panel.say("A boolean is true or false", true)
            }
            "float", "double" -> raw.trim().replace(',', '.').toDoubleOrNull()?.let(::JsonPrimitive) ?: return panel.say("A $type is a number", true)
            else -> raw.trim().toLongOrNull()?.let(::JsonPrimitive) ?: return panel.say("A $type is a whole number", true)
        }
        val uuids = EntityEditor.selectedEntities().map { it.uuid }
        val entry = buildJsonObject {
            put("key", k)
            put("type", type)
            put("value", v)
        }
        EntityEditor.editEntities(uuids, "$k set", buildJsonObject { put("set_data", JsonArray(listOf(entry))) }) { error ->
            panel.say(error ?: "$k set: ${count(uuids.size)}", error != null)
        }
    }

    private fun remove(k: String) {
        val uuids = EntityEditor.selectedEntities().map { it.uuid }
        EntityEditor.editEntities(uuids, "$k removed", buildJsonObject { put("remove_data", JsonArray(listOf(JsonPrimitive(k)))) }) { error ->
            panel.say(error ?: "$k removed: ${count(uuids.size)}", error != null)
        }
    }

    private companion object {
        /** The types `edit_entities` sets. */
        val TYPES = listOf("string", "int", "long", "double", "float", "short", "byte", "boolean")

        /** A value as `list_entities` gives it: `{type, value}`, a list's `of` its elements' type. */
        fun JsonElement.typeName(): String {
            val o = this as? JsonObject ?: return "?"
            val type = o["type"].str() ?: "?"
            return o["of"].str()?.let { "$type of $it" } ?: type
        }

        fun text(v: JsonElement): String = (v as? JsonPrimitive)?.content ?: v.toString()
    }
}

/**
 * The primary's whole NBT as SNBT (what `/data get entity` shows), a top-level key per line: edit it and apply, and
 * the keys you changed are merged in (`/data merge`), those you deleted removed. A bad SNBT says where it is wrong.
 */
class NbtTab(panel: EntityPanel) : PanelTab(panel) {
    override val keepsEnter = true
    override val hint = "Ctrl+Enter applies: changed keys are merged, deleted ones removed"

    private lateinit var area: MultiLineEditBox
    /** The NBT the box was filled from, and its text then. */
    private var shownFrom: EntityInfo? = null
    private var shown = ""

    override fun build(x: Int, y: Int, w: Int, h: Int) {
        area = panel.add(MultiLineEditBox.builder().setX(x).setY(y + 36).setPlaceholder(Component.literal("Reading it from the server…"))
            .build(Minecraft.getInstance().font, w, h - 48, Component.literal("NBT")))
        area.setCharacterLimit(500_000)
    }

    override fun draw(ui: Ui, x: Int, y: Int, w: Int, h: Int): Int {
        val e = panel.primary ?: return 0
        val info = EntityEditor.info(e.uuid)
        val edited = area.value != shown
        if (info != null && info !== shownFrom && !edited) fill(info)
        val n = EntityEditor.selectedEntities().size
        ui.text(ui.fit(if (n > 1) "NBT of ${EntityEditor.label(e)} only" else "NBT · /data get entity", w), x, y, Theme.FAINT)
        val bw = (w - 8) / 3
        ui.button(x, y + 12, bw, 18, "Apply", Style.PRIMARY, info != null && edited && !EntityEditor.busy, tooltip = "Changed keys merged, deleted ones removed (Ctrl+Enter)") { apply() }
        ui.button(x + bw + 4, y + 12, bw, 18, "Reload", enabled = info != null, tooltip = "Its NBT on the server now: your edits go") {
            panel.say("")
            info?.let(::fill)
        }
        ui.button(x + (bw + 4) * 2, y + 12, bw, 18, "Undo", enabled = !EntityEditor.busy, tooltip = "Your last change on the server (${EntityEditor.keyName("display-undo")})") { panel.undo() }
        if (info == null) EntityEditor.infoError?.let { ui.text(ui.fit(it, w), x, y + 42, Theme.ERROR) }
        else if (info !== shownFrom && edited) ui.text(ui.fit("It changed on the server: Reload to see it", w), x, y + h - 9, Theme.WARN)
        return h
    }

    private fun fill(info: EntityInfo) {
        shownFrom = info
        shown = pretty(info.nbt)
        area.value = shown
    }

    override fun apply() {
        val from = shownFrom ?: return
        val primary = panel.primary ?: return
        val edited = try {
            TagParser.parseCompoundFully(area.value)
        } catch (e: CommandSyntaxException) {
            return panel.say(e.message ?: "Not SNBT", true)
        }
        val merge = CompoundTag()
        val removes = mutableListOf<String>()
        diff(from.nbt, edited, "", merge, removes)
        if (merge.isEmpty && removes.isEmpty()) return panel.say("Nothing changed")
        EntityEditor.editEntities(listOf(primary.uuid), "NBT saved", buildJsonObject {
            if (removes.isNotEmpty()) put("remove_nbt", JsonArray(removes.map(::JsonPrimitive)))
            if (!merge.isEmpty) put("merge_nbt", merge.toString())
        }) { error ->
            panel.say(error ?: "NBT saved: " + (merge.keySet().sorted() + removes.map { "-$it" }).joinToString(", "), error != null)
            if (error == null) shown = area.value
        }
    }

    companion object {
        /** [nbt] as SNBT, a top-level key per line, sorted (not indented: a long line wraps from its start). */
        fun pretty(nbt: CompoundTag): String =
            nbt.keySet().sorted().joinToString(",\n", "{\n", "\n}") { k -> CompoundTag().apply { put(k, nbt.get(k)!!.copy()) }.toString().removeSurrounding("{", "}") }

        /**
         * What turns [old] into [new]: in [merge] the keys whose value changed (compounds key by key, as a merge
         * does), in [removes] the paths of the keys that went.
         */
        fun diff(old: CompoundTag, new: CompoundTag, path: String, merge: CompoundTag, removes: MutableList<String>) {
            for (k in old.keySet()) if (!new.contains(k)) removes += join(path, k)
            for (k in new.keySet()) {
                val n = new.get(k) ?: continue
                val o = old.get(k)
                if (o == n) continue
                if (o is CompoundTag && n is CompoundTag) {
                    val sub = CompoundTag()
                    diff(o, n, join(path, k), sub, removes)
                    if (!sub.isEmpty) merge.put(k, sub)
                } else merge.put(k, n.copy())
            }
        }

        /** [path] then [key], as an NBT path: quoted unless it is a plain word. */
        private fun join(path: String, key: String): String {
            val k = if (key.matches(Regex("[A-Za-z0-9_+\\-]+"))) key else "\"" + key.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            return if (path.isEmpty()) k else "$path.$k"
        }
    }
}
