package games.qore.foreman.server.panels

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import games.qore.foreman.server.Foreman
import games.qore.foreman.server.api.ButtonRow
import games.qore.foreman.server.api.Condition
import games.qore.foreman.server.api.ConditionCheck
import games.qore.foreman.server.api.ConditionFacts
import games.qore.foreman.server.api.ConditionSubject
import games.qore.foreman.server.api.ConditionsRow
import games.qore.foreman.server.api.FieldRow
import games.qore.foreman.server.api.ForemanPanels
import games.qore.foreman.server.api.LayoutField
import games.qore.foreman.server.api.LayoutType
import games.qore.foreman.server.api.LinkResult
import games.qore.foreman.server.api.ListRow
import games.qore.foreman.server.api.Panel
import games.qore.foreman.server.api.PanelContext
import games.qore.foreman.server.api.PanelProvider
import games.qore.foreman.server.api.PanelResult
import games.qore.foreman.server.api.PanelTarget
import games.qore.foreman.server.api.Relation
import games.qore.foreman.server.mcp.Caller
import games.qore.foreman.server.mcp.ToolError
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.ServicePriority
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Level

/**
 * The panel providers plugins register ([ForemanPanels]) and every call Foreman makes to them, on the main thread: which
 * one serves a target ([resolve]), its panel, edits checked against the panel's fields, actions against its buttons,
 * links and relations. A provider's exception fails that call only, logged once per provider and method.
 */
class Panels(private val plugin: Foreman) : ForemanPanels, Listener {
    /** A registered provider: its [id], [owner] plugin and [provider]. */
    class Entry(val id: String, val owner: String, val provider: PanelProvider) {
        val label: String get() = runCatching { provider.label() }.getOrDefault(id)
    }

    /** What serves a target: the provider's [entry] and the [target] it resolved it to. */
    class Served(val entry: Entry, val target: PanelTarget)

    /** What an edit, action or unlink did: the provider's [result] and the panel as it is now (null: gone, or it failed). */
    class Outcome(val served: Served, val result: PanelResult, val panel: Panel?)

    /** A relation of [entry]'s, with where its arrow starts and ends when Foreman knows ([from], [to]). */
    class Found(val entry: Entry, val relation: Relation, val from: Vector?, val to: Vector?)

    private val entries = CopyOnWriteArrayList<Entry>()
    /** `provider method` pairs whose failure was logged: the next ones aren't. */
    private val logged = ConcurrentHashMap.newKeySet<String>()

    /** Told (any thread) of each [refresh]: the bridge's feed, while it shows panels or relations. */
    @Volatile var onRefresh: ((PanelTarget) -> Unit)? = null

