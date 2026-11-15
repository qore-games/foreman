package games.qore.foreman.server.api

import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector

/**
 * Your plugin's panels in Foreman's editor, and the relations between the things it manages: a generator's switch and
 * fuel beside the generator, the lamps it powers linked to it by arrows. Builders open a panel by aiming at something
 * (an entity, a block, a [world object][ForemanObjects]); Foreman asks the providers which of them serves it, draws the
 * [Panel] it describes, keeps it live while it is open and sends what the builder changes back. The same panels are tools
 * for the AI (`panel_get`, `panel_edit`, `panel_action`, `list_relations`, `link_targets`, `unlink`, `relink`). Nothing
 * runs while no panel is open and nobody shows relations. Get it as a Bukkit service, there whenever Foreman runs with its `panels`
 * feature (the world tools aren't needed):
 *
 * ```java
 * ForemanPanels panels = Bukkit.getServicesManager().load(ForemanPanels.class); // null without Foreman
 * if (panels != null) panels.register(this, new GeneratorPanels(this));
 * ```
 *
 * A plugin's providers go when it disables.
 */
interface ForemanPanels {
    /** Adds [provider] for [owner], last, or in place of its own of the same id; another plugin's id is refused. */
    fun register(owner: Plugin, provider: PanelProvider)

    fun unregister(owner: Plugin, id: String)

    /** Every provider, in the order they are asked. */
    fun providers(): List<PanelProvider>

    /**
     * Tells Foreman what [target]'s panel or relations show changed on your side: open panels of it are described again
     * at the next tick instead of their next poll, and relations near it asked again. Any thread.
     */
    fun refresh(target: PanelTarget)
}

/**
 * Your side of the panels: which targets you serve ([resolve]), what their panel shows ([describe]) and does ([edit],
 * [action]), and optionally what links your things ([relations], [link], [unlink], [relink]). Called on the server
 * thread. An exception is logged once per provider and method and fails that call only.
 */
@JvmSuppressWildcards
interface PanelProvider {
    /** Its id, `ns:name`. */
    fun id(): String

    /** What builders read: `Generators`. */
    fun label(): String

    /**
     * The target whose panel serves [aimed] (what a builder aims at, or a target a tool names), or null when it is none
     * of yours. Map a part to its whole (a hitbox, a cell block to the root entity); give your own targets back as they
     * are: the tools name a panel by the target you returned. Keep it cheap: every provider is asked in turn.
     */
    fun resolve(aimed: PanelTarget): PanelTarget?

    /**
     * The panel of [PanelContext.target], or null when there is none any more (it was removed). Called when a panel opens,
     * then twice a second while it is open (or at once after [ForemanPanels.refresh]): keep it cheap. Foreman sends it
     * only when it changed.
     */
    fun describe(ctx: PanelContext): Panel?

    /**
     * What [PanelContext.target] is called, for a builder aiming at it (link mode, before its panel opens): `Generator
     * 'north'`. Asked when their aim rests on it, again every few seconds while it stays: keep it cheap. Null: no name.
     * The default is [describe]'s title.
     */
    fun name(ctx: PanelContext): String? = describe(ctx)?.title

    /**
     * Sets the field [key] of the panel to [value], checked against the field first (type, choices, range, required): a
     * String (text, choice, colour `#rrggbb`, area), Integer (int), Double (number, yaw), Boolean, [PanelTarget] (target,
     * resolved), or null (cleared).
     */
    fun edit(ctx: PanelContext, key: String, value: Any?): PanelResult = PanelResult.refused("Nothing to change here")

    /**
     * Runs the button [id] of the panel, with its [args] (and `item`, an item's id, for a list item's button). Only ids of
     * buttons the panel shows now reach it, never a disabled one's.
     */
    fun action(ctx: PanelContext, id: String, args: Map<String, Any?>): PanelResult = PanelResult.refused("No such action")

    /**
     * What links your things that reach into [box] of [world]: drawn as arrows near builders who show relations (asked
     * once a second while they do: keep it cheap) and listed by `list_relations`.
     */
    fun relations(world: World, box: BoundingBox): List<Relation> = emptyList()

