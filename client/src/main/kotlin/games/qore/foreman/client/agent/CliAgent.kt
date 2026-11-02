package games.qore.foreman.client.agent

import games.qore.foreman.client.config.Config
import games.qore.foreman.client.util.JSON
import games.qore.foreman.client.util.writePrivateFile
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * An agent run by a command line program printing one JSON event per line, one instance per run (runs are parallel).
 * The process runs in `config/foreman/agent/` (no project files around) unless the agent picks a folder ([directory]),
 * and is killed on cancel.
 */
abstract class CliAgent(private val name: String) : Agent {
    private val logger = LoggerFactory.getLogger("foreman")
    protected val workDir: Path = Config.dir.resolve("agent")

    /** The command line; [promptViaStdin] decides whether the prompt is written to stdin or is part of it. */
    protected abstract fun command(request: AgentRequest): List<String>
    protected open val promptViaStdin = false
    protected open fun environment(request: AgentRequest, env: MutableMap<String, String>) {}
    /** Where the process runs (default the agent folder, with no project files around). */
    protected open fun directory(request: AgentRequest): String? = null

    /** Handles one JSON event; returns an error message when the event reports a failure. */
    protected abstract fun handle(event: JsonObject, listener: AgentListener): String?

    /** Called once the run ended without error (the agent's last words: what it used). */
    protected open fun finished(listener: AgentListener) {}

    /** The files [writePrivate] wrote for this run: deleted when it ends. */
    private val written = mutableListOf<Path>()

    /** Writes a file of the agent folder only this user can read (it holds the MCP token), for this run. */
    protected fun writePrivate(file: String, content: String): Path = writePrivateFile(workDir.resolve(file), content).also { written.add(it) }

    /** The configured command split on spaces (`npx opencode-ai` works). */
    protected fun executable(configured: String, default: String) = configured.ifBlank { default }.trim().split(Regex("\\s+"))

    override fun run(request: AgentRequest, listener: AgentListener, cancel: CancelToken) {
        try {
            start(request, listener, cancel)
        } finally {
            written.forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }

    private fun start(request: AgentRequest, listener: AgentListener, cancel: CancelToken) {
        Files.createDirectories(workDir)
        val command = command(request)
        val dir = directory(request) ?: workDir.toString()
        val env = HashMap<String, String>()
        environment(request, env)
        val builder = if (FLATPAK) {
            // A sandboxed launcher (Prism from Flathub) can't see the host's programs: run it on the host, through a
            // login shell so the user's PATH (~/.local/bin…) is there. The config folder has the same path on both sides.
            ProcessBuilder(
                listOf("flatpak-spawn", "--host", "--directory=$dir") + env.map { (k, v) -> "--env=$k=$v" } +
                    listOf("bash", "-lc", "PATH=\"$HOST_PATH:\$PATH\"; exec \"\$@\"", "agent") + command,
            )
        } else ProcessBuilder(command).also { it.environment().putAll(env) }
        // Inside the sandbox a host folder may not exist: flatpak-spawn takes the directory itself.
        if (!FLATPAK || dir == workDir.toString()) {
            if (!Files.isDirectory(Path.of(dir))) throw AgentException("The folder $dir doesn't exist: remove it from the chat's workspace")
            builder.directory(java.io.File(dir))
        }
        val process = try {
            builder.start()
        } catch (e: java.io.IOException) {
            throw AgentException("Can't start $name (`${command.first()}`): ${e.message}. Set its command in the settings.")
        }
        cancel.onCancel {
            process.descendants().forEach { it.destroy() }
            process.destroy()
        }

        val stderr = StringBuilder()
        val errThread = Thread {
            process.errorStream.bufferedReader().forEachLine { synchronized(stderr) { stderr.appendLine(it) } }
        }.apply { isDaemon = true; start() }

        process.outputStream.use { if (promptViaStdin) it.write(request.prompt.encodeToByteArray()) }

        var error: String? = null
        process.inputStream.bufferedReader().forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val event = try {
                JSON.parseToJsonElement(line) as? JsonObject
            } catch (_: Exception) {
                null
            }
            if (event == null) listener.info(line) else try {
                handle(event, listener)?.let { error = it }
            } catch (e: Exception) {
                logger.warn("Could not handle {} event {}", name, line, e)
            }
        }
        process.waitFor(10, TimeUnit.SECONDS)
        errThread.join(1000)
        if (cancel.cancelled) return
        error?.let { throw AgentException(it) }
        val exit = if (process.isAlive) 0 else process.exitValue()
        if (exit != 0) {
            val err = synchronized(stderr) { stderr.toString().trim().takeLast(600) }
            if (FLATPAK && (exit == 127 || "org.freedesktop.Flatpak" in err || "ServiceUnknown" in err || "AccessDenied" in err)) throw AgentException(
                "Can't start $name on the host from the Flatpak launcher ($err). Allow it once, then restart the launcher: " +
                    "flatpak override --user --talk-name=org.freedesktop.Flatpak ${System.getenv("FLATPAK_ID") ?: "org.prismlauncher.PrismLauncher"}" +
                    if (exit == 127) " (or `${command.first()}` isn't on the host's PATH: set its full path in the settings)" else "",
            )
            throw AgentException("$name exited with $exit: $err")
        }
        finished(listener)
    }

    private companion object {
        /** Where agent CLIs install themselves when a login shell's PATH doesn't have them (zsh-only setups). */
        const val HOST_PATH = "\$HOME/.local/bin:\$HOME/.opencode/bin:\$HOME/.bun/bin:\$HOME/.npm-global/bin:\$HOME/.claude/local"

        /** Running inside a Flatpak sandbox. */
        val FLATPAK = Files.exists(Path.of("/.flatpak-info")) || System.getenv("FLATPAK_ID") != null
    }
}
