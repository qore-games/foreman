package games.qore.foreman.client.mcp

import com.mojang.blaze3d.platform.NativeImage
import games.qore.foreman.client.lean.Lean
import games.qore.foreman.client.util.Images
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import org.lwjgl.stb.STBImageResize
import org.lwjgl.system.MemoryUtil
import org.slf4j.LoggerFactory
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer

/**
 * `GET /stream` of the client MCP server: what the window shows (screens and the HUD included), live, as Motion JPEG
 * (`multipart/x-mixed-replace`), to watch a client from a launcher or a dashboard, a lean one without a window too.
 * Frames are read back after the game draws them, at the highest open stream's rate (lean clients draw at least that
 * many meanwhile), and each stream sends the newest one when it's ready for one: a slow reader skips frames. Frames
 * stay in memory, in buffers reused from one frame to the next and freed when the stream closes (the last one: the
 * newest frame's too), and nothing runs while no stream is open.
 */
object FrameStream {
    private val logger = LoggerFactory.getLogger("foreman")
    private const val MAX_STREAMS = 4
    private const val BOUNDARY = "foreman-frame"
    /** A reader that takes nothing for this long is dropped: its stream would hold a place and a thread forever. */
    private const val STALL_MS = 60_000L
    /** The socket's send buffer, in bytes: about two frames at the default size. */
    private const val SEND_BUFFER = 64 shl 10
    private val HEAD = ("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=$BOUNDARY\r\n" +
        "Cache-Control: no-store\r\nConnection: close\r\n\r\n").encodeToByteArray()
    private val CRLF = "\r\n".encodeToByteArray()
    private val EMPTY: ByteBuffer = ByteBuffer.allocateDirect(0)

    /** A frame read back: RGBA bytes as NativeImage holds them (the top row first), when it was drawn, its number. */
    private class Frame {
        var pixels = EMPTY
        var width = 0
        var height = 0
        var started = 0L
        var time = 0L
        var number = 0L

        /** Takes [width]×[height] RGBA pixels at [address], in its buffer while the size stays the same. */
        fun copy(address: Long, width: Int, height: Int, started: Long, time: Long, number: Long) {
            val size = width * height * 4
            pixels = sized(pixels, size)
            MemoryUtil.memCopy(address, MemoryUtil.memAddress(pixels), size.toLong())
            this.width = width
            this.height = height
            this.started = started
            this.time = time
            this.number = number
        }

        fun free() {
            pixels = sized(pixels, 0)
            width = 0
        }
    }

    /** [buffer] if it has [size] bytes, else a new one in its place (the old freed now, not at some garbage collection). */
    private fun sized(buffer: ByteBuffer, size: Int): ByteBuffer {
        if (buffer.capacity() == size) return buffer
        if (buffer !== EMPTY) MemoryUtil.memFree(buffer)
        return if (size == 0) EMPTY else MemoryUtil.memAlloc(size)
    }

    /** An open stream: what it asked for, the frames it sent, since when its write waits for the reader (0: not writing). */
    private class Stream(val socket: Socket, val fps: Int, val width: Int, val quality: Int) {
        var sent = 0
        @Volatile var writingSince = 0L
    }

    private val lock = Object()
    private val streams = ArrayList<Stream>()
    /** The newest frame read back, its pixels dropped once no stream is open. */
    private val latest = Frame()
    /** The highest open stream's frames per second, 0 while none is open: all [frameDrawn] reads without one. */
    @Volatile private var rate = 0
    /** The widest open stream's width: frames are read back downscaled as far as it allows. */
    @Volatile private var widest = 0
    /** When frames are read back (client thread). */
    private var pace: Pace? = null
    /** A frame couldn't be read back since the streams opened: said once, not every frame. */
    @Volatile private var warned = false

    /** Serves a `/stream` request (after its token was checked) until the reader goes away. [query]: `fps`, `width`, `quality`. */
    fun serve(socket: Socket, method: String, query: String) {
        if (method != "GET") return ClientMcp.respond(socket, 405, null, "Allow: GET")
        val params = query.split('&').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        val stream = Stream(
            socket,
            fps = params["fps"]?.toIntOrNull()?.coerceIn(1, 30) ?: 10,
            width = params["width"]?.toIntOrNull()?.coerceIn(160, 1920) ?: 640,
            quality = params["quality"]?.toIntOrNull()?.coerceIn(30, 95) ?: 70,
        )
        if (!open(stream)) return ClientMcp.respond(socket, 503, null)
        try {
            socket.tcpNoDelay = true
            // the system would otherwise queue megabytes of frames for a slow reader, which then sees them late
            socket.sendBufferSize = SEND_BUFFER
            val out = BufferedOutputStream(socket.getOutputStream(), 1 shl 16)
            out.write(HEAD)
            out.flush()
            send(stream, out)
        } catch (_: IOException) {
            // the reader went away
        } catch (e: Exception) {
            // past the stream's head: an error page would land in the middle of it
            logger.warn("Live stream failed", e)
        } finally {
            close(stream)
            logger.info("Live stream closed after {} frames", stream.sent)
        }
    }

