package games.qore.foreman.client.mcp

import games.qore.foreman.client.mixin.GameRendererAccessor
import games.qore.foreman.client.packets.PacketInspector
import games.qore.foreman.client.reload.ReloadTimer
import games.qore.foreman.client.util.bool
import games.qore.foreman.client.util.get
import games.qore.foreman.client.util.int
import games.qore.foreman.client.util.str
import net.minecraft.client.Minecraft
import net.minecraft.server.packs.repository.PackCompatibility
import org.apache.logging.log4j.Level
import java.util.concurrent.TimeUnit

/** What the client made of the resource packs: the packs loaded, and the post effects applied or failed. */
object ResourceTools {
    fun init() {
        ClientMcp.register(ClientTool(
            "client_post_effects",
            "The post effects (full-screen shaders: the server's /posteffect, the client's own when spectating a creeper…) " +
                "requested now, the ones applied, and the ones that failed to load (skipped until the next resource reload) " +
                "with the log lines about them. `available`: the post effect ids the loaded packs declare (with `contains`).",
            ClientTools.schema("available" to "boolean: list the declared post effects too (default false)",
                "contains" to "string: for available, ids containing this"),
            acts = { false },
        ) { _, args ->
            val (text, failed) = ClientTools.onClient {
                val minecraft = Minecraft.getInstance()
                val renderer = minecraft.gameRenderer
                val failed = (renderer as GameRendererAccessor).`foreman$failedPostEffects`().map { it.toString() }
                val text = buildString {
                    appendLine("Requested: " + renderer.requestedPostEffects.joinToString(", ").ifEmpty { "none" } + " (minecraft:end_of_frame is always there)")
                    minecraft.player?.activePostEffects?.takeIf { it.isNotEmpty() }?.let { appendLine("From the server: " + it.joinToString(", ")) }
                    renderer.spectatedEntityPostEffect()?.let { appendLine("Spectated entity's: $it") }
                    appendLine("Applied: " + renderer.appliedPostEffects.joinToString(", ").ifEmpty { "none" })
                    append("Failed: " + failed.joinToString(", ").ifEmpty { "none" })
                    if (args["available"].bool() == true) {
                        val contains = args["contains"].str()
                        val ids = minecraft.shaderManager.availablePostEffects.map { it.toString() }.filter { contains == null || it.contains(contains, true) }.sorted().toList()
                        append("\n${ids.size} declared" + (contains?.let { " containing \"$it\"" } ?: "") + ": " + ids.joinToString(", "))
                    }
                }
                text to failed
            }
            val why = failed.joinToString("\n") { id ->
                ClientLog.matching(Level.WARN, id).takeLast(3).joinToString("\n") { "  ${ClientLog.time(it.time)} ${it.level} [${it.logger}] ${PacketInspector.cut(it.text.replace("\n", " ⏎ "), 1200)}" }
                    .ifEmpty { "  $id: nothing in the log about it" }
            }
            listOf(ClientMcp.text(if (why.isEmpty()) text else "$text\nLog about the failed ones:\n$why"))
        })

        ClientMcp.register(ClientTool(
            "client_resource_packs",
            "The resource packs the client has loaded, from the bottom (vanilla) to the top (the server's packs last), and " +
                "when the last resource reload started. `reload`: reloads them like F3+T (the same packs: a server pack isn't " +
                "downloaded again), waits for the end, and returns how long each step took and the warnings and errors logged meanwhile.",
            ClientTools.schema("reload" to "boolean: reload the packs first (default false)", "lines" to "integer: for reload, log lines shown (default 60)"),
            acts = { it["reload"].bool() == true },
        ) { _, args ->
            val minecraft = Minecraft.getInstance()
            val log = if (args["reload"].bool() == true) {
                val asked = System.nanoTime()
                ClientTools.onClient { minecraft.reloadResourcePacks() }.get(180, TimeUnit.SECONDS)
                // The report comes as the loading screen closes: at once with fast reloads, after its fade-out without.
                fun report() = ReloadTimer.last?.takeIf { it.startedNanos > asked }
                val deadline = System.currentTimeMillis() + 3000
                while (ReloadTimer.times() && report() == null && System.currentTimeMillis() < deadline) Thread.sleep(20)
                (report()?.details() ?: "Reloaded.") + "\n\n" +
                    ClientLog.report(Level.WARN, null, (args["lines"].int() ?: 60).coerceIn(1, 500), sinceReload = true) + "\n\n"
            } else ""
            val packs = ClientTools.onClient {
                minecraft.resourcePackRepository.selectedPacks.mapIndexed { i, pack ->
                    val description = PacketInspector.readable(pack.description.string).replace('\n', ' ').trim()
                    "$i. ${pack.id} \"${pack.title.string}\"" + (if (description.isNotEmpty()) ": ${PacketInspector.cut(description, 80)}" else "") +
                        (if (pack.compatibility != PackCompatibility.COMPATIBLE) " (${pack.compatibility.name.lowercase()})" else "") +
                        if (pack.isRequired) " required" else ""
                }
            }
            val reload = ClientLog.lastReload().takeIf { it != 0L }?.let { "Last resource reload started at ${ClientLog.time(it)}.\n" } ?: ""
            listOf(ClientMcp.text(log + reload + "${packs.size} packs, bottom to top:\n" + packs.joinToString("\n")))
        })
    }
}
