package games.qore.foreman.server.skills

import games.qore.foreman.server.Foreman
import games.qore.foreman.server.admin.PluginJars
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarFile

/**
 * A skill: how to do one kind of task (a plugin's commands and conventions, a workflow), in markdown. The AI sees every
 * skill's name and description in its instructions and reads the body ([read_skill]) when its task comes up: a skill
 * costs a line until it's needed, where a tool's definition is sent with every request.
 *
 * [plugin]: the plugin it is about; the skill is offered while that plugin is enabled. [source]: where it comes from.
 */
class Skill(val name: String, val description: String, val plugin: String?, val body: String, val source: String) {
    val active get() = plugin == null || Bukkit.getPluginManager().isPluginEnabled(plugin)

    companion object {
        /**
         * A skill file: front matter between `---` lines (`name`, `description`, `plugin`), then the body. [fallback]:
         * the name when the front matter has none (the file's). Each front matter line is `key: value`, split at the
         * first colon: a description may hold colons without quotes (people write these by hand).
         */
        fun parse(text: String, fallback: String, source: String): Skill {
            val lines = text.replace("\r\n", "\n").split('\n')
            if (lines.firstOrNull()?.trim() != "---") return Skill(fallback, "", null, text.trim(), source)
            val end = lines.drop(1).indexOfFirst { it.trim() == "---" }.takeIf { it >= 0 }?.plus(1) ?: return Skill(fallback, "", null, text.trim(), source)
            val front = lines.subList(1, end).filter { ':' in it }.associate { line ->
                line.substringBefore(':').trim() to line.substringAfter(':').trim().removeSurrounding("\"").removeSurrounding("'")
            }
            return Skill(
                front["name"]?.ifEmpty { null } ?: fallback,
                front["description"].orEmpty(),
                front["plugin"]?.ifEmpty { null },
                lines.drop(end + 1).joinToString("\n").trim(),
                source,
            )
        }

        fun valid(name: String) = name.matches(Regex("[a-z0-9][a-z0-9-]{1,63}"))

        /** The largest skill read, in bytes: the AI reads it whole. */
        const val MAX_SIZE = 64 * 1024
        /** A description is cut there in the index, which every session gets. */
        const val MAX_DESCRIPTION = 300
    }
}

/**
 * Every skill the AI can read, from (a later one wins on a name): Foreman's own (`skills/` in its jar), other plugins'
 * jars (`foreman/skills/<name>.md`: no code needed), plugins registering them through `ForemanTools.registerSkill`, and the
 * server's `plugins/Foreman/skills/` (server owners' and what the AI saved with `save_skill`).
 */
class Skills(private val plugin: Foreman) {
    val folder = File(plugin.dataFolder, "skills")
    private val bundled: List<Skill> = PluginJars.jar(plugin)?.let { read(it, "skills/", "Foreman") }.orEmpty()
    private val registered = ConcurrentHashMap<String, Skill>()
    /** Other plugins' jar skills by plugin name, with the jar's modification time they were read at. */
    private val jars = ConcurrentHashMap<String, Pair<Long, List<Skill>>>()

    /** The skills offered now (their plugin enabled), by name. */
    fun active(): List<Skill> {
        val all = LinkedHashMap<String, Skill>()
        bundled.forEach { all[it.name] = it }
        for (other in Bukkit.getPluginManager().plugins) if (other !== plugin && other.isEnabled) fromJar(other).forEach { all[it.name] = it }
        registered.values.forEach { all[it.name] = it }
        folder.listFiles { f -> f.extension == "md" && f.length() <= Skill.MAX_SIZE }?.sortedBy { it.name }?.forEach { file ->
            runCatching { Skill.parse(file.readText(), file.nameWithoutExtension, "server") }.getOrNull()?.let { all[it.name] = it }
        }
        return all.values.filter { it.active }.sortedBy { it.name }
    }

    fun get(name: String): Skill? = active().firstOrNull { it.name == name }

    fun register(owner: Plugin, name: String, description: String, markdown: String) {
        require(Skill.valid(name)) { "Skill names are lowercase letters, digits and -: '$name'" }
        registered[name]?.let { require(it.source == owner.name) { "Skill '$name' is already registered by ${it.source}" } }
        require(markdown.length <= Skill.MAX_SIZE) { "Skill '$name' is over ${Skill.MAX_SIZE / 1024} KB" }
        registered[name] = Skill(name, description, null, markdown.trim(), owner.name)
    }

    fun unregister(owner: Plugin, name: String) {
        registered.computeIfPresent(name) { _, skill -> skill.takeIf { it.source != owner.name } }
    }

    fun removeOwner(owner: String) {
        registered.values.removeIf { it.source == owner }
        jars.remove(owner)
    }

    /** Writes a server skill (`plugins/Foreman/skills/<name>.md`), replacing one of the same name. */
    fun save(name: String, description: String, about: String?, body: String): File {
        require(Skill.valid(name)) { "Skill names are lowercase letters, digits and -: '$name'" }
        folder.mkdirs()
        val front = buildString {
            appendLine("---")
            appendLine("name: $name")
            appendLine("description: ${description.replace("\n", " ").trim()}")
            about?.let { appendLine("plugin: $it") }
            appendLine("---")
        }
        return File(folder, "$name.md").apply { writeText(front + "\n" + body.trim() + "\n") }
    }

    /** One line per skill, for the AI's instructions. */
    fun index(): String {
        val skills = active()
        if (skills.isEmpty()) return ""
        return "Skills: how to do a kind of task here (a plugin's commands, a workflow). When a request matches one, read it " +
            "with read_skill before starting.\n" + skills.joinToString("\n") { s ->
            "- ${s.name}" + (s.plugin?.let { " ($it)" } ?: "") + ": " + s.description.take(Skill.MAX_DESCRIPTION).ifEmpty { "(no description)" }
        }
    }

    private fun fromJar(other: Plugin): List<Skill> {
        val path = PluginJars.jar(other) ?: return emptyList()
        val modified = runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(0L)
        jars[other.name]?.let { (at, skills) -> if (at == modified) return skills }
        val skills = read(path, "foreman/skills/", other.name).map { s -> if (s.plugin == null) Skill(s.name, s.description, other.name, s.body, s.source) else s }
        jars[other.name] = modified to skills
        return skills
    }

    private fun read(jar: Path, prefix: String, source: String): List<Skill> = runCatching {
        JarFile(jar.toFile()).use { file ->
            file.entries().asSequence().filter { !it.isDirectory && it.name.startsWith(prefix) && it.name.endsWith(".md") && it.size <= Skill.MAX_SIZE }.mapNotNull { entry ->
                val name = entry.name.removePrefix(prefix).removeSuffix(".md").substringAfterLast('/')
                runCatching { Skill.parse(file.getInputStream(entry).use { it.readAllBytes().decodeToString() }, name, source) }
                    .onFailure { plugin.logger.warning("Skill ${entry.name} in ${jar.fileName}: ${it.message}") }.getOrNull()
            }.toList()
        }
    }.getOrDefault(emptyList())
}
