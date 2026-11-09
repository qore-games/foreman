package games.qore.foreman.client.overlays

import games.qore.foreman.client.config.Config
import games.qore.foreman.client.input.Ray
import games.qore.foreman.client.gizmo.BlockBox
import games.qore.foreman.client.library.enters
import games.qore.foreman.client.render.Colors
import games.qore.foreman.client.render.OverlayDraw
import games.qore.foreman.client.render.Overlays
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.block.BlockStateModelSet
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.SectionPos
import net.minecraft.util.RandomSource
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LightBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.status.ChunkStatus
import net.minecraft.world.phys.Vec3
import java.util.IdentityHashMap

/**
 * Blocks the world draws as nothing, outlined around the player: barriers, light blocks (with their level), structure
 * voids, and every block state whose model in the current resource packs has no faces (a pack turning some slabs or
 * trapdoors into invisible collision blocks). Each kind has its colour; the one under the crosshair is named. Chunk
 * sections are scanned when it is turned on and as they come in range (a few a tick, their palettes first), again when
 * one of their blocks changes or the packs reload; what is found is kept per section. Nothing runs while it is off.
 */
object HiddenBlocks {
    enum class Kind(val label: String, val color: Int) {
        BARRIER("Barrier", 0xFFFF5050.toInt()),
        LIGHT("Light", 0xFFFFD84A.toInt()),
        STRUCTURE_VOID("Structure void", 0xFFE070FF.toInt()),
        EMPTY("Drawn as nothing", 0xFF40E0D0.toInt()),
    }

    class Hidden(val pos: BlockPos, val state: BlockState, val kind: Kind) {
        val box get() = BlockBox(pos.x, pos.y, pos.z, pos.x, pos.y, pos.z)

        /** What it is, on its label: `Barrier`, `Light 15`, `oak_slab[type=top] · drawn as nothing`. */
        fun describe(): String = when (kind) {
            Kind.LIGHT -> "Light ${state.getValue(LightBlock.LEVEL)}"
            // Block{minecraft:oak_slab}[type=top]
            Kind.EMPTY -> state.toString().removePrefix("Block{").replaceFirst("}", "").removePrefix("minecraft:") + " · drawn as nothing"
            else -> kind.label
        }
    }

    /** Sections scanned a tick, so turning it on or moving fast never stalls a frame. */
    private const val SCANS_PER_TICK = 48
    /** Outlined at most, the nearest first. */
    private const val MAX_DRAWN = 1500
    /** Light levels are written on lights this near; the crosshair names what is this near. */
    private const val LABELS = 12.0
    private const val HOVER = 48.0
    private val DIRECTIONS = Direction.entries

    val on get() = Config.settings.overlays.hiddenBlocks
    private val radius get() = Config.settings.overlays.hiddenRadius

    /** What each scanned section holds, by [SectionPos.asLong]; scanned sections with nothing hold an empty list. */
    private val sections = HashMap<Long, List<Hidden>>()
    /** Sections a block changed in: scanned again first. */
    private val dirty = LinkedHashSet<Long>()
    /** Whether a state's model has no faces, by state, for the [models] the packs gave. */
    private val empty = IdentityHashMap<BlockState, Boolean>()
    private var models: BlockStateModelSet? = null
    private var level: ClientLevel? = null
    /** The player's section at the last pass, and whether a pass is due. */
    private var centre = Long.MIN_VALUE
    private var due = true
    /** What is outlined, the nearest first, and the block it was sorted from. */
    private var shown: List<Hidden> = emptyList()
    private var shownFrom: BlockPos? = null
    private var changed = true
    private val random = RandomSource.create()

    fun init() {
        ClientTickEvents.END_CLIENT_TICK.register { if (on) tick(it) }
        ClientChunkEvents.CHUNK_LOAD.register { _, chunk -> if (on) loaded(chunk.pos) }
        ClientChunkEvents.CHUNK_UNLOAD.register { _, chunk -> if (on) unloaded(chunk.pos) }
        Overlays.add { draw -> if (on) draw(draw) }
    }

    fun toggle() {
        Config.update { overlays.hiddenBlocks = !overlays.hiddenBlocks }
        if (!on) forget()
    }

    /** The radius changed: what left it goes at the next pass. */
    fun setRadius(blocks: Int) {
        Config.update { overlays.hiddenRadius = blocks }
        due = true
    }

    private fun forget() {
        sections.clear()
        dirty.clear()
        shown = emptyList()
        level = null
        centre = Long.MIN_VALUE
        due = true
    }

    /** A block changed (the ClientLevel mixin): its section is scanned again at the next tick. */
    @JvmStatic
    fun blockChanged(pos: BlockPos) {
        if (!on) return
        val key = SectionPos.asLong(pos)
        if (key in sections) dirty += key
    }

    private fun loaded(pos: ChunkPos) {
        unloaded(pos)
        due = true
    }

    private fun unloaded(pos: ChunkPos) {
        if (sections.keys.removeIf { SectionPos.x(it) == pos.x && SectionPos.z(it) == pos.z }) changed = true
    }

    private fun tick(minecraft: Minecraft) {
        val level = minecraft.level ?: return forget()
        val player = minecraft.player ?: return
        if (level !== this.level) {
            forget()
            this.level = level
        }
        val set = minecraft.modelManager.blockStateModelSet
        if (set !== models) {
            models = set
            empty.clear()
            sections.clear()
            due = true
        }
        val at = player.blockPosition()
        val here = SectionPos.asLong(at)
        if (here != centre) {
            centre = here
            due = true
        }
        var budget = SCANS_PER_TICK
        while (budget > 0 && dirty.isNotEmpty()) {
            val key = dirty.first().also { dirty.remove(it) }
            if (key in sections) sections[key] = scan(level, SectionPos.x(key), SectionPos.y(key), SectionPos.z(key)) ?: emptyList()
            budget--
            changed = true
        }
        if (due) due = !pass(level, at, budget)
        if (changed || shownFrom?.let { it.distManhattan(at) > 2 } != false) sort(at)
    }

