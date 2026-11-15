package games.qore.foreman.client.panels

import games.qore.foreman.client.agent.AgentException
import games.qore.foreman.client.input.playerDimension
import games.qore.foreman.client.link.Bridge
import games.qore.foreman.client.link.LayoutField
import games.qore.foreman.client.link.Layouts
import games.qore.foreman.client.link.ServerCalls
import games.qore.foreman.client.util.JSON
import games.qore.foreman.client.util.arr
import games.qore.foreman.client.util.bool
import games.qore.foreman.client.util.get
import games.qore.foreman.client.util.long
import games.qore.foreman.client.util.obj
import games.qore.foreman.client.util.str
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import net.minecraft.world.phys.Vec3

/**
 * A row of a plugin's panel, by its [kind]: `text`, `value` ([label], [value] in [color], [hint]), `field` ([field],
 * its current [value]), `button` ([id], [label], [style], [confirm], [args]), `list` ([items]), `conditions` ([id],
 * [label], its [conditions], the builder's [add] button, whether subjects are picked in the world ([pick]), what typed
 * expressions look like ([syntax])). Every row has its [text] form, shown for kinds this mod doesn't know.
 */
@Serializable
data class PanelRow(
    val kind: String = "text", val text: String = "", val label: String = "", val value: JsonElement? = null, val color: JsonElement? = null,
    val hint: String = "", val field: LayoutField? = null, val id: String = "", val style: String = "normal", val confirm: String? = null,
    val args: JsonObject? = null, val enabled: Boolean = true, val items: List<PanelItem> = emptyList(),
    val conditions: List<ConditionData> = emptyList(), val add: PanelRow? = null, val pick: Boolean = false, val syntax: String? = null,
)

/**
 * A condition of a conditions row: its [text] as its plugin writes it, what it is for ([label]), whether it [holds] now,
 * what it [reads], why it can't be read ([error]), where it comes from ([origin]), [enabled] for this thing, the
 * [subjects] it reads, an OR group's alternatives ([any]) and its buttons ([actions]).
 */
@Serializable
data class ConditionData(
    val id: String = "", val text: String = "", val label: String? = null, val holds: Boolean? = null, val reads: String? = null,
    val error: String? = null, val origin: String? = null, val enabled: Boolean = true, val subjects: List<SubjectData> = emptyList(),
    val any: List<ConditionData> = emptyList(), val actions: List<PanelRow> = emptyList(),
)

/** Something conditions read: [id] (what its plugin's syntax names it by), [label], [detail], the [group] it is listed under, its [target]. */
@Serializable
data class SubjectData(val id: String = "", val label: String = "", val detail: String? = null, val group: String? = null, val target: JsonObject? = null) {
    val name get() = label.ifEmpty { id }
}

/** A fact of a subject: [name], its [type] (`bool`, `number`, `text`, `choice`), a choice's [values], a number's range, its value [now]. */
@Serializable
data class FactData(
    val name: String = "", val type: String = "bool", val label: String = "", val values: List<String> = emptyList(), val min: Double? = null,
    val max: Double? = null, val now: String? = null, val description: String? = null,
) {
    val shown get() = label.ifEmpty { name }
}

/** A subject as conditions name it, and its [facts]. */
@Serializable
data class FactsData(val subject: SubjectData = SubjectData(), val facts: List<FactData> = emptyList())

/** What a condition reads now: how its plugin writes it ([expression]), whether it [holds], what it [reads], or why it can't be ([error]). */
@Serializable
data class CheckData(val ok: Boolean = true, val expression: String? = null, val holds: Boolean? = null, val reads: String? = null, val error: String? = null)

/**
 * How a thing is doing ([level] `ok`, `off`, `blocked` or `problem`, its colour), in a [word] (a world object's badge)
 * and a sentence ([detail]: a panel's status line).
 */
