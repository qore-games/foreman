package games.qore.foreman.server.tools

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import games.qore.foreman.api.ForemanTool
import games.qore.foreman.server.Foreman
import games.qore.foreman.server.api.LinkResult
import games.qore.foreman.server.api.PanelTarget
import games.qore.foreman.server.api.Relation
import games.qore.foreman.server.mcp.Args
import games.qore.foreman.server.mcp.SchemaBuilder
import games.qore.foreman.server.mcp.Tool
import games.qore.foreman.server.mcp.ToolError
import games.qore.foreman.server.mcp.ToolResult
import games.qore.foreman.server.mcp.schema
import games.qore.foreman.server.panels.Aim
import games.qore.foreman.server.panels.PanelJson
import games.qore.foreman.server.panels.PanelText
import games.qore.foreman.server.panels.Panels
import games.qore.foreman.server.world.Sync
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector

/**
 * The panels plugins show for their things (`ForemanPanels`) and the relations between them, for the AI: reading a panel,
 * changing its fields, pressing its buttons, listing, making and removing links. The mod's panels call them too.
 */
object PanelTools {
    private val gson = GsonBuilder().disableHtmlEscaping().serializeNulls().create()
    private const val MAX_RADIUS = 128.0

    private const val TARGET = "A target: {\"type\": \"entity\", \"world\", \"uuid\"}, {\"type\": \"block\", \"world\", \"x\", \"y\", \"z\"}, " +
        "{\"type\": \"object\", \"world\", \"kind\", \"id\"} or {\"type\": \"global\", \"id\"}"

