package games.qore.foreman.server.mcp

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger

class ListChangedTest {
    @Test
    fun aBurstMakesOneCall() {
        val timer = Executors.newSingleThreadScheduledExecutor()
        val calls = AtomicInteger()
        val debounce = Debounce(timer, 100, 1000) { calls.incrementAndGet() }
        repeat(20) { debounce.poke() }
        Thread.sleep(400)
        assertEquals(1, calls.get())
        debounce.poke()
        Thread.sleep(400)
        assertEquals(2, calls.get())
        timer.shutdownNow()
    }

    @Test
    fun endlessChangesStillCall() {
        val timer = Executors.newSingleThreadScheduledExecutor()
        val calls = AtomicInteger()
        val debounce = Debounce(timer, 100, 250) { calls.incrementAndGet() }
        repeat(40) { debounce.poke(); Thread.sleep(20) }
        assertTrue(calls.get() >= 1, "a call within the max wait while changes keep coming")
        timer.shutdownNow()
    }

    @Test
    fun streamGetsListChangedOnce() {
        val tools = ToolRegistry()
        val dispatcher = McpDispatcher(Logger.getLogger("test"), { "" }, tools, { emptyList() }) { _, _, _, _, _, _ -> }
        val server = McpServer(Logger.getLogger("test"), "127.0.0.1", 0, false, "secret", { null }, { null }, dispatcher).apply { start() }
        val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
        fun request(method: String, session: String? = null, accept: String? = "text/event-stream", body: String? = null) =
            HttpRequest.newBuilder(URI.create(server.url)).apply {
                header("Authorization", "Bearer secret")
                session?.let { header("Mcp-Session-Id", it) }
                accept?.let { header("Accept", it) }
                if (body != null) header("Content-Type", "application/json")
                method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
            }.build()
        try {
            val init = http.send(
                request("POST", body = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}"""),
                HttpResponse.BodyHandlers.ofString(),
            )
            val session = init.headers().firstValue("Mcp-Session-Id").orElse(null)
            assertNotNull(session)
            val capabilities = JsonParser.parseString(init.body()).asJsonObject.getAsJsonObject("result").getAsJsonObject("capabilities")
            assertTrue(capabilities.getAsJsonObject("tools").get("listChanged").asBoolean)

            assertEquals(405, http.send(request("GET", session, accept = "application/json"), HttpResponse.BodyHandlers.discarding()).statusCode())
            assertEquals(400, http.send(request("GET"), HttpResponse.BodyHandlers.discarding()).statusCode())
            assertEquals(404, http.send(request("GET", "unknown"), HttpResponse.BodyHandlers.discarding()).statusCode())

            val stream = http.send(request("GET", session), HttpResponse.BodyHandlers.ofLines())
            assertEquals(200, stream.statusCode())
            assertEquals("text/event-stream", stream.headers().firstValue("Content-Type").orElse(null))
            val lines = LinkedBlockingQueue<String>()
            Thread { stream.body().forEach { lines.add(it) }; lines.add("<end>") }.apply { isDaemon = true }.start()

            repeat(20) { i -> tools.add(Tool("tool_$i", "A tool", JsonObject()) { ToolResult.text("") }) }
            assertEquals("event: message", lines.poll(5, TimeUnit.SECONDS))
            assertEquals("""{"jsonrpc":"2.0","method":"notifications/tools/list_changed"}""", lines.poll(1, TimeUnit.SECONDS)?.removePrefix("data: "))
            assertEquals("", lines.poll(1, TimeUnit.SECONDS))
            assertNull(lines.poll(1500, TimeUnit.MILLISECONDS), "one event for the burst")

            assertEquals(200, http.send(request("DELETE", session), HttpResponse.BodyHandlers.discarding()).statusCode())
            assertEquals("<end>", lines.poll(5, TimeUnit.SECONDS))
            assertEquals(404, http.send(request("GET", session), HttpResponse.BodyHandlers.discarding()).statusCode())
        } finally {
            server.stop()
        }
    }
}