@Serializable
data class Badge(val level: String = "ok", val word: String = "", val detail: String? = null) {
    val text get() = word + (detail?.let { ": $it" } ?: "")

    val argb: Int get() = when (level) {
        "ok" -> 0xFF4ADE80.toInt()
        "off" -> 0xFF9AA4B8.toInt()
        "blocked" -> 0xFFF87171.toInt()
        else -> 0xFFFBBF24.toInt()
    }

    /** Its mark before the word. */
    val mark: String get() = when (level) {
        "ok" -> "●"
        "off" -> "○"
        "blocked" -> "■"
        else -> "▲"
    }
}

/** An item of a list row: [label], [detail], a [color], the [target] it is (its panel a click away), its [actions] (buttons). */
@Serializable
data class PanelItem(
    val id: String = "", val label: String = "", val detail: String = "", val color: JsonElement? = null, val target: JsonObject? = null,
    val actions: List<PanelRow> = emptyList(), val text: String = "",
)

/** A part of a panel: its [title], [id], [rows], and the [tab] it shows on (null: every tab). */
@Serializable
data class PanelSection(val title: String = "", val id: String? = null, val rows: List<PanelRow> = emptyList(), val tab: String? = null)

/** A plugin's panel, as it describes it: Foreman draws it. Its [status] line shows above every tab. */
@Serializable
data class Panel(val title: String = "", val subtitle: String = "", val sections: List<PanelSection> = emptyList(), val status: Badge? = null) {
    /** Its field rows, in every section. */
    val fields get() = sections.flatMap { s -> s.rows.filter { it.kind == "field" && it.field != null } }

    /** Its tabs, in the order its sections name them first; empty: one page. */
    val tabs get() = sections.mapNotNull { it.tab }.distinct()

    companion object {
        /** [json] as a panel, a row this mod can't read kept as its text form. */
        fun parse(json: JsonObject): Panel = Panel(
            json["title"].str().orEmpty(), json["subtitle"].str().orEmpty(),
            json["sections"].arr().orEmpty().mapNotNull { it.obj() }.map { s ->
                PanelSection(s["title"].str().orEmpty(), s["id"].str(), s["rows"].arr().orEmpty().mapNotNull { it.obj() }.map(::row), s["tab"].str())
            },
            json["status"].obj()?.let { runCatching { JSON.decodeFromJsonElement<Badge>(it) }.getOrNull() },
        )

        private fun row(json: JsonObject): PanelRow = runCatching { JSON.decodeFromJsonElement<PanelRow>(json) }.getOrNull()
            ?: PanelRow(kind = "unknown", text = json["text"].str().orEmpty())
    }
}

/**
 * What links two things, as its plugin ([provider]) says: from [from] to [to] (targets), drawn as an arrow between
 * [fromPoint] and [toPoint] with its [label] in its [color]; its [id] is what `unlink` and `relink` get. Selected, it
 * says [detail] (what reads what), and offers the [changes] its plugin allows (`reverse`, `from`, `to`).
 */
@Serializable
data class Relation(
    val id: String, val provider: String = "", val from: JsonObject? = null, val to: JsonObject? = null,
    @SerialName("from_point") val fromPoint: List<Double> = emptyList(), @SerialName("to_point") val toPoint: List<Double> = emptyList(),
    val label: String = "", val color: JsonElement? = null, val state: String? = null, val detail: String? = null,
    val changes: List<String> = emptyList(),
) {
    val fromAt: Vec3? get() = fromPoint.takeIf { it.size == 3 }?.let { Vec3(it[0], it[1], it[2]) }
    val toAt: Vec3? get() = toPoint.takeIf { it.size == 3 }?.let { Vec3(it[0], it[1], it[2]) }

    /** What it is for people: its detail, else its label, else its id. */
    val title: String get() = detail?.ifEmpty { null } ?: label.ifEmpty { id }

    fun same(other: Relation?) = other != null && other.id == id && other.provider == provider
}

