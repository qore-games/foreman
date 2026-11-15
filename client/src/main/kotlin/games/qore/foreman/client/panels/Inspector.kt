package games.qore.foreman.client.panels

import com.mojang.blaze3d.platform.InputConstants
import games.qore.foreman.client.editor.EditorKey
import games.qore.foreman.client.editor.Hint
import games.qore.foreman.client.editor.Note
import games.qore.foreman.client.editor.Shown
import games.qore.foreman.client.editor.ToolStatus
import games.qore.foreman.client.input.MouseButton
import games.qore.foreman.client.input.Ray
import games.qore.foreman.client.input.Tools
import games.qore.foreman.client.input.playerDimension
import games.qore.foreman.client.link.Bridge
import games.qore.foreman.client.link.BridgeLayer
import games.qore.foreman.client.objects.ObjectFade
import games.qore.foreman.client.objects.ObjectsTool
import games.qore.foreman.client.render.Colors
import games.qore.foreman.client.render.OverlayDraw
import games.qore.foreman.client.ui.Screens
import games.qore.foreman.client.util.Chat
import kotlinx.serialization.json.JsonObject
import net.minecraft.world.phys.Vec3

/**
 * What the objects tool does with the server's panels and relations (`ForemanPanels`), while it is on: each relation
 * near the player as a line with its label and chevrons flowing from its source to its target, the [panel][PanelScreen]
 * of what the crosshair is on ([inspect]: N with nothing selected; a world object's own, its form and panel), K the
 * link mode (click a source, then what to link it to; the plugin may ask which link, or open the source's condition
 * builder filled in), and a panel's target field is picked here. A click on a relation (in link mode too) selects it:
 * a card says what it links and what its keys do, Delete takes it apart, and its plugin may let it be reversed (R) or
 * one end moved to another thing (I its source, O its destination: clicked in the world or picked from a list),
 * through `relink`. A relation with a state is green while what it carries gets through (its chevrons flowing), red
 * when it doesn't, grey for events; F narrows the overlay to the selected object's chain (what it reads and what reads
 * it, all the way). What the crosshair is on there is what the providers serve ([Resolver]): a furniture for its cell
 * block, by its name. Nothing is asked of the server while the tool is off.
 */
object Inspector {
    /** Relations are picked this close to the crosshair; labels show this near. */
    private const val PICK = 0.3
    private const val LABELS = 24.0
    private const val RELATION = 0xFF9AA4B8.toInt()
    private const val LINKING = 0xFF7FD8FF.toInt()
    private const val ACTIVE = 0xFF4ADE80.toInt()
    private const val BLOCKED = 0xFFF87171.toInt()
    private const val IDLE = 0xFF8A93A6.toInt()
    /** The alpha of an arrow's part behind things. */
    private const val THROUGH = 0x60000000
    private const val NOTE_MS = 6000L
    private const val CONFIRM_MS = 3000L
    /** A thing a provider serves wins over the nearest thing when it is at most this much farther along the crosshair. */
    private const val MARGIN = 0.75
    /** [inspect] waits this long at most for the providers' answers before opening the panel of what it has. */
    private const val INSPECT_WAIT_MS = 800L
    /** The chevrons along a relation: [FLOW_GAP] blocks apart, moving that far every [FLOW_PERIOD_MS], [CHEVRON] wide, [FLOW_MAX] at most. */
    private const val FLOW_GAP = 0.75
    private const val FLOW_PERIOD_MS = 700L
    private const val CHEVRON = 0.12
    private const val FLOW_MAX = 40
    /** A relation changed is selected again once the server sends its new one, if it does within this. */
    private const val EXPECT_MS = 4000L
    /** The things a relation's end may move to, picked from a list: world objects this near, at most [LIST_MAX]. */
    private const val LIST_RADIUS = 32.0
    private const val LIST_MAX = 24

    /** A panel's field waiting for a target picked in the world: [what] it is, [done] gets it. */
    private class Pick(val what: String, val done: (JsonObject?) -> Unit)

    /** [inspect] asked: the panel of one of [candidates] opens once the providers answered for them, or [until]. */
    private class Inspecting(val candidates: List<Aimed>, val until: Long)

    /** The things of a chain (by [Targets.key]) and its relations (provider and id), for an object (its kind and id). */
    private class Chain(val of: String, val relations: List<Relation>, val nodes: Set<String>, val links: Set<String>)

