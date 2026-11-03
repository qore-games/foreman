package games.qore.foreman.client.mcp

import com.mojang.serialization.JsonOps
import games.qore.foreman.client.mixin.DisplayAccessor
import games.qore.foreman.client.packets.PacketInspector
import games.qore.foreman.client.util.bool
import games.qore.foreman.client.util.double
import games.qore.foreman.client.util.get
import games.qore.foreman.client.util.int
import games.qore.foreman.client.util.str
import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.ComponentSerialization
import net.minecraft.resources.RegistryOps
import net.minecraft.world.entity.Display
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.Interaction
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import org.joml.Quaternionfc
import org.joml.Vector3fc
import java.util.Locale

/**
 * What the client has of the entities around the player (whatever the server sent, entities only packets made too):
 * type, place, rotation, riding, and per kind the synced data plugins set (display entities' transformation and looks,
 * their item, block or text, interactions' size…); and the components of the player's items.
 */
object EntityTools {
    fun init() {
        ClientMcp.register(ClientTool(
            "client_entities",
            "The entities the client has around the player (within `radius`), or along the look (`look`: those the crosshair " +
                "line passes near, nearest first): whatever the server sent, entities that exist only in packets too. One block " +
                "each: id, type, position, rotation, distance, riding/passengers, name and flags, then per kind: display entities " +
                "(billboard, transformation, brightness, interpolation, view range, shadow, glow, size), item displays (item, " +
                "item_model, custom_model_data, display context), text displays (text, background, line width, opacity, flags), " +
                "block displays (block state), interactions (size), item frames, armor stands, dropped items, mobs' equipment.",
            ClientTools.schema(
                "radius" to "number: blocks around the player (default 8, max 256)",
                "look" to "boolean: along the look instead, up to `distance` blocks (default 32), within `margin` of the line (default 0.5)",
                "distance" to "number", "margin" to "number",
                "type" to "string: entity types containing this, comma-separated, e.g. `item_display,text_display`",
                "contains" to "string: only entities whose block contains this (an item model, a text, a name), case-insensitive",
                "id" to "integer: one entity by id (from packets or another list), with its UUID, its item's components and text as JSON",
                "json" to "boolean: items' components and texts as JSON too (default false)",
                "limit" to "integer: default 20, max 200",
            ),
            acts = { false },
        ) { _, args -> listOf(ClientMcp.text(ClientTools.onClient { list(args) })) })

        ClientMcp.register(ClientTool(
            "client_item",
            "An item the player has, as the client sees it: `slot` mainhand (default), offhand, head, chest, legs, feet, or an " +
                "inventory index (0-8 hotbar, 9-35 inventory, 36-39 armor, 40 offhand): its summary, then the whole stack as " +
                "JSON (id, count, every component the server set).",
            ClientTools.schema("slot" to "string|integer: a slot name or an inventory index"),
            acts = { false },
        ) { _, args ->
            listOf(ClientMcp.text(ClientTools.onClient {
                val player = Minecraft.getInstance().player ?: throw ToolFailure("Not in a world")
                val slot = args["slot"].str() ?: "mainhand"
                val stack = slot.toIntOrNull()?.let {
                    if (it !in 0 until player.inventory.containerSize) throw ToolFailure("Inventory index 0..${player.inventory.containerSize - 1}")
                    player.inventory.getItem(it)
                } ?: player.getItemBySlot(runCatching { EquipmentSlot.byName(slot) }.getOrNull() ?: throw ToolFailure("slot: mainhand, offhand, head, chest, legs, feet or an index"))
                if (stack.isEmpty) "$slot: empty" else "$slot: ${item(stack)}\n${itemJson(stack)}"
            }))
        })
    }