    /**
     * Links [from] (a target of yours, [PanelContext.target]) to [to] (anything, resolved by the providers). [choice] is
     * null at first; return [LinkResult.choose] to ask the builder, and the [LinkResult.Option] id they pick comes back
     * as [choice] (as many rounds as you need).
     */
    fun link(ctx: PanelContext, from: PanelTarget, to: PanelTarget, choice: String?): LinkResult = LinkResult.refused("${label()} links nothing")

    /** Removes your relation [relationId] ([Relation.id]); [PanelContext.target] is the end it was named by. */
    fun unlink(ctx: PanelContext, relationId: String): PanelResult = PanelResult.refused("${label()} unlinks nothing")

    /**
     * Changes your relation [relationId] as a builder asks, one of the [Relation.changes] it offers: [Relation.Change.REVERSE]
     * (the other way: [to] is null), [Relation.Change.FROM] or [Relation.Change.TO] (that end moves to [to], resolved by
     * the providers). Answer as [link] does, [choice] being the builder's pick after [LinkResult.choose]. Done: the new
     * link is made and the old one removed, by you. [LinkResult.build]: the builder finishes a condition in the panel
     * [LinkResult.on] names, and Foreman removes the old relation ([unlink]) once they add it. Refused: nothing changed.
     */
    fun relink(ctx: PanelContext, relationId: String, change: Relation.Change, to: PanelTarget?, choice: String?): LinkResult =
        LinkResult.refused("${label()} doesn't change links: unlink it and link again")

    /** What the condition builder of the [ConditionsRow] [row] of the panel offers to read, in the order to list them. */
    fun subjects(ctx: PanelContext, row: String): List<ConditionSubject> = emptyList()

    /**
     * The facts of the subject [subject] (one of [subjects]' ids) or of [target], a thing the builder picked in the world
     * ([ConditionsRow.pick]): the subject as conditions will name it, and its facts. Null: nothing to read there.
     */
    fun facts(ctx: PanelContext, row: String, subject: String?, target: PanelTarget?): ConditionFacts? = null

    /** What [condition] reads now for the [ConditionsRow] [row]: how you write it, whether it holds, or why it can't be read. */
    fun check(ctx: PanelContext, row: String, condition: Condition): ConditionCheck = ConditionCheck.error("${label()} checks no conditions")
}

/**
 * Who asks what of a provider: the [target] (resolved), the acting [player] (null for the console, or an AI with the
 * server's token), and [callerName] (the player's name, else `console`).
 */
class PanelContext(val target: PanelTarget, val player: Player?, val callerName: String)

/**
 * How an edit, an action or an unlink went: [isOk] with an optional [message] for the builder, or refused ([message]
 * says why, or [errors] per field key, shown under the fields). With [panel], the panel as it is now (else Foreman
 * describes it again).
 */
class PanelResult private constructor(val isOk: Boolean, val message: String?, val errors: Map<String, String>, val panel: Panel?) {
    /** This result, with the panel as it is now. */
    fun panel(panel: Panel) = PanelResult(isOk, message, errors, panel)

    companion object {
        @JvmStatic fun ok() = PanelResult(true, null, emptyMap(), null)

        /** Done, with [message] for the builder (`Started`). */
        @JvmStatic fun ok(message: String) = PanelResult(true, message, emptyMap(), null)

        /** Not done: [message] says why (`Out of fuel`). */
        @JvmStatic fun refused(message: String) = PanelResult(false, message, emptyMap(), null)

        /** Not done: what is wrong with each field, by key (`fuel` → `at most 20`). */
        @JvmStatic fun errors(errors: Map<String, String>) = PanelResult(false, null, LinkedHashMap(errors), null)
    }
}

/**
 * A link between two things, drawn as an arrow from [fromPoint] to [toPoint] (where it starts and ends: usually the two
 * things' centres; without them, Foreman takes the centres of the entities, blocks and objects it names), with a [label]
 * and a [color] (`0xRRGGBB`), or a [state] saying whether what it carries gets through. [id] is yours, unique among your
 * relations: what [PanelProvider.unlink] and [PanelProvider.relink] get. [detail] is what a builder reads once it is
 * selected (what reads what), [changes] what they may change besides unlinking it.
 */