    /** One end of [relation] being moved ([change]: `from`, its source, or `to`, its destination): the builder picks where. */
    private class Moving(val relation: Relation, val change: String) {
        val what get() = if (change == FROM) "source" else "destination"
        val key get() = if (change == FROM) InputConstants.KEY_I else InputConstants.KEY_O
        /** The end that stays, and the one that moves, as targets and points. */
        val fixed get() = if (change == FROM) relation.to else relation.from
        val moved get() = if (change == FROM) relation.from else relation.to
        val fixedAt get() = if (change == FROM) relation.toAt else relation.fromAt
    }

    /**
     * A relation of [provider] just changed: the first new one (not [before]) whose ends have the keys [from] and [to]
     * (null: any) is selected when it shows, until [until].
     */
    private class Expect(val provider: String, val from: String?, val to: String?, val before: Set<String>, val until: Long)

    /** The selected relation (Delete takes it apart, R, I and O change it). */
    var relation: Relation? = null
        private set
    private var moving: Moving? = null
    private var expect: Expect? = null
    /** Only the selected object's chain shows. */
    var chainOnly = false
        private set
    private var chain: Chain? = null
    private var hovered: Relation? = null
    private var linking = false
    /** Link mode's source, once clicked: as it was aimed at ([shown] serves it). */
    private var source: Aimed? = null
    private var pick: Pick? = null
    private var inspecting: Inspecting? = null
    /** What the crosshair is on, in link mode or while picking a target; where it ends otherwise. */
    private var aimed: Aimed? = null
    private var crosshair: Vec3? = null
    private var subscribed = false
    private var busy = false
    private var note: Pair<String, Long>? = null
    private var unlinkAsked = 0L

    /** The clicks are the link mode's, the target pick's or a relation's new end's. */
    val takesClicks get() = linking || pick != null || moving != null

    /** Something is picked in the world (a target, a relation's new end): only its keys. */
    val picking get() = pick != null || moving != null

    fun keys(): List<EditorKey> {
        if (!Panels.supported) return emptyList()
        if (pick != null) return emptyList()
        moving?.let { m -> return listOf(EditorKey(m.key, "Its new ${m.what}: from a list of things near you") { listEnds(m) }) }
        return listOf(
            EditorKey(InputConstants.KEY_K, if (linking) "Leave link mode" else "Link mode: link two things") { toggleLink() },
            EditorKey(InputConstants.KEY_F, "Its chain only: " + if (chainOnly) "on" else "off") { toggleChain() },
        ) + (relation?.takeIf { source == null }?.let(::changeKeys) ?: emptyList())
    }

    /** The keys that change [r], those its plugin allows. */
    private fun changeKeys(r: Relation): List<EditorKey> = listOfNotNull(
        EditorKey(InputConstants.KEY_R, "Reverse the link") { reverse(r) }.takeIf { REVERSE in r.changes },
        EditorKey(InputConstants.KEY_I, "Change its source: click the new one") { move(r, FROM) }.takeIf { FROM in r.changes },
        EditorKey(InputConstants.KEY_O, "Change its destination: click the new one") { move(r, TO) }.takeIf { TO in r.changes },
    )

    // ---- The chain --------------------------------------------------------------------------------------------------

    private fun toggleChain() {
        chainOnly = !chainOnly
        if (chainOnly && ObjectsTool.selected == null) say("§eSelect an object: only what it reads and what reads it show")
    }

    /** The selected object's chain while [chainOnly], else null (everything shows). */
    private fun chain(): Chain? {
        if (!chainOnly) return null
        // A selection the server doesn't send any more (gone, or out of view) narrows nothing.
        val selected = ObjectsTool.selected?.takeIf { s -> Bridge.objects.any { it.id == s.id && it.kind == s.kind } } ?: return null
        val of = "object|${selected.kind}|${selected.id}"
        val all = Panels.relations
        chain?.takeIf { it.of == of && it.relations === all }?.let { return it }
        val nodes = hashSetOf(of)
        val links = HashSet<String>()
        /** Follows the relations from [start]: toward what feeds it ([up]) or what it feeds. */
        fun walk(start: String, up: Boolean) {
            val queue = ArrayDeque(listOf(start))
            val seen = hashSetOf(start)
            while (queue.isNotEmpty()) {
                val at = queue.removeFirst()
                for (r in all) {
                    val from = r.from?.let(Targets::key) ?: continue
                    val to = r.to?.let(Targets::key) ?: continue
                    val (here, next) = if (up) to to from else from to to
                    if (here != at) continue
                    links += r.provider + " " + r.id
                    nodes += next
                    if (seen.add(next)) queue += next
                }
            }
        }
        walk(of, up = true)
        walk(of, up = false)
        return Chain(of, all, nodes, links).also { chain = it }
    }

