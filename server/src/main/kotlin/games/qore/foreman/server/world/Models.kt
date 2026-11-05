package games.qore.foreman.server.world

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import games.qore.foreman.server.Foreman
import org.bukkit.Material
import org.bukkit.block.data.BlockData
import org.joml.Matrix4d
import org.joml.Vector3d
import java.awt.image.BufferedImage
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream
import kotlin.math.cos

/**
 * Game assets for the renders: the vanilla client jar (downloaded once from Mojang, or `resources.client-jar`) under
 * the server's resource packs (`resources.packs`: zips or folders). Later sources win.
 */
object Resources {
    private class Source(val file: File) {
        val modified = file.lastModified()
        val zip: ZipFile? = if (file.isFile) ZipFile(file) else null

        fun read(path: String): ByteArray? = if (zip != null) zip.getEntry(path)?.let { e -> zip.getInputStream(e).use { it.readBytes() } }
        else File(file, path).takeIf { it.isFile }?.readBytes()
    }

    private var sources: List<Source> = emptyList()
    private var problem: String? = null
    private val listeners = mutableListOf<() -> Unit>()

    /** Called when the sources change (a pack was rebuilt): caches built on them are dropped. */
    fun onReload(listener: () -> Unit) = synchronized(listeners) { listeners += listener }

    /** The source files, in priority order (last wins). */
    private fun files(plugin: Foreman): List<File> {
        val out = ArrayList<File>()
        clientJar(plugin)?.let { out += it }
        val packs = plugin.config.getStringList("resources.packs")
        packs.forEach { p -> File(p).let { if (it.isAbsolute) it else File(plugin.server.worldContainer, p) }.takeIf { it.exists() }?.let { out += it } }
        return out
    }

    private fun clientJar(plugin: Foreman): File? {
        plugin.config.getString("resources.client-jar")?.takeIf { it.isNotEmpty() }?.let { return File(it).takeIf { f -> f.isFile } }
        val version = plugin.server.minecraftVersion
        val cached = File(plugin.dataFolder, "cache/client-$version.jar")
        if (cached.isFile) return cached
        return try {
            val manifest = JsonParser.parseString(URI("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json").toURL().readText()).asJsonObject
            val entry = manifest.getAsJsonArray("versions").map { it.asJsonObject }.first { it.get("id").asString == version }
            val meta = JsonParser.parseString(URI(entry.get("url").asString).toURL().readText()).asJsonObject
            val url = meta.getAsJsonObject("downloads").getAsJsonObject("client").get("url").asString
            cached.parentFile.mkdirs()
            URI(url).toURL().openStream().use { input -> cached.outputStream().use { input.copyTo(it) } }
            cached
        } catch (e: Exception) {
            problem = "No client jar for $version (set resources.client-jar): ${e.message}"
            null
        }
    }

    private var dirty = false

    /** Drops the caches: files were written into a source directory. */
    fun invalidate() {
        dirty = true
    }

    /** Opens the sources, or reopens them when one changed on disk. */
    @Synchronized
    fun ensure() {
        val plugin = Foreman.instance
        val files = files(plugin)
        if (!dirty && files.map { it.absolutePath to it.lastModified() } == sources.map { it.file.absolutePath to it.modified }) return
        dirty = false
        sources.forEach { runCatching { it.zip?.close() } }
        // A pack being rebuilt is half written: skip it this time, it is opened again on the next call.
        val opened = files.mapNotNull { runCatching { Source(it) }.getOrNull() }
        sources = opened
        dirty = opened.size < files.size
        synchronized(listeners) { listeners.forEach { it() } }
    }

    fun status(): String {
        ensure()
        return if (sources.isEmpty()) "no asset sources" + (problem?.let { ": $it" } ?: "")
        else sources.joinToString(", ") { it.file.name } + if (dirty) " (a pack is being rebuilt: its models are missing until it is done)" else ""
    }

    fun read(path: String): ByteArray? {
        ensure()
        for (i in sources.indices.reversed()) sources[i].read(path)?.let { return it }
        return null
    }

    fun json(path: String): JsonObject? = read(path)?.let { runCatching { JsonParser.parseString(it.decodeToString()).asJsonObject }.getOrNull() }

    /** `minecraft:block/stone` → `assets/minecraft/<folder>/block/stone<ext>`. */
    fun path(id: String, folder: String, ext: String): String {
        val ns = if (':' in id) id.substringBefore(':') else "minecraft"
        return "assets/$ns/$folder/${id.substringAfter(':')}$ext"
    }
}

