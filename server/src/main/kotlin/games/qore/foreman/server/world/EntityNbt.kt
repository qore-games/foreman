package games.qore.foreman.server.world

import com.mojang.brigadier.StringReader
import com.mojang.brigadier.exceptions.CommandSyntaxException
import games.qore.foreman.server.mcp.ToolError
import org.bukkit.Bukkit
import org.bukkit.entity.Entity
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Entities' whole NBT, read and written as `/data get|merge|remove entity` do it (the server's own data accessor: a
 * player can't be written, the uuid stays), without running the command, so nothing is told to the admins. The Paper
 * API has no NBT: the server's classes are reached by reflection, looked up on first use. Entities are read and written
 * on the main thread; SNBT and paths are parsed anywhere.
 */
object EntityNbt {
    /** A compound tag of the server's, held without its type; its text is its SNBT. */
    class Compound internal constructor(internal val tag: Any) {
        val keys: Set<String> get() = (call(server().keySet, tag) as Set<*>).map { it.toString() }.toSet()

        /** The value under [key] as SNBT, null when there is none. */
        operator fun get(key: String): String? = call(server().get, tag, key)?.toString()

        fun copy() = Compound(call(server().copy, tag)!!)

        fun remove(key: String) {
            call(server().remove, tag, key)
        }

        override fun toString() = tag.toString()
        override fun equals(other: Any?) = other is Compound && other.tag == tag
        override fun hashCode() = tag.hashCode()
    }

    /** A path into NBT, as `/data remove` takes it: `a.b[0]."key:x"`. */
    class Path internal constructor(internal val path: Any, val text: String)

    /** The server's classes and members, looked up once. */
    private class Server {
        private val loader: ClassLoader = Bukkit::class.java.classLoader
        private fun type(name: String): Class<*> = Class.forName(name, false, loader)

        val compound = type("net.minecraft.nbt.CompoundTag")
        val handle: Method = type("org.bukkit.craftbukkit.entity.CraftEntity").getMethod("getHandle")
        val accessor = type("net.minecraft.server.commands.data.EntityDataAccessor").getConstructor(type("net.minecraft.world.entity.Entity"))
        val getData: Method = accessor.declaringClass.getMethod("getData")
        val setData: Method = accessor.declaringClass.getMethod("setData", compound)
        val parse: Method = type("net.minecraft.nbt.TagParser").getMethod("parseCompoundFully", String::class.java)
        val keySet: Method = compound.getMethod("keySet")
        val get: Method = compound.getMethod("get", String::class.java)
        val remove: Method = compound.getMethod("remove", String::class.java)
        val copy: Method = compound.getMethod("copy")
        val merge: Method = compound.getMethod("merge", compound)
        val pathArgument = type("net.minecraft.commands.arguments.NbtPathArgument").getConstructor()
        val parsePath: Method = pathArgument.declaringClass.getMethod("parse", StringReader::class.java)
        val removePath: Method = type("net.minecraft.commands.arguments.NbtPathArgument\$NbtPath").getMethod("remove", type("net.minecraft.nbt.Tag"))
    }

    private val looked by lazy { runCatching { Server() } }

    private fun server() = looked.getOrElse { throw ToolError("This server's NBT can't be reached (${it.javaClass.simpleName}: ${it.message})") }

    /** Calls [method]; a failure inside it comes out as itself (a [ToolError] with its message for a syntax error). */
    private fun call(method: Method, target: Any?, vararg args: Any?): Any? = try {
        method.invoke(target, *args)
    } catch (e: InvocationTargetException) {
        val cause = e.cause ?: e
        throw if (cause is CommandSyntaxException) ToolError(cause.message ?: "invalid") else cause
    }

    private fun accessor(e: Entity): Any = server().accessor.newInstance(call(server().handle, e))

    /** [e]'s NBT as `/data get entity` shows it (without its type). */
    fun read(e: Entity) = Compound(call(server().getData, accessor(e))!!)

    /** Sets [e]'s NBT to [nbt], as `/data merge` does once it merged (its uuid stays; a player refuses). */
    fun write(e: Entity, nbt: Compound) {
        call(server().setData, accessor(e), nbt.tag)
    }

    /** [snbt] as a compound; a [ToolError] saying where it is wrong otherwise. */
    fun parse(snbt: String) = Compound(call(server().parse, null, snbt)!!)

    /** [text] as an NBT path; a [ToolError] saying where it is wrong otherwise. */
    fun path(text: String): Path {
        val reader = StringReader(text)
        val path = call(server().parsePath, server().pathArgument.newInstance(), reader)!!
        if (reader.canRead()) throw ToolError("'$text' is one NBT path, without spaces (it stops at position ${reader.cursor})")
        return Path(path, text)
    }

    /** Merges [from] into [into] as `/data merge`: compounds key by key, any other value replaced. */
    fun merge(into: Compound, from: Compound) {
        call(server().merge, into.tag, from.tag)
    }

    /** Removes what [path] finds in [nbt]; how many it removed. */
    fun remove(nbt: Compound, path: Path): Int = call(server().removePath, path.path, nbt.tag) as Int
}
