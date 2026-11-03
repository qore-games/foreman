package games.qore.foreman.client.packets

import games.qore.foreman.client.mc.Mc
import io.netty.buffer.ByteBuf
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.core.SectionPos
import net.minecraft.core.Vec3i
import net.minecraft.network.ConnectionProtocol
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.contents.TranslatableContents
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket
import net.minecraft.network.protocol.cookie.ServerboundCookieResponsePacket
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket
import net.minecraft.network.protocol.game.ServerboundChatPacket
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket
import net.minecraft.network.protocol.game.ServerboundEditBookPacket
import net.minecraft.network.protocol.game.ServerboundRenameItemPacket
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.phys.Vec3
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.Locale
import java.util.Optional

/**
 * What a packet is about, packed in a long: an entity id or a chunk, or nothing. The kind is in the top two bits.
 * Chunk coordinates take 28 bits each (far more than a world's border).
 */
object Subject {
    const val NONE = 0L
    private const val ENTITY = 1L shl 62
    private const val CHUNK = 2L shl 62
    private const val KIND = 3L shl 62
    private const val MASK = (1L shl 28) - 1

    fun entity(id: Int) = ENTITY or (id.toLong() and 0xFFFFFFFFL)
    fun chunk(x: Int, z: Int) = CHUNK or ((x.toLong() and MASK) shl 28) or (z.toLong() and MASK)

    fun isEntity(s: Long) = s and KIND == ENTITY
    fun isChunk(s: Long) = s and KIND == CHUNK
    fun entityId(s: Long) = s.toInt()
    fun chunkX(s: Long) = (((s shr 28) and MASK) shl 36 shr 36).toInt()
    fun chunkZ(s: Long) = ((s and MASK) shl 36 shr 36).toInt()

    fun describe(s: Long) = when {
        isEntity(s) -> "entity ${entityId(s)}"
        isChunk(s) -> "chunk ${chunkX(s)},${chunkZ(s)}"
        else -> ""
    }
}

/**
 * Reads packets without knowing them: a short type name, what they are about (an entity, a chunk) and their fields as
 * one line of text. Each packet class is looked at once by reflection, then read through what was found.
 */
object PacketInspector {
    /** `set_entity_data`, `custom_payload/foreman:locations`, `configuration/registry_data`… */
    fun typeName(protocol: ConnectionProtocol, packet: Packet<*>): String {
        val name = channel(packet)?.let { "custom_payload/$it" } ?: short(packet.type().id())
        return if (protocol == ConnectionProtocol.PLAY) name else "${protocol.id()}/$name"
    }

    /** `zombie`, `mymod:thing`: vanilla's ids without their namespace. */
    fun short(id: Identifier): String = if (id.namespace == "minecraft") id.path else id.toString()

    /** The plugin channel of a custom payload: types are kept apart by channel. */
    fun channel(packet: Packet<*>): Identifier? = when (packet) {
        is ClientboundCustomPayloadPacket -> packet.payload().type().id()
        is ServerboundCustomPayloadPacket -> packet.payload().type().id()
        else -> null
    }

    // ---- Subject ----------------------------------------------------------------------------------------------------

    private fun interface SubjectReader {
        fun read(packet: Any): Long
    }

    /** Packets with an int `id` that is not an entity's. */
    private val notEntityIds = setOf("ClientboundContainerSetDataPacket", "ClientboundCommandSuggestionsPacket", "ClientboundPlayerPositionPacket", "ClientboundPingPacket")
    private val entityFields = listOf("entityId", "id", "entity", "vehicle", "sourceId", "itemId")

    private val subjectReaders = object : ClassValue<SubjectReader>() {
        override fun computeValue(type: Class<*>): SubjectReader = subjectReader(type)
    }

