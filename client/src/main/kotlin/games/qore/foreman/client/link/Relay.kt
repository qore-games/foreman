package games.qore.foreman.client.link

import games.qore.foreman.client.net.Net
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * The server's tools through the game connection (the plugin's relay on [CHANNEL]), for servers that open no MCP port:
 * a JSON-RPC request goes to the server in parts, its answer comes back in parts. The server runs a player's requests
 * one after the other; a call here waits for its answer, until the game leaves the server or [TIMEOUT_MS] passes.
 *
 * A message: its kind (a byte, [REQUEST] or [ANSWER]), the request's id (an int), the part's index and the number of
 * parts (shorts), then the part's bytes of the UTF-8 body.
 */
object Relay {
    const val CHANNEL = "foreman:mcp"
    private const val REQUEST: Byte = 0
    private const val ANSWER: Byte = 1
    /** What the server accepts in one message from a client is 32767 bytes, header included. */
    private const val PART = 30000
    /** As long as the longest tool call is allowed (the AI's MCP client waits 10 minutes). */
    private const val TIMEOUT_MS = 10 * 60_000L

    private class Pending {
        val answer = CompletableFuture<String>()
        var parts: Array<ByteArray?>? = null
    }

    private val out = Net.outgoing<ByteArray>(CHANNEL) { it }
    private val ids = AtomicInteger()
    private val pending = ConcurrentHashMap<Int, Pending>()

    fun init() {
        Net.incoming(CHANNEL, { it }, ::receive)
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            pending.values.forEach { it.answer.completeExceptionally(IOException("The game left the server")) }
            pending.clear()
        }
    }

    /** Whether the server listens on the relay's channel. */
    val available get() = out.available

    /** Sends [body] (one JSON-RPC request) to the server and waits for its answer. */
    fun call(body: String): String {
        val id = ids.incrementAndGet()
        val call = Pending().also { pending[id] = it }
        try {
            val bytes = body.encodeToByteArray()
            val count = maxOf(1, (bytes.size + PART - 1) / PART)
            val messages = (0 until count).map { i ->
                ByteArrayOutputStream().apply {
                    DataOutputStream(this).run {
                        writeByte(REQUEST.toInt())
                        writeInt(id)
                        writeShort(i)
                        writeShort(count)
                        write(bytes, i * PART, minOf(PART, bytes.size - i * PART))
                    }
                }.toByteArray()
            }
            // From this thread: the connection queues packets from any thread, and the client thread may be busy.
            if (!messages.all { out.send(it) }) throw IOException("The server doesn't relay: not in game, or its Foreman has no relay")
            return call.answer.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            throw IOException("No answer from the server in ${TIMEOUT_MS / 1000} s")
        } catch (e: ExecutionException) {
            throw e.cause as? IOException ?: IOException(e.cause?.message ?: "Relay failed")
        } finally {
            pending.remove(id)
        }
    }

    /** A part of an answer (on the client thread). */
    private fun receive(data: ByteArray) {
        val input = DataInputStream(data.inputStream())
        if (input.readByte() != ANSWER) return
        val id = input.readInt()
        val part = input.readUnsignedShort()
        val count = input.readUnsignedShort()
        val call = pending[id] ?: return
        val parts = call.parts ?: arrayOfNulls<ByteArray>(count).also { call.parts = it }
        if (part >= parts.size) return
        parts[part] = input.readAllBytes()
        if (parts.any { it == null }) return
        call.answer.complete(ByteArrayOutputStream().apply { parts.forEach { write(it!!) } }.toByteArray().decodeToString())
    }
}