    /** Whether [obj] shows: it is in the selected object's chain, or the chain filter is off. */
    fun shows(obj: games.qore.foreman.client.link.GameObject): Boolean = chain()?.nodes?.contains("object|${obj.kind}|${obj.id}") ?: true

    private fun shows(r: Relation): Boolean = chain()?.links?.contains(r.provider + " " + r.id) ?: true

    // ---- Inspecting -------------------------------------------------------------------------------------------------

    /**
     * Opens the panel of what the crosshair is on (once the providers said what serves it), else of the selected object:
     * a world object's is its own panel, its form and its plugin's panel ([ObjectsTool.inspect]).
     */
    fun inspect() {
        val candidates = Targets.candidates(Ray.crosshair())
        if (candidates.isEmpty()) {
            val selected = ObjectsTool.selected ?: return Chat.warn("Aim at something: an entity, a block or an object")
            return if (selected.type != null) ObjectsTool.inspect(selected) else PanelScreen.open(Targets.of(selected))
        }
        Resolver.want(candidates.map { it.target }, now = true)
        inspecting = Inspecting(candidates, System.currentTimeMillis() + INSPECT_WAIT_MS)
        opening()
    }

    /** Opens the panel being inspected once the providers answered for every candidate, or it waited long enough. */
    private fun opening() {
        val i = inspecting ?: return
        if (!Resolver.answered(i.candidates.map { it.target }) && System.currentTimeMillis() < i.until) return
        inspecting = null
        val aimed = aimedAmong(i.candidates) ?: return
        val obj = ObjectsTool.objectOf(aimed.target)
        if (obj != null) ObjectsTool.inspect(obj) else PanelScreen.open(shown(aimed))
    }

    /** Opens the panel of [target] ([name] for people): a world object's with its form ([ObjectsTool.inspect]), else its plugin's. */
    fun open(target: JsonObject, name: String) {
        val obj = ObjectsTool.objectOf(target)
        if (obj != null) return ObjectsTool.inspect(obj)
        Panels.open(target, name)
        Screens.open(PanelScreen())
    }

    /** What the builder aims at among [candidates], as it is served ([aimedAmong]). */
    private fun choose(candidates: List<Aimed>): Aimed? = aimedAmong(candidates)?.let(::shown)

    /**
     * What the builder aims at among [candidates] (nearest first): the first one a provider serves within [MARGIN] of
     * the nearest (a prop behind its own cell block, a switch just before its wall), else the nearest.
     */
    private fun aimedAmong(candidates: List<Aimed>): Aimed? {
        val nearest = candidates.firstOrNull() ?: return null
        return candidates.firstOrNull { it.along <= nearest.along + MARGIN && Resolver.of(it.target)?.target != null } ?: nearest
    }

    /** [aimed] as its provider serves it (a furniture for its cell block, by its name) once the server said; else as it is. */
    private fun shown(aimed: Aimed): Aimed {
        val answer = Resolver.of(aimed.target) ?: return aimed
        return Targets.resolved(aimed, answer.target ?: return aimed, answer.title)
    }

    /** A panel's target field: the builder aims at it and clicks (right click or Esc: none), then [done] gets it. */
    fun pickTarget(what: String, done: (JsonObject?) -> Unit) {
        if (!ObjectsTool.enabled) Tools.activate(ObjectsTool)
        linking = false
        source = null
        pick = Pick(what, done)
    }

    private fun picked(target: JsonObject?) {
        val p = pick ?: return
        pick = null
        p.done(target)
    }

    // ---- Link mode --------------------------------------------------------------------------------------------------

    private fun toggleLink() {
        linking = !linking
        source = null
        if (linking) {
            relation = null
            ObjectsTool.select(null)
        }
    }

    /** Leaves link mode and drops a target pick (a new object being placed). */
    fun leaveModes() {
        linking = false
        source = null
    }

    /** Right click or Esc in link mode: the source dropped, else the relation selected, else link mode left. */
    private fun back() {
        when {
            source != null -> source = null
            relation != null -> relation = null
            else -> linking = false
        }
    }