/**
 * The panel open: what was [aimed] at ([name] for people), the [target] its plugin ([provider], [providerLabel]) serves
 * (null: none has a panel for it) and its [panel], once the server [answered]; [error] when the plugin failed.
 */
data class PanelState(
    val aimed: JsonObject, val name: String, val answered: Boolean = false, val target: JsonObject? = null, val provider: String? = null,
    val providerLabel: String? = null, val panel: Panel? = null, val error: String? = null,
)

/** How an edit, an action or an unlink went: [ok], a [message] to the builder, [errors] by field, the refreshed [panel]. */
class PanelResult(val ok: Boolean, val message: String?, val errors: Map<String, String> = emptyMap(), val panel: Panel? = null)

/**
 * How a link went: [status] `done`, `refused`, `choose` (a [question] and its [options]) or `build` (the conditions
 * [row] of the source, or of [target], opens its builder with [condition]).
 */
class LinkResult(
    val status: String, val message: String?, val question: String? = null, val options: List<LinkOption> = emptyList(), val row: String? = null,
    val condition: JsonObject? = null, val target: JsonObject? = null,
)

/** A link its plugin offers to choose from: [id] picks it, [label] and [hint] (what it carries) for people. */
class LinkOption(val id: String, val label: String, val hint: String = "")

/**
 * The panels and relations of the server's plugins (`ForemanPanels`), over the bridge: the panel open (pushed while it
 * is, live), the relations near the player (while the objects tool shows them), and the tools that change things
 * (`panel_edit`, `panel_action`, `link_targets`, `unlink`, `relink`), called like the mod's other server tools.
 */
object Panels {
    /** The row kinds this mod draws, told to the server. */
    private val ROWS = listOf("text", "value", "field", "button", "list", "conditions")

    /** The server offers panels. */
    val supported get() = Bridge.hello?.let { "panels" in it.features || it.panelsVersion > 0 } == true

    /** The panel open, null when none is. */
    var shown: PanelState? = null
        private set

    /** The relations near the player, in [relationsDim]. */
    var relations: List<Relation> = emptyList()
        private set
    var relationsDim = ""
        private set

    /** The server said hello: the mod says what it draws. */
    fun hello() {
        if (!supported) return
        Bridge.send(buildJsonObject {
            put("type", "hello")
            put("panels", 1)
            put("rows", JsonArray(ROWS.map(::JsonPrimitive)))
        })
    }

    /** Asks for the panel of [aimed] ([name] for people): the server answers, then pushes it as it changes. */
    fun open(aimed: JsonObject, name: String) {
        shown = PanelState(aimed, name)
        Bridge.send(buildJsonObject {
            put("type", "panel_open")
            put("target", aimed)
        })
    }

    fun close() {
        if (shown == null) return
        shown = null
        Bridge.send(buildJsonObject { put("type", "panel_close") })
    }

    fun reset() {
        shown = null
        relations = emptyList()
        Resolver.clear()
    }

    /** A `panel`, `panel_resolved` or `relations` message. */
    fun handle(type: String, message: JsonObject) {
        when (type) {
            "panel_resolved" -> Resolver.handle(message)
            "panel" -> {
                val s = shown ?: return
                val aimed = message["aimed"].obj()
                if (aimed != null && !Targets.same(aimed, s.aimed)) return
                val panel = message["panel"].obj()?.let(Panel::parse)
                shown = s.copy(
                    answered = true, target = message["target"].obj(), provider = message["provider"].str(), providerLabel = message["provider_label"].str(),
                    panel = panel, error = message["error"].str(),
                )
            }
            "relations" -> {
                relations = message["relations"].arr().orEmpty().mapNotNull { r -> runCatching { JSON.decodeFromJsonElement<Relation>(r) }.getOrNull() }
                relationsDim = message["dim"].str() ?: playerDimension().orEmpty()
            }
        }
    }