    fun all(plugin: Foreman) = listOf(
        Tool(
            ForemanTool.PANEL_GET,
            "The panel a plugin shows for a thing (an entity, a block, a world object) or for its own settings, as builders " +
                "see it in the mod: values, editable fields (type, range, choices) and buttons. Give `target`, or `aimed: true` " +
                "for what you (the calling player) aim at. Returns the panel with the target it is about, or that nothing has one. " +
                "Read it before panel_edit and panel_action.",
            schema {
                prop("target", SchemaBuilder.type("object"), TARGET, required = false)
                bool("aimed", "The panel of what the calling player aims at (within 64 blocks) instead of target", required = false)
                string("provider", "Only this provider (ns:name)", required = false)
                enum("format", listOf("text", "json"), "text (default) or json: {aimed, target, provider, provider_label, panel} (target null: no panel)", required = false)
            },
        ) { args -> get(plugin, args) },

        Tool(
            ForemanTool.PANEL_EDIT,
            "Sets a field of a panel (panel_get lists them: `key` is the field's name) to `value`, checked against the field " +
                "(type, choices, range; null clears it), then the plugin applies or refuses it. Returns done or refused (why, " +
                "per field) and the panel as it is now.",
            schema {
                prop("target", SchemaBuilder.type("object"), "The panel's target (panel_get gives it)")
                string("key", "The field's name")
                prop("value", JsonObject(), "Its new value, as its type holds: text, number, true/false, \"#rrggbb\", a target object; null clears it")
                string("provider", "The panel's provider, when several serve the target", required = false)
                enum("format", listOf("text", "json"), "text (default) or json: {ok, message?, errors?: {key: why}, panel?}", required = false)
            },
            modifies = true,
        ) { args -> edit(plugin, args) },

        Tool(
            ForemanTool.PANEL_ACTION,
            "Presses a button of a panel (panel_get lists them, with their args): the plugin does what it says (start, refill, " +
                "reset…). A list item's button is named by its args (`item`). Returns done or refused (why) and the panel as it " +
                "is now.",
            schema {
                prop("target", SchemaBuilder.type("object"), "The panel's target (panel_get gives it)")
                string("id", "The button's id")
                prop("args", SchemaBuilder.type("object"), "Its args, as panel_get shows them (default: the button's own)", required = false)
                string("provider", "The panel's provider, when several serve the target", required = false)
                enum("format", listOf("text", "json"), "text (default) or json: {ok, message?, errors?, panel?}", required = false)
            },
            modifies = true,
        ) { args -> action(plugin, args) },

        Tool(
            ForemanTool.PANEL_FACTS,
            "What a panel's conditions row (panel_get shows it, [conditions <row>]) can read: its subjects; with `subject` " +
                "(an id) or `pick` (a target), that one's facts with their type, values and value now; with `check`, whether " +
                "a condition holds now and what it reads, or why it can't be read. Use it before adding a condition.",
            schema {
                prop("target", SchemaBuilder.type("object"), "The panel's target (panel_get gives it)")
                string("row", "The conditions row's id")
                string("subject", "A subject's id: its facts", required = false)
                prop("pick", SchemaBuilder.type("object"), "A thing of the world as a target: its facts, under the name conditions will use", required = false)
                prop("check", SchemaBuilder.type("object"), "A condition to check: {expression: \"…\"} or {any: [{subject, fact, op, value}]}", required = false)
                string("provider", "The panel's provider, when several serve the target", required = false)
                enum("format", listOf("text", "json"), "text (default) or json: {subjects} | {subject, facts} | {ok, expression?, holds?, reads?, error?}", required = false)
            },
        ) { args -> facts(plugin, args) },

        Tool(
            ForemanTool.LIST_RELATIONS,
            "Lists what links the things plugins manage near a point (`center`, within `radius`) or the calling player: a " +
                "generator to the lamps it powers, a switch to its doors. Each with its id, provider, the two targets, and " +
                "where the arrow builders see runs, and what relink may change of it. Read it before unlink and relink, or to " +
                "understand how a place is wired.",
            schema {
                prop("center", SchemaBuilder.pointSchema(), "[x, y, z]: around this point (default: the calling player)", required = false)
                number("radius", "In blocks (default 32, at most 128)", required = false)
                string("world", "World name (default: the calling player's)", required = false)
                enum("format", listOf("text", "json"), "text (default) or json: {world, dim, relations: [{id, provider, from, to, from_point?, to_point?, label?, color?, state?, detail?, changes?: [reverse|from|to]}]}", required = false)
            },
        ) { args -> relations(plugin, args) },

        Tool(
            ForemanTool.LINK_TARGETS,
            "Links two things through the plugin serving `from` (its panel's provider): a lamp to a generator, a door to a " +
                "switch. Returns done, refused (why), or a question with options: call again with the same from and to and " +
                "`choice`, an option's id.",
            schema {
                prop("from", SchemaBuilder.type("object"), "The thing to link from (its provider decides). $TARGET")
                prop("to", SchemaBuilder.type("object"), "The thing to link to")
                string("choice", "The id of the option picked, after a question", required = false)
                enum("format", listOf("text", "json"), "text (default) or json: {status: done|refused|choose|build, message?, question?, options?: [{id, label}], row?, condition?, target?, provider}", required = false)
            },
            modifies = true,
        ) { args -> link(plugin, args) },

        Tool(
            ForemanTool.UNLINK,
            "Removes a relation (list_relations gives its id, provider and ends) through its plugin. Returns done or refused (why).",
            schema {
                prop("target", SchemaBuilder.type("object"), "One of its ends: its `from` in list_relations")
                string("relation", "The relation's id")
                string("provider", "Its provider (list_relations gives it)", required = false)
                enum("format", listOf("text", "json"), "text (default) or json: {ok, message?, errors?, panel?}", required = false)
            },
            modifies = true,
        ) { args -> unlink(plugin, args) },

        Tool(
            ForemanTool.RELINK,
            "Changes a relation through its plugin, as builders do in link mode: `reverse: true` turns it the other way, " +
                "`from` or `to` (a target) moves that end to another thing; list_relations says which a relation allows " +
                "(relink: reverse|from|to). The plugin makes the new link and removes the old one. Returns done, refused " +
                "(why), a question (call again with the same arguments and `choice`), or build: no link fits as it is, so " +
                "add the condition it gives with panel_action on that row, then unlink the old relation.",
            schema {
                prop("target", SchemaBuilder.type("object"), "One of its ends: its `from` in list_relations")
                string("relation", "The relation's id")
                string("provider", "Its provider (list_relations gives it)", required = false)
                bool("reverse", "The other way round", required = false)
                prop("from", SchemaBuilder.type("object"), "Its from end moves to this thing. $TARGET", required = false)
                prop("to", SchemaBuilder.type("object"), "Its to end moves to this thing", required = false)
                string("choice", "The id of the option picked, after a question", required = false)
                enum("format", listOf("text", "json"), "text (default) or json: as link_targets ({status, message?, question?, options?, row?, condition?, target?, provider}), and with build, replaces: {relation, provider, target} to unlink once the condition is added", required = false)
            },
            modifies = true,
        ) { args -> relink(plugin, args) },
    )