    private fun link(source: Aimed, to: Aimed, choice: String?) {
        val from = shown(source)
        busy = true
        Panels.link(from.target, to.target, choice) { r ->
            busy = false
            when (r.status) {
                "done" -> say("§a✔ " + (r.message ?: "Linked ${from.name} to ${to.name}"))
                "choose" -> choose(r) { id -> link(source, to, id) }
                "build" -> build(r.target ?: from.target, if (r.target == null) from.name else null, r, null)
                else -> refused(r)
            }
        }
    }

    /** The plugin asks which link: the builder picks one of its options, [again] calls it with that choice. */
    private fun choose(r: LinkResult, again: (String) -> Unit) {
        if (r.options.isEmpty()) return say("§c✖ " + (r.message ?: "Its plugin asked a question with no answers"))
        Screens.open(ChoiceScreen(r.question ?: "Which link?", r.options, again))
    }

    private fun refused(r: LinkResult) = say("§c✖ " + (r.message?.lineSequence()?.firstOrNull() ?: "Refused"))

    /**
     * No link fits as it is: [target]'s panel opens ([name] for people, else its provider's), its condition builder
     * filled in as [r] asks; the relation it [replaces] goes once the builder adds it.
     */
    private fun build(target: JsonObject, name: String?, r: LinkResult, replaces: Relation?) {
        val row = r.row
        val condition = r.condition
        if (row == null || condition == null) return say("§c✖ " + (r.message ?: "Its plugin asked for a condition without one"))
        say("§e" + (r.message ?: "Finish the condition in its panel"))
        Panels.prefill = Panels.Prefill(target, row, condition, replaces)
        linking = false
        source = null
        moving = null
        relation = null
        open(target, name ?: Targets.named(target))
    }

    // ---- Changing a relation ----------------------------------------------------------------------------------------

    private fun reverse(r: Relation) {
        if (busy) return say("§eWait for the last change")
        relink(r, REVERSE, null, null)
    }

    /** One end of [r] moves: the builder clicks the new one, or picks it from a list. */
    private fun move(r: Relation, change: String) {
        if (busy) return say("§eWait for the last change")
        source = null
        moving = Moving(r, change)
        // Their names, for the list.
        nearby(moving!!).forEach { Resolver.title(it.second) }
    }

    /** The world objects near the player that [m]'s end may move to, nearest first: each with its target. */
    private fun nearby(m: Moving): List<Pair<games.qore.foreman.client.link.GameObject, JsonObject>> {
        val dimension = playerDimension() ?: return emptyList()
        val at = Ray.crosshair().origin
        val ends = listOfNotNull(m.fixed, m.moved).map(Targets::key).toSet()
        return ObjectsTool.objects(dimension).filter { it.type != null }
            .map { it to (it.line.minOfOrNull { p -> p.distanceTo(at) } ?: Double.MAX_VALUE) }
            .filter { it.second <= LIST_RADIUS }.sortedBy { it.second }
            .map { it.first to Targets.of(it.first).target }.filter { Targets.key(it.second) !in ends }.take(LIST_MAX)
    }

    /** [m]'s new end, picked from the things near the player. */
    private fun listEnds(m: Moving) {
        val at = Ray.crosshair().origin
        val near = nearby(m)
        if (near.isEmpty()) return say("§eNothing near to list: click its new ${m.what} in the world")
        val options = near.mapIndexed { i, (o, target) ->
            val d = o.line.minOfOrNull { it.distanceTo(at) } ?: 0.0
            LinkOption(i.toString(), Targets.named(target), "${ObjectsTool.name(o)} · ${d.toInt()} m")
        }
        Screens.open(ChoiceScreen("The new ${m.what} of ${m.relation.title}", options) { id ->
            val (o, target) = near.getOrNull(id.toIntOrNull() ?: -1) ?: return@ChoiceScreen
            relink(m.relation, m.change, Aimed(target, Targets.named(target), o.line.firstOrNull() ?: Vec3.ZERO, null, 0.0), null)
        })
    }

    /** [m]'s new end was clicked: [at]. */
    private fun moveTo(m: Moving, at: Aimed) {
        val key = Targets.key(at.target)
        when (key) {
            m.fixed?.let(Targets::key) -> say("§eThat is its other end: click another thing")
            m.moved?.let(Targets::key) -> say("§eThat is its ${m.what} already: click another thing")
            else -> relink(m.relation, m.change, at, null)
        }
    }

