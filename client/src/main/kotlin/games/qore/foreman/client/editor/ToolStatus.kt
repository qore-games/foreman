package games.qore.foreman.client.editor

/**
 * Something a tool shows on its status card: its [kind] (`Asset area`, `Pasted copy`, `Display`, `Point`), its [name]
 * (`cottage`, `loot:container`) and what tells it apart ([detail]: `of asset cottage`, `12×5×9`).
 */
data class Shown(val kind: String, val name: String = "", val detail: String = "")

/** What an input does now: [input] as the card shows it (`Left click`, `Right click`, `Scroll`, `Drag`, a key), what it [does]. */
data class Hint(val input: String, val does: String)

/** A line of state under the hints: saving, the last change's outcome, a warning. */
data class Note(val text: String, val tone: Tone = Tone.INFO) {
    enum class Tone { INFO, BUSY, OK, ERROR }

    companion object {
        private val CODE = Regex("§.")

        /** An outcome kept with its § colour first (§a done, §c failed, §e waiting), as a note of that tone. */
        fun coloured(text: String): Note {
            val tone = when (text.getOrNull(1).takeIf { text.startsWith('§') }) {
                'a' -> Tone.OK
                'c' -> Tone.ERROR
                'e', '6' -> Tone.BUSY
                else -> Tone.INFO
            }
            return Note(text.replace(CODE, ""), tone)
        }
    }
}

/**
 * What a tool says on the HUD, drawn in one place for every tool (the [StatusCard], top left): what is [selected]
 * (else [empty]: what to do first) and its [details], what the crosshair is on besides it ([hovered]), what the mouse
 * and the tool's keys do now ([hints]), a question waiting for an answer or the click that confirms ([prompt],
 * highlighted; the hints say how to answer), and lines of state ([notes]).
 */
data class ToolStatus(
    val selected: Shown? = null,
    val empty: String? = null,
    val details: List<String> = emptyList(),
    val hovered: Shown? = null,
    val hints: List<Hint> = emptyList(),
    val prompt: String? = null,
    val notes: List<Note> = emptyList(),
) {
    companion object {
        const val LEFT = "Left click"
        const val RIGHT = "Right click"
        const val SCROLL = "Scroll"
        const val DRAG = "Drag"
    }
}
