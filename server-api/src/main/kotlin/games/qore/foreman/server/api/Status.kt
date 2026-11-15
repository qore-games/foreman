package games.qore.foreman.server.api

/**
 * How a thing is doing, read at a glance: its [level] (the colour builders see: ok green, off grey, blocked red, problem
 * orange), a [word] or two (`working`, `off`, `no power`) and what it means in a sentence ([detail], optional). On a
 * [WorldObject] it is the badge the objects tool draws over it (the word); on a [Panel] its status line, above every tab
 * (`Blocked: the switch is off`). Immutable: each call returns a changed copy.
 *
 * ```java
 * Status.blocked("No power").detail("the switch is off");
 * ```
 */
class Status private constructor(val level: Level, val word: String, val detail: String?) {
    enum class Level {
        /** It does what it is for (green). */
        OK,

        /** Turned off, on purpose or by its own state (grey). */
        OFF,

        /** On, but something it needs isn't there (red). */
        BLOCKED,

        /** Its setup is wrong: a link to nothing, a value it doesn't know (orange). */
        PROBLEM;

        val key: String get() = name.lowercase()
    }

    fun detail(detail: String?) = Status(level, word, detail?.takeIf { it.isNotBlank() })

    /** The status line: `Blocked: the switch is off`, or the word alone. */
    fun text(): String = word + (detail?.let { ": $it" } ?: "")

    override fun toString() = text()

    companion object {
        @JvmStatic fun of(level: Level, word: String): Status {
            require(word.isNotBlank()) { "A status needs a word" }
            return Status(level, word.trim(), null)
        }

        @JvmStatic fun ok(word: String) = of(Level.OK, word)

        @JvmStatic fun off(word: String) = of(Level.OFF, word)

        @JvmStatic fun blocked(word: String) = of(Level.BLOCKED, word)

        @JvmStatic fun problem(word: String) = of(Level.PROBLEM, word)
    }
}
