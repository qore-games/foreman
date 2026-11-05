package games.qore.foreman.client.util

import com.mojang.blaze3d.platform.NativeImage
import org.lwjgl.stb.STBIWriteCallback
import org.lwjgl.stb.STBImageWrite
import org.lwjgl.system.MemoryUtil
import java.io.IOException
import java.nio.ByteBuffer

/** Images encoded in memory with the game's own stb_image_write: screenshots never touch the disk. */
object Images {
    /** [image] as PNG. */
    fun png(image: NativeImage): ByteArray = encode {
        STBImageWrite.nstbi_write_png_to_func(it, 0L, image.width, image.height, image.format().components(), image.pointer, 0)
    }

    /** [rgba] (a direct buffer, 4 bytes a pixel, the top row first; alpha ignored) as JPEG of [quality] (1 to 100). */
    fun jpeg(rgba: ByteBuffer, width: Int, height: Int, quality: Int): ByteArray = encode {
        STBImageWrite.nstbi_write_jpg_to_func(it, 0L, width, height, 4, MemoryUtil.memAddress(rgba), quality)
    }

    /** What [write] gives stb's callback (its address), collected. */
    private fun encode(write: (Long) -> Int): ByteArray {
        val sink = Sink()
        try {
            if (write(sink.address()) == 0) throw IOException("Could not encode the image")
            return sink.bytes.copyOf(sink.length)
        } finally {
            sink.free()
        }
    }

    /** stb's output in a growing array: its JPEG writer calls back for each byte, so nothing is allocated per call. */
    private class Sink : STBIWriteCallback() {
        var bytes = ByteArray(1 shl 16)
        var length = 0

        override fun invoke(context: Long, data: Long, size: Int) {
            if (length + size > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, length + size))
            if (size == 1) bytes[length] = MemoryUtil.memGetByte(data) else MemoryUtil.memByteBuffer(data, size).get(bytes, length, size)
            length += size
        }
    }
}