class Relation private constructor(
    val id: String,
    val from: PanelTarget,
    val to: PanelTarget,
    val fromPoint: Vector?,
    val toPoint: Vector?,
    val label: String?,
    val color: Int?,
    val state: State?,
    val detail: String? = null,
    val changes: Set<Change> = emptySet(),
) {
    /** What a builder may change of a relation with [PanelProvider.relink]: reverse it, move its from end, its to end. */
    enum class Change {
        REVERSE,
        FROM,
        TO;

        val key: String get() = name.lowercase()
    }

    /** Whether what a relation carries gets through now: it colours the arrow over its [color]. */
    enum class State {
        /** It does (green, its chevrons flowing). */
        ACTIVE,

        /** It doesn't (red, still): what reads it isn't met. */
        BLOCKED,

        /** Nothing flows through it all the time (grey, still): an event sent now and then. */
        IDLE;

        val key: String get() = name.lowercase()
    }

    fun points(from: Vector, to: Vector) = Relation(id, this.from, this.to, from.clone(), to.clone(), label, color, state, detail, changes)

    fun label(label: String) = Relation(id, from, to, fromPoint, toPoint, label, color, state, detail, changes)

    fun color(rgb: Int) = Relation(id, from, to, fromPoint, toPoint, label, rgb and 0xFFFFFF, state, detail, changes)

    fun state(state: State?) = Relation(id, from, to, fromPoint, toPoint, label, color, state, detail, changes)

    /** What it says once selected, what reads what: `lamp.power ← hall_switch` (default: its [label]). */
    fun detail(detail: String) = Relation(id, from, to, fromPoint, toPoint, label, color, state, detail, changes)

    /** What builders may change of it with [PanelProvider.relink] (default: nothing but unlinking it). */
    fun changes(vararg changes: Change) = Relation(id, from, to, fromPoint, toPoint, label, color, state, detail, changes.toSet())

    companion object {
        @JvmStatic fun of(id: String, from: PanelTarget, to: PanelTarget): Relation {
            require(id.isNotBlank()) { "A relation needs an id" }
            return Relation(id, from, to, null, null, null, null, null)
        }
    }
}

/**
 * What [PanelProvider.link] did: [Status.DONE], [Status.REFUSED] ([message] says why), [Status.CHOOSE]: the builder
 * picks one of [options] ([message] is the question) and `link` is called again with its id, or [Status.BUILD]: no link
 * fits as it is, the source's panel (or [target]'s) opens with the condition builder of its [ConditionsRow] [row] filled
 * in with [condition], for the builder to finish and add.
 */
class LinkResult private constructor(
    val status: Status,
    val message: String?,
    val options: List<Option>,
    val row: String? = null,
    val condition: Condition? = null,
    val target: PanelTarget? = null,
) {
    /**
     * A [build]'s builder opens in [target]'s panel instead of the source's: what [PanelProvider.relink] answers, where
     * the thing that takes the condition is none Foreman knows.
     */
    fun on(target: PanelTarget) = LinkResult(status, message, options, row, condition, target)

    enum class Status { DONE, REFUSED, CHOOSE, BUILD }

    /** A choice offered to the builder: [id] comes back to [PanelProvider.link], [label] is what they read, a [hint] on hover. */
    class Option private constructor(val id: String, val label: String, val hint: String?) {
        fun hint(hint: String) = Option(id, label, hint)

        companion object {
            @JvmStatic fun of(id: String, label: String): Option {
                require(id.isNotBlank()) { "An option needs an id" }
                return Option(id, label, null)
            }
        }
    }

    companion object {
        @JvmStatic fun done() = LinkResult(Status.DONE, null, emptyList())

        /** Linked, with [message] for the builder (`Linked to output 2`). */
        @JvmStatic fun done(message: String) = LinkResult(Status.DONE, message, emptyList())

        @JvmStatic fun refused(message: String) = LinkResult(Status.REFUSED, message, emptyList())

        /** Asks [question] (`Which output?`): the builder picks one of [options]. */
        @JvmStatic fun choose(question: String, options: List<Option>): LinkResult {
            require(options.isNotEmpty()) { "A choice needs options" }
            return LinkResult(Status.CHOOSE, question, options.toList())
        }

        @JvmStatic fun choose(question: String, vararg options: Option) = choose(question, options.toList())

        /** Opens the source's condition builder of [row] filled in with [condition] (its subject: what was clicked second). */
        @JvmStatic @JvmOverloads fun build(row: String, condition: Condition, message: String? = null) =
            LinkResult(Status.BUILD, message, emptyList(), row, condition)
    }
}