    /** Sends the newest frame each time one is due, until a write fails or the reader closed its side. */
    private fun send(stream: Stream, out: OutputStream) {
        val pace = Pace(stream.fps)
        val encoder = Encoder(stream.width, stream.quality)
        val frame = Frame()
        var last = synchronized(lock) { latest.number }
        try {
            while (true) {
                if (!next(last, frame)) {
                    if (readerClosed(stream.socket)) return
                    continue
                }
                last = frame.number
                if (!pace.take(frame.started)) continue
                val jpeg = encoder.encode(frame)
                stream.writingSince = System.currentTimeMillis()
                out.write("--$BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\nX-Frame-Time: ${frame.time}\r\n\r\n".encodeToByteArray())
                out.write(jpeg)
                out.write(CRLF)
                out.flush()
                stream.writingSince = 0L
                stream.sent++
            }
        } finally {
            frame.free()
            encoder.free()
        }
    }

    /** Copies the newest frame into [into] when it's newer than frame [last], waiting a second at most for one. */
    private fun next(last: Long, into: Frame): Boolean {
        synchronized(lock) {
            if (latest.number == last) lock.wait(1000)
            if (latest.number == last || latest.width == 0) return false
            into.copy(MemoryUtil.memAddress(latest.pixels), latest.width, latest.height, latest.started, latest.time, latest.number)
            return true
        }
    }

    /** Whether the reader closed its side, while no frame comes to find out by writing (it sends nothing after its request). */
    private fun readerClosed(socket: Socket): Boolean = try {
        socket.soTimeout = 1
        socket.getInputStream().read() == -1
    } catch (_: SocketTimeoutException) {
        false
    }

    private fun open(stream: Stream): Boolean {
        synchronized(lock) {
            if (streams.size >= MAX_STREAMS) return false
            if (streams.isEmpty()) warned = false
            streams += stream
            changed()
        }
        logger.info("Live stream opened: {} fps, {} px wide, quality {}", stream.fps, stream.width, stream.quality)
        return true
    }

    private fun close(stream: Stream) = synchronized(lock) {
        streams -= stream
        if (streams.isEmpty()) latest.free()
        changed()
    }

    private fun changed() {
        widest = streams.maxOfOrNull { it.width } ?: 0
        rate = streams.maxOfOrNull { it.fps } ?: 0
        Lean.watch(rate)
    }

    /** LeanMinecraftMixin, after each frame: while a stream is open, reads the frame back when one is due. */
    @JvmStatic
    fun frameDrawn() {
        val rate = rate
        if (rate == 0) return
        val pace = pace?.takeIf { it.fps == rate } ?: Pace(rate).also { pace = it }
        val started = Lean.frameStarted
        if (!pace.take(started)) return
        val target = Minecraft.getInstance().gameRenderer.mainRenderTarget()
        val time = System.currentTimeMillis()
        // The client only downscales by a factor dividing both sides; the stream scales the rest.
        var downscale = (target.width / widest.coerceAtLeast(1)).coerceAtLeast(1)
        while (downscale > 1 && (target.width % downscale != 0 || target.height % downscale != 0)) downscale--
        try {
            // Caught inside too: vanilla frees the GPU buffer it read into only when this returns.
            Screenshot.takeScreenshot(target, downscale) { image -> runCatching { image.use { publish(it, started, time) } }.onFailure(::failed) }
        } catch (e: Exception) {
            failed(e)
        }
    }

    private fun failed(e: Throwable) {
        if (warned) return
        warned = true
        logger.warn("Could not read a frame back for the live stream", e)
    }

    /** [image] as the newest frame, for the streams waiting; readers stalled for [STALL_MS] dropped. */
    private fun publish(image: NativeImage, started: Long, time: Long) = synchronized(lock) {
        if (streams.isEmpty()) return@synchronized
        latest.copy(image.pointer, image.width, image.height, started, time, latest.number + 1)
        lock.notifyAll()
        val now = System.currentTimeMillis()
        streams.forEach { if (it.writingSince != 0L && now - it.writingSince > STALL_MS) runCatching { it.socket.close() } }
    }

    /**
     * Picks frames at [fps] at most by when they started, from frames drawn at any rate: every frame of a lean client
     * drawing at [fps], [fps] on average of a faster one.
     */
    private class Pace(val fps: Int) {
        private val interval = 1_000_000_000L / fps
        private var due = Long.MIN_VALUE

        fun take(started: Long): Boolean {
            if (started < due) return false
            due = maxOf(due + interval, started + interval / 2)
            return true
        }
    }

    /** A stream's frames scaled down to its width (never up) and encoded, in a buffer it keeps. */
    private class Encoder(private val width: Int, private val quality: Int) {
        private var scaled = EMPTY

        fun free() {
            scaled = sized(scaled, 0)
        }

        fun encode(frame: Frame): ByteArray {
            val w = minOf(width, frame.width)
            if (w == frame.width) return Images.jpeg(frame.pixels, w, frame.height, quality)
            val h = ((frame.height.toLong() * w + frame.width / 2) / frame.width).toInt().coerceAtLeast(1)
            scaled = sized(scaled, w * h * 4)
            STBImageResize.stbir_resize_uint8_linear(frame.pixels, frame.width, frame.height, 0, scaled, w, h, 0, STBImageResize.STBIR_4CHANNEL)
                ?: throw IOException("Could not scale the frame")
            return Images.jpeg(scaled, w, h, quality)
        }
    }
}