    private fun list(args: kotlinx.serialization.json.JsonObject): String {
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: throw ToolFailure("Not in a world")
        val player = minecraft.player ?: throw ToolFailure("Not in a world")
        val json = args["json"].bool() == true
        args["id"].int()?.let { id ->
            val e = level.getEntity(id) ?: throw ToolFailure("The client has no entity $id (gone, or out of its view distance)")
            return describe(e, player.distanceTo(e).toDouble(), detail = true)
        }
        val types = args["type"].str()?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val contains = args["contains"].str()?.takeIf { it.isNotBlank() }
        val limit = (args["limit"].int() ?: 20).coerceIn(1, 200)
        val look = args["look"].bool() == true
        val candidates = level.entitiesForRendering().filter { it !== player && (types.isEmpty() || types.any { t -> type(it).contains(t, true) }) }
        val near: List<Pair<Entity, Double>> = if (look) {
            val eye = player.eyePosition
            val end = eye.add(player.getViewVector(1f).scale((args["distance"].double() ?: 32.0).coerceIn(1.0, 512.0)))
            val margin = (args["margin"].double() ?: 0.5).coerceIn(0.0, 8.0)
            candidates.mapNotNull { e ->
                val box = e.boundingBox.inflate(margin)
                if (box.contains(eye)) e to 0.0 else box.clip(eye, end).orElse(null)?.let { e to it.distanceTo(eye) }
            }
        } else {
            val radius = (args["radius"].double() ?: 8.0).coerceIn(0.5, 256.0)
            candidates.map { it to player.distanceTo(it).toDouble() }.filter { it.second <= radius }
        }
        val blocks = near.sortedBy { it.second }.asSequence()
            .map { (e, d) -> describe(e, d, detail = json) }
            .filter { contains == null || it.contains(contains, true) }
            .toList()
        val where = if (look) "along the look" else "within ${num(args["radius"].double() ?: 8.0)} blocks"
        val what = (if (types.isEmpty()) "" else " (${types.joinToString(", ")})") + (contains?.let { " containing \"$it\"" } ?: "")
        if (blocks.isEmpty()) return "No entity $where$what."
        return "${blocks.size} entities $where$what" + (if (blocks.size > limit) ", the nearest $limit" else "") + ":\n" + blocks.take(limit).joinToString("\n")
    }

    private fun type(e: Entity) = PacketInspector.short(EntityType.getKey(e.type))

    /** One entity: a header line, then a line per kind of data it has. */
    private fun describe(e: Entity, distance: Double, detail: Boolean): String = buildString {
        append("#${e.id} ${type(e)} ${num(e.x)} ${num(e.y)} ${num(e.z)} yaw ${num(e.yRot.toDouble(), 1)} pitch ${num(e.xRot.toDouble(), 1)}")
        if (e is LivingEntity && Math.abs(e.yHeadRot - e.yRot) > 1) append(" head ${num(e.yHeadRot.toDouble(), 1)}")
        append(", ${num(distance, 1)} m")
        if (detail) append("\n  uuid ${e.uuid}")
        val riding = buildList {
            e.vehicle?.let { add("riding #${it.id} ${type(it)}") }
            if (e.passengers.isNotEmpty()) add("passengers " + e.passengers.joinToString(" ") { "#${it.id} ${type(it)}" })
        }
        if (riding.isNotEmpty()) append("\n  ").append(riding.joinToString(" · "))
        val flags = buildList {
            val name = e.customName ?: (e as? Player)?.name
            name?.let { add("name \"${text(it.string, 60)}\"" + if (e.isCustomNameVisible) " shown" else "") }
            if (e.isInvisible) add("invisible")
            if (e.hasGlowingTag()) add("glowing")
            if (e.isNoGravity && e !is Display && e !is Interaction) add("no gravity")
            if (e is LivingEntity && e !is ArmorStand) add("health ${num(e.health.toDouble(), 1)}/${num(e.maxHealth.toDouble(), 1)}")
        }
        if (flags.isNotEmpty()) append("\n  ").append(flags.joinToString(" · "))
        when (e) {
            is Display -> display(this, e, detail)
            is Interaction -> append("\n  interaction ${num(e.width.toDouble())}x${num(e.height.toDouble())}" + if (e.response) " · response" else "")
            is ItemFrame -> append("\n  frame: ${item(e.item)} · rotation ${e.rotation} · facing ${e.direction.serializedName}").also { if (detail && !e.item.isEmpty) append("\n  item json ${itemJson(e.item)}") }
            is ItemEntity -> append("\n  item: ${item(e.item)} · age ${e.age}").also { if (detail) append("\n  item json ${itemJson(e.item)}") }
        }
        if (e is ArmorStand) {
            val stand = buildList {
                if (e.isSmall) add("small")
                if (e.isMarker) add("marker")
                if (e.showArms()) add("arms")
                if (!e.showBasePlate()) add("no base plate")
                e.headPose.takeIf { it.x() != 0f || it.y() != 0f || it.z() != 0f }?.let { add("head pose ${num(it.x().toDouble(), 1)},${num(it.y().toDouble(), 1)},${num(it.z().toDouble(), 1)}") }
            }
            if (stand.isNotEmpty()) append("\n  armor stand: ").append(stand.joinToString(" · "))
        }
        if (e is LivingEntity) {
            val worn = EquipmentSlot.entries.mapNotNull { slot -> e.getItemBySlot(slot).takeUnless { it.isEmpty }?.let { slot to it } }
            if (worn.isNotEmpty()) append("\n  equipment: ").append(worn.joinToString(" · ") { (slot, stack) -> "${slot.serializedName} ${item(stack)}" })
            if (detail) worn.forEach { (slot, stack) -> append("\n  ${slot.serializedName} json ${itemJson(stack)}") }
        }
    }

