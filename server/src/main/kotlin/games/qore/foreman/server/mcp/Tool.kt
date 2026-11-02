package games.qore.foreman.server.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.sk89q.worldedit.math.BlockVector3
import games.qore.foreman.api.ForemanTool
import java.util.Base64
import java.util.UUID

/** An error the model can act on: returned as an `isError` tool result instead of a JSON-RPC error. */
class ToolError(message: String) : Exception(message)

class Tool(
    val name: String,
    val description: String,
    val schema: JsonObject,
    /** Tools that change the world are announced in game. */
    val modifies: Boolean = false,
    /** Who publishes it: Foreman, or the plugin that registered it through the tool API. */
    val provider: String = "Foreman",
    val handler: (Args) -> ToolResult,
) {
    /** A Foreman tool: built-in tools are named by [ForemanTool], which the client knows too. */
    constructor(
        tool: ForemanTool, description: String, schema: JsonObject, modifies: Boolean = false, provider: String = "Foreman",
        handler: (Args) -> ToolResult,
    ) : this(tool.id, description, schema, modifies, provider, handler)
}

/** The tools the MCP server offers: Foreman's own and those other plugins publish ([games.qore.foreman.server.api.ForemanTools]). */
class ToolRegistry {
    private val tools = java.util.concurrent.ConcurrentHashMap<String, Tool>()

    fun add(tool: Tool) {
        tools[tool.name] = tool
    }

    fun remove(name: String) = tools.remove(name)

    fun removeProvider(provider: String) = tools.values.removeIf { it.provider == provider }

    operator fun get(name: String?): Tool? = name?.let { tools[it] }

    /** Foreman's first, then by provider and name. */
    fun all(): List<Tool> = tools.values.sortedWith(compareBy({ it.provider != "Foreman" }, { it.provider }, { it.name }))

    val size get() = tools.size
}

class ToolResult(val content: List<JsonObject>, val isError: Boolean = false) {
    companion object {
        fun text(text: String) = ToolResult(listOf(textBlock(text)))

        fun error(text: String) = ToolResult(listOf(textBlock(text)), isError = true)

        /** A caption followed by several images. */
        fun images(caption: String, pngs: List<ByteArray>) = ToolResult(listOf(textBlock(caption)) + pngs.map { imageBlock(it) })

        private fun imageBlock(png: ByteArray) = JsonObject().apply {
            addProperty("type", "image")
            addProperty("mimeType", "image/png")
            addProperty("data", Base64.getEncoder().encodeToString(png))
        }

        fun image(png: ByteArray, caption: String) = ToolResult(listOf(textBlock(caption), imageBlock(png)))

        private fun textBlock(text: String) = JsonObject().apply {
            addProperty("type", "text")
            addProperty("text", text)
        }
    }
}

/** The player whose personal token made a call. */
data class Caller(val id: UUID, val name: String)

/** Typed access to `tools/call` arguments. Missing or malformed values throw a [ToolError] naming the argument. */
class Args(val json: JsonObject) {
    /** The player whose token made the call (per-player tokens of the bridge), or null for the server's token. */
    var caller: Caller? = null

    fun has(name: String) = json.has(name) && !json.get(name).isJsonNull

    private fun get(name: String): JsonElement =
        if (has(name)) json.get(name) else throw ToolError("Missing argument '$name'")

    private fun <T> parse(name: String, convert: (JsonElement) -> T): T =
        try {
            convert(get(name))
        } catch (e: ToolError) {
            throw e
        } catch (e: Exception) {
            throw ToolError("Invalid argument '$name': ${json.get(name)}")
        }

    fun int(name: String): Int = parse(name) { it.asInt }
    fun int(name: String, default: Int): Int = if (has(name)) int(name) else default
    fun double(name: String, default: Double): Double = if (has(name)) parse(name) { it.asDouble } else default
    fun string(name: String): String = parse(name) { it.asString }
    fun string(name: String, default: String?): String? = if (has(name)) string(name) else default
    fun bool(name: String, default: Boolean): Boolean = if (has(name)) parse(name) { it.asBoolean } else default
    fun array(name: String): JsonArray = parse(name) { it.asJsonArray }
    fun obj(name: String): JsonObject = parse(name) { it.asJsonObject }

    fun vec(name: String): BlockVector3 = parse(name) { vecOf(it) }
    fun vec(name: String, default: BlockVector3?): BlockVector3? = if (has(name)) vec(name) else default

    /** [x, y, z] with decimals allowed (camera positions). */
    fun point(name: String): DoubleArray = parse(name) { e ->
        val a = e.asJsonArray
        if (a.size() != 3) throw ToolError("Expected [x, y, z], got $e")
        doubleArrayOf(a[0].asDouble, a[1].asDouble, a[2].asDouble)
    }

    fun strings(name: String): List<String> = if (has(name)) parse(name) { e -> e.asJsonArray.map { it.asString } } else emptyList()

    companion object {
        fun vecOf(element: JsonElement): BlockVector3 {
            val a = element.asJsonArray
            if (a.size() != 3) throw ToolError("Expected [x, y, z], got $element")
            return BlockVector3.at(a[0].asInt, a[1].asInt, a[2].asInt)
        }
    }
}

/** Builds the JSON schema of a tool's input. */
class SchemaBuilder {
    private val properties = JsonObject()
    private val required = JsonArray()

    fun prop(name: String, schema: JsonObject, description: String, required: Boolean = true) {
        schema.addProperty("description", description)
        properties.add(name, schema)
        if (required) this.required.add(name)
    }

    fun int(name: String, description: String, required: Boolean = true) = prop(name, type("integer"), description, required)
    fun number(name: String, description: String, required: Boolean = true) = prop(name, type("number"), description, required)
    fun string(name: String, description: String, required: Boolean = true) = prop(name, type("string"), description, required)
    fun bool(name: String, description: String, required: Boolean = true) = prop(name, type("boolean"), description, required)
    fun vec(name: String, description: String, required: Boolean = true) = prop(name, vecSchema(), description, required)

    fun enum(name: String, values: List<String>, description: String, required: Boolean = true) =
        prop(name, type("string").apply { add("enum", JsonArray().apply { values.forEach { add(it) } }) }, description, required)

    fun strings(name: String, description: String, required: Boolean = true) =
        prop(name, type("array").apply { add("items", type("string")) }, description, required)

    fun array(name: String, items: JsonObject, description: String, required: Boolean = true) =
        prop(name, type("array").apply { add("items", items) }, description, required)

    /** `render` and `mark` of the tools that change the world: check and name the result in the same call. */
    fun resultOptions() {
        enum("render", listOf("top", "slope", "plan", "iso_se", "iso_sw", "iso_ne", "iso_nw"), "Also return an image of the changed box from this view, instead of a separate render call", required = false)
        string("mark", "Also save the changed box as a mark with this name", required = false)
    }

    fun build() = JsonObject().apply {
        addProperty("type", "object")
        add("properties", properties)
        add("required", required)
    }

    companion object {
        fun type(type: String) = JsonObject().apply { addProperty("type", type) }

        fun vecSchema() = type("array").apply {
            add("items", type("integer"))
            addProperty("minItems", 3)
            addProperty("maxItems", 3)
        }
    }
}

fun schema(block: SchemaBuilder.() -> Unit) = SchemaBuilder().apply(block).build()
