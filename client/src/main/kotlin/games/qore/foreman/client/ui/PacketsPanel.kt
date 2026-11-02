package games.qore.foreman.client.ui

import games.qore.foreman.client.config.Config
import games.qore.foreman.client.packets.PacketRecorder
import games.qore.foreman.client.packets.PacketReports
import games.qore.foreman.client.packets.PacketReports.bytes
import games.qore.foreman.client.packets.PacketReports.count
import games.qore.foreman.client.packets.PacketReports.ms
import games.qore.foreman.client.packets.PacketReports.share
import games.qore.foreman.client.packets.PacketSuspects
import games.qore.foreman.client.packets.PacketViewer
import games.qore.foreman.client.packets.Subject
import games.qore.foreman.client.ui.kit.Style
import games.qore.foreman.client.ui.kit.Theme
import games.qore.foreman.client.ui.kit.Col
import games.qore.foreman.client.ui.kit.Ui
import games.qore.foreman.client.ui.kit.table
import net.minecraft.client.Minecraft
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The hub's Packets section: record, the bytes received each second, then the suspects (packets grouped by what likely
 * sends them), the packet types, the entities and chunks that get the most, or the packets one by one. Clicking a row
 * lists its packets. What it shows is read from the recorder four times a second, not every frame; the suspects are
 * worked out on another thread every two seconds.
 */
object PacketsPanel {
    private enum class Tab(val label: String) { SUSPECTS("Suspects"), TYPES("Types"), ENTITIES("Entities"), CHUNKS("Chunks"), PACKETS("Packets") }

    private val directions = listOf("Received", "Sent", "Both")
    private const val LOG_LIMIT = 500
    private val CAPACITIES = listOf(20_000, 50_000, 100_000, 250_000, 500_000)

    private var tab = Tab.SUSPECTS
    private var direction = 0
    /** Types sorted by: 0 bytes, 1 count, 2 client time. */
    private var sort = 0
    /** Packets of this type only (clicked in Types). */
    private var type: String? = null
    /** Packets about this entity or chunk only (clicked in Entities or Chunks). */
    private var subject = Subject.NONE

    private class Named(val totals: PacketRecorder.SubjectTotals, val name: String)

    private class View(
        val seconds: List<LongArray> = emptyList(),
        val types: List<PacketRecorder.TypeTotals> = emptyList(),
        val subjects: List<Named> = emptyList(),
        val events: List<PacketRecorder.Event> = emptyList(),
        val suspects: List<PacketSuspects.Suspect> = emptyList(),
    )

    private var view = View()
    private var viewKey = ""
    private var viewAt = 0L