    private fun display(out: StringBuilder, e: Display, detail: Boolean) {
        val looks = buildList {
            add("billboard ${e.billboardConstraints.serializedName}")
            e.brightnessOverride?.let { add("brightness ${it.block()}/${it.sky()}") }
            add("view range ${num(e.viewRange.toDouble())}")
            if (e.shadowRadius != 0f) add("shadow ${num(e.shadowRadius.toDouble())}/${num(e.shadowStrength.toDouble())}")
            if (e.glowColorOverride != -1) add("glow color #%06x".format(e.glowColorOverride and 0xFFFFFF))
            if (e.width != 0f || e.height != 0f) add("size ${num(e.width.toDouble())}x${num(e.height.toDouble())}")
        }
        out.append("\n  display: ").append(looks.joinToString(" · "))
        val t = DisplayAccessor.`foreman$transformation`(e.getEntityData())
        val parts = buildList {
            if (!zero(t.translation())) add("translation ${vec(t.translation())}")
            if (!one(t.scale())) add("scale ${vec(t.scale())}")
            if (!identity(t.leftRotation())) add("left rotation ${quat(t.leftRotation())}")
            if (!identity(t.rightRotation())) add("right rotation ${quat(t.rightRotation())}")
        }
        val timing = buildList {
            if (e.transformationInterpolationDuration != 0) add("interpolation ${e.transformationInterpolationDuration}t (start ${e.transformationInterpolationDelay})")
            if (e.posRotInterpolationDuration != 0) add("teleport ${e.posRotInterpolationDuration}t")
        }
        out.append("\n  transformation: ").append(parts.joinToString(" · ").ifEmpty { "identity" })
        if (timing.isNotEmpty()) out.append(" · ").append(timing.joinToString(" · "))
        when (e) {
            is Display.ItemDisplay -> {
                out.append("\n  item: ${item(e.itemStack)} · context ${e.itemTransform.serializedName}")
                if (detail && !e.itemStack.isEmpty) out.append("\n  item json ${itemJson(e.itemStack)}")
            }
            is Display.BlockDisplay -> out.append("\n  block: ${e.blockState}")
            is Display.TextDisplay -> {
                val flags = e.flags.toInt()
                val style = buildList {
                    add("line width ${e.lineWidth}")
                    add(if (flags and Display.TextDisplay.FLAG_USE_DEFAULT_BACKGROUND.toInt() != 0) "default background" else "background #%08x".format(e.backgroundColor))
                    if (e.textOpacity.toInt() != -1) add("opacity ${e.textOpacity.toInt() and 0xFF}")
                    if (flags and Display.TextDisplay.FLAG_SHADOW.toInt() != 0) add("shadow")
                    if (flags and Display.TextDisplay.FLAG_SEE_THROUGH.toInt() != 0) add("see through")
                    add("align ${Display.TextDisplay.getAlign(e.flags).serializedName}")
                }
                out.append("\n  text: \"${text(e.text.string, 300)}\" · ").append(style.joinToString(" · "))
                if (detail) out.append("\n  text json ${componentJson(e)}")
            }
        }
    }