    fun start() {
        Bukkit.getServicesManager().register(ForemanPanels::class.java, this, plugin, ServicePriority.Normal)
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    @EventHandler
    fun onDisable(event: PluginDisableEvent) {
        val gone = entries.filter { it.owner == event.plugin.name }
        if (gone.isEmpty()) return
        entries.removeAll(gone.toSet())
        gone.forEach { e -> logged.removeIf { it.startsWith(e.id + " ") } }
    }

    override fun register(owner: Plugin, provider: PanelProvider) {
        val id = provider.id()
        require(id.matches(LayoutType.ID)) { "Panel provider ids are namespaced, lowercase: 'ns:name', not '$id'" }
        synchronized(entries) {
            val i = entries.indexOfFirst { it.id == id }
            if (i >= 0) {
                require(entries[i].owner == owner.name) { "$id is registered by ${entries[i].owner}" }
                entries[i] = Entry(id, owner.name, provider)
            } else entries += Entry(id, owner.name, provider)
        }
        logged.removeIf { it.startsWith("$id ") }
    }

    override fun unregister(owner: Plugin, id: String) {
        entries.removeIf { it.id == id && it.owner == owner.name }
    }

    override fun providers(): List<PanelProvider> = entries.map { it.provider }

    override fun refresh(target: PanelTarget) {
        onRefresh?.invoke(target)
    }

    fun entries(): List<Entry> = entries.toList()

    /** Whether [entry] is still registered (its plugin may have disabled, or registered it again). */
    fun registered(entry: Entry): Boolean = entries.contains(entry)

    fun entry(id: String): Entry = entries.firstOrNull { it.id == id } ?: throw ToolError("No panel provider '$id'" + known())

    /** The providers, for a message saying what there is: ` (providers: a, b)` or ` (no plugin registers panels here)`. */
    fun known(): String = if (entries.isEmpty()) " (no plugin registers panels here)" else " (providers: ${entries.joinToString { it.id }})"

    // ---- Calls ------------------------------------------------------------------------------------------------------

    /** The context of a call made for [caller] (null: the console or the server's token). */
    fun context(target: PanelTarget, caller: Caller?) = PanelContext(target, caller?.let { Bukkit.getPlayer(it.id) }, caller?.name ?: Caller.CONSOLE.name)

    fun context(target: PanelTarget, player: Player) = PanelContext(target, player, player.name)

    /** The first provider (or [only] that one) serving [aimed], and the target it gives. Main thread. */
    fun resolve(aimed: PanelTarget, only: String? = null): Served? {
        val located = located(aimed)
        for (e in entries) {
            if (only != null && e.id != only) continue
            val target = quietly(e, "resolve") { e.provider.resolve(located) } ?: continue
            return Served(e, target)
        }
        return null
    }

    /** [t], an entity named by its UUID alone placed in the one world that has it (a [ToolError] asks which when several do). */
    private fun located(t: PanelTarget): PanelTarget {
        if (t.type != PanelTarget.Type.ENTITY || t.worldName != null) return t
        val found = Bukkit.getWorlds().mapNotNull { it.getEntity(t.uuid!!) }
        if (found.size > 1) throw ToolError("${t.uuid} is in several worlds (${found.joinToString { it.world.name }}): give world")
        return found.firstOrNull()?.let { PanelTarget.entity(it) } ?: t
    }

    /** [target] as the providers resolve it, else as it is: what a target field or a link's end holds. */
    fun resolved(target: PanelTarget): PanelTarget = resolve(target)?.target ?: target

    /** The panel [served] shows to [ctx], null when there is none any more. Main thread. */
    fun describe(served: Served, ctx: PanelContext): Panel? = call(served.entry, "describe") { served.entry.provider.describe(ctx) }

    /** What [served]'s target is called, null when its provider names nothing (or failed). Main thread. */
    fun name(served: Served, ctx: PanelContext): String? =
        quietly(served.entry, "name") { served.entry.provider.name(ctx) }?.trim()?.takeIf { it.isNotEmpty() }

    /** Sets the field [key] of [served]'s panel to [raw], checked against that field. Main thread. */
    fun edit(served: Served, caller: Caller?, key: String, raw: JsonElement?): Outcome {
        val ctx = context(served.target, caller)
        val panel = describe(served, ctx) ?: throw ToolError("${served.target} has no panel any more")
        val fields = panel.rows().filterIsInstance<FieldRow>()
        val field = fields.firstOrNull { it.field.name == key }?.field
            ?: return Outcome(served, PanelResult.refused("No field '$key' in its panel (${fields.joinToString { it.field.name }.ifEmpty { "it has none" }})"), panel)
        val parsed = PanelJson.parse(field, raw)
        val error = parsed.error ?: area(field, parsed.value, served.target.worldName)
        if (error != null) return Outcome(served, PanelResult.errors(mapOf(key to error)), panel)
        val value = (parsed.value as? PanelTarget)?.let(::resolved) ?: parsed.value
        val result = call(served.entry, "edit") { served.entry.provider.edit(ctx, key, value) }
        return outcome(served, ctx, result)
    }

    /**
     * Presses the button [id] of [served]'s panel with [args]: the button's own args, and those given (which must agree
     * with the button's where both have one: a list item's button is picked by its `item`). Main thread.
     */
    fun action(served: Served, caller: Caller?, id: String, args: JsonObject?): Outcome {
        val ctx = context(served.target, caller)
        val panel = describe(served, ctx) ?: throw ToolError("${served.target} has no panel any more")
        val buttons = buttons(panel).filter { it.id == id }
        fun refused(why: String) = Outcome(served, PanelResult.refused(why), panel)
        if (buttons.isEmpty()) return refused("No button '$id' in its panel (${buttons(panel).map { it.id }.distinct().joinToString().ifEmpty { "it has none" }})")
        val given = args?.entrySet()?.associate { (k, v) -> k to v } ?: emptyMap()
        val agreeing = buttons.filter { b -> given.all { (k, v) -> k !in b.args || PanelJson.json(b.args[k]) == v } }
        val enabled = agreeing.filter { it.enabled }
        val button = when {
            agreeing.isEmpty() -> return refused("No button '$id' with these args: " + buttons.joinToString(" | ") { PanelJson.json(it.args).toString() })
            enabled.isEmpty() -> return refused(agreeing.first().hint ?: "'$id' is disabled")
            enabled.map { it.args }.distinct().size > 1 -> return refused("Several buttons '$id': give args, one of " + enabled.joinToString(" | ") { PanelJson.json(it.args).toString() })
            else -> enabled.first()
        }
        val merged = LinkedHashMap<String, Any?>(button.args).apply { given.forEach { (k, v) -> putIfAbsent(k, PanelJson.plain(v)) } }
        val result = call(served.entry, "action") { served.entry.provider.action(ctx, id, merged) }
        return outcome(served, ctx, result)
    }

    /** Links [from] to [to] through the provider serving [from]. Main thread. */
    fun link(caller: Caller?, from: PanelTarget, to: PanelTarget, choice: String?): Pair<Served, LinkResult> {
        val served = resolve(from) ?: throw ToolError("Nothing serves $from: it has no panel to link from" + known())
        val ctx = context(served.target, caller)
        return served to call(served.entry, "link") { served.entry.provider.link(ctx, served.target, resolved(to), choice) }
    }

    /** Removes the relation [relation] of the provider serving [target] (or of [only]). Main thread. */
    fun unlink(caller: Caller?, target: PanelTarget, relation: String, only: String?): Outcome {
        val served = relationOf(target, only)
        val ctx = context(served.target, caller)
        val result = call(served.entry, "unlink") { served.entry.provider.unlink(ctx, relation) }
        return Outcome(served, result, result.panel)
    }

    /**
     * Changes the relation [relation] of the provider serving [target] (or of [only]): reversed, or the end [change]
     * names moved to [to] (resolved by the providers). Main thread.
     */
    fun relink(caller: Caller?, target: PanelTarget, relation: String, only: String?, change: Relation.Change, to: PanelTarget?, choice: String?): Pair<Served, LinkResult> {
        val served = relationOf(target, only)
        val ctx = context(served.target, caller)
        val result = call(served.entry, "relink") { served.entry.provider.relink(ctx, relation, change, to?.let(::resolved), choice) }
        if (result.status == LinkResult.Status.DONE) refresh(served.target)
        return served to result
    }

    /** The provider of a relation named by one of its ends, [target] (or [only] with the target as given). Main thread. */
    private fun relationOf(target: PanelTarget, only: String?): Served = resolve(target, only) ?: only?.let { Served(entry(it), target) }
        ?: throw ToolError("Nothing serves $target" + known())

    /** The conditions row [row] of [served]'s panel for [caller], else a [ToolError] naming those it has. Main thread. */
    fun conditionsRow(served: Served, caller: Caller?, row: String): ConditionsRow {
        val panel = describe(served, context(served.target, caller)) ?: throw ToolError("${served.target} has no panel any more")
        val rows = panel.rows().filterIsInstance<ConditionsRow>()
        return rows.firstOrNull { it.id == row }
            ?: throw ToolError("No conditions row '$row' in its panel (${rows.joinToString { it.id }.ifEmpty { "it has none" }})")
    }

    /** What the builder of [served]'s conditions row [row] offers to read. Main thread. */
    fun subjects(served: Served, caller: Caller?, row: String): List<ConditionSubject> {
        conditionsRow(served, caller, row)
        return call(served.entry, "subjects") { served.entry.provider.subjects(context(served.target, caller), row) }
    }

    /** The facts of [subject] (an id of [subjects]) or of [target] (picked in the world, resolved). Main thread. */
    fun facts(served: Served, caller: Caller?, row: String, subject: String?, target: PanelTarget?): ConditionFacts? {
        conditionsRow(served, caller, row)
        val picked = target?.let(::resolved)
        return call(served.entry, "facts") { served.entry.provider.facts(context(served.target, caller), row, subject, picked) }
    }

    /** What [condition] reads now for [served]'s conditions row [row]. Main thread. */
    fun check(served: Served, caller: Caller?, row: String, condition: Condition): ConditionCheck {
        conditionsRow(served, caller, row)
        return call(served.entry, "check") { served.entry.provider.check(context(served.target, caller), row, condition) }
    }

    /** Every provider's relations reaching into [box] of [world]; a provider that fails is left out. Main thread. */
    fun relations(world: World, box: BoundingBox): List<Found> = entries.flatMap { e ->
        (quietly(e, "relations") { e.provider.relations(world, box.clone()) } ?: emptyList()).map { r ->
            Found(e, r, r.fromPoint ?: center(r.from), r.toPoint ?: center(r.to))
        }
    }

    /** Where [target] shows: an entity's or block's centre, an object's (with the world tools), null for a global one. */
    fun center(target: PanelTarget): Vector? = when (target.type) {
        PanelTarget.Type.ENTITY -> target.findEntity()?.boundingBox?.center
        PanelTarget.Type.BLOCK -> Vector(target.x + 0.5, target.y + 0.5, target.z + 0.5)
        PanelTarget.Type.OBJECT -> if (plugin.worldTools) Aim.objectCenter(plugin, target) else null
        PanelTarget.Type.GLOBAL -> null
    }

    /** Why [value] of an area field names no area of [world] (checked with the world tools only), else null. */
    private fun area(field: LayoutField, value: Any?, world: String?): String? {
        if (field.type != LayoutField.Type.AREA || value !is String || world == null || !plugin.worldTools) return null
        return if (plugin.worlds.area(value, world) == null) "no area $value in $world" else null
    }

    /** Every button of [panel]: its rows', its list items' and its conditions' (with their `item`), conditions rows' add. */
    fun buttons(panel: Panel): List<ButtonRow> = panel.rows().flatMap { r ->
        when (r) {
            is ButtonRow -> listOf(r)
            is ListRow -> r.items.flatMap { it.buttons() }
            is ConditionsRow -> r.buttons()
            else -> emptyList()
        }
    }

    /** [result] with the panel as it is now: the one it carries, else described again. */
    private fun outcome(served: Served, ctx: PanelContext, result: PanelResult): Outcome {
        val now = result.panel ?: runCatching { describe(served, ctx) }.getOrNull()
        refresh(served.target)
        return Outcome(served, result, now)
    }

    /** Calls [entry]'s provider: an exception is logged (once per provider and method) and becomes a [ToolError]. */
    fun <T> call(entry: Entry, method: String, block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        throw failed(entry, method, e)
    } catch (e: LinkageError) {
        throw failed(entry, method, e)
    }

    /** Calls [entry]'s provider: an exception is logged (once per provider and method) and gives null. */
    private fun <T> quietly(entry: Entry, method: String, block: () -> T): T? = try {
        block()
    } catch (e: Exception) {
        failed(entry, method, e).let { null }
    } catch (e: LinkageError) {
        failed(entry, method, e).let { null }
    }

    private fun failed(entry: Entry, method: String, e: Throwable): ToolError {
        if (logged.add("${entry.id} $method")) plugin.logger.log(Level.WARNING, "${entry.owner}'s panel provider ${entry.id} failed in $method (logged once)", e)
        return ToolError("${entry.owner}'s panel provider ${entry.id} failed in $method: ${e.message ?: e.javaClass.simpleName}")
    }
}
