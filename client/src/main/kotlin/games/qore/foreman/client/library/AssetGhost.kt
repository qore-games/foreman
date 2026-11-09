package games.qore.foreman.client.library

import com.google.gson.JsonParser
import com.mojang.brigadier.StringReader
import com.mojang.math.Transformation
import com.mojang.serialization.JsonOps
import games.qore.foreman.client.agent.AgentException
import games.qore.foreman.client.editor.Note
import games.qore.foreman.client.link.ServerCalls
import games.qore.foreman.client.util.JSON
import games.qore.foreman.client.util.arr
import games.qore.foreman.client.util.bool
import games.qore.foreman.client.util.double
import games.qore.foreman.client.util.get
import games.qore.foreman.client.util.int
import games.qore.foreman.client.util.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.commands.arguments.item.ItemParser
import net.minecraft.core.BlockPos
import net.minecraft.core.RegistryAccess
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.ComponentSerialization
import net.minecraft.resources.Identifier
import net.minecraft.resources.RegistryOps
import net.minecraft.util.Brightness
import net.minecraft.world.entity.Display
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.state.BlockState
import org.joml.Quaternionf
import org.joml.Vector3f

/**
 * The ghost of the asset the [PlacementTool] places: what its paste would put there, as entities only this client has
 * (never sent to the server). Its blocks with a face in the open are block displays, its display entities (furniture
 * models, decor) displays posed as theirs, all where `paste_asset` puts them: turned a quarter at a time about the
 * asset's bottom center, block states turned as WorldEdit turns them, displays' positions and yaws. They glow, show
 * full bright, and the blocks are a little smaller than a block (no fighting with the blocks already there), so it reads
 * as a ghost. The content comes from the server's `asset_content` when placing starts; past [MAX_BLOCKS] only the tool's
 * box shows. Nothing exists while not placing.
 */
object AssetGhost {
    /** Most blocks drawn, a block display each. */
    const val MAX_BLOCKS = 5000
    private const val GLOW = 0x7FD8FF
    private const val SHRINK = 0.96f
    /** The entities' ids, down from here: negative, never ones the server gives. */
    private const val FIRST_ID = -0x47480000
    /** How far it shows, in 64 blocks: as far as assets are placed from. */
    private const val VIEW_RANGE = 4f
    /** Turns a quarter more at a time, clockwise seen from above (NONE first). */
    private val ROTATIONS = listOf(Rotation.NONE, Rotation.CLOCKWISE_90, Rotation.CLOCKWISE_180, Rotation.COUNTERCLOCKWISE_90)

    /** A display of the asset: what it shows (an entity made from it on the client thread), where, posed how. */
    private class Spec(
        val type: String, val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float, val transformation: Transformation,
        val billboard: Display.BillboardConstraints, val item: ItemStack?, val context: ItemDisplayContext, val block: BlockState?,
        val text: Component?, val json: JsonElement,
    )

    /**
     * [blocks]: x, y, z and palette index of each block, from the min corner. [palette]: the states turned 0 to 3
     * quarters. [tooBig]: how many blocks it has when they are past [MAX_BLOCKS] (nothing is drawn then).
     */
    private class Content(val sizeX: Int, val sizeZ: Int, val palette: List<List<BlockState>>, val blocks: IntArray, val displays: List<Spec>, val tooBig: Int?) {
        val blockCount get() = blocks.size / 4
    }

    private var asset: String? = null
    private var content: Content? = null
    private var error: String? = null
    /** Bumped when the ghost is dropped: content fetched for an older one is thrown away. */
    private var generation = 0

    private var level: ClientLevel? = null
    private val blocks = ArrayList<Display.BlockDisplay>()
    private val displays = ArrayList<Pair<Spec, Display>>()
    /** Where its bottom center stands and its quarter turns, as last drawn; [hidden] while no spot is looked at. */
    private var at: BlockPos? = null
    private var turns = -1
    private var hidden = false

    /** Fetches [asset]'s content for its ghost, the last one dropped. */
    fun load(asset: LibraryAsset) {
        clear()
        val registries = Minecraft.getInstance().level?.registryAccess() ?: return
        this.asset = asset.id
        val generation = generation
        val args = buildJsonObject {
            put("name", asset.id)
            put("max_blocks", MAX_BLOCKS)
            put("format", "json")
        }
        ServerCalls.run({ parse(ServerCalls.text(ServerCalls.callNow("asset_content", args)), registries) }) { result ->
            if (generation != this.generation) return@run
            content = result.getOrNull()
            error = result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
        }
    }

    /** Shows the ghost with its bottom center at [at], turned [turns] quarters; hides it when [at] is null. Each frame. */
    fun show(at: BlockPos?, turns: Int) {
        val content = content?.takeIf { it.tooBig == null } ?: return
        val level = Minecraft.getInstance().level ?: return
        if (this.level !== level) spawn(level, content)
        if (at == null) return hide(true)
        hide(false)
        if (turns != this.turns) {
            this.turns = turns
            this.at = null
            blocks.forEachIndexed { i, e -> e.setBlockState(content.palette[turns][content.blocks[i * 4 + 3]]) }
        }
        if (at != this.at) {
            this.at = at
            place(content, at, turns)
        }
    }

