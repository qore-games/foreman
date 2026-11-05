package games.qore.foreman.client.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What an OpenRouter chat keeps of its earlier runs when a new one starts. */
class OpenRouterHistoryTest {
    private fun message(role: String, content: String) = buildJsonObject { put("role", role); put("content", content) }

    private fun images() = buildJsonObject {
        put("role", "user")
        put("content", buildJsonArray {
            add(buildJsonObject { put("type", "text"); put("text", "Images returned by the tool calls above:") })
            add(buildJsonObject { put("type", "image_url"); put("image_url", buildJsonObject { put("url", "data:image/png;base64,AAAA") }) })
        })
    }

    private fun history() = mutableListOf(
        message("system", "instructions"),
        message("user", "check the log"),
        message("tool", "x".repeat(10_000)),
        message("tool", "short"),
        images(),
        message("assistant", "a".repeat(10_000)),
    )

    @Test
    fun cutsLongResultsAndDropsImages() {
        val history = history()
        OpenRouterAgent(history, mutableSetOf()).compact()
        val log = history[2]["content"]!!.jsonPrimitive.content
        assertTrue(log.length <= 2000, "kept ${log.length}")
        assertTrue(log.startsWith("x".repeat(1000)) && "8200 more characters not kept" in log, log.takeLast(120))
        assertEquals("short", history[3]["content"]!!.jsonPrimitive.content)
        assertTrue(history[4]["content"] is JsonPrimitive && "not kept" in history[4]["content"]!!.jsonPrimitive.content)
        // The model's own words stay whole, so do the prompts and the instructions.
        assertEquals(10_000, history[5]["content"]!!.jsonPrimitive.content.length)
        assertEquals(listOf("instructions", "check the log"), history.take(2).map { it["content"]!!.jsonPrimitive.content })
    }

    /** A run is cut once: the next ones leave it as it is, so the provider's cache of it holds. */
    @Test
    fun cutsOnce() {
        val history = history()
        val agent = OpenRouterAgent(history, mutableSetOf())
        agent.compact()
        val once = history.map { JsonObject(it) }
        agent.compact()
        assertEquals(once, history)
    }
}