/** A texture's pixels (ARGB), the first frame of animated ones. */
class Texture(val width: Int, val height: Int, val pixels: IntArray) {
    fun sample(u: Double, v: Double): Int {
        val x = (u * width).toInt().coerceIn(0, width - 1)
        val y = (v * height).toInt().coerceIn(0, height - 1)
        return pixels[y * width + x]
    }

    /** Mean opaque color, for map-like fallbacks. */
    val average: Int by lazy {
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        for (p in pixels) if ((p ushr 24) > 128) { r += (p shr 16) and 255; g += (p shr 8) and 255; b += p and 255; n++ }
        if (n == 0) 0 else (0xff shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
    }

    companion object {
        fun of(image: BufferedImage): Texture {
            val w = image.width
            val h = if (image.height > w) w else image.height
            val pixels = IntArray(w * h)
            image.getRGB(0, 0, w, h, pixels, 0, w)
            return Texture(w, h, pixels)
        }
    }
}

/**
 * One face of a baked model, a parallelogram: [origin] + s·[edgeU] + t·[edgeV] for s, t in 0..1, in block units.
 * [uv] holds the texture coordinates (0..1) at origin, origin + edgeU and origin + edgeV. [color] replaces the texture
 * when there is none (blocks drawn by the client in code: chests, signs…).
 */
class Quad(
    val origin: Vector3d, val edgeU: Vector3d, val edgeV: Vector3d, val normal: Vector3d,
    val uv: DoubleArray, val texture: Texture?, val tint: Int, val shade: Boolean, val color: Int = 0,
) {
    fun transformed(m: Matrix4d): Quad {
        val o = m.transformPosition(Vector3d(origin))
        val u = m.transformDirection(Vector3d(edgeU))
        val v = m.transformDirection(Vector3d(edgeV))
        val n = Vector3d(u).cross(v).normalize()
        // A mirrored transform flips the winding: keep the normal pointing out like before.
        if (n.dot(m.transformDirection(Vector3d(normal))) < 0) n.negate()
        return Quad(o, u, v, n, uv, texture, tint, shade, color)
    }

    fun min() = Vector3d(origin).min(Vector3d(origin).add(edgeU)).min(Vector3d(origin).add(edgeV)).min(Vector3d(origin).add(edgeU).add(edgeV))
    fun max() = Vector3d(origin).max(Vector3d(origin).add(edgeU)).max(Vector3d(origin).add(edgeV)).max(Vector3d(origin).add(edgeU).add(edgeV))
}

/** A model baked to quads in block units: blocks span 0..1, item models before their display transform too. */
class Baked(val quads: List<Quad>) {
    val min: Vector3d = quads.fold(Vector3d(Double.MAX_VALUE)) { a, q -> a.min(q.min()) }
    val max: Vector3d = quads.fold(Vector3d(-Double.MAX_VALUE)) { a, q -> a.max(q.max()) }
    val empty get() = quads.isEmpty()

    fun transformed(m: Matrix4d) = Baked(quads.map { it.transformed(m) })

    companion object {
        val EMPTY = Baked(emptyList())
    }
}

/** Block and item models from [Resources], baked and cached. */
object Models {
    private class Model(val textures: Map<String, String>, val elements: JsonArray?, val display: Map<String, JsonObject>, val generated: Boolean)

    private val models = ConcurrentHashMap<String, Model>()
    private val textures = ConcurrentHashMap<String, Texture?>()
    private val blocks = ConcurrentHashMap<String, Baked>()
    private val items = ConcurrentHashMap<String, ItemModel?>()

    init {
        Resources.onReload { models.clear(); textures.clear(); blocks.clear(); items.clear() }
    }

    /** An item model and its `fixed` display transform (what item displays and frames use). */
    class ItemModel(val baked: Baked, val fixed: Matrix4d)

    private fun model(id: String, depth: Int = 0): Model? {
        val key = if (':' in id) id else "minecraft:$id"
        models[key]?.let { return it }
        if (key == "minecraft:builtin/generated") return Model(emptyMap(), null, emptyMap(), true)
        if (depth > 16) return null
        val json = Resources.json(Resources.path(key, "models", ".json")) ?: return null
        val parent = json.get("parent")?.asString?.let { model(it, depth + 1) }
        val textures = HashMap(parent?.textures ?: emptyMap())
        json.getAsJsonObject("textures")?.entrySet()?.forEach { (k, v) -> if (v.isJsonPrimitive) textures[k] = v.asString }
        val display = HashMap(parent?.display ?: emptyMap())
        json.getAsJsonObject("display")?.entrySet()?.forEach { (k, v) -> display[k] = v.asJsonObject }
        val model = Model(textures, json.getAsJsonArray("elements") ?: parent?.elements, display, parent?.generated == true && !json.has("elements"))
        models[key] = model
        return model
    }