    /** A note for the tool's status: why the ghost isn't drawn, when it isn't. */
    fun note(): Note? = when {
        asset == null -> null
        error != null -> Note("No ghost: ${error!!.take(60)}", Note.Tone.ERROR)
        content == null -> Note("Loading its ghost…", Note.Tone.BUSY)
        content!!.tooBig != null -> Note("Too big for a ghost (${content!!.tooBig} blocks): its box only")
        else -> null
    }

    /** Removes the ghost and forgets its asset. */
    fun clear() {
        generation++
        despawn()
        asset = null
        content = null
        error = null
    }

    private fun spawn(level: ClientLevel, content: Content) {
        despawn()
        this.level = level
        var id = FIRST_ID
        for (i in 0 until content.blockCount) {
            val e = GhostBlock(level)
            e.setBlockState(content.palette[0][content.blocks[i * 4 + 3]])
            e.setTransformation(Transformation(Vector3f((1 - SHRINK) / 2), null, Vector3f(SHRINK), null))
            // Culled by its box like a block: centered on its position, so two blocks wide.
            e.setWidth(2f)
            e.setHeight(1f)
            blocks += ghostly(e, id--)
        }
        for (spec in content.displays) {
            val e = display(level, spec) ?: continue
            e.setTransformation(spec.transformation)
            e.setBillboardConstraints(spec.billboard)
            displays += spec to ghostly(e, id--)
        }
        (blocks + displays.map { it.second }).forEach(level::addEntity)
        at = null
        turns = -1
        hidden = false
    }

    private fun display(level: Level, spec: Spec): Display? = when (spec.type) {
        "item" -> spec.item?.let { item -> GhostItem(level).also { it.setItemStack(item); it.setItemTransform(spec.context) } }
        "block" -> spec.block?.let { block -> GhostBlock(level).also { it.setBlockState(block) } }
        "text" -> GhostText(level).also { e ->
            e.setText(spec.text ?: Component.empty())
            spec.json["line_width"].int()?.let(e::setLineWidth)
            spec.json["background"].int()?.let(e::setBackgroundColor)
            spec.json["opacity"].int()?.let { e.setTextOpacity(it.toByte()) }
            var flags = 0
            if (spec.json["shadow"].bool() == true) flags = flags or Display.TextDisplay.FLAG_SHADOW.toInt()
            if (spec.json["see_through"].bool() == true) flags = flags or Display.TextDisplay.FLAG_SEE_THROUGH.toInt()
            if (spec.json["default_background"].bool() == true) flags = flags or Display.TextDisplay.FLAG_USE_DEFAULT_BACKGROUND.toInt()
            when (spec.json["alignment"].str()) {
                "left" -> flags = flags or Display.TextDisplay.FLAG_ALIGN_LEFT.toInt()
                "right" -> flags = flags or Display.TextDisplay.FLAG_ALIGN_RIGHT.toInt()
            }
            e.setFlags(flags.toByte())
        }
        else -> null
    }

    /** Full bright with a glowing outline, seen from afar. */
    private fun <T : Display> ghostly(e: T, id: Int): T {
        e.id = id
        e.setBrightnessOverride(Brightness.FULL_BRIGHT)
        e.setGlowColorOverride(GLOW)
        e.setViewRange(VIEW_RANGE)
        return e
    }

    /**
     * Puts every entity where the paste puts its source: blocks turned about the bottom center's block ([at]), as
     * WorldEdit turns a clipboard; displays about that block's center, their yaw turned.
     */
    private fun place(content: Content, at: BlockPos, turns: Int) {
        val ox = (content.sizeX - 1) / 2
        val oz = (content.sizeZ - 1) / 2
        blocks.forEachIndexed { i, e ->
            val (x, z) = turn((content.blocks[i * 4] - ox).toDouble(), (content.blocks[i * 4 + 2] - oz).toDouble(), turns)
            move(e, at.x + x, at.y + content.blocks[i * 4 + 1].toDouble(), at.z + z, 0f, 0f)
        }
        for ((spec, e) in displays) {
            val (x, z) = turn(spec.x - ox - 0.5, spec.z - oz - 0.5, turns)
            move(e, at.x + 0.5 + x, at.y + spec.y, at.z + 0.5 + z, spec.yaw + 90f * turns, spec.pitch)
        }
    }

    private fun move(e: Entity, x: Double, y: Double, z: Double, yaw: Float, pitch: Float) {
        e.snapTo(x, y, z, yaw, pitch)
        e.setOldPosAndRot()
    }