    /**
     * Changes [r] through its plugin ([change]: reversed, or that end moved to [to]), with the [choice] picked when it
     * asked: done, the new relation is selected once it shows; build, the panel it names opens with its condition
     * builder, the old relation unlinked once the builder adds it.
     */
    private fun relink(r: Relation, change: String, to: Aimed?, choice: String?) {
        busy = true
        val before = Panels.relations.map { it.provider + " " + it.id }.toSet()
        Panels.relink(r, change, to?.target, choice) { result ->
            busy = false
            when (result.status) {
                "done" -> {
                    say("§a✔ " + (result.message ?: if (change == REVERSE) "Reversed" else "Moved"))
                    moving = null
                    relation = null
                    val from = r.from?.let(Targets::key)
                    val toKey = r.to?.let(Targets::key)
                    expect = when (change) {
                        REVERSE -> Expect(r.provider, toKey, from, before, System.currentTimeMillis() + EXPECT_MS)
                        FROM -> Expect(r.provider, null, toKey, before, System.currentTimeMillis() + EXPECT_MS)
                        else -> Expect(r.provider, from, null, before, System.currentTimeMillis() + EXPECT_MS)
                    }
                }
                "choose" -> choose(result) { id -> relink(r, change, to, id) }
                "build" -> build(result.target ?: r.from ?: return@relink refused(result), null, result, r)
                else -> refused(result)
            }
        }
    }

    /** Selects the relation a change made once the server sends it ([expect]). */
    private fun expected(dimension: String) {
        val e = expect ?: return
        if (System.currentTimeMillis() > e.until) return run { expect = null }
        val found = shown(dimension).firstOrNull { r ->
            r.provider == e.provider && (r.provider + " " + r.id) !in e.before &&
                (e.from == null || r.from?.let(Targets::key) == e.from) && (e.to == null || r.to?.let(Targets::key) == e.to)
        } ?: return
        expect = null
        if (!linking || source == null) relation = found
    }

    // ---- Relations --------------------------------------------------------------------------------------------------

    private fun shown(dimension: String) = if (Panels.relationsDim == dimension) Panels.relations else emptyList()

    /**
     * The relation under [ray] and how far along it, the nearest: the selected one, or one near enough to show
     * ([ObjectFade]).
     */
    private fun relationAt(ray: Ray): Pair<Relation, Double>? {
        val dimension = playerDimension() ?: return null
        return shown(dimension).filter(::shows).mapNotNull { r ->
            val a = r.fromAt ?: return@mapNotNull null
            val b = r.toAt ?: return@mapNotNull null
            if (!r.same(relation) && !ObjectFade.inRange(minOf(ray.origin.distanceTo(a), ray.origin.distanceTo(b)))) return@mapNotNull null
            val (distance, along) = ray.toSegment(a, b)
            if (distance <= PICK && along <= ObjectsTool.REACH) r to along else null
        }.minByOrNull { it.second }
    }

    fun deselect() {
        relation = null
    }

    /** Delete with a relation selected: takes it apart (asked twice); false when none is selected. */
    fun delete(): Boolean {
        val r = relation ?: return false
        val now = System.currentTimeMillis()
        if (now - unlinkAsked > CONFIRM_MS) {
            unlinkAsked = now
            return true
        }
        unlinkAsked = 0
        busy = true
        Panels.unlink(r) { result ->
            busy = false
            if (result.ok) {
                say("§a✔ " + (result.message ?: "Unlinked"))
                if (relation?.same(r) == true) relation = null
            } else say("§c✖ " + (result.message?.lineSequence()?.firstOrNull() ?: "Refused"))
        }
        return true
    }

    // ---- The tool's input -------------------------------------------------------------------------------------------