    private fun panels(plugin: Foreman): Panels = plugin.panels ?: throw ToolError("Panels are off on this server (config.yml features: panels)")

    private fun json(args: Args) = when (args.string("format", "text")) {
        "text" -> false
        "json" -> true
        else -> throw ToolError("format is text or json")
    }

    private fun get(plugin: Foreman, args: Args): ToolResult {
        val panels = panels(plugin)
        val json = json(args)
        if (!args.bool("aimed", false) && !args.has("target")) throw ToolError("Give target, or aimed: true")
        val provider = args.string("provider", null)?.also { panels.entry(it) }
        val (aimed, served, panel) = Sync.main {
            val aimed = if (args.bool("aimed", false)) aimed(plugin, args) else PanelJson.target(args.json.get("target"))
            val served = panels.resolve(aimed, provider)
            Triple(aimed, served, served?.let { panels.describe(it, panels.context(it.target, args.caller)) })
        }
        if (json) return ToolResult.text(gson.toJson(JsonObject().apply {
            add("aimed", PanelJson.target(aimed))
            add("target", served?.takeIf { panel != null }?.let { PanelJson.target(it.target) } ?: JsonNull.INSTANCE)
            served?.let {
                addProperty("provider", it.entry.id)
                addProperty("provider_label", it.entry.label)
            }
            add("panel", panel?.let { PanelJson.panel(it) } ?: JsonNull.INSTANCE)
        }))
        if (served == null) throw ToolError("Nothing has a panel for $aimed" + panels.known())
        if (panel == null) throw ToolError("${served.target} has no panel any more (${served.entry.id})")
        return ToolResult.text(
            PanelText.panel(panel, served.entry, served.target) +
                "\n\npanel_edit {target, key, value} sets a field; panel_action {target, id, args?} presses a button."
        )
    }

    /** What the calling player aims at. Main thread. */
    private fun aimed(plugin: Foreman, args: Args): PanelTarget {
        val player = args.caller?.let { Bukkit.getPlayer(it.id) }
            ?: throw ToolError("aimed: true needs a player: call with your own token (the mod's AI), or give target")
        return Aim.target(plugin, player) ?: throw ToolError("${player.name} aims at nothing within ${Aim.REACH.toInt()} blocks")
    }

    /** The provider serving [args]' `target`, else its `provider` with the target as given, else a [ToolError]. Main thread. */
    private fun served(panels: Panels, args: Args): Panels.Served {
        val target = PanelJson.target(args.json.get("target"))
        val provider = args.string("provider", null)
        return panels.resolve(target, provider) ?: provider?.let { Panels.Served(panels.entry(it), target) }
            ?: throw ToolError("Nothing has a panel for $target" + panels.known())
    }

    private fun edit(plugin: Foreman, args: Args): ToolResult {
        val panels = panels(plugin)
        val json = json(args)
        val key = args.string("key")
        if (!args.json.has("value")) throw ToolError("Missing argument 'value' (null clears the field)")
        return outcome(json, "set $key") { Sync.main { panels.edit(served(panels, args), args.caller, key, args.json.get("value")) } }
    }

    private fun action(plugin: Foreman, args: Args): ToolResult {
        val panels = panels(plugin)
        val json = json(args)
        val id = args.string("id")
        val given = if (args.has("args")) args.obj("args") else null
        return outcome(json, "pressed $id") { Sync.main { panels.action(served(panels, args), args.caller, id, given) } }
    }