    /** What a received packet is about: the block, chunk or position it names, else the entity. */
    fun subject(packet: Packet<*>): Long =
        if (packet.type().flow() != PacketFlow.CLIENTBOUND) Subject.NONE
        else try { subjectReaders.get(packet.javaClass).read(packet) } catch (_: Exception) { Subject.NONE }

    private fun subjectReader(type: Class<*>): SubjectReader {
        val fields = fields(type)
        fun named(name: String, kind: Class<*>) = fields.firstOrNull { it.name == name && it.type == kind }
        val name = type.simpleName

        fields.firstOrNull { BlockPos::class.java.isAssignableFrom(it.type) }?.let { f ->
            return SubjectReader { p -> (f.get(p) as? BlockPos)?.let { Subject.chunk(it.x shr 4, it.z shr 4) } ?: Subject.NONE }
        }
        fields.firstOrNull { SectionPos::class.java.isAssignableFrom(it.type) }?.let { f ->
            return SubjectReader { p -> (f.get(p) as? SectionPos)?.let { Subject.chunk(it.x(), it.z()) } ?: Subject.NONE }
        }
        fields.firstOrNull { it.type == ChunkPos::class.java }?.let { f ->
            return SubjectReader { p -> (f.get(p) as? ChunkPos)?.let { Subject.chunk(it.x(), it.z()) } ?: Subject.NONE }
        }
        entityFields.firstNotNullOfOrNull { field -> named(field, Int::class.javaPrimitiveType!!)?.takeUnless { field == "id" && name in notEntityIds } }?.let { f ->
            return SubjectReader { p -> Subject.entity(f.getInt(p)) }
        }
        val intX = named("x", Int::class.javaPrimitiveType!!)
        val intZ = named("z", Int::class.javaPrimitiveType!!)
        if (intX != null && intZ != null) {
            // Chunk packets name the chunk; sound positions are in eighths of a block.
            if ("Chunk" in name || "Light" in name) return SubjectReader { p -> Subject.chunk(intX.getInt(p), intZ.getInt(p)) }
            if (name == "ClientboundSoundPacket") return SubjectReader { p -> Subject.chunk(Math.floorDiv(intX.getInt(p), 8) shr 4, Math.floorDiv(intZ.getInt(p), 8) shr 4) }
        }
        val doubleX = named("x", Double::class.javaPrimitiveType!!)
        val doubleZ = named("z", Double::class.javaPrimitiveType!!)
        if (doubleX != null && doubleZ != null) {
            return SubjectReader { p -> Subject.chunk(Math.floor(doubleX.getDouble(p)).toInt() shr 4, Math.floor(doubleZ.getDouble(p)).toInt() shr 4) }
        }
        named("center", Vec3::class.java)?.let { f ->
            return SubjectReader { p -> (f.get(p) as? Vec3)?.let { Subject.chunk(Math.floor(it.x).toInt() shr 4, Math.floor(it.z).toInt() shr 4) } ?: Subject.NONE }
        }
        return SubjectReader { Subject.NONE }
    }

    // ---- Contents ---------------------------------------------------------------------------------------------------