    /** A click in link mode, while picking a target or a relation's new end: true when taken. */
    fun press(button: MouseButton, ray: Ray): Boolean {
        if (pick != null) {
            if (button == MouseButton.RIGHT) picked(null)
            else choose(Targets.candidates(ray))?.let { picked(it.target) } ?: say("§eAim at something")
            return true
        }
        moving?.let { m ->
            when {
                button == MouseButton.RIGHT -> moving = null
                busy -> say("§eWait for the last change")
                else -> choose(Targets.candidates(ray))?.let { moveTo(m, it) } ?: say("§eAim at its new ${m.what}")
            }
            return true
        }
        if (!linking) return false
        if (button == MouseButton.RIGHT) {
            back()
            return true
        }
        if (busy) return true.also { say("§eWait for the last link") }
        val from = source
        // With no source yet, a relation in front of what the crosshair is on is selected.
        if (from == null) linkHover(ray, Targets.candidates(ray))?.let { r ->
            relation = r
            return true
        }
        val at = choose(Targets.candidates(ray)) ?: return true.also {
            if (relation != null) relation = null else say("§eAim at something")
        }
        when {
            from == null -> {
                relation = null
                source = at
            }
            Targets.same(shown(from).target, at.target) -> say("§eThat is the source: click what to link it to")
            else -> link(from, at, null)
        }
        return true
    }

    /**
     * In link mode without a source: the relation under [ray] when it is in front of the thing the crosshair is on (the
     * one of [candidates] a click would pick).
     */
    private fun linkHover(ray: Ray, candidates: List<Aimed>): Relation? {
        val (r, along) = relationAt(ray) ?: return null
        val thing = aimedAmong(candidates)
        return r.takeIf { thing == null || along < thing.along }
    }

    /**
     * A click on nothing of the tool's own: selects the relation under [ray] (true), or deselects it (false: the tool
     * goes on with the click).
     */
    fun click(ray: Ray): Boolean {
        relation = relationAt(ray)?.first
        return relation != null
    }

    /** Every frame while the tool is on; [objectHovered]: the crosshair is on an object (it wins). */
    fun update(ray: Ray, objectHovered: Boolean) {
        if (!subscribed) {
            Bridge.need(BridgeLayer.RELATIONS, true)
            subscribed = true
        }
        opening()
        val candidates = if (takesClicks) Targets.candidates(ray) else emptyList()
        if (takesClicks) Resolver.want(candidates.map { it.target } + listOfNotNull(source?.target))
        aimed = choose(candidates)
        crosshair = if (takesClicks) ray.block(ObjectsTool.REACH)?.location ?: ray.at(6.0) else null
        hovered = when {
            linking && source == null && pick == null && moving == null -> linkHover(ray, candidates)?.also { aimed = null }
            takesClicks || objectHovered -> null
            else -> relationAt(ray)?.first
        }
        // Taken apart (here or elsewhere), or left behind: no longer selected.
        relation = relation?.let { r -> playerDimension()?.let(::shown)?.firstOrNull { it.same(r) } }
        if (moving != null && relation?.same(moving?.relation) != true) moving = null
        playerDimension()?.let(::expected)
    }

    fun escape(): Boolean {
        when {
            pick != null -> picked(null)
            moving != null -> moving = null
            linking -> back()
            relation != null -> relation = null
            else -> return false
        }
        return true
    }

    fun deactivated() {
        if (subscribed) {
            Bridge.need(BridgeLayer.RELATIONS, false)
            subscribed = false
        }
        if (pick != null) {
            // The panel waited for a target behind the tool: it is closed with it.
            pick = null
            Panels.close()
        }
        linking = false
        source = null
        moving = null
        expect = null
        inspecting = null
        aimed = null
        hovered = null
        relation = null
        Resolver.clear()
    }

    // ---- Drawing ----------------------------------------------------------------------------------------------------

