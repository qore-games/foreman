package games.qore.foreman.server.api

import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Entity
import java.util.UUID

/**
 * What a panel is about: an [entity][Type.ENTITY], a [block][Type.BLOCK], a [world object][Type.OBJECT] (a
 * [ForemanObjects] kind's) or something not in the world ([Type.GLOBAL]: a plugin's settings). Its JSON is what the tools
 * and the mod exchange ([toMap], [of]); worlds are named by their name:
 *
 * - `{"type":"entity","world":"world","uuid":"…"}`
 * - `{"type":"block","world":"world","x":0,"y":64,"z":0}`
 * - `{"type":"object","world":"world","kind":"ns:name","id":"…"}`
 * - `{"type":"global","id":"ns:name"}`
 *
 * Equal targets name the same thing (an entity by its world and UUID: worlds cloned from one map share entity UUIDs).
 */
class PanelTarget private constructor(
    val type: Type,
    /** The world's name; null for a global target and an entity named by its UUID alone. */
    val worldName: String?,
    /** An entity's. */
    val uuid: UUID?,
    /** A block's position. */
    val x: Int,
    val y: Int,
    val z: Int,
    /** A world object's kind (`ns:name`). */
    val kind: String?,
    /** A world object's id, or a global target's (`ns:name`). */
    val id: String?,
) {
    enum class Type {
        ENTITY, BLOCK, OBJECT, GLOBAL;

        /** Its name in JSON: `entity`, `block`, `object`, `global`. */
        val key: String get() = name.lowercase()
    }

    /** Its world, when it is in a loaded one. */
    fun world(): World? = worldName?.let { Bukkit.getWorld(it) }

    /**
     * The entity it names, when it is one and loaded: looked up in its world (worlds cloned from one map share entity
     * UUIDs); for an entity named by its UUID alone, in the one world that has it (none when several do).
     */
    fun findEntity(): Entity? {
        if (type != Type.ENTITY) return null
        val id = uuid ?: return null
        if (worldName != null) return world()?.getEntity(id)
        return Bukkit.getWorlds().mapNotNull { it.getEntity(id) }.singleOrNull()
    }

    /** The block it names, when it is one in a loaded world. */
    fun findBlock(): Block? = if (type == Type.BLOCK) world()?.getBlockAt(x, y, z) else null

    /** Its JSON as a map: `type`, then `world`, `uuid`, `x`/`y`/`z`, `kind`, `id` as its type has them. */
    fun toMap(): Map<String, Any> = LinkedHashMap<String, Any>().apply {
        put("type", type.key)
        worldName?.let { put("world", it) }
        when (type) {
            Type.ENTITY -> put("uuid", uuid.toString())
            Type.BLOCK -> { put("x", x); put("y", y); put("z", z) }
            Type.OBJECT -> { put("kind", kind!!); put("id", id!!) }
            Type.GLOBAL -> put("id", id!!)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (other !is PanelTarget || other.type != type) return false
        return when (type) {
            Type.ENTITY -> other.uuid == uuid && other.worldName == worldName
            Type.BLOCK -> other.worldName == worldName && other.x == x && other.y == y && other.z == z
            Type.OBJECT -> other.worldName == worldName && other.kind == kind && other.id == id
            Type.GLOBAL -> other.id == id
        }
    }

    override fun hashCode(): Int = when (type) {
        Type.ENTITY -> uuid.hashCode()
        Type.BLOCK -> ((worldName.hashCode() * 31 + x) * 31 + y) * 31 + z
        Type.OBJECT -> (worldName.hashCode() * 31 + kind.hashCode()) * 31 + id.hashCode()
        Type.GLOBAL -> id.hashCode()
    }

    /** In a few words: `block world 10 64 -3`, `entity <uuid>`, `object ns:kind <id> in world`, `global ns:name`. */
    override fun toString(): String = when (type) {
        Type.ENTITY -> "entity $uuid"
        Type.BLOCK -> "block $worldName $x $y $z"
        Type.OBJECT -> "object $kind $id in $worldName"
        Type.GLOBAL -> "global $id"
    }

    companion object {
        @JvmStatic fun entity(entity: Entity) = PanelTarget(Type.ENTITY, entity.world.name, entity.uniqueId, 0, 0, 0, null, null)

        @JvmStatic fun entity(world: World, uuid: UUID) = PanelTarget(Type.ENTITY, world.name, uuid, 0, 0, 0, null, null)

        @JvmStatic fun block(block: Block) = block(block.world, block.x, block.y, block.z)

        @JvmStatic fun block(world: World, x: Int, y: Int, z: Int) = PanelTarget(Type.BLOCK, world.name, null, x, y, z, null, null)

        /** An object of a [ForemanObjects] kind: its [kind] (`ns:name`) and the [id] its handler gives it. */
        @JvmStatic fun worldObject(world: World, kind: String, id: String): PanelTarget {
            require(kind.matches(LayoutType.ID)) { "Object kinds are namespaced: 'ns:name', not '$kind'" }
            require(id.isNotBlank()) { "An object target needs its id" }
            return PanelTarget(Type.OBJECT, world.name, null, 0, 0, 0, kind, id)
        }

        /** Something not in the world, named `ns:name` (a plugin's settings). */
        @JvmStatic fun global(id: String): PanelTarget {
            require(id.matches(LayoutType.ID)) { "Global targets are namespaced: 'ns:name', not '$id'" }
            return PanelTarget(Type.GLOBAL, null, null, 0, 0, 0, null, id)
        }

        /**
         * A target from its JSON map ([toMap]): its `world` a world's name, UUID or key (`minecraft:overworld`), or a
         * `dim` key instead. Throws [IllegalArgumentException] saying what is wrong.
         */
        @JvmStatic fun of(map: Map<String, *>): PanelTarget {
            val type = (map["type"] as? String)?.let { t -> Type.entries.firstOrNull { it.key == t } }
                ?: throw IllegalArgumentException("A target's type is entity, block, object or global, not ${map["type"]}")
            val world = (map["world"] as? String)?.takeIf { it.isNotBlank() }?.let(::worldName)
                ?: (map["dim"] as? String)?.takeIf { it.isNotBlank() }?.let(::worldName)
            fun string(key: String) = (map[key] as? String)?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("A ${type.key} target needs its $key")
            fun int(key: String): Int {
                val n = map[key] as? Number ?: throw IllegalArgumentException("A block target needs its $key, a whole number")
                val d = n.toDouble()
                require(d.isFinite() && d == Math.floor(d) && Math.abs(d) < Int.MAX_VALUE) { "A block's $key is a whole number, not $n" }
                return d.toInt()
            }
            return when (type) {
                Type.ENTITY -> {
                    val uuid = runCatching { UUID.fromString(string("uuid")) }.getOrElse { throw IllegalArgumentException("Not an entity UUID: ${map["uuid"]}") }
                    PanelTarget(Type.ENTITY, world, uuid, 0, 0, 0, null, null)
                }
                Type.BLOCK -> PanelTarget(Type.BLOCK, world ?: throw IllegalArgumentException("A block target needs its world"), null, int("x"), int("y"), int("z"), null, null)
                Type.OBJECT -> {
                    val kind = string("kind")
                    require(kind.matches(LayoutType.ID)) { "Object kinds are namespaced: 'ns:name', not '$kind'" }
                    PanelTarget(Type.OBJECT, world ?: throw IllegalArgumentException("An object target needs its world"), null, 0, 0, 0, kind, string("id"))
                }
                Type.GLOBAL -> global(string("id"))
            }
        }

        /** The name of the world [name] names (its name, UUID or key), on a running server; else [name] as it is. */
        private fun worldName(name: String): String {
            if (Bukkit.getServer() == null) return name
            val uuid = runCatching { UUID.fromString(name) }.getOrNull()
            val world = when {
                uuid != null -> Bukkit.getWorld(uuid)
                ':' in name -> NamespacedKey.fromString(name)?.let { Bukkit.getWorld(it) }
                else -> null
            }
            return world?.name ?: name
        }
    }
}
