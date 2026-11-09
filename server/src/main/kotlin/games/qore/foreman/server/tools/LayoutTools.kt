package games.qore.foreman.server.tools

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import games.qore.foreman.api.ForemanTool
import games.qore.foreman.server.Foreman
import games.qore.foreman.server.mcp.Tool
import games.qore.foreman.server.mcp.ToolResult
import games.qore.foreman.server.mcp.schema
import games.qore.foreman.server.world.Layout
import games.qore.foreman.server.world.Point

/** The point and area types plugins declare ([Layout]), and the check of a world's points and areas against them. */
object LayoutTools {
    private val pretty = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun all(plugin: Foreman) = listOf(
        Tool(
            ForemanTool.LAYOUT_TYPES,
            "The point and area types the server's plugins declare (`ns:name`): what each is for and the fields its `data` " +
                "holds (type, required, default, choices, range). A point is of the type its `type` names, an area of the " +
                "types its tags or claim kind name. Read it before adding points or tagging areas for a plugin (points add " +
                "data, set_mark data); plain type names are free text no plugin reads through a declaration.",
            schema { enum("format", listOf("text", "json"), "text (default) or json (every detail, as the client gets it)", required = false) },
        ) { args ->
            val layout = plugin.worlds.layout
            if (args.string("format", "text") == "json") ToolResult.text(pretty.toJson(JsonObject().apply { add("types", layout.json()) }))
            else ToolResult.text(layout.describe())
        },

        Tool(
            ForemanTool.CHECK_LAYOUT,
            "Checks a world's points and areas against the declared types (layout_types): namespaced types no plugin " +
                "declares, missing required fields, values of the wrong kind or out of range, unknown fields, areas a field " +
                "names that don't exist, and each plugin's own check. Returns the problems with where they are (a point's " +
                "position, an area's name and box), errors first, and the plain types left unchecked. Run it after " +
                "placing gameplay points or tagging areas.",
            schema {
                enum("format", listOf("text", "json"), "text (default) or json", required = false)
                string("world", "World name", required = false)
            },
        ) { args ->
            val report = plugin.worlds.layout.check(plugin.world(args.string("world", null)).name)
            ToolResult.text(if (args.string("format", "text") == "json") pretty.toJson(json(report)) else text(report))
        },
    )

    private fun text(r: Layout.Report): String {
        val out = StringBuilder("Layout of ${r.world}: ${r.points} points (${r.typedPoints} of declared types), ${r.areas} areas (${r.typedAreas} of declared types).")
        val errors = r.issues.count { it.error }
        out.append(if (r.issues.isEmpty()) "\nNo problems." else "\n$errors errors, ${r.issues.size - errors} warnings:")
        r.issues.take(200).forEach { i -> out.append("\n  ${if (i.error) "error" else "warning"} ${where(i)}: ${i.text}") }
        if (r.issues.size > 200) out.append("\n  … ${r.issues.size - 200} more (format json gives them all)")
        if (r.free.isNotEmpty()) out.append("\nPlain point types (free text, not checked): " + r.free.entries.joinToString { "${it.key} ×${it.value}" })
        return out.toString()
    }

    private fun where(i: Layout.Issue): String = when {
        i.point != null -> i.point.let { p -> "point ${p.type} at [${Point.fmt(p.x)}, ${Point.fmt(p.y)}, ${Point.fmt(p.z)}]" + (if (p.build.isNotEmpty()) " (${p.build})" else "") }
        i.area != null -> "area ${i.area.name} ${i.area.bounds.describe()}"
        else -> i.type
    }

    private fun json(r: Layout.Report) = JsonObject().apply {
        addProperty("world", r.world)
        addProperty("points", r.points)
        addProperty("typed_points", r.typedPoints)
        addProperty("areas", r.areas)
        addProperty("typed_areas", r.typedAreas)
        add("issues", JsonArray().apply {
            r.issues.forEach { i ->
                add(JsonObject().apply {
                    addProperty("severity", if (i.error) "error" else "warning")
                    addProperty("type", i.type)
                    i.field?.let { addProperty("field", it) }
                    addProperty("text", i.text)
                    i.point?.let { p ->
                        addProperty("target", "point")
                        add("position", JsonArray().apply { add(p.x); add(p.y); add(p.z) })
                        if (p.build.isNotEmpty()) addProperty("build", p.build)
                    }
                    i.area?.let { a ->
                        addProperty("target", "area")
                        addProperty("area", a.name)
                        val b = a.bounds
                        add("box", JsonObject().apply {
                            add("min", JsonArray().apply { add(b.min.x()); add(b.min.y()); add(b.min.z()) })
                            add("max", JsonArray().apply { add(b.max.x()); add(b.max.y()); add(b.max.z()) })
                        })
                    }
                })
            }
        })
        add("free", JsonObject().apply { r.free.forEach { (k, v) -> addProperty(k, v) } })
    }
}