    fun draw(draw: OverlayDraw) {
        for (r in shown(draw.dimension)) {
            val a = r.fromAt ?: continue
            val b = r.toAt ?: continue
            if (!shows(r)) continue
            val distance = minOf(draw.distance(a), draw.distance(b))
            val selected = r.same(relation)
            val lit = r.same(hovered)
            // The others fade with distance, like the objects ([ObjectFade]).
            val near = if (selected || lit) 1f else ObjectFade.byDistance(distance)
            if (near <= 0f) continue
            val color = when {
                selected -> Colors.WHITE
                lit -> Colors.HOVER
                else -> stateColor(r.state) ?: Panels.color(r.color) ?: RELATION
            }
            // Faint through what is in front of it (its arrowhead often sits inside what it points at), solid where seen.
            if (!selected) draw.arrow(a, b, Colors.withAlpha(color and 0xFFFFFF or THROUGH, near), 2f, onTop = true)
            draw.arrow(a, b, Colors.withAlpha(color, near), if (selected || lit) 3.5f else 2.5f, onTop = selected)
            // What flows shows its way; a blocked or idle relation stands still.
            if (r.state == null || r.state == "active") {
                flow(draw, a, b, Colors.withAlpha(if (selected || lit) color else brighter(color), near), if (selected || lit) 3f else 2.5f)
            }
            if (selected) {
                card(draw, r, a.add(b).scale(0.5).add(0.0, 0.3, 0.0))
                continue
            }
            val label = r.label.ifEmpty { null } ?: continue
            // Text this faint would be drawn opaque.
            if (lit || (distance <= LABELS && near > 0.1f)) draw.label(a.add(b).scale(0.5).add(0.0, 0.25, 0.0), label, Colors.withAlpha(color, near), onTop = lit, size = 0.6f)
        }
        if (!takesClicks) return
        source?.let(::shown)?.let { s ->
            outline(draw, s, LINKING)
            draw.line(s.at, aimed?.at ?: crosshair ?: return@let, LINKING, 2.5f, onTop = true)
        }
        moving?.let { m -> m.fixedAt?.let { fixed -> draw.line(fixed, aimed?.at ?: crosshair ?: fixed, LINKING, 2.5f, onTop = true) } }
        aimed?.let { a ->
            outline(draw, a, Colors.HOVER)
            draw.label(a.at.add(0.0, 0.5, 0.0), a.name, Colors.HOVER, size = 0.7f)
        }
    }

    /**
     * Chevrons along [a] → [b], [FLOW_GAP] apart, each moving one gap toward [b] every [FLOW_PERIOD_MS]: which way the
     * relation goes, seen from anywhere; at most [FLOW_MAX] of them, the nearest the camera. Drawn on top, they show
     * through what hides the line.
     */
    private fun flow(draw: OverlayDraw, a: Vec3, b: Vec3, color: Int, width: Float) {
        val length = a.distanceTo(b)
        if (length < 0.3) return
        val dir = b.subtract(a).scale(1 / length)
        val offset = (System.currentTimeMillis() % FLOW_PERIOD_MS) / FLOW_PERIOD_MS.toDouble() * FLOW_GAP
        val count = ((length - offset) / FLOW_GAP).toInt() + 1
        val along = (0 until count).map { offset + it * FLOW_GAP }.filter { it in 0.2..(length - 0.25) }
            .sortedBy { draw.distance(a.add(dir.scale(it))) }.take(FLOW_MAX)
        for (t in along) {
            val tip = a.add(dir.scale(t))
            val side = dir.cross(draw.camera.subtract(tip)).normalize().scale(CHEVRON)
            if (side.lengthSqr() < 1e-6) continue
            val back = tip.subtract(dir.scale(CHEVRON * 1.2))
            draw.line(back.add(side), tip, color, width, onTop = true)
            draw.line(back.subtract(side), tip, color, width, onTop = true)
        }
    }

    /** A relation's colour for its state: green while what it carries gets through, red when not, grey for events. */
    private fun stateColor(state: String?): Int? = when (state) {
        "active" -> ACTIVE
        "blocked" -> BLOCKED
        "idle" -> IDLE
        else -> null
    }

    /** [argb] halfway to white: chevrons stand out on their line. */
    private fun brighter(argb: Int): Int {
        fun mix(shift: Int) = (((argb shr shift) and 0xFF) + 255) / 2 shl shift
        return (argb and 0xFF000000.toInt()) or mix(16) or mix(8) or mix(0)
    }

    /**
     * The selected relation's card at [at]: what it links (its detail, else its label), then what its keys do, or what
     * to click while one of its ends moves.
     */
    private fun card(draw: OverlayDraw, r: Relation, at: Vec3) {
        val m = moving
        val keys = if (m != null) "Click its new ${m.what} · ${keyName(m.key)}: from a list · right click: as it is"
        else (listOf("Delete: unlink") + listOfNotNull(
            "R: reverse".takeIf { REVERSE in r.changes }, "I: new source".takeIf { FROM in r.changes }, "O: new destination".takeIf { TO in r.changes },
        )).joinToString(" · ")
        draw.label(at, listOf(r.title to Colors.WHITE, keys to Colors.GRAY), onTop = true, size = 0.6f)
    }

    private fun keyName(key: Int) = if (key == InputConstants.KEY_I) "I" else "O"

