package games.qore.foreman.server.tools

import games.qore.foreman.api.ForemanTool
import games.qore.foreman.server.Foreman
import games.qore.foreman.server.mcp.Tool
import games.qore.foreman.server.mcp.ToolError
import games.qore.foreman.server.mcp.ToolResult
import games.qore.foreman.server.mcp.schema

/** The skills ([games.qore.foreman.server.skills.Skills]): reading one, and saving the server's own. */
object SkillTools {
    fun all(plugin: Foreman) = listOf(
        Tool(
            ForemanTool.READ_SKILL,
            "Reads a skill: how to do a kind of task on this server (a plugin's commands and conventions, a workflow). " +
                "Your instructions list them with what each is for: read the one that matches a request before starting " +
                "it. Without a name, lists them with where each comes from.",
            schema { string("name", "The skill's name", required = false) },
        ) { args ->
            val name = args.string("name", null)
            if (name == null) {
                val all = plugin.skills.active()
                ToolResult.text(if (all.isEmpty()) "No skill on this server." else all.joinToString("\n") { s ->
                    "- ${s.name} (${s.source}${s.plugin?.let { p -> ", for $p" }.orEmpty()}): ${s.description}"
                })
            } else {
                val skill = plugin.skills.get(name)
                    ?: throw ToolError("No skill '$name'. Skills: ${plugin.skills.active().joinToString { it.name }.ifEmpty { "none" }}")
                ToolResult.text("# ${skill.name}\n${skill.description}\n\n${skill.body}")
            }
        },

        Tool(
            ForemanTool.SAVE_SKILL,
            "Saves a skill for every later session on this server (plugins/Foreman/skills/<name>.md, replacing one of the " +
                "same name): how to do a kind of task here, when the user asks you to remember it or you worked out a " +
                "workflow worth keeping. The description says when to read it; the body is the how: commands, steps, " +
                "pitfalls, in markdown. For a one-sentence rule, use learn instead.",
            schema {
                string("name", "Lowercase letters, digits and -, e.g. arena-setup")
                string("description", "One line: what it's for and when to read it")
                string("body", "The skill, in markdown")
                string("plugin", "The plugin it's about: the skill is offered while that plugin runs", required = false)
            },
        ) { args ->
            ServerTools.admin(args)
            val file = try {
                plugin.skills.save(args.string("name"), args.string("description"), args.string("plugin", null), args.string("body"))
            } catch (e: IllegalArgumentException) {
                throw ToolError(e.message ?: "Invalid skill")
            }
            ToolResult.text("Saved ${file.path}: later sessions see it in their skill list.")
        },
    )
}
