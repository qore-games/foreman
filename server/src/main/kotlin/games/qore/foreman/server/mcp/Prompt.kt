package games.qore.foreman.server.mcp

/** An MCP prompt: a message template the client fills with [arguments] (`{{name}}` in [body]). */
class Prompt(val name: String, val description: String, val arguments: List<Argument>, val body: String) {
    class Argument(val name: String, val description: String, val required: Boolean)

    fun render(values: Map<String, String>): String {
        arguments.filter { it.required && values[it.name].isNullOrBlank() }.takeIf { it.isNotEmpty() }?.let { missing ->
            throw ToolError("Missing arguments: ${missing.joinToString(", ") { it.name }}")
        }
        return Regex("\\{\\{(\\w+)}}").replace(body) { m -> values[m.groupValues[1]].orEmpty() }
    }
}
