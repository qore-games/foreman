package games.qore.foreman.server.mcp

import com.sun.net.httpserver.HttpExchange
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The open streams of server messages of MCP sessions (`GET /mcp`, Server-Sent Events), one per session, each written
 * by a thread of its own, never the request threads: the messages [broadcast] sends, and a keep-alive comment every
 * [KEEP_ALIVE_S] s. A stream ends when its client goes (a write fails), its session ends, or the server stops.
 */
class EventStreams {
    private val streams = ConcurrentHashMap<String, Stream>()
    private val threads = Executors.newCachedThreadPool { Thread(it, "foreman-mcp-stream").apply { isDaemon = true } }

    val isEmpty get() = streams.isEmpty()

    /** Whether [session] may open a stream: its new one replaces the one it had, others wait for room. */
    fun hasRoom(session: String) = streams.containsKey(session) || streams.size < MAX_STREAMS

    /** Answers [exchange] with the stream of [session], which it closes when the stream ends. */
    fun open(session: String, exchange: HttpExchange) {
        val stream = Stream(exchange)
        streams.put(session, stream)?.end()
        threads.execute {
            stream.run()
            streams.remove(session, stream)
        }
    }

    fun close(session: String) {
        streams.remove(session)?.end()
    }

    /** Sends a JSON-RPC [message] on every open stream. */
    fun broadcast(message: String) = streams.values.forEach { it.send(message) }

    fun stop() {
        streams.values.forEach { it.end() }
        streams.clear()
        threads.shutdown()
    }

    private class Stream(private val exchange: HttpExchange) {
        private val queue = LinkedBlockingQueue<Any>()

        fun send(message: String) = queue.offer(message)

        fun end() = queue.offer(END)

        fun run() {
            try {
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.responseHeaders.add("Cache-Control", "no-cache")
                // Reverse proxies (nginx) pass each event on at once.
                exchange.responseHeaders.add("X-Accel-Buffering", "no")
                exchange.sendResponseHeaders(200, 0)
                val out = exchange.responseBody
                out.flush()
                while (true) {
                    val message = queue.poll(KEEP_ALIVE_S, TimeUnit.SECONDS)
                    if (message === END) break
                    out.write((if (message == null) ": keep-alive\n\n" else "event: message\ndata: $message\n\n").encodeToByteArray())
                    out.flush()
                }
            } catch (_: IOException) {
                // The client went.
            } catch (_: InterruptedException) {
            } finally {
                runCatching { exchange.close() }
            }
        }
    }

    companion object {
        const val KEEP_ALIVE_S = 25L
        /** Each holds a thread: a client opening streams without end gets 429 past this. */
        const val MAX_STREAMS = 32
        private val END = Any()
    }
}