    private fun refresh(query: String): View {
        val key = "$tab|$direction|$sort|$type|$subject|$query|${PacketRecorder.startedAt}|${PacketRecorder.hasData}"
        val now = System.currentTimeMillis()
        if (key == viewKey && now - viewAt < 250) return view
        viewKey = key
        viewAt = now
        val seconds = PacketRecorder.seconds()
        view = when (tab) {
            Tab.SUSPECTS -> View(seconds, suspects = suspects().filter { s ->
                query.isEmpty() || s.title.contains(query, true) || s.clues.any { it.contains(query, true) } || s.types.any { it.first.contains(query, true) }
            })
            Tab.TYPES -> View(seconds, types = PacketRecorder.types()
                .filter { shows(it.outbound) && (query.isEmpty() || it.name.contains(query, true)) }
                .sortedByDescending { when (sort) { 1 -> it.count; 2 -> it.handleNanos; else -> it.wire } })
            Tab.ENTITIES -> View(seconds, subjects = PacketRecorder.entities(200)
                .map { Named(it, "#${Subject.entityId(it.subject)} " + PacketReports.entityName(Subject.entityId(it.subject))) }
                .filter { query.isEmpty() || it.name.contains(query, true) || it.totals.types.any { t -> t.first.contains(query, true) } })
            Tab.CHUNKS -> View(seconds, subjects = PacketRecorder.chunks(200).map { Named(it, chunkName(it.subject)) }
                .filter { query.isEmpty() || it.name.contains(query, true) || it.totals.types.any { t -> t.first.contains(query, true) } })
            Tab.PACKETS -> View(seconds, events = PacketRecorder.events(newestFirst = true).filter { e ->
                shows(e.outbound) && (type == null || e.type == type) && (subject == Subject.NONE || e.subject == subject) &&
                    (query.isEmpty() || e.type.contains(query, true) || Subject.describe(e.subject).contains(query, true) || e.detail?.contains(query, true) == true)
            }.take(LOG_LIMIT).toList())
        }
        return view
    }

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "foreman-packet-suspects").apply { isDaemon = true } }
    private var suspects: List<PacketSuspects.Suspect> = emptyList()
    /** The recording ([PacketRecorder.startedAt]) and its packet count when [suspects] were worked out. */
    private var suspectsOf = 0L to 0L
    private var suspectsAt = 0L
    private var computing = false
    private var receivedWire = 0L

    /** The last suspects worked out; a new run starts when the recording changed (every 2 s while it records). */
    private fun suspects(): List<PacketSuspects.Suspect> {
        val of = PacketRecorder.startedAt to PacketRecorder.total
        if (of.first != suspectsOf.first) suspects = emptyList()
        val now = System.currentTimeMillis()
        if (!computing && of != suspectsOf && (now - suspectsAt > 2000 || !PacketRecorder.recording)) {
            computing = true
            val infos = PacketSuspects.capture()
            worker.execute {
                val result = runCatching { PacketSuspects.analyze(infos, 60) }.getOrDefault(emptyList())
                val wire = PacketReports.receivedWire()
                Minecraft.getInstance().execute {
                    suspects = result
                    receivedWire = wire
                    suspectsOf = of
                    suspectsAt = System.currentTimeMillis()
                    computing = false
                }
            }
        }
        return suspects
    }

    private fun shows(outbound: Boolean) = direction == 2 || outbound == (direction == 1)

    private fun chunkName(s: Long): String {
        val x = Subject.chunkX(s)
        val z = Subject.chunkZ(s)
        val player = Minecraft.getInstance().player ?: return "$x, $z"
        return "$x, $z · ${maxOf(Math.abs((player.blockX shr 4) - x), Math.abs((player.blockZ shr 4) - z))} chunks away"
    }

    fun draw(ui: Ui, x: Int, y: Int, w: Int, h: Int, query: String) {
        val v = refresh(query.trim())
        var cy = y
        // Narrow hub (big GUI scale): shorter labels, the recording's settings and the filters on rows of their own.
        val compact = w < 300
        val bw = if (compact) listOf(64, 44, 50, 54) else listOf(84, 50, 56, 76)
        var bx = x
        if (PacketRecorder.recording) ui.button(bx, cy, bw[0], 18, "■ Stop", Style.DANGER) { PacketRecorder.stop() }
        else ui.button(bx, cy, bw[0], 18, if (compact) "● Rec" else "● Record", Style.PRIMARY, tooltip = "Records the packets both ways (drops the last recording). Do what lags meanwhile.") { PacketRecorder.start() }
        bx += bw[0] + 4
        ui.button(bx, cy, bw[1], 18, "Clear", Style.GHOST, enabled = PacketRecorder.hasData) { PacketRecorder.clear(); type = null; subject = Subject.NONE }
        bx += bw[1] + 4
        ui.button(bx, cy, bw[2], 18, "Export", enabled = PacketRecorder.hasData, tooltip = "Writes the summary and every kept packet to config/foreman/packets/") { PacketViewer.export() }
        bx += bw[2] + 4
        ui.button(bx, cy, bw[3], 18, if (compact) "Ask AI" else "Ask the AI", tooltip = "A new chat looks for what lags in the recording (it records 20 s itself when there is none)") { PacketViewer.ask() }
        bx += bw[3] + 8
        // The recording's settings: on the buttons' row when they fit, else on a row of their own under the status.
        val settingsW = 156
        val settingsOnTop = x + w - settingsW >= bx
        fun settings(sx: Int, sy: Int) {
            val kept = Config.settings.packets.capacity
            ui.button(sx, sy, 66, 16, "Keep ${kept / 1000}k", Style.GHOST,
                tooltip = "Packets kept one by one: ${count(kept.toLong())} (the oldest go first; the totals cover the whole recording). Click for more or fewer, from the next recording") {
                Config.update { packets.capacity = CAPACITIES[(CAPACITIES.indexOf(kept) + 1) % CAPACITIES.size] }
            }
            ui.switch(sx + 72, sy, 84, "Fields", Config.settings.packets.contents,
                "Keep what each packet says, not only its type and size (from the next recording)") { Config.update { packets.contents = !packets.contents } }
        }
        if (settingsOnTop) settings(x + w - settingsW, cy + 1)
        cy += 24

        status(ui, x, cy, w, v)
        if (!settingsOnTop) {
            cy += 14
            settings(x, cy)
            cy += 8
        }
        cy += 14
        chart(ui, x, cy, w, 36, v.seconds)
        cy += 44

        val tabsW = Tab.entries.sumOf { ui.font.width(it.label) + 15 }
        ui.segmented(x, cy, Tab.entries.map { it.label }, tab.ordinal) { tab = Tab.entries[it] }
        val withDirection = tab == Tab.TYPES || tab == Tab.PACKETS
        val dw = directions.sumOf { ui.font.width(it) + 15 }
        val directionBelow = withDirection && tabsW + 8 + dw > w
        if (withDirection && !directionBelow) ui.segmented(x + w - dw, cy, directions, direction) { direction = it }
        val filtered = tab == Tab.PACKETS && (type != null || subject != Subject.NONE)
        if (directionBelow || filtered) {
            cy += 20
            if (directionBelow) ui.segmented(x + w - dw, cy, directions, direction) { direction = it }
            var fx = x
            type?.let { t -> fx += filterChip(ui, fx, cy + 2, ui.fit(t, (w - (if (directionBelow) dw else 0)) / 2 - 12)) { type = null } + 4 }
            if (subject != Subject.NONE) filterChip(ui, fx, cy + 2, Subject.describe(subject)) { subject = Subject.NONE }
        }
        cy += 22

        val th = y + h - cy
        if (!PacketRecorder.hasData) {
            ui.paragraph("Record, then do what lags: walk into the busy area, join the event, open the menu. Each packet's type, size " +
                "on the wire and time on your client thread are kept; Ask the AI, or export them. /packets start [seconds] works too.", x, cy + 4, w)
            return
        }
        when (tab) {
            Tab.SUSPECTS -> suspects(ui, x, cy, w, th, v.suspects)
            Tab.TYPES -> types(ui, x, cy, w, th, v.types)
            Tab.ENTITIES, Tab.CHUNKS -> subjects(ui, x, cy, w, th, v.subjects)
            Tab.PACKETS -> packets(ui, x, cy, w, th, v.events)
        }
    }

    private fun status(ui: Ui, x: Int, y: Int, w: Int, v: View) {
        val elapsed = PacketRecorder.elapsedNanos()
        val secs = (elapsed / 1e9).coerceAtLeast(0.001)
        val inWire = v.seconds.sumOf { it[PacketRecorder.IN_WIRE] }
        val outWire = v.seconds.sumOf { it[PacketRecorder.OUT_WIRE] }
        val handle = v.seconds.sumOf { it[PacketRecorder.HANDLE] }
        // The longest that fits: the rates go first, then the client thread, the totals stay.
        val state = (if (PacketRecorder.recording) "Recording " else "Stopped after ") + PacketReports.clock(elapsed)
        val packets = "${count(PacketRecorder.total)} packets"
        val kept = if (PacketRecorder.total > PacketRecorder.kept()) " · last ${count(PacketRecorder.kept())} kept" else ""
        val client = " · client thread ${String.format(Locale.ROOT, "%.1f%%", handle / 1e7 / secs)}"
        val variants = listOf(
            "$state · $packets · in ${bytes(inWire)} (${bytes((inWire / secs).toLong())}/s) · out ${bytes(outWire)} (${bytes((outWire / secs).toLong())}/s)$client$kept",
            "$state · $packets · in ${bytes(inWire)} · out ${bytes(outWire)}$client$kept",
            "$state · $packets · in ${bytes(inWire)} · out ${bytes(outWire)}",
            "${PacketReports.clock(elapsed)} · $packets · in ${bytes(inWire)} · out ${bytes(outWire)}",
        )
        val room = w - if (PacketRecorder.recording) 10 else 0
        val text = if (!PacketRecorder.hasData) "Not recording" else variants.firstOrNull { ui.font.width(it) <= room } ?: variants.last()
        var tx = x
        if (PacketRecorder.recording) { ui.text("●", x, y, Theme.ERROR); tx += 10 }
        ui.text(ui.fit(text, w - (tx - x)), tx, y, if (PacketRecorder.hasData) Theme.TEXT else Theme.FAINT)
    }

    /** Bytes received per second, the latest seconds that fit; each bar's tooltip has the second's numbers. */
    private fun chart(ui: Ui, x: Int, y: Int, w: Int, h: Int, seconds: List<LongArray>) {
        ui.rect(x, y + h, w, 1, Theme.BORDER)
        if (seconds.isEmpty()) {
            ui.text("Bytes received per second", x + 4, y + h - 12, Theme.FAINT)
            return
        }
        val labelW = 64
        val plotW = w - labelW
        val n = seconds.size.coerceAtMost(plotW / 3)
        val step = (plotW / n.coerceAtLeast(1)).coerceIn(3, 12)
        val first = seconds.size - n
        val peak = seconds.subList(first, seconds.size).maxOf { it[PacketRecorder.IN_WIRE] }.coerceAtLeast(1)
        for (i in 0 until n) {
            val s = seconds[first + i]
            val value = s[PacketRecorder.IN_WIRE]
            val bh = (value * h / peak).toInt().coerceAtLeast(if (value > 0) 1 else 0)
            val bx = x + i * step
            val hover = ui.hovered(bx, y, step, h + 1)
            ui.rect(bx, y + h - bh, step - 1, bh, if (hover) Theme.TEXT else Theme.ACCENT)
            ui.hit(bx, y, step, h + 1, "+${first + i}s\n" +
                "received ${bytes(value)} (${count(s[PacketRecorder.IN_COUNT])} packets)\n" +
                "sent ${bytes(s[PacketRecorder.OUT_WIRE])} (${count(s[PacketRecorder.OUT_COUNT])} packets)\n" +
                "client thread ${ms(s[PacketRecorder.HANDLE])}, longest frame ${ms(s[PacketRecorder.WORST_FRAME])}")
        }
        ui.text("${bytes(peak)}/s", x + w - ui.font.width("${bytes(peak)}/s"), y, Theme.MUTED)
        ui.text("received", x + w - ui.font.width("received"), y + 11, Theme.FAINT)
    }

    private fun filterChip(ui: Ui, x: Int, y: Int, label: String, clear: () -> Unit): Int {
        val w = ui.chip(x, y, "$label ✕", Theme.ACCENT)
        ui.hit(x, y, w, 12, "Show every packet again", clear)
        return w
    }

    // ---- Tables -----------------------------------------------------------------------------------------------------

    private fun suspects(ui: Ui, x: Int, y: Int, w: Int, h: Int, rows: List<PacketSuspects.Suspect>) {
        if (rows.isEmpty()) {
            ui.paragraph(if (computing) "Working out the suspects…" else "No received packet kept.", x, y + 4, w)
            return
        }
        val cols = listOf(
            Col<PacketSuspects.Suspect>("Suspect", 0, left = true, color = { Theme.TEXT }) { it.title },
            Col("/s", 44, keep = 3) { String.format(Locale.ROOT, "%.1f", it.count / it.seconds) },
            Col("Wire", 56, keep = 7) { bytes(it.wire) },
            Col("Share", 40, keep = 5) { share(it.wire, receivedWire) },
            Col("Repeats", 48, keep = 4, color = { if (it.repeats * 2 >= it.count && it.count >= 20) Theme.WARN else Theme.MUTED }) { share(it.repeats, it.count) },
            Col("Rhythm", 84, keep = 6, left = true) { it.cadence ?: "" },
        )
        ui.table("packet-suspects", x, y, w, h, rows, cols, tooltip = { s ->
            PacketSuspects.text(s, rows.indexOf(s) + 1, receivedWire).lines().joinToString("\n") { it.trim() } +
                "\n\nThe client can't see which plugin sent them: search these clues in your plugins, or Ask the AI. Click to list them"
        }) { s -> type = s.mainType; subject = s.subject; direction = 0; tab = Tab.PACKETS }
    }

    private fun types(ui: Ui, x: Int, y: Int, w: Int, h: Int, rows: List<PacketRecorder.TypeTotals>) {
        val secs = (PacketRecorder.elapsedNanos() / 1e9).coerceAtLeast(0.001)
        val cols = listOf(
            Col<PacketRecorder.TypeTotals>("Type", 0, left = true, color = { Theme.TEXT }) { (if (direction == 2) (if (it.outbound) "↑ " else "↓ ") else "") + it.name },
            Col("Count", 52, keep = 5, sort = 1) { count(it.count) },
            Col("/s", 40, keep = 2) { String.format(Locale.ROOT, "%.1f", it.count / secs) },
            Col("Wire", 56, keep = 7, sort = 0) { bytes(it.wire) },
            Col("Avg", 50, keep = 1) { bytes(it.wire / it.count.coerceAtLeast(1)) },
            Col("Max", 50, keep = 1) { bytes(it.maxWire.toLong()) },
            Col("Repeats", 48, keep = 6, color = { if (it.repeats * 2 >= it.count && it.count >= 20) Theme.WARN else Theme.MUTED }) { share(it.repeats, it.count) },
            Col("Client", 56, keep = 4, sort = 2) { if (it.handled > 0) ms(it.handleNanos) else "-" },
        )
        ui.table("packet-types", x, y, w, h, rows, cols, sorted = sort, onSort = { sort = it }, tooltip = { t ->
            "${t.name} (${if (t.outbound) "sent" else "received"})\n" +
                "${count(t.count)} packets, ${String.format(Locale.ROOT, "%.1f", t.count / secs)}/s\n" +
                "${bytes(t.wire)} on the wire" + (if (t.payload > t.wire) ", ${bytes(t.payload)} decoded" else "") + "\n" +
                "average ${bytes(t.wire / t.count.coerceAtLeast(1))}, biggest ${bytes(t.maxWire.toLong())}\n" +
                "repeats: ${count(t.repeats)} (${share(t.repeats, t.count)}), ${bytes(t.repeatWire)}" +
                (if (t.handled > 0) "\nclient thread: ${ms(t.handleNanos)}, longest ${ms(t.maxHandleNanos)}" else "") +
                "\n\nClick to list them"
        }) { t -> type = t.name; subject = Subject.NONE; direction = if (t.outbound) 1 else 0; tab = Tab.PACKETS }
    }

    private fun subjects(ui: Ui, x: Int, y: Int, w: Int, h: Int, rows: List<Named>) {
        if (rows.isEmpty()) {
            ui.paragraph(if (tab == Tab.ENTITIES) "No packet about an entity yet." else "No packet about a chunk yet.", x, y + 4, w)
            return
        }
        val secs = (PacketRecorder.elapsedNanos() / 1e9).coerceAtLeast(0.001)
        val cols = listOf(
            Col<Named>(if (tab == Tab.ENTITIES) "Entity" else "Chunk", 0, left = true, color = { Theme.TEXT }) { it.name },
            Col("Packets", 52, keep = 5) { count(it.totals.count) },
            Col("/s", 40, keep = 3) { String.format(Locale.ROOT, "%.1f", it.totals.count / secs) },
            Col("Wire", 56, keep = 6) { bytes(it.totals.wire) },
            Col("Mostly", (w / 4).coerceIn(80, 200), keep = 2, left = true) { it.totals.types.firstOrNull()?.first.orEmpty() },
        )
        ui.table("packet-${tab.name}", x, y, w, h, rows, cols, tooltip = { s ->
            s.name + "\n" + s.totals.types.take(8).joinToString("\n") { (name, n, wire) -> "$name: ${count(n)}, ${bytes(wire)}" } + "\n\nClick to list them"
        }) { s -> subject = s.totals.subject; type = null; direction = 0; tab = Tab.PACKETS }
    }

    private fun packets(ui: Ui, x: Int, y: Int, w: Int, h: Int, rows: List<PacketRecorder.Event>) {
        if (rows.isEmpty()) {
            ui.paragraph("No kept packet matches.", x, y + 4, w)
            return
        }
        val cols = listOf(
            Col<PacketRecorder.Event>("Time", 48, keep = 4) { String.format(Locale.ROOT, "%.3f", it.t / 1e9) },
            Col("", 8, keep = 5, left = true) { if (it.outbound) "↑" else "↓" },
            Col("Type", (w / 4).coerceIn(90, 180), keep = 7, left = true, color = { Theme.TEXT }) { it.type },
            Col("Wire", 52, keep = 6, color = { if (it.repeat) Theme.WARN else Theme.MUTED }) { (if (it.repeat) "↻ " else "") + bytes(it.wire.toLong()) },
            Col("About", 70, keep = 3, left = true) { Subject.describe(it.subject) },
            Col("Fields", 0, left = true, color = { Theme.FAINT }) { it.detail ?: "" },
        )
        val full = rows.size == LOG_LIMIT
        ui.table("packet-log", x, y, w, if (full) h - 12 else h, rows, cols, tooltip = { e ->
            PacketReports.line(e).replace(" · ", "\n") + if (e.repeat) "\n\n↻ Identical to one of the same type in the 2 s before" else ""
        }, onClick = null)
        if (full) ui.text("The newest $LOG_LIMIT: filter to see others, or export them all", x, y + h - 9, Theme.FAINT)
    }
}
