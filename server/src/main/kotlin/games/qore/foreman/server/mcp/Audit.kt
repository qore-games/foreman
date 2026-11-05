package games.qore.foreman.server.mcp

import com.google.gson.JsonObject
import games.qore.foreman.server.Secrets
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The tool calls, a JSON line each in [file] (`audit.log`, readable by the server's user only): when, how the call came
 * (`http`, `relay`), the player who made it (none: the server's token), the tool, its arguments cut short, whether it
 * worked and how long it took. Past [MAX_SIZE] the file moves to `audit.log.1` and a new one starts.
 */
class Audit(private val file: File) {
    @Synchronized
    fun call(via: String, caller: Caller?, tool: String, args: JsonObject, ok: Boolean, ms: Long) {
        val line = JsonObject().apply {
            addProperty("time", Instant.now().truncatedTo(ChronoUnit.MILLIS).toString())
            addProperty("via", via)
            caller?.let { addProperty("player", it.name); addProperty("uuid", it.id.toString()) }
            addProperty("tool", tool)
            addProperty("args", args.toString().let { if (it.length > MAX_ARGS) it.take(MAX_ARGS) + "…" else it })
            addProperty("ok", ok)
            addProperty("ms", ms)
        }
        runCatching {
            if (file.length() > MAX_SIZE) file.renameTo(File(file.path + ".1"))
            Secrets.append(file, "$line\n")
        }
    }

    companion object {
        const val MAX_SIZE = 10L shl 20
        private const val MAX_ARGS = 500
    }
}