    /** Sets field [key] of [target]'s panel to [value] (null clears it). */
    fun edit(target: JsonObject, key: String, value: JsonElement?, done: (PanelResult) -> Unit) {
        val provider = provider(target)
        result(target, done) { call("panel_edit", buildJsonObject {
            put("target", target)
            put("key", key)
            put("value", value ?: kotlinx.serialization.json.JsonNull)
            provider?.let { put("provider", it) }
        }) }
    }

    /** Runs action [id] of [target]'s panel with [args]. */
    fun action(target: JsonObject, id: String, args: JsonObject?, done: (PanelResult) -> Unit) {
        val provider = provider(target)
        result(target, done) { call("panel_action", buildJsonObject {
            put("target", target)
            put("id", id)
            put("args", args ?: JsonObject(emptyMap()))
            provider?.let { put("provider", it) }
        }) }
    }

    /** The provider of the panel shown, when it is [target]'s: the one that serves its edits and actions. */
    private fun provider(target: JsonObject): String? = shown?.takeIf { s -> s.target?.let { Targets.same(it, target) } == true }?.provider

    /** Takes [relation] apart. */
    fun unlink(relation: Relation, done: (PanelResult) -> Unit) = result(null, done) {
        call("unlink", buildJsonObject {
            relation.from?.let { put("target", it) }
            put("relation", relation.id)
            if (relation.provider.isNotEmpty()) put("provider", relation.provider)
        })
    }

    /** Links [from] to [to], with the [choice] picked when the plugin asked for one. */
    fun link(from: JsonObject, to: JsonObject, choice: String?, done: (LinkResult) -> Unit) = linking("Couldn't link them", done) {
        call("link_targets", buildJsonObject {
            put("from", from)
            put("to", to)
            choice?.let { put("choice", it) }
        })
    }

    /**
     * Changes [relation] through its plugin: [change] `reverse`, or `from` / `to`, that end moved to [to]; with the
     * [choice] picked when the plugin asked for one.
     */
    fun relink(relation: Relation, change: String, to: JsonObject?, choice: String?, done: (LinkResult) -> Unit) = linking("Couldn't change it", done) {
        call("relink", buildJsonObject {
            relation.from?.let { put("target", it) }
            put("relation", relation.id)
            if (relation.provider.isNotEmpty()) put("provider", relation.provider)
            if (change == "reverse") put("reverse", true) else to?.let { put(change, it) }
            choice?.let { put("choice", it) }
        })
    }

    /** Runs [work], a link's call, off the client thread: its answer as a [LinkResult], a failure as [failed]. */
    private fun linking(failed: String, done: (LinkResult) -> Unit, work: () -> JsonObject) = ServerCalls.run(work) { result ->
        done(result.fold({ json ->
            LinkResult(
                json["status"].str() ?: if (json["ok"].bool() == false) "refused" else "done", json["message"].str(), json["question"].str(),
                json["options"].arr().orEmpty().mapNotNull { o -> o["id"].str()?.let { LinkOption(it, o["label"].str() ?: it, o["hint"].str().orEmpty()) } },
                json["row"].str(), json["condition"].obj(), json["target"].obj(),
            )
        }, { LinkResult("refused", it.message ?: failed) }))
    }

    /**
     * A conditions row's builder to open filled in, once [target]'s panel shows it: what a link asked for (`build`);
     * the relation it [replaces] is unlinked once the builder adds it.
     */
    class Prefill(val target: JsonObject, val row: String, val condition: JsonObject, val replaces: Relation? = null)

    var prefill: Prefill? = null

    /** What the builder of [target]'s conditions row [row] offers to read. */
    fun subjects(target: JsonObject, row: String, done: (Result<List<SubjectData>>) -> Unit) = vocabulary(target, row, {}, done) { json ->
        json["subjects"].arr().orEmpty().mapNotNull { runCatching { JSON.decodeFromJsonElement<SubjectData>(it) }.getOrNull() }
    }