    private fun facts(plugin: Foreman, args: Args): ToolResult {
        val panels = panels(plugin)
        val json = json(args)
        val row = args.string("row")
        val subject = args.string("subject", null)
        val pick = if (args.has("pick")) PanelJson.target(args.json.get("pick"), "pick") else null
        if (args.has("check")) {
            val condition = PanelJson.condition(args.json.get("check"), "check")
            val check = Sync.main { panels.check(served(panels, args), args.caller, row, condition) }
            if (json) return ToolResult.text(gson.toJson(PanelJson.check(check)))
            val text = check.error?.let { "Can't be read: $it" + (check.expression?.let { e -> " ($e)" }.orEmpty()) }
                ?: "${check.expression}: " + when (check.holds) {
                    true -> "holds now"
                    false -> "doesn't hold now"
                    null -> "not read here"
                } + (check.reads?.let { " (reads $it)" }.orEmpty())
            return if (check.isOk) ToolResult.text(text) else ToolResult.error(text)
        }
        if (subject == null && pick == null) {
            val subjects = Sync.main { panels.subjects(served(panels, args), args.caller, row) }
            if (json) return ToolResult.text(gson.toJson(JsonObject().apply { add("subjects", JsonArray().apply { subjects.forEach { add(PanelJson.subject(it)) } }) }))
            if (subjects.isEmpty()) return ToolResult.text("Its plugin offers no subjects for $row.")
            return ToolResult.text("Subjects of $row (panel_facts with subject: its facts):\n" + subjects.joinToString("\n") { s ->
                "- ${s.id}: ${s.label}" + listOfNotNull(s.detail, s.group).joinToString(", ").let { if (it.isEmpty()) "" else " ($it)" } +
                    (s.target?.let { " → $it" }.orEmpty())
            })
        }
        val facts = Sync.main { panels.facts(served(panels, args), args.caller, row, subject, pick) }
            ?: throw ToolError("Nothing to read in ${subject ?: pick}")
        if (json) return ToolResult.text(gson.toJson(PanelJson.facts(facts)))
        return ToolResult.text("${facts.subject.id} (${facts.subject.label}): ${facts.facts.size} facts\n" + facts.facts.joinToString("\n") { f ->
            "- ${f.name}: ${f.type.key}" + (if (f.values.isNotEmpty()) " ${f.values.joinToString("|")}" else "") +
                (f.min?.let { " >= ${PanelJson.fmt(it)}" }.orEmpty()) + (f.max?.let { " <= ${PanelJson.fmt(it)}" }.orEmpty()) +
                (f.now?.let { ", now $it" }.orEmpty()) + (if (f.label != f.name) " (${f.label})" else "") +
                (if (f.description.isNotEmpty()) " — ${f.description}" else "")
        } + "\n\nTerms read {subject: \"${facts.subject.id}\", fact, op: == != < <= > >=, value}.")
    }

    private fun unlink(plugin: Foreman, args: Args): ToolResult {
        val panels = panels(plugin)
        val json = json(args)
        val relation = args.string("relation")
        val target = PanelJson.target(args.json.get("target"))
        val provider = args.string("provider", null)
        return outcome(json, "unlinked $relation") { Sync.main { panels.unlink(args.caller, target, relation, provider) } }
    }

    private fun relink(plugin: Foreman, args: Args): ToolResult {
        val panels = panels(plugin)
        val json = json(args)
        val relation = args.string("relation")
        val target = PanelJson.target(args.json.get("target"))
        val provider = args.string("provider", null)
        val ends = listOf("from", "to").filter { args.has(it) }
        val reverse = args.bool("reverse", false)
        if (ends.size + (if (reverse) 1 else 0) != 1) throw ToolError("Give one of reverse: true, from or to")
        val change = if (reverse) Relation.Change.REVERSE else if (ends[0] == "from") Relation.Change.FROM else Relation.Change.TO
        val to = ends.firstOrNull()?.let { PanelJson.target(args.json.get(it), it) }
        val choice = args.string("choice", null)
        val (served, result) = try {
            Sync.main { panels.relink(args.caller, target, relation, provider, change, to, choice) }
        } catch (e: ToolError) {
            if (!json) throw e
            return ToolResult.text(gson.toJson(PanelJson.link(LinkResult.refused(e.message.orEmpty()))))
        }
        if (json) return ToolResult.text(gson.toJson(PanelJson.link(result).apply {
            addProperty("provider", served.entry.id)
            if (result.status == LinkResult.Status.BUILD) add("replaces", JsonObject().apply {
                addProperty("relation", relation)
                addProperty("provider", served.entry.id)
                add("target", PanelJson.target(target))
            })
        }))
        val what = when (change) {
            Relation.Change.REVERSE -> "reversed $relation"
            else -> "moved the ${change.key} end of $relation to $to"
        }
        val text = PanelText.relink(what, result, "{target: ${PanelJson.target(target)}, relation: \"$relation\", provider: \"${served.entry.id}\"}")
        return if (result.status == LinkResult.Status.REFUSED) ToolResult.error(text) else ToolResult.text(text)
    }

