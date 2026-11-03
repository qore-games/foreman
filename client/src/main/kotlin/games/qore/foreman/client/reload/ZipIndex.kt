package games.qore.foreman.client.reload

import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * A zip's files sorted by name, so a pack lists a folder by looking it up rather than going through every entry of the
 * zip (vanilla does that for each folder it lists: dozens per reload, over tens of thousands of entries).
 */
class ZipIndex private constructor(val zip: ZipFile, private val names: Array<String>, private val entries: Array<ZipEntry>, private val order: IntArray) {
    /** The files whose name starts with [prefix], in the zip's order (vanilla's), to [action]. */
    fun forEachStartingWith(prefix: String, action: (ZipEntry) -> Unit) {
        var from = names.binarySearch(prefix).let { if (it < 0) -it - 1 else it }
        var to = from
        while (to < names.size && names[to].startsWith(prefix)) to++
        if (from == to) return
        val found = (from until to).sortedBy { order[it] }
        for (i in found) action(entries[i])
    }

    companion object {
        fun of(zip: ZipFile): ZipIndex {
            val files = ArrayList<ZipEntry>()
            val all = zip.entries()
            while (all.hasMoreElements()) all.nextElement().takeIf { !it.isDirectory }?.let(files::add)
            val sorted = files.indices.sortedBy { files[it].name }
            return ZipIndex(zip, Array(sorted.size) { files[sorted[it]].name }, Array(sorted.size) { files[sorted[it]] }, IntArray(sorted.size) { sorted[it] })
        }
    }
}