    /** The facts of [subject] (an id), or of [pick], a thing picked in the world. */
    fun facts(target: JsonObject, row: String, subject: String?, pick: JsonObject?, done: (Result<FactsData>) -> Unit) = vocabulary(target, row, {
        subject?.let { put("subject", it) }
        pick?.let { put("pick", it) }
    }, done) { JSON.decodeFromJsonElement<FactsData>(it) }

    /** What [condition] reads now. */
    fun check(target: JsonObject, row: String, condition: JsonObject, done: (Result<CheckData>) -> Unit) =
        vocabulary(target, row, { put("check", condition) }, done) { JSON.decodeFromJsonElement<CheckData>(it) }

    /** Calls `panel_facts` for [target]'s [row] with [more] args; [read] turns its JSON answer into what [done] gets. */
    private fun <T> vocabulary(
        target: JsonObject, row: String, more: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit, done: (Result<T>) -> Unit, read: (JsonObject) -> T,
    ) {
        val provider = provider(target)
        ServerCalls.run({
            call("panel_facts", buildJsonObject {
                put("target", target)
                put("row", row)
                provider?.let { put("provider", it) }
                more()
            })
        }) { result ->
            done(result.mapCatching { json ->
                if (json["ok"].bool() == false && json["error"] == null) throw IllegalStateException(json["message"].str() ?: "Its plugin refused")
                read(json)
            })
        }
    }

    /** Runs [work] off the client thread, its answer as a [PanelResult]; its panel replaces [target]'s shown one. */
    private fun result(target: JsonObject?, done: (PanelResult) -> Unit, work: () -> JsonObject) = ServerCalls.run(work) { result ->
        val r = result.fold(::read) { PanelResult(false, it.message ?: it.javaClass.simpleName) }
        val s = shown
        if (r.panel != null && s != null && target != null && s.target != null && Targets.same(s.target, target)) shown = s.copy(panel = r.panel)
        done(r)
    }

    private fun read(json: JsonObject) = PanelResult(
        json["ok"].bool() ?: (json["status"].str()?.let { it == "ok" || it == "done" } ?: true), json["message"].str(),
        json["errors"].obj()?.mapNotNull { (k, v) -> v.str()?.let { k to it } }?.toMap().orEmpty(),
        json["panel"].obj()?.let(Panel::parse),
    )

    /** Calls [tool] with [args] (JSON answers asked): what it answered, a refusal that isn't JSON as its message. */
    private fun call(tool: String, args: JsonObject): JsonObject {
        val withFormat = JsonObject(args + ("format" to JsonPrimitive("json")))
        val text = try {
            ServerCalls.text(ServerCalls.callNow(tool, withFormat, java.time.Duration.ofSeconds(30)))
        } catch (e: AgentException) {
            val message = e.message.orEmpty()
            return json(message)?.let { JsonObject(it + ("ok" to JsonPrimitive(it["ok"].bool() ?: false))) }
                ?: buildJsonObject {
                    put("ok", false)
                    put("status", "refused")
                    put("message", message)
                }
        }
        return json(text) ?: buildJsonObject {
            put("ok", true)
            if (text.isNotBlank()) put("message", text)
        }
    }

    private fun json(text: String): JsonObject? = text.trim().takeIf { it.startsWith("{") }?.let { runCatching { JSON.parseToJsonElement(it).obj() }.getOrNull() }

    /** A row's or relation's colour as opaque ARGB: `#rrggbb` or a number; null when it has none. */
    fun color(value: JsonElement?): Int? {
        val p = value as? JsonPrimitive ?: return null
        if (p.isString) return Layouts.color(p.content)
        val n = p.long() ?: return null
        return if (n <= 0xFFFFFF) (n.toInt() or 0xFF000000.toInt()) else n.toInt()
    }
}
