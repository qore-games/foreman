package games.qore.foreman.server.panels

import com.google.gson.JsonNull
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import games.qore.foreman.server.api.ButtonRow
import games.qore.foreman.server.api.Condition
import games.qore.foreman.server.api.ConditionItem
import games.qore.foreman.server.api.ConditionSubject
import games.qore.foreman.server.api.LayoutData
import games.qore.foreman.server.api.LayoutField
import games.qore.foreman.server.api.LinkResult
import games.qore.foreman.server.api.ListItem
import games.qore.foreman.server.api.Panel
import games.qore.foreman.server.api.PanelContext
import games.qore.foreman.server.api.PanelProvider
import games.qore.foreman.server.api.PanelResult
import games.qore.foreman.server.api.PanelTarget
import games.qore.foreman.server.api.Relation
import games.qore.foreman.server.api.Row
import games.qore.foreman.server.api.Section
import games.qore.foreman.server.api.Status
import games.qore.foreman.server.mcp.ToolError
import org.bukkit.util.Vector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PanelJsonTest {
    private val uuid = "00000000-0000-0000-0000-00000000002a"
    private fun json(text: String) = JsonParser.parseString(text)

    @Test
    fun targetsRoundTrip() {
        for (text in listOf(
            """{"type":"entity","world":"world","uuid":"$uuid"}""",
            """{"type":"block","world":"world","x":1,"y":-64,"z":3}""",
            """{"type":"object","world":"world","kind":"rides:zipline","id":"abc"}""",
            """{"type":"global","id":"gens:settings"}""",
        )) {
            val target = PanelJson.target(json(text))
            assertEquals(json(text), PanelJson.target(target), text)
            assertEquals(target, PanelJson.target(PanelJson.target(target)))
        }
    }

    @Test
    fun targetsFromTheMod() {
        // The mod sends the world's name and its dim; numbers come as doubles.
        val t = PanelJson.target(json("""{"type":"block","world":"world","dim":"minecraft:overworld","x":1.0,"y":2,"z":3}"""))
        assertEquals(PanelTarget.Type.BLOCK, t.type)
        assertEquals(listOf(1, 2, 3), listOf(t.x, t.y, t.z))
        // Worlds cloned from one map share entity UUIDs: an entity is itself in its world.
        assertEquals(PanelJson.target(json("""{"type":"entity","world":"w","uuid":"$uuid"}""")), PanelJson.target(json("""{"type":"entity","world":"w","uuid":"$uuid"}""")))
        assertNotEquals(PanelJson.target(json("""{"type":"entity","world":"a","uuid":"$uuid"}""")), PanelJson.target(json("""{"type":"entity","world":"b","uuid":"$uuid"}""")))
        assertNotEquals(PanelJson.target(json("""{"type":"block","world":"a","x":0,"y":0,"z":0}""")), PanelJson.target(json("""{"type":"block","world":"b","x":0,"y":0,"z":0}""")))
    }

    @Test
    fun targetsInObjectData() {
        // An object's data holds a target field as its JSON map (numbers as doubles), or as the PanelTarget its plugin put there.
        val target = PanelJson.target(json("""{"type":"block","world":"world","x":1,"y":2,"z":3}"""))
        val data = LayoutData(mapOf("input" to PanelJson.plain(PanelJson.target(target)), "made" to target, "name" to "north"))
        assertEquals(target, data.target("input"))
        assertEquals(target, data.target("made"))
        assertNull(data.target("name"))
        assertNull(data.target("missing"))
        assertEquals(PanelJson.target(target), PanelJson.json(data.target("made")))
    }

    @Test
    fun badTargets() {
        for (text in listOf("""{"type":"chunk"}""", """{"type":"block","world":"w","x":1.5,"y":0,"z":0}""", """{"type":"block","x":1,"y":0,"z":0}""",
            """{"type":"entity","uuid":"nope"}""", """{"type":"global","id":"plain"}""", "[1, 2]")) {
            assertThrows(ToolError::class.java, { PanelJson.target(json(text)) }, text)
        }
    }

    @Test
    fun fieldValues() {
        val fuel = LayoutField.integer("fuel").range(0.0, 20.0)
        assertEquals(12, PanelJson.parse(fuel, JsonPrimitive(12.0)).value)
        assertEquals("at most 20", PanelJson.parse(fuel, JsonPrimitive(21)).error)
        assertTrue(PanelJson.parse(fuel, JsonPrimitive(1.5)).error != null)
        assertTrue(PanelJson.parse(fuel, JsonPrimitive("3")).error != null)
        assertNull(PanelJson.parse(fuel, JsonNull.INSTANCE).value)
        assertEquals("fuel is required", PanelJson.parse(fuel.required(), null).error)
        assertEquals(0.5, PanelJson.parse(LayoutField.number("t"), JsonPrimitive(0.5)).value)
        assertEquals("b", PanelJson.parse(LayoutField.choice("c", "a", "b"), JsonPrimitive("b")).value)
        assertTrue(PanelJson.parse(LayoutField.choice("c", "a", "b"), JsonPrimitive("z")).error != null)
        assertEquals("#a0b0c0", PanelJson.parse(LayoutField.color("c"), JsonPrimitive("#A0B0C0")).value)
        assertEquals(true, PanelJson.parse(LayoutField.bool("on"), JsonPrimitive(true)).value)
        val target = PanelJson.parse(LayoutField.target("source"), json("""{"type":"global","id":"a:b"}"""))
        assertEquals(PanelTarget.global("a:b"), target.value)
        assertTrue(PanelJson.parse(LayoutField.target("source"), JsonPrimitive("a:b")).error != null)
    }

    /** What a small provider would show: a lamp switch, with the lamps it powers. */
    private fun panel() = Panel.of("Switch").subtitle("On")
        .section(Section.of("State").id("state")
            .row(Row.value("Load", "2 / 4").color(0xE0B040).hint("lamps on / lamps linked"))
            .row(Row.field(LayoutField.bool("on").label("On"), true))
            .row(Row.field(LayoutField.target("source"), PanelTarget.global("example:grid")))
            .row(Row.button("reset", "Reset").style(ButtonRow.Style.DANGER).confirm("Reset it?").enabled(false).hint("Already reset")))
        .section(Section.of("Lamps").row(Row.list(
            ListItem.of("lamp_1", "Lamp 1").detail("on").target(PanelTarget.global("example:lamp_1")).actions(Row.button("unlink", "Unlink")),
        )))

    @Test
    fun panelJson() {
        val p = PanelJson.panel(panel())
        assertEquals("Switch", p["title"].asString)
        assertEquals("On", p["subtitle"].asString)
        val rows = p.getAsJsonArray("sections")[0].asJsonObject.getAsJsonArray("rows").map { it.asJsonObject }
        assertEquals(listOf("value", "field", "field", "button"), rows.map { it["kind"].asString })
        assertTrue(rows.all { it.has("text") })
        assertEquals("#e0b040", rows[0]["color"].asString)
        assertEquals("bool", rows[1].getAsJsonObject("field")["type"].asString)
        assertEquals("target", rows[2].getAsJsonObject("field")["type"].asString)
        assertEquals("global", rows[2].getAsJsonObject("value")["type"].asString)
        assertEquals("danger", rows[3]["style"].asString)
        assertFalse(rows[3]["enabled"].asBoolean)
        val item = p.getAsJsonArray("sections")[1].asJsonObject.getAsJsonArray("rows")[0].asJsonObject.getAsJsonArray("items")[0].asJsonObject
        assertEquals("lamp_1", item.getAsJsonArray("actions")[0].asJsonObject.getAsJsonObject("args")["item"].asString)
        assertEquals("global", item.getAsJsonObject("target")["type"].asString)
    }

    @Test
    fun unknownRowKindsAsText() {
        val p = PanelJson.panel(panel(), setOf("text", "value"))
        val rows = p.getAsJsonArray("sections")[0].asJsonObject.getAsJsonArray("rows").map { it.asJsonObject }
        assertEquals(listOf("value", "text", "text", "text"), rows.map { it["kind"].asString })
        assertEquals("On: true (field on)", rows[1]["text"].asString)
    }

    @Test
    fun results() {
        val ok = PanelJson.result(PanelResult.ok("Started"), null)
        assertEquals(json("""{"ok":true,"message":"Started"}"""), ok)
        val errors = PanelJson.result(PanelResult.errors(mapOf("fuel" to "at most 20")), null)
        assertEquals(json("""{"ok":false,"errors":{"fuel":"at most 20"}}"""), errors)
        val choose = PanelJson.link(LinkResult.choose("Which output?", LinkResult.Option.of("a", "Output A")))
        assertEquals(json("""{"status":"choose","question":"Which output?","options":[{"id":"a","label":"Output A"}]}"""), choose)
        assertEquals(json("""{"status":"refused","message":"Too far"}"""), PanelJson.link(LinkResult.refused("Too far")))
    }

    @Test
    fun text() {
        val text = panel().text()
        assertTrue("Load: 2 / 4" in text, text)
        assertTrue("- Lamp 1: on" in text, text)
        assertTrue("[Unlink] (button unlink, args {item: lamp_1})" in text, text)
        assertEquals("source: target {type, world, …}", PanelText.field(LayoutField.target("source")))
        assertEquals("fuel*: int 0..20 — liters", PanelText.field(LayoutField.integer("fuel").range(0.0, 20.0).required().description("liters")))
    }

    @Test
    fun aimAtSegments() {
        val from = Vector(0, 0, 0)
        val dir = Vector(1, 0, 0)
        // A segment crossing the ray 5 blocks ahead, 0.2 above it.
        val (along, gap) = Aim.closest(from, dir, Vector(5.0, 0.2, -1.0), Vector(5.0, 0.2, 1.0))
        assertEquals(5.0, along, 1e-9)
        assertEquals(0.2, gap, 1e-9)
        // Behind the eye: measured from the eye.
        val (behind, far) = Aim.closest(from, dir, Vector(-3.0, 0.0, -1.0), Vector(-3.0, 0.0, 1.0))
        assertEquals(0.0, behind, 1e-9)
        assertTrue(far > 2.9)
        // A point (a one-anchor object).
        val (at, off) = Aim.closest(from, dir, Vector(2.0, 0.1, 0.0), Vector(2.0, 0.1, 0.0))
        assertEquals(2.0, at, 1e-9)
        assertEquals(0.1, off, 1e-9)
    }

    @Test
    fun tabsStatusAndConditions() {
        val target = PanelJson.target(json("""{"type":"block","world":"world","x":1,"y":2,"z":3}"""))
        val panel = Panel.of("Lamp").status(Status.blocked("Blocked").detail("the switch is off"))
            .section(Section.of("Why").tab("Status").row(Row.text("…")))
            .section(Section.of("Works when").tab("Conditions").row(Row.conditions("requires").label("Works when")
                .item(ConditionItem.of("power", "power.on").label("Power").holds(false).reads("power.on = false")
                    .subject(ConditionSubject.of("switch", "Switch").target(target)).actions(Row.button("disable", "Turn off")))
                .add(Row.button("add", "Add")).pick(true)))
            .section(Section.of("Note").row(Row.text("on every tab")))
        assertEquals(listOf("Status", "Conditions"), panel.tabs())
        val out = PanelJson.panel(panel)
        assertEquals("blocked", out.getAsJsonObject("status").get("level").asString)
        assertEquals("Conditions", out.getAsJsonArray("sections")[1].asJsonObject.get("tab").asString)
        val row = out.getAsJsonArray("sections")[1].asJsonObject.getAsJsonArray("rows")[0].asJsonObject
        assertEquals("conditions", row.get("kind").asString)
        val item = row.getAsJsonArray("conditions")[0].asJsonObject
        assertFalse(item.get("holds").asBoolean)
        assertEquals("power", item.getAsJsonArray("actions")[0].asJsonObject.getAsJsonObject("args").get("item").asString)
        // A mod that doesn't draw conditions gets their text; the AI reads how to add one.
        val old = PanelJson.panel(panel, setOf("text", "value", "field", "button", "list"))
        assertEquals("text", old.getAsJsonArray("sections")[1].asJsonObject.getAsJsonArray("rows")[0].asJsonObject.get("kind").asString)
        // The add button and the items' buttons are the panel's, pressed through panel_action.
        val buttons = panel.rows().filterIsInstance<games.qore.foreman.server.api.ConditionsRow>().single().buttons().map { it.id }
        assertEquals(listOf("add", "disable"), buttons)
    }

    @Test
    fun conditionsFromJson() {
        val typed = PanelJson.condition(json("""{"expression":"switch.on and not alarm"}"""))
        assertTrue(typed.isTyped)
        val built = PanelJson.condition(json("""{"any":[{"subject":"switch","fact":"on","op":"==","value":true},{"subject":"@world","fact":"time","op":">=","value":13000}],"label":"Lit"}"""))
        assertEquals(2, built.terms.size)
        assertEquals(Condition.Op.GE, built.terms[1].op)
        assertEquals("Lit", built.label)
        assertEquals(built.toMap(), Condition.of(built.toMap()).toMap())
        // One term alone reads as a condition too.
        assertEquals(1, PanelJson.condition(json("""{"subject":"this","fact":"fuel","op":"<","value":3}""")).terms.size)
        assertThrows(ToolError::class.java) { PanelJson.condition(json("""{"any":[{"fact":"on"}]}""")) }
        assertThrows(ToolError::class.java) { PanelJson.condition(json("""{"subject":"a","fact":"b","op":"=~"}""")) }
    }

    @Test
    fun buildLinks() {
        val result = LinkResult.build("requires", Condition.of(Condition.term("gen", "working", Condition.Op.EQ, true)), "No slot fits")
        val out = PanelJson.link(result)
        assertEquals("build", out.get("status").asString)
        assertEquals("requires", out.get("row").asString)
        assertEquals("gen", out.getAsJsonObject("condition").getAsJsonArray("any")[0].asJsonObject.get("subject").asString)
        assertEquals("No slot fits", out.get("message").asString)
        assertNull(out.get("target"))
        val on = PanelJson.link(result.on(PanelTarget.global("gens:lamp")))
        assertEquals(json("""{"type":"global","id":"gens:lamp"}"""), on.get("target"))
    }

    @Test
    fun relationChanges() {
        val provider = object : PanelProvider {
            override fun id() = "gens:links"
            override fun label() = "Links"
            override fun resolve(aimed: PanelTarget): PanelTarget? = null
            override fun describe(ctx: PanelContext): Panel? = null
        }
        val entry = Panels.Entry("gens:links", "Gens", provider)
        val plain = Relation.of("a:power:b", PanelTarget.global("gens:b"), PanelTarget.global("gens:a")).label("power")
        val out = PanelJson.relation(Panels.Found(entry, plain, null, null))
        assertNull(out.get("changes"))
        assertNull(out.get("detail"))
        val editable = plain.detail("a.power ← b").changes(Relation.Change.TO, Relation.Change.REVERSE)
        val more = PanelJson.relation(Panels.Found(entry, editable.points(Vector(0, 0, 0), Vector(1, 0, 0)), null, null))
        assertEquals("a.power ← b", more.get("detail").asString)
        assertEquals(json("""["reverse","to"]"""), more.get("changes"))
        assertTrue(PanelText.relation(Panels.Found(entry, editable, null, null)).endsWith("relink: reverse|to"))
        val ctx = PanelContext(PanelTarget.global("gens:b"), null, "console")
        assertEquals(LinkResult.Status.REFUSED, provider.relink(ctx, "a:power:b", Relation.Change.REVERSE, null, null).status)
    }
}
