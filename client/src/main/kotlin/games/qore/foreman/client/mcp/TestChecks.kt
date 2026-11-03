package games.qore.foreman.client.mcp

import games.qore.foreman.client.util.bool
import games.qore.foreman.client.util.get
import games.qore.foreman.client.util.obj
import games.qore.foreman.client.util.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

/**
 * client_test's checks on what a step returned (`expect`), the values it captures (`capture`), the \${vars} put into
 * steps, and what of a failed step's output is shown.
 */
internal object TestChecks {
    private val VAR = Regex("""\$\{([A-Za-z0-9_]+)}""")
    private val NUMBER = Regex("""[-+]?\d+(?:\.\d+)?(?:[eE][-+]?\d+)?""")
    private const val LONG = 1200

    /** A check's outcome: why it failed (null when it held) and where in the text, and the numbers it read. */
    class Verdict(val failure: String?, val at: Int? = null, val note: String? = null)

    /**
     * How [text] holds up to [expect]: a text it contains, a list of them, or an object of `contains`, `not_contains`,
     * `regex`, `not_regex`, `number` (the first match's group 1, default the first number) or `all` (every match's),
     * against `min` / `max` (numbers, or arithmetic of them once vars are in: "\${before} + 0.5"), and `error`.
     */
    fun check(expect: JsonElement?, text: String, error: Boolean): Verdict {
        val wantsError = expect.obj()?.get("error").bool() == true
        if (error != wantsError) return Verdict(if (wantsError) "expected it to fail" else "failed: ${shorten(text.lineSequence().firstOrNull() ?: "", 160)}")
        return when (expect) {
            null -> Verdict(null)
            is JsonPrimitive -> Verdict(expect.str()?.takeIf { !text.contains(it, ignoreCase = true) }?.let { "no \"$it\"" })
            is JsonArray -> Verdict(expect.mapNotNull { it.str() }.firstOrNull { !text.contains(it, ignoreCase = true) }?.let { "no \"$it\"" })
            is JsonObject -> checkObject(expect, text)
        }
    }

    private fun checkObject(expect: JsonObject, text: String): Verdict {
        texts(expect["contains"]).firstOrNull { !text.contains(it, ignoreCase = true) }?.let { return Verdict("no \"$it\"") }
        texts(expect["not_contains"]).firstNotNullOfOrNull { t -> text.indexOf(t, ignoreCase = true).takeIf { it >= 0 }?.let { t to it } }
            ?.let { (t, at) -> return Verdict("has \"$t\"", at) }
        texts(expect["regex"]).firstOrNull { !Regex(it).containsMatchIn(text) }?.let { return Verdict("no match for /$it/") }
        texts(expect["not_regex"]).firstNotNullOfOrNull { r -> Regex(r).find(text)?.let { r to it.range.first } }
            ?.let { (r, at) -> return Verdict("matches /$r/", at) }
        val min = bound(expect["min"])
        val max = bound(expect["max"])
        if (min == null && max == null) return Verdict(null)
        val all = expect["all"].str()
        val matches = if (all != null) Regex(all).findAll(text).toList() else listOfNotNull(
            expect["number"].str()?.let { Regex(it).find(text) ?: return Verdict("no match for /$it/") } ?: NUMBER.find(text)
        )
        if (matches.isEmpty()) return Verdict("no match for /${all ?: ""}/")
        val values = matches.map { m ->
            val group = m.groups[1] ?: m.groups[0]!!
            (NUMBER.find(group.value)?.value?.toDoubleOrNull() ?: return Verdict("no number in \"${group.value}\"", m.range.first)) to m.range.first
        }
        val note = if (values.size == 1) "= ${number(values[0].first)}"
            else "${values.size} values ${number(values.minOf { it.first })}..${number(values.maxOf { it.first })}"
        values.firstOrNull { min != null && it.first < min }?.let { return Verdict("${number(it.first)} < ${number(min!!)} ($note)", it.second, note) }
        values.firstOrNull { max != null && it.first > max }?.let { return Verdict("${number(it.first)} > ${number(max!!)} ($note)", it.second, note) }
        return Verdict(null, note = note)
    }