    /**
     * What [call] did, as json (a refusal, or a failure of the call, is `ok: false` with its message: what the mod shows)
     * or text (a refusal is an error).
     */
    private fun outcome(json: Boolean, what: String, call: () -> Panels.Outcome): ToolResult {
        if (json) {
            val outcome = try {
                call()
            } catch (e: ToolError) {
                return ToolResult.text(gson.toJson(JsonObject().apply { addProperty("ok", false); addProperty("message", e.message) }))
            }
            return ToolResult.text(gson.toJson(PanelJson.result(outcome.result, outcome.panel)))
        }
        val outcome = call()
        val text = PanelText.result(what, outcome)
        return if (outcome.result.isOk) ToolResult.text(text) else ToolResult.error(text)
    }

    private fun link(plugin: Foreman, args: Args): ToolResult {
        val panels = panels(plugin)
        val json = json(args)
        val from = PanelJson.target(args.json.get("from"), "from")
        val to = PanelJson.target(args.json.get("to"), "to")
        val choice = args.string("choice", null)
        if (json) {
            val (served, result) = try {
                Sync.main { panels.link(args.caller, from, to, choice) }
            } catch (e: ToolError) {
                return ToolResult.text(gson.toJson(PanelJson.link(LinkResult.refused(e.message.orEmpty()))))
            }
            return ToolResult.text(gson.toJson(PanelJson.link(result).apply { addProperty("provider", served.entry.id) }))
        }
        val (served, result) = Sync.main { panels.link(args.caller, from, to, choice) }
        val text = PanelText.link(served.target, to, result)
        return if (result.status == LinkResult.Status.REFUSED) ToolResult.error(text) else ToolResult.text(text)
    }

    private fun relations(plugin: Foreman, args: Args): ToolResult {
        val panels = panels(plugin)
        val json = json(args)
        val radius = args.double("radius", 32.0).takeIf { it > 0 && it <= MAX_RADIUS } ?: throw ToolError("radius is above 0 and at most 128")
        val (world, found, center) = Sync.main {
            val player = args.caller?.let { Bukkit.getPlayer(it.id) }
            val world: World = args.string("world", null)?.let { plugin.world(it) } ?: player?.world ?: plugin.world(null)
            val center = if (args.has("center")) args.point("center").let { Vector(it[0], it[1], it[2]) }
            else player?.takeIf { it.world == world }?.location?.toVector()
                ?: throw ToolError("Give center: no player of yours in ${world.name} to look around")
            val box = BoundingBox(center.x - radius, center.y - radius, center.z - radius, center.x + radius, center.y + radius, center.z + radius)
            Triple(world, panels.relations(world, box), center)
        }
        if (json) return ToolResult.text(gson.toJson(JsonObject().apply {
            addProperty("world", world.name)
            addProperty("dim", world.key.toString())
            add("relations", JsonArray().apply { found.forEach { add(PanelJson.relation(it)) } })
        }))
        val at = "[${center.blockX}, ${center.blockY}, ${center.blockZ}]"
        if (found.isEmpty()) return ToolResult.text("No relations within ${PanelJson.fmt(radius)} blocks of $at in ${world.name}" + if (panels.entries().isEmpty()) panels.known() else ".")
        return ToolResult.text(
            "${found.size} relation${if (found.size == 1) "" else "s"} within ${PanelJson.fmt(radius)} blocks of $at in ${world.name}:\n" +
                found.joinToString("\n") { PanelText.relation(it) } +
                "\n\nTargets as JSON: format json. unlink {target: its from, relation: its id, provider} removes one; relink {…, reverse | from | to} changes one."
        )
    }
}