    /** Scans up to [budget] sections in the radius not scanned yet, forgets those out of it; true once all are scanned. */
    private fun pass(level: ClientLevel, at: BlockPos, budget: Int): Boolean {
        val r = radius
        val wanted = HashSet<Long>()
        var left = budget
        var done = true
        for (sx in SectionPos.blockToSectionCoord(at.x - r)..SectionPos.blockToSectionCoord(at.x + r)) {
            for (sz in SectionPos.blockToSectionCoord(at.z - r)..SectionPos.blockToSectionCoord(at.z + r)) {
                for (sy in maxOf(level.minSectionY, SectionPos.blockToSectionCoord(at.y - r))..minOf(level.maxSectionY, SectionPos.blockToSectionCoord(at.y + r))) {
                    val key = SectionPos.asLong(sx, sy, sz)
                    wanted += key
                    if (key in sections) continue
                    if (left <= 0) {
                        done = false
                        continue
                    }
                    // Not loaded yet: its chunk's load asks for another pass.
                    val found = scan(level, sx, sy, sz) ?: continue
                    sections[key] = found
                    left--
                    changed = true
                }
            }
        }
        if (sections.keys.retainAll(wanted)) changed = true
        return done
    }

    /** What section [sx] [sy] [sz] hides, or null when its chunk isn't loaded. */
    private fun scan(level: ClientLevel, sx: Int, sy: Int, sz: Int): List<Hidden>? {
        val chunk = level.chunkSource.getChunk(sx, sz, ChunkStatus.FULL, false) ?: return null
        val section = chunk.getSection(chunk.getSectionIndexFromSectionY(sy))
        if (section.hasOnlyAir() || !section.maybeHas { kind(it) != null }) return emptyList()
        val found = ArrayList<Hidden>()
        for (y in 0..15) for (z in 0..15) for (x in 0..15) {
            val state = section.getBlockState(x, y, z)
            val kind = kind(state) ?: continue
            found += Hidden(BlockPos(sx * 16 + x, sy * 16 + y, sz * 16 + z), state, kind)
        }
        return found
    }

    /** Which hidden kind [state] is, or null when the world draws it. */
    private fun kind(state: BlockState): Kind? = when {
        state.isAir -> null
        state.block === Blocks.BARRIER -> Kind.BARRIER
        state.block === Blocks.LIGHT -> Kind.LIGHT
        state.block === Blocks.STRUCTURE_VOID -> Kind.STRUCTURE_VOID
        // Fluids and the like are drawn otherwise; block entities by their own renderers.
        state.renderShape != RenderShape.MODEL || state.hasBlockEntity() -> null
        empty.getOrPut(state) { drawsNothing(state) } -> Kind.EMPTY
        else -> null
    }

    /** Whether the packs' model for [state] has no faces at all. */
    private fun drawsNothing(state: BlockState): Boolean {
        val model = models?.get(state) ?: return false
        val parts = ArrayList<BlockStateModelPart>()
        random.setSeed(42)
        model.collectParts(random, parts)
        return parts.all { part -> part.getQuads(null).isEmpty() && DIRECTIONS.all { part.getQuads(it).isEmpty() } }
    }

    /** What is outlined from [at]: everything within the radius, the nearest [MAX_DRAWN]. */
    private fun sort(at: BlockPos) {
        val r2 = radius.toDouble() * radius
        val all = sections.values.flatten().filter { it.pos.distSqr(at) <= r2 }
        shown = if (all.size <= MAX_DRAWN) all else all.sortedBy { it.pos.distSqr(at) }.take(MAX_DRAWN)
        shownFrom = at
        changed = false
    }

    private fun draw(draw: OverlayDraw) {
        val ray = Ray.crosshair()
        val world = ray.block(HOVER)?.location?.distanceTo(ray.origin) ?: HOVER
        var hovered: Hidden? = null
        var nearest = Double.MAX_VALUE
        for (h in shown) {
            val color = h.kind.color
            draw.blockBox(h.pos.x, h.pos.y, h.pos.z, h.pos.x, h.pos.y, h.pos.z, color, 1.5f, Colors.withAlpha(color, 0.12f))
            val centre = Vec3.atCenterOf(h.pos)
            val distance = centre.distanceTo(draw.camera)
            if (h.kind == Kind.LIGHT && distance <= LABELS) draw.label(centre, "${h.state.getValue(LightBlock.LEVEL)}", color, onTop = false)
            if (distance > HOVER) continue
            val t = ray.enters(h.box)?.takeIf { it > 0 && it <= world + 0.01 } ?: continue
            if (t < nearest) {
                nearest = t
                hovered = h
            }
        }
        hovered?.let { h ->
            draw.blockBox(h.pos.x, h.pos.y, h.pos.z, h.pos.x, h.pos.y, h.pos.z, Colors.HOVER, 3f, onTop = true)
            draw.label(Vec3.atCenterOf(h.pos).add(0.0, 0.8, 0.0), listOf(h.describe() to h.kind.color, "${h.pos.x} ${h.pos.y} ${h.pos.z}" to Colors.WHITE), size = 0.8f)
        }
    }

    /** How many of each kind are outlined, for the hub. */
    fun counts(): Map<Kind, Int> = shown.groupingBy { it.kind }.eachCount()
}
