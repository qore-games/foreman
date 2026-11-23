package games.qore.foreman.client.panels

import games.qore.foreman.client.entity.EntityEditor
import games.qore.foreman.client.entity.EntityShapes
import games.qore.foreman.client.input.Ray
import games.qore.foreman.client.input.playerDimension
import games.qore.foreman.client.link.Bridge
import games.qore.foreman.client.link.GameObject
import games.qore.foreman.client.objects.ObjectsTool
import games.qore.foreman.client.util.get
import games.qore.foreman.client.util.int
import games.qore.foreman.client.util.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Display
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Interaction
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * Something the builder aims at, for panels and links: its [target] (the bridge's JSON), its [name] for people, [at]
 * (its centre, where arrows start), its outline ([box], or [line] for a world object) and how far along the crosshair
 * it is ([along]).
 */
class Aimed(val target: JsonObject, val name: String, val at: Vec3, val box: AABB?, val along: Double, val line: List<Vec3> = emptyList())

/**
 * Panel targets ([the bridge's JSON][Panels]): what the crosshair is on, the nearest of a world object (as the objects
 * tool picks them), a display entity (by its shape), another entity (its box) and a block, and what a target is for
 * people. Worlds go by the name the bridge gives, with the dimension.
 */
object Targets {
    /** Entities and blocks are aimed at this far. */
    private const val REACH = 128.0
    /** The entities along the crosshair [candidates] offers. */
    private const val ENTITIES = 3

    private val found = object : LinkedHashMap<String, Entity>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entity>?) = size > 8
    }

    /** What [ray] is on, the nearest; null when nothing is. */
    fun aimed(ray: Ray): Aimed? = candidates(ray).firstOrNull()

    /**
     * What [ray] passes through, nearest first: the world object it is on, the first [ENTITIES] entities it meets and the
     * block it ends on. The nearest is what it is on; the others are behind it (or around: an entity inside the block).
     */
    fun candidates(ray: Ray): List<Aimed> {
        val dimension = playerDimension() ?: return emptyList()
        return (listOfNotNull(
            ObjectsTool.aimedObject(ray, dimension)?.let { (o, along) -> of(o, along) },
            ray.block(REACH)?.let { hit -> block(hit.blockPos, hit.location.distanceTo(ray.origin)) },
        ) + entities(ray)).sortedBy { it.along }
    }

    /** The first [ENTITIES] entities [ray] meets: displays by their shape, the others by their box. */
    private fun entities(ray: Ray): List<Aimed> {
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return emptyList()
        val end = ray.at(REACH)
        val displays = ArrayList<Display>()
        val hits = ArrayList<Pair<Entity, Double>>()
        for (e in level.entitiesForRendering()) {
            if (e === minecraft.player || e === minecraft.cameraEntity || e.rootVehicle === minecraft.player || e.distanceToSqr(ray.origin) > REACH * REACH) continue
            if (e is Display) {
                if (!aroundCamera(e, ray.origin)) displays += e
                continue
            }
            val box = e.boundingBox.inflate(e.pickRadius.toDouble())
            val along = if (box.contains(ray.origin)) 0.0 else box.clip(ray.origin, end).map { it.distanceTo(ray.origin) }.orElse(null) ?: continue
            hits += e to along
        }
        EntityShapes.pick(ray, displays).filter { it.exact }.forEach { hits += it.entity to it.distance }
        return hits.sortedBy { it.second }.take(ENTITIES).map { (e, along) -> of(e, along) }
    }

    /**
     * [target] (what a provider resolved [aimed] to: a furniture for its cell block) as the builder sees it: [name], its
     * outline and centre where the client has it (an entity it draws, a block, an object), else [aimed]'s.
     */
    fun resolved(aimed: Aimed, target: JsonObject, name: String?): Aimed {
        val shown = when (target["type"].str()) {
            "entity" -> entity(target)?.let { of(it, aimed.along) }
            "block" -> pos(target)?.let { block(it, aimed.along) }?.takeIf { sameWorld(target) }
            "object" -> target["id"].str()?.let { id -> Bridge.objects.firstOrNull { it.id == id } }?.let { of(it, aimed.along) }
            else -> null
        } ?: aimed
        return Aimed(target, name ?: shown.name, shown.at, shown.box, aimed.along, shown.line)
    }

    /** The entity [target] names, when the client has it (those found lately are kept: drawn every frame). */
    private fun entity(target: JsonObject): Entity? {
        val uuid = target["uuid"].str() ?: return null
        val level = Minecraft.getInstance().level ?: return null
        found[uuid]?.takeIf { !it.isRemoved && it.level() === level }?.let { return it }
        return level.entitiesForRendering().firstOrNull { it.uuid.toString() == uuid }?.also { found[uuid] = it }
    }

    private fun pos(target: JsonObject): BlockPos? {
        val x = target["x"].int() ?: return null
        val y = target["y"].int() ?: return null
        val z = target["z"].int() ?: return null
        return BlockPos(x, y, z)
    }

    /**
     * A display the camera is in (inside what it draws, or at its centre when it draws nothing): one following the
     * camera (a plugin's light probe, an overlay), never what the builder aims at.
     */
    private fun aroundCamera(e: Display, camera: Vec3): Boolean {
        val bounds = EntityShapes.bounds(e)
        return bounds.contains(camera) || bounds.center.distanceToSqr(camera) < 0.25
    }

    fun of(e: Entity, along: Double = 0.0): Aimed {
        val box = if (e is Display) EntityShapes.bounds(e) else e.boundingBox
        return Aimed(buildJsonObject {
            put("type", "entity")
            world()
            put("uuid", e.uuid.toString())
        }, name(e), box.center, box.inflate(0.02), along)
    }

    fun block(pos: BlockPos, along: Double = 0.0): Aimed = Aimed(buildJsonObject {
        put("type", "block")
        world()
        put("x", pos.x)
        put("y", pos.y)
        put("z", pos.z)
    }, blockName(pos), Vec3.atCenterOf(pos), AABB(pos).inflate(0.004), along)

    fun of(o: GameObject, along: Double = 0.0): Aimed {
        val line = o.line
        return Aimed(buildJsonObject {
            put("type", "object")
            put("world", o.world.ifEmpty { Bridge.world })
            (o.dim.ifEmpty { null } ?: playerDimension())?.let { put("dim", it) }
            put("kind", o.kind)
            put("id", o.id)
        }, ObjectsTool.name(o), line.getOrNull(line.size / 2) ?: o.anchorsAt.firstOrNull() ?: Vec3.ZERO, null, along, line)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.world() {
        put("world", Bridge.world)
        playerDimension()?.let { put("dim", it) }
    }

    /** Its custom name; else a display's furniture or model, an interaction's display (it is that display's hitbox), its type. */
    private fun name(e: Entity): String {
        val custom = if (e.hasCustomName()) e.customName?.string?.trim()?.takeIf { it.isNotEmpty() } else null
        return custom ?: when (e) {
            is Display -> EntityEditor.label(e)
            is Interaction -> drawnAt(e)?.let(EntityEditor::label)
            else -> null
        } ?: EntityType.getKey(e.type).path.replace('_', ' ')
    }

    /** The display drawn where [e] is, the nearest whose box holds its centre. */
    private fun drawnAt(e: Interaction): Display? {
        val centre = e.boundingBox.center
        return Minecraft.getInstance().level?.entitiesForRendering()?.filterIsInstance<Display>()
            ?.filter { it.distanceToSqr(centre) < 16 && EntityShapes.bounds(it).inflate(0.1).contains(centre) }
            ?.minByOrNull { it.distanceToSqr(centre) }
    }

    private fun blockName(pos: BlockPos): String {
        val state = Minecraft.getInstance().level?.getBlockState(pos)
        return (state?.block?.name?.string ?: "Block") + " ${pos.x} ${pos.y} ${pos.z}"
    }

    /** What [target] is called: its provider's name for it once the server said (asked here), else [describe]. */
    fun named(target: JsonObject): String = (if (Panels.supported) Resolver.title(target) else null) ?: describe(target)

    /** What [target] is, for people: an entity's name when it is near, a block's, an object's kind and id. */
    fun describe(target: JsonObject): String = when (target["type"].str()) {
        "entity" -> entity(target)?.let(::name) ?: "entity ${target["uuid"].str().orEmpty().take(8)}"
        "block" -> pos(target)?.let { if (sameWorld(target)) blockName(it) else "block ${it.x} ${it.y} ${it.z}" } ?: "a block"
        "object" -> {
            val id = target["id"].str().orEmpty()
            Bridge.objects.firstOrNull { it.id == id }?.let { ObjectsTool.name(it) + " " + id.take(12) } ?: "${target["kind"].str() ?: "object"} ${id.take(12)}"
        }
        "global" -> target["id"].str() ?: "global"
        else -> target.toString().take(60)
    }

    private fun sameWorld(target: JsonObject) = target["world"].str().let { it == null || it == Bridge.world } &&
        target["dim"].str().let { it == null || it == playerDimension() }

    /** [target] as a key: equal for the same thing, however it names its world. */
    fun key(target: JsonObject): String = when (val type = target["type"].str()) {
        "entity" -> "entity|${target["uuid"].str()}"
        "block" -> "block|${target["x"].int()}|${target["y"].int()}|${target["z"].int()}"
        "object" -> "object|${target["kind"].str()}|${target["id"].str()}"
        "global" -> "global|${target["id"].str()}"
        else -> "$type|$target"
    }

    /** Whether [a] and [b] are the same thing, however each names its world. */
    fun same(a: JsonObject, b: JsonObject): Boolean {
        val type = a["type"].str()
        if (type != b["type"].str()) return false
        fun eq(key: String) = a[key].str() == b[key].str()
        return when (type) {
            "entity" -> eq("uuid")
            "block" -> eq("x") && eq("y") && eq("z")
            "object" -> eq("kind") && eq("id")
            "global" -> eq("id")
            else -> a == b
        }
    }
}