    fun texture(id: String): Texture? = textures.computeIfAbsent(if (':' in id) id else "minecraft:$id") { key ->
        Resources.read(Resources.path(key, "textures", ".png"))?.let { bytes ->
            runCatching { MemoryCacheImageInputStream(bytes.inputStream()).use { ImageIO.read(it) }?.let { Texture.of(it) } }.getOrNull()
        }
    }

    private fun resolve(model: Model, ref: String): Texture? {
        var name = ref
        repeat(8) {
            if (!name.startsWith("#")) return texture(name)
            name = model.textures[name.substring(1)] ?: return null
        }
        return null
    }

    /** Bakes a model's elements (0..16 model units) to block units, with its own element rotations. */
    private fun bake(model: Model): List<Quad> {
        if (model.generated) return generated(model)
        val out = ArrayList<Quad>()
        model.elements?.forEach { e ->
            val el = e.asJsonObject
            val from = vec(el.getAsJsonArray("from"))
            val to = vec(el.getAsJsonArray("to"))
            val rotation = elementRotation(el.getAsJsonObject("rotation"))
            val shade = el.get("shade")?.asBoolean ?: true
            el.getAsJsonObject("faces")?.entrySet()?.forEach { (dir, f) ->
                val face = f.asJsonObject
                val texture = face.get("texture")?.asString?.let { resolve(model, it) }
                val quad = face(dir, from, to, face, texture) ?: return@forEach
                out += if (rotation != null) quad.transformed(rotation) else quad
            }
        }
        return out
    }