    private fun outline(draw: OverlayDraw, a: Aimed, color: Int) {
        a.box?.let { draw.box(Vec3(it.minX, it.minY, it.minZ), Vec3(it.maxX, it.maxY, it.maxZ), color, 2f, onTop = true) }
        for (i in 0 until a.line.size - 1) draw.line(a.line[i], a.line[i + 1], color, 3f, onTop = true)
    }

    // ---- Status -----------------------------------------------------------------------------------------------------

    private fun say(text: String) {
        note = text to System.currentTimeMillis()
    }

    private fun notes(): List<Note> = listOfNotNull(
        if (busy || inspecting != null) Note("Asking its plugin…", Note.Tone.BUSY) else note?.takeIf { System.currentTimeMillis() - it.second < NOTE_MS }?.let { Note.coloured(it.first) },
    )

    /** The objects tool's [status], in link mode or while picking a target this; else it with the relations added. */
    fun status(status: ToolStatus): ToolStatus {
        val aiming = aimed?.let { listOf("Aiming at ${it.name}") }.orEmpty()
        pick?.let { p ->
            return ToolStatus(
                Shown("Pick", p.what), details = aiming,
                hints = listOf(Hint(ToolStatus.LEFT, "this one"), Hint(ToolStatus.RIGHT, "back to the panel without one")), notes = notes(),
            )
        }
        moving?.let { m ->
            return ToolStatus(
                Shown("New ${m.what}", m.relation.title), details = ends(m.relation) + aiming,
                hints = listOf(
                    Hint(ToolStatus.LEFT, "its new ${m.what}: this one"), Hint(keyName(m.key), "pick it from a list"),
                    Hint(ToolStatus.RIGHT, "leave it as it is"),
                ),
                notes = notes(),
            )
        }
        val r = relation
        val asking = r != null && System.currentTimeMillis() - unlinkAsked <= CONFIRM_MS
        val prompt = if (asking) "Press Delete again to unlink them" else null
        if (linking) {
            val s = source?.let(::shown)
            val selected = r?.takeIf { s == null }
            return ToolStatus(
                if (selected != null) Shown("Link", selected.title, selected.provider) else Shown("Link", s?.name ?: "first the source"),
                details = (selected?.let(::ends) ?: emptyList()) + aiming,
                hovered = hovered?.takeIf { !it.same(r) }?.let { Shown("Relation", it.title, it.provider) },
                hints = (selected?.let(::changeHints) ?: emptyList()) + listOf(
                    Hint(ToolStatus.LEFT, if (s == null) "the source: this one; on a link: select it" else "link the source to this one"),
                    Hint(ToolStatus.RIGHT, if (s != null) "another source" else if (selected != null) "deselect the link" else "leave link mode"),
                    Hint("K", "leave link mode"),
                ),
                prompt = prompt,
                notes = notes(),
            )
        }
        if (!Panels.supported) return status
        var shown = status
        if (r != null && status.selected == null) shown = shown.copy(
            selected = Shown("Relation", r.title, r.provider),
            details = ends(r),
            hints = status.hints + changeHints(r),
        )
        hovered?.takeIf { status.hovered == null && !it.same(r) }?.let { shown = shown.copy(hovered = Shown("Relation", it.title, it.provider)) }
        chain()?.let { c -> shown = shown.copy(details = shown.details + "Its chain only (F): ${c.nodes.size - 1} things, ${c.links.size} links") }
        return shown.copy(
            hints = shown.hints + (if (ObjectsTool.selected == null) listOf(Hint("N", "the panel of what you aim at")) else emptyList()) + Hint("K", "link mode"),
            prompt = prompt ?: shown.prompt,
            notes = notes() + shown.notes,
        )
    }

    /** What [r] links, for the status card: its source and destination. */
    private fun ends(r: Relation): List<String> = listOfNotNull(r.from?.let { "From " + Targets.named(it) }, r.to?.let { "To " + Targets.named(it) })

    /** What can be done to [r]: unlink it, and the changes its plugin allows. */
    private fun changeHints(r: Relation): List<Hint> = listOfNotNull(
        Hint("Delete", "unlink them (press twice)"),
        Hint("R", "reverse it").takeIf { REVERSE in r.changes },
        Hint("I", "a new source").takeIf { FROM in r.changes },
        Hint("O", "a new destination").takeIf { TO in r.changes },
    )

    /** The changes a relation may allow (`relink`). */
    private const val REVERSE = "reverse"
    private const val FROM = "from"
    private const val TO = "to"
}