    /** ([x], [z]) turned [turns] quarters clockwise seen from above: north (-z) goes east (+x). */
    private fun turn(x: Double, z: Double, turns: Int): Pair<Double, Double> {
        var a = x
        var b = z
        repeat(turns) { val t = a; a = -b; b = t }
        return a to b
    }

    /** Out of sight (nothing looked at) without removing it: shown again when a spot is. */
    private fun hide(hide: Boolean) {
        if (hide == hidden) return
        hidden = hide
        val range = if (hide) 0f else VIEW_RANGE
        blocks.forEach { it.setViewRange(range) }
        displays.forEach { it.second.setViewRange(range) }
    }

    private fun despawn() {
        val level = level
        if (level != null && level === Minecraft.getInstance().level) {
            blocks.forEach { level.removeEntity(it.id, Entity.RemovalReason.DISCARDED) }
            displays.forEach { level.removeEntity(it.second.id, Entity.RemovalReason.DISCARDED) }
        }
        this.level = null
        blocks.clear()
        displays.clear()
    }

    /** `asset_content`'s JSON read, its states and items made. Off the client thread. */
    private fun parse(text: String, registries: RegistryAccess): Content {
        val json = runCatching { JSON.parseToJsonElement(text) }.getOrNull()
            ?.takeIf { it["size"] != null } ?: throw AgentException(text.ifBlank { "No content" })
        val size = json["size"].arr().orEmpty().map { it.int() ?: 1 }
        val listed = json["listed"].int() ?: 0
        if (json["truncated"].bool() == true || listed > MAX_BLOCKS) {
            return Content(size[0], size[2], emptyList(), IntArray(0), emptyList(), (json["total"].int() ?: 0) - (json["hidden"].int() ?: 0))
        }
        val states = json["palette"].arr().orEmpty().map { s ->
            s.str()?.let { runCatching { BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, it, false).blockState() }.getOrNull() }
        }
        val palette = ROTATIONS.map { r -> states.map { (it ?: net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()).rotate(r) } }
        // Blocks whose state this game doesn't know are left out.
        val flat = json["blocks"].arr().orEmpty().map { it.int() ?: 0 }.chunked(4).filter { states.getOrNull(it[3]) != null }.flatten().toIntArray()
        val displays = json["displays"].arr().orEmpty().mapNotNull { spec(it, registries) }
        return Content(size[0], size[2], palette, flat, displays, null)
    }

    private fun spec(d: JsonElement, registries: RegistryAccess): Spec? {
        val pos = d["pos"].arr()?.map { it.double() ?: 0.0 }?.takeIf { it.size == 3 } ?: return null
        val t = d["transformation"]
        fun floats(name: String) = t[name].arr()?.map { it.double()?.toFloat() ?: 0f }
        val transformation = Transformation(
            floats("translation")?.let { Vector3f(it[0], it[1], it[2]) },
            floats("left_rotation")?.let { Quaternionf(it[0], it[1], it[2], it[3]) },
            floats("scale")?.let { Vector3f(it[0], it[1], it[2]) },
            floats("right_rotation")?.let { Quaternionf(it[0], it[1], it[2], it[3]) },
        )
        val billboard = Display.BillboardConstraints.entries.firstOrNull { it.serializedName == d["billboard"].str() } ?: Display.BillboardConstraints.FIXED
        val type = d["type"].str() ?: return null
        val item = if (type == "item") item(d["item"].str(), d["model"].str(), registries) else null
        val context = ItemDisplayContext.entries.firstOrNull { it.serializedName == d["item_display"].str() } ?: ItemDisplayContext.NONE
        val block = d["block"].str()?.let { runCatching { BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, it, false).blockState() }.getOrNull() }
        val text = d["text"]?.let { j ->
            ComponentSerialization.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, registries), JsonParser.parseString(j.toString())).result().orElse(null)
        }
        return Spec(type, pos[0], pos[1], pos[2], (d["yaw"].double() ?: 0.0).toFloat(), (d["pitch"].double() ?: 0.0).toFloat(), transformation, billboard, item, context, block, text, d)
    }

    /** The stack [item] (`id[components]`) says, else one showing [model]. */
    private fun item(item: String?, model: String?, registries: RegistryAccess): ItemStack? =
        item?.let { runCatching { ItemParser(registries).parse(StringReader(it)).createItemStack(1) }.getOrNull() }
            ?: model?.let { Identifier.tryParse(it) }?.let { ItemStack(Items.PAPER).apply { set(DataComponents.ITEM_MODEL, it) } }

    /** Glowing on this client: the game takes an entity's glow from the server's flag, which these never get. */
    private class GhostBlock(level: Level) : Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, level) {
        override fun isCurrentlyGlowing() = true
    }

    private class GhostItem(level: Level) : Display.ItemDisplay(EntityTypes.ITEM_DISPLAY, level) {
        override fun isCurrentlyGlowing() = true
    }

    private class GhostText(level: Level) : Display.TextDisplay(EntityTypes.TEXT_DISPLAY, level) {
        override fun isCurrentlyGlowing() = true
    }
}
