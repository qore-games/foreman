package games.qore.foreman.server.api

import org.bukkit.entity.Player

/**
 * A key your plugin adds to the editor's objects tool, for builders working on your objects: a switch (a preview of your
 * machines running, a debug view) or a one-off action. Its [id] (`ns:name`), the [label] the tool's keys list reads, the
 * [key] it asks for (a letter; the mod takes another one when the tool already uses it), a [description] on hover, and
 * your [handler]. Added with [ForemanObjects.addAction]:
 *
 * ```java
 * objects.addAction(this, ObjectAction.of("rides:test_run").label("Run the rides").key('R')
 *     .description("Runs every ride of this world, for everyone in it")
 *     .handler(new TestRun(this))
 *     .build());
 * ```
 */
class ObjectAction private constructor(
    val id: String,
    val label: String,
    val key: Char?,
    val description: String,
    val handler: ObjectActionHandler,
) {
    /** Makes an [ObjectAction]: [handler] (required), then [build]. */
    class Builder internal constructor(private val id: String) {
        private var label = ""
        private var key: Char? = null
        private var description = ""
        private var handler: ObjectActionHandler? = null

        fun label(label: String) = apply { this.label = label }

        /** The key it asks for: a letter, A to Z. */
        fun key(key: Char) = apply {
            require(key.uppercaseChar() in 'A'..'Z') { "An action's key is a letter, not '$key'" }
            this.key = key.uppercaseChar()
        }

        fun description(description: String) = apply { this.description = description }

        fun handler(handler: ObjectActionHandler) = apply { this.handler = handler }

        fun build(): ObjectAction {
            require(id.matches(LayoutType.ID)) { "Action ids are namespaced, lowercase: 'ns:name', not '$id'" }
            val handler = requireNotNull(handler) { "$id has no handler" }
            val name = label.ifEmpty { id.substringAfter(':').replace('_', ' ').replaceFirstChar { it.uppercase() } }
            return ObjectAction(id, name, key, description, handler)
        }
    }

    companion object {
        @JvmStatic fun of(id: String) = Builder(id)
    }
}

/** Your side of an [ObjectAction], called on the main thread for the builder pressing its key. */
@JvmSuppressWildcards
interface ObjectActionHandler {
    /**
     * Whether it is on for [player] now (a switch: the mod shows it on or off), null when it isn't a switch. Asked twice
     * a second for each builder with the objects tool on, and right after it ran: keep it cheap.
     */
    fun state(player: Player): Boolean? = null

    /** Whether it is offered to [player] here (a preview isn't where your machines already run). Asked with [state]. */
    fun available(player: Player): Boolean = true

    /** Runs it for [player] (a switch: flips it): how it went, which the builder reads. */
    fun run(player: Player): PanelResult
}