    /** A bound: a number, or a text of arithmetic (vars already put in). */
    private fun bound(e: JsonElement?): Double? {
        val p = e as? JsonPrimitive ?: return null
        if (!p.isString) return p.str()?.toDoubleOrNull()
        val s = p.str() ?: return null
        return try {
            Arithmetic(s).value()
        } catch (_: IllegalArgumentException) {
            throw ToolFailure("min/max \"$s\" isn't a number nor arithmetic of numbers (a var not set?)")
        }
    }

    /** + - * / and brackets on numbers. */
    private class Arithmetic(private val s: String) {
        private var i = 0

        fun value(): Double = sum().also { skip(); require(i == s.length) }

        private fun sum(): Double {
            var v = product()
            while (true) {
                skip()
                v = when (s.getOrNull(i)) {
                    '+' -> { i++; v + product() }
                    '-' -> { i++; v - product() }
                    else -> return v
                }
            }
        }

        private fun product(): Double {
            var v = unary()
            while (true) {
                skip()
                v = when (s.getOrNull(i)) {
                    '*' -> { i++; v * unary() }
                    '/' -> { i++; v / unary() }
                    else -> return v
                }
            }
        }

        private fun unary(): Double {
            skip()
            return when (s.getOrNull(i)) {
                '-' -> { i++; -unary() }
                '+' -> { i++; unary() }
                '(' -> { i++; sum().also { skip(); require(s.getOrNull(i) == ')'); i++ } }
                else -> {
                    val m = Regex("""\d+(?:\.\d+)?(?:[eE][-+]?\d+)?|\.\d+""").find(s, i)?.takeIf { it.range.first == i }
                    require(m != null)
                    i = m.range.last + 1
                    m.value.toDouble()
                }
            }
        }

        private fun skip() {
            while (i < s.length && s[i].isWhitespace()) i++
        }
    }

    /** Puts what [capture]'s regexes find in [text] into [vars]; the first var nothing matched, null when all did. */
    fun capture(capture: JsonObject?, text: String, vars: MutableMap<String, String>): String? {
        capture?.forEach { (name, regex) ->
            val match = Regex(regex.str() ?: return name).find(text) ?: return name
            vars[name] = (match.groups[1] ?: match.groups[0])!!.value
        }
        return null
    }

    /** [e] with \${var} replaced in every string (not in `capture`'s regexes); a string that is one var of a number becomes it. */
    fun substitute(e: JsonElement, vars: Map<String, String>): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.mapValues { (k, v) -> if (k == "capture") v else substitute(v, vars) })
        is JsonArray -> JsonArray(e.map { substitute(it, vars) })
        is JsonPrimitive -> {
            val s = e.str()
            if (!e.isString || s == null || !s.contains("\${")) e
            else {
                val whole = VAR.matchEntire(s)?.groupValues?.get(1)?.let { vars[it] }
                whole?.toLongOrNull()?.let { JsonPrimitive(it) } ?: whole?.toDoubleOrNull()?.let { JsonPrimitive(it) }
                    ?: JsonPrimitive(VAR.replace(s) { m -> vars[m.groupValues[1]] ?: m.value })
            }
        }
        else -> e
    }

    /** What to show of a failed step's [text]: the lines around [at] (what failed the check), else its start. */
    fun excerpt(text: String, at: Int?): String {
        if (at == null || text.length <= LONG) return shorten(text, LONG)
        val lines = text.lines()
        var line = 0
        var offset = 0
        while (line < lines.size - 1 && offset + lines[line].length < at) offset += lines[line++].length + 1
        // the first line too: often a summary or the header of the rows below
        val around = lines.subList(maxOf(1, line - 4), minOf(lines.size, line + 6))
        return shorten(lines.first() + (if (line - 4 > 1) "\n…" else "") + "\n" + around.joinToString("\n"), LONG * 2)
    }

    private fun texts(e: JsonElement?): List<String> = when (e) {
        null -> emptyList()
        is JsonArray -> e.mapNotNull { it.str() }
        else -> listOfNotNull(e.str())
    }

    private fun number(d: Double) = if (d == Math.rint(d) && Math.abs(d) < 1e15) d.toLong().toString() else String.format(Locale.ROOT, "%.3f", d).trimEnd('0')

    fun shorten(s: String, max: Int) = if (s.length <= max) s else s.take(max - 1) + "…"
}
