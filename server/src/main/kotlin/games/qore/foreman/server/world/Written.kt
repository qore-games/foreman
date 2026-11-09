package games.qore.foreman.server.world

import com.sk89q.worldedit.math.BlockVector3
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.BitSet
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * The blocks a paste wrote in its [box] (the asset's blocks that aren't air, turned): a bit per block of the box, x
 * fastest, then z, then y. Kept with the instance, deflated and in base64, so the copy can be removed without touching
 * the blocks it didn't write.
 */
data class Written(val box: Box, val bits: BitSet) {
    val count: Int get() = bits.cardinality()

    private fun index(x: Int, y: Int, z: Int) = ((y - box.min.y()) * box.sizeZ + (z - box.min.z())) * box.sizeX + (x - box.min.x())

    fun has(x: Int, y: Int, z: Int) = box.contains(x, y, z) && bits[index(x, y, z)]

    /** The blocks written, in index order. */
    fun blocks(): Sequence<BlockVector3> = generateSequence(bits.nextSetBit(0).takeIf { it >= 0 }) { i -> bits.nextSetBit(i + 1).takeIf { it >= 0 } }.map { i ->
        val x = i % box.sizeX
        val z = i / box.sizeX % box.sizeZ
        val y = i / box.sizeX / box.sizeZ
        BlockVector3.at(box.min.x() + x, box.min.y() + y, box.min.z() + z)
    }

    /** Where [placement] puts them. */
    fun moved(placement: Placement): Written = of(placement.box(box), blocks().map(placement::block))

    fun encode(): String {
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out, Deflater(Deflater.BEST_COMPRESSION)).use { it.write(bits.toByteArray()) }
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    companion object {
        /** [blocks] (in [box]) as a mask over [box]. */
        fun of(box: Box, blocks: Sequence<BlockVector3>): Written {
            val written = Written(box, BitSet())
            blocks.forEach { if (box.contains(it.x(), it.y(), it.z())) written.bits.set(written.index(it.x(), it.y(), it.z())) }
            return written
        }

        fun decode(box: Box, text: String): Written =
            Written(box, BitSet.valueOf(InflaterInputStream(Base64.getDecoder().decode(text).inputStream()).use { it.readBytes() }))
    }
}
