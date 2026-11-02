package games.qore.foreman.server.tools

import games.qore.foreman.api.ForemanTool
import games.qore.foreman.server.Foreman
import games.qore.foreman.server.mcp.Tool
import games.qore.foreman.server.mcp.ToolError
import games.qore.foreman.server.mcp.ToolResult
import games.qore.foreman.server.mcp.schema

/** The server's memory of how to build well here: see [games.qore.foreman.server.Lessons]. */
object LessonTools {
    private const val MAX_LENGTH = 300
    private const val MAX_LESSONS = 100

    fun all(plugin: Foreman) = listOf(
        Tool(
            ForemanTool.LEARN,
            "Records a lesson for every later session on this server: a mistake you made that the checks did not " +
                "catch, something the user corrected, a rule of a zone, a tool trick that worked. One short, general " +
                "sentence (\"Shop counters need a 2-wide gap to walk behind\"), not a log of what you did.",
            schema { string("lesson", "The lesson, one sentence") },
        ) { args ->
            // Every later session reads them, the console's too: only admins write them.
            ServerTools.admin(args)
            val lesson = args.string("lesson")
            if (lesson.length > MAX_LENGTH) throw ToolError("A lesson is one sentence (at most $MAX_LENGTH characters)")
            if (plugin.lessons.list().size >= MAX_LESSONS) throw ToolError("$MAX_LESSONS lessons already: remove outdated ones with `lessons`")
            plugin.lessons.add(lesson)
            ToolResult.text("Learned. ${plugin.lessons.list().size} lessons on this server.")
        },

        Tool(
            ForemanTool.LESSONS,
            "Lists the lessons learned on this server (numbered), or removes one that is wrong or outdated.",
            schema { int("remove", "Number of the lesson to remove", required = false) },
        ) { args ->
            if (args.has("remove")) {
                ServerTools.admin(args)
                val removed = plugin.lessons.remove(args.int("remove") - 1) ?: throw ToolError("No lesson ${args.int("remove")}")
                ToolResult.text("Removed: $removed")
            } else {
                val all = plugin.lessons.list()
                ToolResult.text(if (all.isEmpty()) "No lessons yet" else all.mapIndexed { i, l -> "${i + 1}. $l" }.joinToString("\n"))
            }
        },
    )
}
