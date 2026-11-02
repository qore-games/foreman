package games.qore.foreman.client.link

import games.qore.foreman.client.config.Config
import games.qore.foreman.client.gizmo.BlockBox
import games.qore.foreman.client.input.Tools
import games.qore.foreman.client.net.Net
import games.qore.foreman.client.render.OverlayDraw
import games.qore.foreman.client.render.Overlays
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import java.util.ArrayDeque

/**
 * The player's WorldEdit selection, as WorldEdit tells a CUI client (`worldedit:cui`: the `v|4` handshake, then the
 * shape `s|…` and its points, radii and heights). Works on any server with WorldEdit, Foreman or not: it's drawn in the
 * world, edited with [WorldEditTool], and chats get it as the place to work in. Off when WorldEdit CUI (or WorldEdit
 * itself) is installed: they own the channel.
 */
object WorldEditSelection {
    private const val CHANNEL = "worldedit:cui"
    const val COLOR = 0xFFFFB347.toInt()

    /** False when another mod owns the channel. */
    var available = false
        private set
    /** WorldEdit's selector: cuboid, polygon2d, ellipsoid, sphere, cylinder, polyhedron; empty before it says. */
    var type = ""
        private set
    private val points = sortedMapOf<Int, IntArray>()
    private val points2 = sortedMapOf<Int, IntArray>()
    private var minY: Int? = null
    private var maxY: Int? = null
    private var center: DoubleArray? = null
    private var radius: DoubleArray? = null
    private val faces = mutableListOf<IntArray>()
    /** The dimension the selection was made in: it isn't drawn in another. */
    private var dimension: String? = null

    /** Ticks left to keep trying the handshake after joining (the server announces its channels a moment later). */
    private var handshake = 0
    /** Commands to send for the tool, a few at a time so the server's spam check never kicks. */
    private val queue = ArrayDeque<String>()
    /** Our estimate of the server's chat spam counter: +20 a message, -1 a tick, kicks over 200. */
    private var spam = 0
    /** Ticks since the last command was sent. */
    var quiet = 0
        private set

    fun init() {
        if (listOf("worldeditcui", "worldedit").any { FabricLoader.getInstance().isModLoaded(it) }) return
        available = true
        Net.incoming(CHANNEL, { it.decodeToString() }, ::handle)
        val out = Net.outgoing<String>(CHANNEL) { it.encodeToByteArray() }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> handshake = 100 }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> reset(""); dimension = null; queue.clear() }
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (handshake > 0) {
                handshake--
                if (handshake % 20 == 0 && out.send("v|4")) handshake = 0
            }
            if (spam > 0) spam--
            quiet++
            val connection = client.player?.connection ?: return@register queue.clear()
            if (queue.isNotEmpty() && spam <= 160) {
                connection.sendCommand(queue.poll())
                spam += 20
                quiet = 0
            }
        }
        Overlays.add { if (Tools.active !== WorldEditTool && Config.settings.overlays.worldEdit) draw(it, shape()) }
    }

    private fun reset(type: String) {
        this.type = type
        points.clear()
        points2.clear()
        minY = null
        maxY = null
        center = null
        radius = null
        faces.clear()
    }

    private fun handle(message: String) {
        val parts = message.split('|')
        fun int(i: Int) = parts.getOrNull(i)?.toDoubleOrNull()?.toInt()
        fun double(i: Int) = parts.getOrNull(i)?.toDoubleOrNull()
        when (parts.firstOrNull()) {
            "s" -> {
                reset(parts.getOrElse(1) { "" })
                dimension = Minecraft.getInstance().level?.dimension()?.identifier()?.toString()
            }
            "p" -> points[int(1) ?: return] = intArrayOf(int(2) ?: return, int(3) ?: return, int(4) ?: return)
            "p2" -> points2[int(1) ?: return] = intArrayOf(int(2) ?: return, int(3) ?: return)
            "mm" -> {
                minY = int(1)
                maxY = int(2)
            }
            "e" -> {
                val xyz = doubleArrayOf(double(2) ?: return, double(3) ?: return, double(4) ?: return)
                if (int(1) == 0) center = xyz else radius = xyz
            }
            "cyl" -> {
                center = doubleArrayOf(double(1) ?: return, double(2) ?: return, double(3) ?: return)
                radius = doubleArrayOf(double(4) ?: return, 0.0, double(5) ?: return)
            }
            "poly" -> faces += (1 until parts.size).mapNotNull { int(it) }.toIntArray()
        }
    }

    /** The selection, or null while WorldEdit hasn't sent a whole one. */
    fun shape(): WeShape? {
        val c = center
        val r = radius
        return when (type) {
            "cuboid" -> {
                val a = points[0] ?: return null
                val b = points[1] ?: return null
                WeShape.Cuboid(BlockBox.of(a[0], a[1], a[2], b[0], b[1], b[2]))
            }
            "polygon2d" -> if (points2.isEmpty()) null else WeShape.Polygon(points2.values.toList(), minY ?: return null, maxY ?: return null)
            "ellipsoid", "sphere" -> if (c == null || r == null) null else WeShape.Ellipsoid(c, r, type == "sphere" || (r[0] == r[1] && r[1] == r[2]))
            "cylinder" -> if (c == null || r == null) null else WeShape.Cylinder(c[0], c[2], r[0], r[2], minY ?: c[1].toInt(), maxY ?: c[1].toInt())
            "polyhedron" -> if (points.isEmpty()) null else WeShape.Polyhedron(points.values.toList(), faces.toList())
            else -> null
        }
    }

    /** Draws [shape] with its size above it, in the dimension the selection belongs to. */
    fun draw(draw: OverlayDraw, shape: WeShape?, color: Int = COLOR) {
        if (shape == null || (dimension != null && dimension != draw.dimension)) return
        shape.draw(draw, color)
        val b = shape.bounds
        draw.label(AIOverlay.topCenter(b), "WorldEdit ${shape.label}", color)
    }

    /** Sends WorldEdit [commands] as the player, in order, paced. */
    fun run(commands: List<String>) {
        queue.addAll(commands)
        quiet = 0
    }

    /** Commands still to send, or sent less than half a second ago: WorldEdit hasn't answered them all yet. */
    val busy get() = queue.isNotEmpty() || quiet < 10

    /** "cuboid [x, y, z] to [x, y, z] (WxHxD)" and the like, or null without a selection. */
    fun describe(): String? = shape()?.let { "${it.label}, ${it.bounds.describe()}" }
}