    private val fieldLists = object : ClassValue<List<Field>>() {
        override fun computeValue(type: Class<*>): List<Field> = generateSequence(type) { it.superclass }
            .takeWhile { it != Any::class.java && it != Record::class.java }
            .toList().asReversed()
            .flatMap { c -> c.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic } }
            .filter { it.trySetAccessible() }
    }

    private fun fields(type: Class<*>) = fieldLists.get(type)

    /**
     * The packet's fields as one line, cut at [max] characters: `x=3 z=-7 chunkData={buffer=byte[18432] …}`. What the
     * player typed (chat, command arguments, signs, books, item names) is left out: `/login <password>` is a command.
     */
    fun describe(packet: Any, max: Int = 200): String {
        typed(packet)?.let { return it }
        val out = StringBuilder()
        try {
            fields(packet.javaClass).forEachIndexed { i, f ->
                if (out.length >= max) return@forEachIndexed
                if (i > 0) out.append(' ')
                out.append(f.name).append('=')
                value(out, f.get(packet), 0, max)
            }
        } catch (_: Exception) {
            if (out.isEmpty()) out.append(packet.javaClass.simpleName)
        }
        return if (out.length > max) cut(out, max - 1) + "…" else out.toString()
    }

    /** The first [n] characters of [s], without splitting a surrogate pair (glyphs out of the BMP, as in server fonts). */
    fun cut(s: CharSequence, n: Int): String {
        if (s.length <= n) return s.toString()
        val end = if (n > 0 && Character.isHighSurrogate(s[n - 1])) n - 1 else n
        return s.subSequence(0, end).toString()
    }

    private fun typed(packet: Any): String? = when (packet) {
        is ServerboundChatCommandPacket -> "command=/${packet.command().substringBefore(' ')} (arguments hidden)"
        is ServerboundChatCommandSignedPacket -> "command=/${packet.command().substringBefore(' ')} (arguments hidden)"
        is ServerboundCommandSuggestionPacket -> "command=${Mc.command(packet).substringBefore(' ')} (arguments hidden)"
        is ServerboundChatPacket -> "message hidden (${packet.message().length} characters)"
        is ServerboundSignUpdatePacket, is ServerboundEditBookPacket, is ServerboundRenameItemPacket, is ServerboundCookieResponsePacket -> "(what the player typed is hidden)"
        else -> null
    }

    private fun value(out: StringBuilder, v: Any?, depth: Int, max: Int) {
        if (out.length >= max) return
        when (v) {
            null -> out.append("null")
            is String -> readable(v).let { out.append('"').append(cut(it, 60)).append(if (it.length > 60) "…\"" else "\"") }
            is Float -> out.append(compact(v.toDouble()))
            is Double -> out.append(compact(v))
            is Number, is Boolean, is Char -> out.append(v)
            is Enum<*> -> out.append(v.name.lowercase())
            is Identifier, is java.util.UUID -> out.append(v)
            is ResourceKey<*> -> out.append(v.identifier())
            is Holder<*> -> out.append(v.unwrapKey().map { it.identifier().toString() }.orElse("direct"))
            is Component -> {
                readable(v.string).let { out.append('"').append(cut(it, 60)).append(if (it.length > 60) "…\"" else "\"") }
                translationKeys(v).takeIf { it.isNotEmpty() }?.let { out.append("{key=").append(it.joinToString(",")).append('}') }
            }
            is ItemStack -> item(out, v)
            is Vec3i -> out.append(v.x).append(',').append(v.y).append(',').append(v.z)
            is Vec3 -> out.append(compact(v.x)).append(',').append(compact(v.y)).append(',').append(compact(v.z))
            is ChunkPos -> out.append(v.x()).append(',').append(v.z())
            is Optional<*> -> if (v.isPresent) value(out, v.get(), depth, max) else out.append('-')
            is SynchedEntityData.DataValue<*> -> { out.append('#').append(v.id()).append('='); value(out, v.value(), depth, max) }
            is ByteBuf -> out.append("bytes(").append(v.readableBytes()).append(')')
            is ByteArray -> out.append("byte[").append(v.size).append(']')
            is IntArray -> if (v.size <= 6) out.append(v.joinToString(",", "[", "]")) else out.append("int[").append(v.size).append(']')
            is LongArray -> out.append("long[").append(v.size).append(']')
            is ShortArray -> out.append("short[").append(v.size).append(']')
            is Array<*> -> items(out, v.size, v.asIterable(), depth, max)
            is Collection<*> -> items(out, v.size, v, depth, max)
            is Map<*, *> -> out.append('{').append(v.size).append(" entries}")
            is java.util.BitSet -> out.append("bits(").append(v.cardinality()).append(')')
            else -> {
                // Objects are read field by field (no code of theirs runs: a mod's payload may print megabytes), two levels deep.
                val type = v.javaClass
                val jdk = type.name.startsWith("java.") || type.name.startsWith("javax.") || type.name.startsWith("jdk.") || type.name.startsWith("sun.")
                out.append(type.simpleName.ifEmpty { "?" })
                if (!jdk && depth < 2) {
                    out.append('{')
                    fields(type).forEachIndexed { i, f ->
                        if (out.length >= max) return@forEachIndexed
                        if (i > 0) out.append(' ')
                        out.append(f.name).append('=')
                        value(out, runCatching { f.get(v) }.getOrNull(), depth + 1, max)
                    }
                    out.append('}')
                }
            }
        }
    }

    /** `3 paper{model=myplugin:guard/head data=myplugin:id}`: what identifies an item (its model, custom data keys). */
    private fun item(out: StringBuilder, stack: ItemStack) {
        if (stack.isEmpty) {
            out.append("empty")
            return
        }
        out.append(stack.count).append(' ').append(short(BuiltInRegistries.ITEM.getKey(stack.item)))
        val extras = ArrayList<String>()
        stack.get(DataComponents.ITEM_MODEL)?.let { extras += "model=$it" }
        stack.get(DataComponents.CUSTOM_NAME)?.let { extras += "name=\"${cut(readable(it.string), 30)}\"" }
        stack.get(DataComponents.CUSTOM_DATA)?.takeUnless { it.isEmpty }?.copyTag()?.let { tag ->
            // Plugins' persistent data (PDC) is under PublicBukkitValues, keyed by plugin namespace.
            val keys = ((tag.get("PublicBukkitValues") as? CompoundTag)?.keySet().orEmpty()) + tag.keySet().filter { it != "PublicBukkitValues" }
            if (keys.isNotEmpty()) extras += "data=" + keys.take(4).joinToString(",")
        }
        if (extras.isNotEmpty()) out.append(extras.joinToString(" ", "{", "}"))
    }

    /** The translation keys of a text (the first three): what to search in the language files. */
    private fun translationKeys(c: Component, into: MutableList<String> = ArrayList()): List<String> {
        (c.contents as? TranslatableContents)?.let { if (into.size < 3) into += it.key }
        for (sibling in c.siblings) {
            if (into.size >= 3) break
            translationKeys(sibling, into)
        }
        return into
    }

    /**
     * [s] with the runs of private-use characters (a server's font glyphs: backgrounds, icons) shown as `⟨18 glyphs⟩`:
     * they say nothing readable and take most of the line.
     */
    fun readable(s: String): String {
        if (s.none { it in '\uE000'..'\uF8FF' || Character.isSurrogate(it) }) return s
        val out = StringBuilder()
        var glyphs = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (cp in 0xE000..0xF8FF || cp >= 0xF0000) glyphs++
            else {
                if (glyphs > 0) out.append("⟨").append(glyphs).append(if (glyphs == 1) " glyph⟩" else " glyphs⟩")
                glyphs = 0
                out.appendCodePoint(cp)
            }
            i += Character.charCount(cp)
        }
        if (glyphs > 0) out.append("⟨").append(glyphs).append(if (glyphs == 1) " glyph⟩" else " glyphs⟩")
        return out.toString()
    }

    /** `[n: a, b, c…]`: the size and the first three. */
    private fun items(out: StringBuilder, size: Int, items: Iterable<*>, depth: Int, max: Int) {
        out.append('[').append(size)
        if (size > 0) out.append(": ")
        items.take(3).forEachIndexed { i, item ->
            if (i > 0) out.append(", ")
            value(out, item, depth + 1, max)
        }
        if (size > 3) out.append(", …")
        out.append(']')
    }

    private fun compact(d: Double) = if (d == Math.rint(d) && Math.abs(d) < 1e9) d.toLong().toString() else "%.2f".format(Locale.ROOT, d)
}
