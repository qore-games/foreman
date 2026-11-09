package games.qore.foreman.client.input

import com.mojang.blaze3d.platform.InputConstants
import games.qore.foreman.client.editor.EditorKey
import games.qore.foreman.client.editor.EditorTool

/** An editor tool that puts something in the world: R turns it, Enter places it. */
interface Placing : EditorTool {
    /** Turns it [quarters] quarter turns clockwise seen from above. */
    fun turn(quarters: Int)

    /** Places it where it is shown. */
    fun place()

    /** The keys every placing tool has, for its [keys]. */
    fun placingKeys() = listOf(
        EditorKey(InputConstants.KEY_R, "Turn a quarter") { turn(1) },
        EditorKey(InputConstants.KEY_RETURN, "Place it") { place() },
    )
}