    /** `3 paper{item_model=ns:id custom_model_data=floats[1] name="…"}`: what tells an item apart. */
    fun item(stack: ItemStack): String {
        if (stack.isEmpty) return "empty"
        val parts = buildList {
            stack.get(DataComponents.ITEM_MODEL)?.let { add("item_model=$it") }
            stack.get(DataComponents.CUSTOM_MODEL_DATA)?.let { cmd ->
                val lists = buildList {
                    if (cmd.floats().isNotEmpty()) add("floats" + cmd.floats().joinToString(",", "[", "]") { num(it.toDouble(), 3) })
                    if (cmd.flags().isNotEmpty()) add("flags" + cmd.flags().joinToString(",", "[", "]"))
                    if (cmd.strings().isNotEmpty()) add("strings" + cmd.strings().joinToString(",", "[", "]"))
                    if (cmd.colors().isNotEmpty()) add("colors" + cmd.colors().joinToString(",", "[", "]") { "#%06x".format(it and 0xFFFFFF) })
                }
                if (lists.isNotEmpty()) add("custom_model_data=" + lists.joinToString(" "))
            }
            stack.get(DataComponents.DYED_COLOR)?.let { add("dyed_color=#%06x".format(it.rgb() and 0xFFFFFF)) }
            stack.get(DataComponents.CUSTOM_NAME)?.let { add("name=\"${text(it.string, 40)}\"") }
        }
        return (if (stack.count > 1) "${stack.count} " else "") + PacketInspector.short(BuiltInRegistries.ITEM.getKey(stack.item)) +
            if (parts.isEmpty()) "" else parts.joinToString(" ", "{", "}")
    }

    private fun ops() = RegistryOps.create(JsonOps.INSTANCE, (Minecraft.getInstance().level ?: throw ToolFailure("Not in a world")).registryAccess())

    private fun itemJson(stack: ItemStack): String =
        ItemStack.CODEC.encodeStart(ops(), stack).result().map { cut(it.toString(), 4000) }.orElse("(can't be written as JSON)")

    private fun componentJson(e: Display.TextDisplay): String =
        ComponentSerialization.CODEC.encodeStart(ops(), e.text).result().map { cut(it.toString(), 4000) }.orElse("(can't be written as JSON)")

    private fun cut(s: String, n: Int) = if (s.length > n) PacketInspector.cut(s, n) + "…" else s

    private fun text(s: String, n: Int) = cut(PacketInspector.readable(s).replace("\n", "\\n"), n)

    /** [d] rounded to [decimals], without trailing zeros. */
    private fun num(d: Double, decimals: Int = 2): String =
        String.format(Locale.ROOT, "%.${decimals}f", d).let { if ('.' in it) it.trimEnd('0').trimEnd('.') else it }.let { if (it == "-0") "0" else it }

    private fun vec(v: Vector3fc) = "${num(v.x().toDouble(), 3)},${num(v.y().toDouble(), 3)},${num(v.z().toDouble(), 3)}"
    private fun quat(q: Quaternionfc) = "${num(q.x().toDouble(), 3)},${num(q.y().toDouble(), 3)},${num(q.z().toDouble(), 3)},${num(q.w().toDouble(), 3)}"
    private fun zero(v: Vector3fc) = v.x() == 0f && v.y() == 0f && v.z() == 0f
    private fun one(v: Vector3fc) = v.x() == 1f && v.y() == 1f && v.z() == 1f
    private fun identity(q: Quaternionfc) = q.x() == 0f && q.y() == 0f && q.z() == 0f && Math.abs(q.w()) == 1f
}
