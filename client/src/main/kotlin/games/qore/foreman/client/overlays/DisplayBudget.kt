package games.qore.foreman.client.overlays

import games.qore.foreman.client.config.Config
import games.qore.foreman.client.render.Colors
import games.qore.foreman.client.render.OverlayDraw
import games.qore.foreman.client.render.Overlays
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.SectionPos
import net.minecraft.world.entity.Display
import net.minecraft.world.entity.Interaction
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.phys.Vec3

/**
 * The display budget: how many display entities (item, block and text) and interaction entities each chunk around the
 * player has, its outline green, yellow from [budgetWarn][games.qore.foreman.client.config.OverlaySettings.budgetWarn],
 * red from `budgetHigh`, with its numbers on top; the HUD has the totals of what the client has loaded. Counted twice
 * a second from the client's entities; nothing runs while it is off.
 */
object DisplayBudget {
    private const val EVERY_TICKS = 10
    /** Chunks this far (in chunks) from the player are drawn. */
    private const val REACH = 8
    private const val GREEN = 0xFF55DD77.toInt()
    private const val YELLOW = 0xFFFFD84A.toInt()
    private const val RED = 0xFFFF5050.toInt()

    /** One chunk's count, and the heights its entities span. */
    class Count(val x: Int, val z: Int) {
        var items = 0
        var blocks = 0
        var texts = 0
        var interactions = 0
        var minY = Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        val displays get() = items + blocks + texts
        val total get() = displays + interactions

        fun add(y: Double) {
            minY = minOf(minY, y)
            maxY = maxOf(maxY, y)
        }
    }

    val on get() = Config.settings.overlays.displayBudget

    private var chunks: List<Count> = emptyList()
    private var totals = Count(0, 0)
    private var ticks = 0

    fun init() {
        ClientTickEvents.END_CLIENT_TICK.register { if (on && ++ticks % EVERY_TICKS == 0) count(it) }
        Overlays.add { draw -> if (on) draw(draw) }
    }

    fun toggle() {
        Config.update { overlays.displayBudget = !overlays.displayBudget }
        if (!on) {
            chunks = emptyList()
            totals = Count(0, 0)
        } else Minecraft.getInstance().let(::count)
    }

    private fun count(minecraft: Minecraft) {
        val level = minecraft.level ?: return
        val byChunk = HashMap<Long, Count>()
        val all = Count(0, 0)
        for (e in level.entitiesForRendering()) {
            if (e !is Display && e !is Interaction) continue
            val cx = SectionPos.blockToSectionCoord(e.blockX)
            val cz = SectionPos.blockToSectionCoord(e.blockZ)
            val c = byChunk.getOrPut(ChunkPos.pack(cx, cz)) { Count(cx, cz) }
            for (count in listOf(c, all)) when (e) {
                is Display.ItemDisplay -> count.items++
                is Display.BlockDisplay -> count.blocks++
                is Display.TextDisplay -> count.texts++
                is Interaction -> count.interactions++
            }
            c.add(e.y)
        }
        chunks = byChunk.values.toList()
        totals = all
    }

    private fun color(c: Count): Int {
        val o = Config.settings.overlays
        return when {
            c.total >= o.budgetHigh -> RED
            c.total >= o.budgetWarn -> YELLOW
            else -> GREEN
        }
    }

    private fun draw(draw: OverlayDraw) {
        val cx = SectionPos.blockToSectionCoord(draw.camera.x)
        val cz = SectionPos.blockToSectionCoord(draw.camera.z)
        for (c in chunks) {
            if (maxOf(kotlin.math.abs(c.x - cx), kotlin.math.abs(c.z - cz)) > REACH) continue
            val color = color(c)
            val x0 = c.x * 16.0
            val z0 = c.z * 16.0
            val bottom = c.minY
            val top = c.maxY + 1
            draw.box(Vec3(x0, bottom, z0), Vec3(x0 + 16, top, z0 + 16), color, 2f, Colors.withAlpha(color, 0.04f))
            val kinds = listOf("item" to c.items, "block" to c.blocks, "text" to c.texts).filter { it.second > 0 }.joinToString(" · ") { "${it.second} ${it.first}" }
            draw.label(Vec3(x0 + 8, top + 0.6, z0 + 8), listOfNotNull(
                "${c.displays} display${if (c.displays == 1) "" else "s"} · ${c.interactions} interaction${if (c.interactions == 1) "" else "s"}" to color,
                kinds.ifEmpty { null }?.let { it to Colors.WHITE },
            ))
        }
    }

    /** The totals of what the client has loaded and the busiest chunk, for the HUD; none while it is off. */
    fun hud(): List<String> {
        if (!on) return emptyList()
        val busiest = chunks.maxByOrNull { it.total }
        return listOfNotNull(
            "§bDisplays §f${totals.displays} §7(${totals.items} item, ${totals.blocks} block, ${totals.texts} text) · §f${totals.interactions} §7interactions",
            busiest?.let { "§7Busiest chunk ${it.x} ${it.z}: §f${it.total}" },
        )
    }
}
