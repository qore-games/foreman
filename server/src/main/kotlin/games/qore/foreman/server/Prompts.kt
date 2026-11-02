package games.qore.foreman.server

import games.qore.foreman.server.mcp.Prompt
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

/**
 * The MCP prompts: the plugin's own (`prompts/<name>.md` in the jar) and the server's (the `.md` files of
 * `plugins/foreman/prompts`, which replace the plugin's of the same name). A prompt file starts with a YAML header
 * between `---` lines (`description`, `arguments: [{name, description, required}]`), then the text, where `{{argument}}`
 * is replaced and `{{lessons}}` is what the server learned (see [Lessons]).
 */
class Prompts(private val plugin: Foreman, private val lessons: Lessons) {
    private val folder = File(plugin.dataFolder, "prompts")

    fun all(): List<Prompt> {
        val texts = LinkedHashMap<String, String>()
        // Foreman's own building prompts: with the world tools only. The dupe-test one needs no world tools.
        if (plugin.worldTools) DEFAULTS.forEach { name -> plugin.getResource("prompts/$name.md")?.use { texts[name] = it.readAllBytes().decodeToString() } }
        ALWAYS.forEach { name -> plugin.getResource("prompts/$name.md")?.use { texts[name] = it.readAllBytes().decodeToString() } }
        folder.listFiles { f -> f.extension == "md" }?.sortedBy { it.name }?.forEach { texts[it.nameWithoutExtension] = it.readText() }
        return texts.mapNotNull { (name, text) -> runCatching { parse(name, text) }.onFailure { plugin.logger.warning("Prompt $name: ${it.message}") }.getOrNull() }
    }

    private fun parse(name: String, text: String): Prompt {
        val parts = text.split(Regex("^---\\s*$", RegexOption.MULTILINE), limit = 3)
        val (header, body) = if (parts.size == 3 && parts[0].isBlank()) parts[1] to parts[2].trimStart() else "" to text
        val yaml = YamlConfiguration().apply { loadFromString(header) }
        val arguments = yaml.getMapList("arguments").map { m ->
            Prompt.Argument(m["name"].toString(), m["description"]?.toString() ?: "", m["required"] as? Boolean ?: false)
        }
        return Prompt(name, yaml.getString("description") ?: name, arguments, body.replace("{{lessons}}", lessons.text().ifEmpty { "(none yet)" }))
    }

    companion object {
        val DEFAULTS = listOf("build_place", "review_area", "repair_area")
        val ALWAYS = listOf("find_dupes")
    }
}

/**
 * What builders learned on this server (a mistake the checks missed, a rule of a zone, a tool trick), one per line in
 * `lessons.md`. Recorded with the `learn` tool, shown in the instructions and the prompts, so the next session starts
 * knowing them.
 */
class Lessons(private val file: File) {
    fun list(): List<String> = if (file.exists()) file.readLines().filter { it.startsWith("- ") }.map { it.removePrefix("- ") } else emptyList()

    fun text(): String = list().joinToString("\n") { "- $it" }

    @Synchronized
    fun add(lesson: String) {
        val line = lesson.replace('\n', ' ').trim()
        if (line.isEmpty() || line in list()) return
        file.parentFile.mkdirs()
        if (!file.exists()) file.writeText("# Lessons learned building on this server (edit freely: one `- ` line each)\n\n")
        file.appendText("- $line\n")
    }

    @Synchronized
    fun remove(index: Int): String? {
        val all = list()
        if (index !in all.indices) return null
        val removed = all[index]
        val lines = file.readLines()
        var seen = -1
        file.writeText(lines.filterNot { it.startsWith("- ") && ++seen == index }.joinToString("\n", postfix = "\n"))
        return removed
    }
}