    /** `builtin/generated` items: the layer textures as a 1/16 thick plate. */
    private fun generated(model: Model): List<Quad> {
        val out = ArrayList<Quad>()
        for (i in 0..4) {
            val texture = model.textures["layer$i"]?.let { texture(it) } ?: continue
            val z0 = 7.5 / 16 - i * 0.001
            val z1 = 8.5 / 16 + i * 0.001
            out += Quad(Vector3d(1.0, 1.0, z0), Vector3d(-1.0, 0.0, 0.0), Vector3d(0.0, -1.0, 0.0), Vector3d(0.0, 0.0, -1.0), doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 1.0), texture, i, true)
            out += Quad(Vector3d(0.0, 1.0, z1), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, -1.0, 0.0), Vector3d(0.0, 0.0, 1.0), doubleArrayOf(0.0, 0.0, 1.0, 0.0, 0.0, 1.0), texture, i, true)
        }
        return out
    }

    private fun vec(a: JsonArray) = Vector3d(a[0].asDouble / 16, a[1].asDouble / 16, a[2].asDouble / 16)

    private fun elementRotation(r: JsonObject?): Matrix4d? {
        if (r == null) return null
        val origin = r.getAsJsonArray("origin")?.let { vec(it) } ?: Vector3d(0.5)
        val m = Matrix4d().translate(origin)
        if (r.has("axis")) {
            val angle = Math.toRadians(r.get("angle")?.asDouble ?: 0.0)
            if (angle == 0.0) return null
            val rescale = r.get("rescale")?.asBoolean == true
            val s = if (rescale) 1 / cos(angle) else 1.0
            when (r.get("axis").asString) {
                "x" -> m.rotateX(angle).scale(1.0, s, s)
                "y" -> m.rotateY(angle).scale(s, 1.0, s)
                else -> m.rotateZ(angle).scale(s, s, 1.0)
            }
        } else {
            m.rotateXYZ(Math.toRadians(r.get("x")?.asDouble ?: 0.0), Math.toRadians(r.get("y")?.asDouble ?: 0.0), Math.toRadians(r.get("z")?.asDouble ?: 0.0))
        }
        return m.translate(Vector3d(origin).negate())
    }

    /**
     * The face [dir] of the element box [from]..[to]: its corners top-left, top-right, bottom-left as the client
     * maps the uv rectangle on them (FaceBakery), honouring the face's uv and rotation.
     */
    private fun face(dir: String, f: Vector3d, t: Vector3d, face: JsonObject, texture: Texture?): Quad? {
        // Corners TL, TR, BR, BL of each face seen from outside, and its default uv in pixels.
        val (corners, normal, uvDefault) = when (dir) {
            "north" -> Triple(listOf(Vector3d(t.x, t.y, f.z), Vector3d(f.x, t.y, f.z), Vector3d(f.x, f.y, f.z), Vector3d(t.x, f.y, f.z)), Vector3d(0.0, 0.0, -1.0), doubleArrayOf(16 - t.x * 16, 16 - t.y * 16, 16 - f.x * 16, 16 - f.y * 16))
            "south" -> Triple(listOf(Vector3d(f.x, t.y, t.z), Vector3d(t.x, t.y, t.z), Vector3d(t.x, f.y, t.z), Vector3d(f.x, f.y, t.z)), Vector3d(0.0, 0.0, 1.0), doubleArrayOf(f.x * 16, 16 - t.y * 16, t.x * 16, 16 - f.y * 16))
            "west" -> Triple(listOf(Vector3d(f.x, t.y, f.z), Vector3d(f.x, t.y, t.z), Vector3d(f.x, f.y, t.z), Vector3d(f.x, f.y, f.z)), Vector3d(-1.0, 0.0, 0.0), doubleArrayOf(f.z * 16, 16 - t.y * 16, t.z * 16, 16 - f.y * 16))
            "east" -> Triple(listOf(Vector3d(t.x, t.y, t.z), Vector3d(t.x, t.y, f.z), Vector3d(t.x, f.y, f.z), Vector3d(t.x, f.y, t.z)), Vector3d(1.0, 0.0, 0.0), doubleArrayOf(16 - t.z * 16, 16 - t.y * 16, 16 - f.z * 16, 16 - f.y * 16))
            "up" -> Triple(listOf(Vector3d(f.x, t.y, f.z), Vector3d(t.x, t.y, f.z), Vector3d(t.x, t.y, t.z), Vector3d(f.x, t.y, t.z)), Vector3d(0.0, 1.0, 0.0), doubleArrayOf(f.x * 16, f.z * 16, t.x * 16, t.z * 16))
            "down" -> Triple(listOf(Vector3d(f.x, f.y, t.z), Vector3d(t.x, f.y, t.z), Vector3d(t.x, f.y, f.z), Vector3d(f.x, f.y, f.z)), Vector3d(0.0, -1.0, 0.0), doubleArrayOf(f.x * 16, 16 - t.z * 16, t.x * 16, 16 - f.z * 16))
            else -> return null
        }
        val uv = face.getAsJsonArray("uv")?.map { it.asDouble }?.toDoubleArray() ?: uvDefault
        val u0 = uv[0] / 16; val v0 = uv[1] / 16; val u1 = uv[2] / 16; val v1 = uv[3] / 16
        // UVs of the corners TL, TR, BR, BL; a face rotation turns the texture clockwise.
        val cornerUv = listOf(doubleArrayOf(u0, v0), doubleArrayOf(u1, v0), doubleArrayOf(u1, v1), doubleArrayOf(u0, v1))
        val k = ((face.get("rotation")?.asInt ?: 0) / 90).mod(4)
        fun uvAt(i: Int) = cornerUv[(i - k).mod(4)]
        val tl = corners[0]
        val tr = corners[1]
        val bl = corners[3]
        val tint = face.get("tintindex")?.asInt ?: -1
        return Quad(
            tl, Vector3d(tr).sub(tl), Vector3d(bl).sub(tl), normal,
            doubleArrayOf(uvAt(0)[0], uvAt(0)[1], uvAt(1)[0], uvAt(1)[1], uvAt(3)[0], uvAt(3)[1]),
            texture, tint, true,
        )
    }

    // ---- Blocks ---------------------------------------------------------------------------------------------------

    /** The block state's model in block units (0..1), rotated as its blockstate file says. Empty for air and liquids. */
    fun block(data: BlockData): Baked = blocks.computeIfAbsent(data.asString) { bakeBlock(data) }

    private fun properties(data: BlockData): Map<String, String> {
        val s = data.asString
        if ('[' !in s) return emptyMap()
        return s.substringAfter('[').removeSuffix("]").split(',').associate { it.substringBefore('=') to it.substringAfter('=') }
    }

    private fun matches(spec: String, props: Map<String, String>): Boolean =
        spec.isEmpty() || spec.split(',').all { c -> props[c.substringBefore('=')] == c.substringAfter('=') }

    private fun matchesWhen(json: JsonObject, props: Map<String, String>): Boolean {
        json.getAsJsonArray("OR")?.let { list -> return list.any { matchesWhen(it.asJsonObject, props) } }
        json.getAsJsonArray("AND")?.let { list -> return list.all { matchesWhen(it.asJsonObject, props) } }
        return json.entrySet().all { (k, v) -> props[k] in v.asString.split('|') }
    }

    private fun bakeBlock(data: BlockData): Baked {
        val m = data.material
        if (m.isAir || Blocks.isLiquid(m) || m == Material.BARRIER || m == Material.LIGHT || m == Material.STRUCTURE_VOID) return Baked.EMPTY
        val id = data.asString.substringBefore('[')
        val states = Resources.json(Resources.path(id, "blockstates", ".json")) ?: return fallback(data)
        val props = properties(data)
        val applied = ArrayList<JsonObject>()
        states.getAsJsonObject("variants")?.let { variants ->
            variants.entrySet().firstOrNull { matches(it.key, props) }?.value?.let { applied += first(it) }
        }
        states.getAsJsonArray("multipart")?.forEach { part ->
            val p = part.asJsonObject
            if (p.getAsJsonObject("when")?.let { matchesWhen(it, props) } != false) applied += first(p.get("apply"))
        }
        val quads = ArrayList<Quad>()
        applied.forEach { a ->
            val model = model(a.get("model").asString) ?: return@forEach
            val x = a.get("x")?.asInt ?: 0
            val y = a.get("y")?.asInt ?: 0
            val baked = bake(model)
            if (x == 0 && y == 0) quads += baked
            else {
                // The variant turns the model around the block center: x first, then y (clockwise seen from above).
                val r = Matrix4d().translate(0.5, 0.5, 0.5).rotateY(-Math.toRadians(y.toDouble())).rotateX(-Math.toRadians(x.toDouble())).translate(-0.5, -0.5, -0.5)
                baked.forEach { quads += it.transformed(r) }
            }
        }
        return if (quads.isEmpty()) fallback(data) else Baked(quads)
    }

    private fun first(e: JsonElement): JsonObject = if (e.isJsonArray) e.asJsonArray[0].asJsonObject else e.asJsonObject

    /** Blocks the client draws in code (chests, signs, beds…): their collision box in their map color. */
    private fun fallback(data: BlockData): Baked {
        // No map color (glass, air-like blocks): nothing sensible to draw.
        val c = runCatching { data.mapColor }.getOrNull()?.takeIf { it.red + it.green + it.blue > 0 } ?: return Baked.EMPTY
        val color = (0xff shl 24) or (c.red shl 16) or (c.green shl 8) or c.blue
        val box = boxes[data.material] ?: doubleArrayOf(1.0 / 16, 0.0, 1.0 / 16, 15.0 / 16, 14.0 / 16, 15.0 / 16)
        return Baked(cuboid(Vector3d(box[0], box[1], box[2]), Vector3d(box[3], box[4], box[5]), color))
    }

    private val boxes = mapOf(
        Material.CHEST to doubleArrayOf(1.0 / 16, 0.0, 1.0 / 16, 15.0 / 16, 14.0 / 16, 15.0 / 16),
    )

    /** Six untextured faces of a box. */
    fun cuboid(f: Vector3d, t: Vector3d, color: Int): List<Quad> {
        fun q(o: Vector3d, u: Vector3d, v: Vector3d, n: Vector3d) = Quad(o, u, v, n, doubleArrayOf(0.0, 0.0, 1.0, 0.0, 0.0, 1.0), null, -1, true, color)
        val d = Vector3d(t).sub(f)
        return listOf(
            q(Vector3d(f.x, t.y, f.z), Vector3d(d.x, 0.0, 0.0), Vector3d(0.0, 0.0, d.z), Vector3d(0.0, 1.0, 0.0)),
            q(Vector3d(f.x, f.y, f.z), Vector3d(d.x, 0.0, 0.0), Vector3d(0.0, 0.0, d.z), Vector3d(0.0, -1.0, 0.0)),
            q(Vector3d(f.x, f.y, f.z), Vector3d(d.x, 0.0, 0.0), Vector3d(0.0, d.y, 0.0), Vector3d(0.0, 0.0, -1.0)),
            q(Vector3d(f.x, f.y, t.z), Vector3d(d.x, 0.0, 0.0), Vector3d(0.0, d.y, 0.0), Vector3d(0.0, 0.0, 1.0)),
            q(Vector3d(f.x, f.y, f.z), Vector3d(0.0, 0.0, d.z), Vector3d(0.0, d.y, 0.0), Vector3d(-1.0, 0.0, 0.0)),
            q(Vector3d(t.x, f.y, f.z), Vector3d(0.0, 0.0, d.z), Vector3d(0.0, d.y, 0.0), Vector3d(1.0, 0.0, 0.0)),
        )
    }

    // ---- Items ----------------------------------------------------------------------------------------------------

    /** The model an item model id (`example:brown_sofa`, from the item's item_model) shows by default. */
    fun item(id: String): ItemModel? = items.computeIfAbsent(if (':' in id) id else "minecraft:$id") { key ->
        val definition = Resources.json(Resources.path(key, "items", ".json"))?.getAsJsonObject("model")
        val modelId = definition?.let { modelOf(it) } ?: key.substringBefore(':') + ":item/" + key.substringAfter(':')
        modelFile(modelId)
    }

    /** A model file by id (`example:item/furniture/brown_sofa`), baked with its fixed display transform. */
    fun modelFile(id: String): ItemModel? {
        val model = model(id) ?: return null
        return ItemModel(Baked(bake(model)), fixed(model.display["fixed"]))
    }

    /** Bakes a model JSON that is not in the sources (yet), with its fixed display transform. */
    fun fromJson(json: JsonObject): ItemModel {
        val parent = json.get("parent")?.asString?.let { model(it) }
        val textures = HashMap(parent?.textures ?: emptyMap())
        json.getAsJsonObject("textures")?.entrySet()?.forEach { (k, v) -> textures[k] = v.asString }
        val display = HashMap(parent?.display ?: emptyMap())
        json.getAsJsonObject("display")?.entrySet()?.forEach { (k, v) -> display[k] = v.asJsonObject }
        val model = Model(textures, json.getAsJsonArray("elements") ?: parent?.elements, display, false)
        return ItemModel(Baked(bake(model)), fixed(display["fixed"]))
    }

    /** The first plain model an item model definition can show (cases of select/condition/range, composite parts). */
    private fun modelOf(json: JsonObject): String? {
        when (json.get("type")?.asString?.removePrefix("minecraft:")) {
            "model" -> return json.get("model")?.asString
            "composite" -> return json.getAsJsonArray("models")?.firstNotNullOfOrNull { modelOf(it.asJsonObject) }
            "condition" -> return json.getAsJsonObject("on_false")?.let { modelOf(it) } ?: json.getAsJsonObject("on_true")?.let { modelOf(it) }
            "select", "range_dispatch" -> {
                json.getAsJsonObject("fallback")?.let { modelOf(it) }?.let { return it }
                val cases = json.getAsJsonArray("cases") ?: json.getAsJsonArray("entries")
                return cases?.firstNotNullOfOrNull { c -> c.asJsonObject.getAsJsonObject("model")?.let { modelOf(it) } }
            }
        }
        return null
    }

    /** ItemTransform: translate, rotate X then Y then Z (applied to the model in reverse), scale, around the center. */
    private fun fixed(display: JsonObject?): Matrix4d {
        val m = Matrix4d()
        if (display != null) {
            display.getAsJsonArray("translation")?.let { t -> m.translate((t[0].asDouble / 16).coerceIn(-5.0, 5.0), (t[1].asDouble / 16).coerceIn(-5.0, 5.0), (t[2].asDouble / 16).coerceIn(-5.0, 5.0)) }
            display.getAsJsonArray("rotation")?.let { r -> m.rotateXYZ(Math.toRadians(r[0].asDouble), Math.toRadians(r[1].asDouble), Math.toRadians(r[2].asDouble)) }
            display.getAsJsonArray("scale")?.let { s -> m.scale(s[0].asDouble.coerceIn(-4.0, 4.0), s[1].asDouble.coerceIn(-4.0, 4.0), s[2].asDouble.coerceIn(-4.0, 4.0)) }
        }
        return m.translate(-0.5, -0.5, -0.5)
    }

    /** Block units bounds of a quad list, or null. */
    fun bounds(quads: List<Quad>): Pair<Vector3d, Vector3d>? = if (quads.isEmpty()) null else Baked(quads).let { it.min to it.max }
}
