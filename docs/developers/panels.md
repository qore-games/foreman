# Panels and relations

Your plugin can show its own panels in Foreman's editor and draw the relations between the things it manages: a
generator's switch and fuel level beside the generator, the lamps it powers linked to it by arrows. Builders open a
panel by aiming at something (an entity, a block, a [world object](world-objects.md)) with the
[objects tool](../client/panels.md); Foreman asks the plugins which of them has a panel for it, shows it beside the
world, keeps its values live while it is open and sends what the builder changes back to the plugin. The same panels
are tools for the AI (`panel_get`, `panel_edit`, `panel_action`, `list_relations`, `link_targets`, `unlink`, `relink`).

Foreman knows nothing about what a panel shows: you describe it as data (sections of rows), Foreman draws it. Nothing
runs while no panel is open and nobody shows relations.

`compileOnly` on `games.qore:foreman-server-api`: [Add Foreman to your plugin](install.md#the-server-api).

## Registering

`ForemanPanels` is a Bukkit service, there whenever Foreman runs with its `panels` feature (on in the `dev` preset, off
in `production`: [features](../server/setup.md#features)); the world tools aren't needed:

```java
ForemanPanels panels = Bukkit.getServicesManager().load(ForemanPanels.class); // null without Foreman
if (panels != null) panels.register(this, new LeverPanels());
```

| | |
|---|---|
| `register(plugin, provider)` | Adds it, asked after those registered before; registering your own id again replaces it in place. Another plugin's id is refused (`IllegalArgumentException`) |
| `unregister(plugin, id)`, `providers()` | Your providers go when your plugin disables |
| `refresh(target)` | What the target's panel or relations show changed on your side: its open panels are described again at the next tick instead of their next poll, relations near builders asked again. Any thread |

When Foreman enables after you, register when its service registers (`ServiceRegisterEvent` for
`games.qore.foreman.server.api.ForemanPanels`), as with [layout types](layout-types.md#when-foreman-enables-after-you).

## Targets

A `PanelTarget` is what a panel is about. Its JSON is what the tools and the mod exchange; a world is named by its name
(Foreman also reads a world's UUID or key there, or a `dim` key instead, and adds `dim` to the targets it sends):

| Target | JSON | Meaning |
|---|---|---|
| `PanelTarget.entity(entity)`, `entity(world, uuid)` | `{"type":"entity","world":"world","uuid":"…"}` | an entity |
| `PanelTarget.block(block)`, `block(world, x, y, z)` | `{"type":"block","world":"world","x":0,"y":64,"z":0}` | a block |
| `PanelTarget.worldObject(world, kind, id)` | `{"type":"object","world":"world","kind":"ns:name","id":"…"}` | a [world object](world-objects.md) |
| `PanelTarget.global(id)` | `{"type":"global","id":"ns:name"}` | something not in the world (a plugin's settings) |

`getType()`, `getWorldName()`, `getUuid()`, `getX()`… read it; `world()`, `findEntity()` and `findBlock()` look it up
(null when unloaded or gone); `toMap()` and `PanelTarget.of(map)` are its JSON as plain values. Equal targets name the
same thing (an entity by its world and UUID: worlds cloned from one map share entity UUIDs, so `findEntity()` looks
in the target's own world).

What a builder aims at reaches each provider's `resolve(aimed)` in registration order; the first that returns a target
serves it, and the target it returns is the panel's. A provider may map a part to its whole (a furniture's hitbox or
cell block to its root entity). The tools name a panel by that target, so `resolve` also gets your own targets back:
return them as they are.

A [world object](world-objects.md)'s panel opens inside the object's own in the mod: its form first, then the panel of
the provider that resolves its `object` target, in one scrolling panel ([Panels for your
objects](world-objects.md#panels-for-your-objects)). Resolve your objects' targets to serve them there.

## A provider

```java
public interface PanelProvider {
    String id();                                         // "ns:name"
    String label();                                      // "Generators"
    @Nullable PanelTarget resolve(PanelTarget aimed);
    @Nullable Panel describe(PanelContext ctx);          // null: it is gone
    // Optional: the defaults use describe's title, refuse, or have no relations.
    default @Nullable String name(PanelContext ctx);
    default PanelResult edit(PanelContext ctx, String key, @Nullable Object value);
    default PanelResult action(PanelContext ctx, String id, Map<String, Object> args);
    default List<Relation> relations(World world, BoundingBox box);
    default LinkResult link(PanelContext ctx, PanelTarget from, PanelTarget to, @Nullable String choice);
    default PanelResult unlink(PanelContext ctx, String relationId);
    default LinkResult relink(PanelContext ctx, String relationId, Relation.Change change, @Nullable PanelTarget to, @Nullable String choice);
    // Optional: the vocabulary of your conditions rows (below).
    default List<ConditionSubject> subjects(PanelContext ctx, String row);
    default @Nullable ConditionFacts facts(PanelContext ctx, String row, @Nullable String subject, @Nullable PanelTarget target);
    default ConditionCheck check(PanelContext ctx, String row, Condition condition);
}
```

`PanelContext` holds the `target` (resolved), the acting `player` (null for the console, or an AI with the server's
token) and `callerName`: check your own permissions there. Every method is called on the server thread. An exception
fails that call only (the builder or the AI reads it) and is logged once per provider and method.

`describe` is called when a panel opens, then twice a second while it is open (or at once after `refresh`): keep it
cheap. Foreman sends the panel only when it changed.

`name` is what a target is called for a builder aiming at it in link mode, or before its panel opens (`Generator
'north'`): the mod outlines and names what `resolve` gave rather than the block or hitbox aimed at. It is asked when
the builder's aim rests on a thing there, again every few seconds while it stays; the default describes the panel for
its title, so give a cheaper one when `describe` isn't cheap.

A small one, for any lever:

```java
final class LeverPanels implements PanelProvider {
    public String id() { return "example:levers"; }
    public String label() { return "Levers"; }

    public PanelTarget resolve(PanelTarget aimed) {
        Block block = aimed.findBlock();
        return block != null && block.getType() == Material.LEVER ? aimed : null;
    }

    public Panel describe(PanelContext ctx) {
        Block block = ctx.getTarget().findBlock();
        if (block == null || block.getType() != Material.LEVER) return null;
        boolean on = ((Powerable) block.getBlockData()).isPowered();
        return Panel.of("Lever").subtitle(on ? "On" : "Off")
            .section(Section.of("State")
                .row(Row.field(LayoutField.bool("on").label("Powered").required(), on))
                .row(Row.button("toggle", "Toggle").style(ButtonRow.Style.PRIMARY)));
    }

    public PanelResult edit(PanelContext ctx, String key, Object value) {
        power(ctx.getTarget().findBlock(), (Boolean) value);
        return PanelResult.ok();
    }

    public PanelResult action(PanelContext ctx, String id, Map<String, Object> args) {
        Block block = ctx.getTarget().findBlock();
        power(block, !((Powerable) block.getBlockData()).isPowered());
        return PanelResult.ok("Toggled");
    }

    private static void power(Block block, boolean on) {
        Powerable data = (Powerable) block.getBlockData();
        data.setPowered(on);
        block.setBlockData(data);
    }
}
```

## Panels

A `Panel` has a `title`, an optional `subtitle`, an optional `status` and `sections`; a `Section` a `title` (empty:
none), an optional `id`, its `rows` and an optional `tab`. Everything is immutable: each call returns a changed copy.

| Row | Builder | Shows |
|---|---|---|
| text | `Row.text("…")` | a paragraph |
| value | `Row.value("Fuel", "12 / 20").color(0xE0B040).hint("…")` | a label and a read-only value |
| field | `Row.field(LayoutField.number("fuel").range(0, 20), 12).hint("…")` | an editable value: the [layout field types](layout-types.md#fields) (string, int, number, bool, choice, color, yaw, area) and `LayoutField.target(name)`: another target, picked in the world |
| button | `Row.button("start", "Start").style(ButtonRow.Style.PRIMARY).confirm("Start it?").args(Map.of(…)).enabled(false).hint("Out of fuel")` | an action; styles `NORMAL`, `PRIMARY`, `DANGER`, `GHOST`; a disabled button shows its hint as why |
| list | `Row.list(ListItem.of("lamp_2", "Lamp 2").detail("off").color(…).target(lamp).actions(Row.button("unlink", "Unlink")))` | items, each with a detail, the target it is about (the mod shows where it is) and its own buttons |
| conditions | `Row.conditions("requires").item(ConditionItem.of(…)).add(Row.button("add", "Add a condition")).pick(true)` | conditions with whether each holds now, and a builder adding new ones ([below](#conditions)) |

Every row has a text form (`row.text()`): what the AI reads, and what a mod that doesn't know a row's kind draws, so
kinds can be added freely.

A field row's value is a String, Number, Boolean or `PanelTarget` as its type holds (a colour as `0xRRGGBB` or
`"#rrggbb"`), null when unset.

### Tabs and status

A panel that shows much (a machine's state, what it works by, its links, its setup, its history) can be cut into tabs:
`Section.tab("Status")` puts a section on that tab. The mod shows a tab bar under the title, the tabs in the order the
sections name them first, and keeps the last tab a builder picked for that kind of thing (a world object's kind, else
your provider). Sections without a tab show on every tab; a panel whose sections name no tab is one page, as before.
Under a [world object's form](world-objects.md#panels-for-your-objects), a field group goes to the section of the same
title, wherever its tab, and the object's anchors to your section titled `Location` when there is one; what no section
takes shows on the first tab.

`Panel.status(Status)` is its status line, above every tab in its level's colour:

```java
Panel.of("Lamp 'hall'").status(Status.blocked("Blocked").detail("its switch is off"))
```

`Status.ok`, `off`, `blocked` and `problem` (green, grey, red, orange) take a word or two; `detail` says why. The same
`Status` is a world object's [badge](world-objects.md#status-badges).

### Edits and actions

A field's edit is checked against the field the panel shows now (its type, choices, range, and required: null clears a
field that isn't) before it reaches `edit(ctx, key, value)`, the value as Java has it: a `String` (string, choice,
colour `#rrggbb`, area), `Integer` (int), `Double` (number, yaw), `Boolean`, `PanelTarget` (target, resolved by the
providers), or null. A key the panel has no field for is refused before it reaches you.

`action(ctx, id, args)` gets only the ids of enabled buttons the panel shows now, with the button's `args`. A list
item's buttons get `item`, the item's id, in their args besides their own: several items may have an `unlink` button.

`PanelResult` says how it went: `PanelResult.ok()`, `ok("Started")` (a message to the builder),
`refused("Out of fuel")`, `errors(Map.of("fuel", "at most 20"))` (shown under the fields), each optionally
`.panel(p)`: the panel as it is now (else Foreman describes it again).

## Conditions

Things that work by conditions (a lamp working while its switch is on, a gate opening at night) can show and edit them
in their panel, whatever your syntax: a `ConditionsRow` lists them, each with whether it holds now and what it read,
and the mod draws a builder under it that makes new ones from your vocabulary. Foreman never parses a condition.

```java
Row.conditions("requires").label("Works when")
    .item(ConditionItem.of("power", "switch.on").label("Power").origin("definition")
        .holds(false).reads("switch.on = false")
        .subject(ConditionSubject.of("switch", "Switch 'hall'").target(switchTarget))
        .actions(Row.button("toggle", "Turn off").args(Map.of("on", false))))
    .add(Row.button("add_requirement", "Add a condition"))
    .pick(true)
    .syntax("<name>.<fact> == <value>; and, or, not");
```

| `ConditionItem` | |
|---|---|
| `of(id, text)` | its id and the condition as your syntax writes it |
| `label`, `origin` | what it is for, where it comes from (`definition`, `added`) |
| `holds(Boolean)`, `reads`, `error` | whether it holds now (null: unknown), the values it read, why it can't be read |
| `enabled(false)` | turned off for this thing: shown struck |
| `subject(ConditionSubject)` | a thing it reads: with a target, its name opens that thing's panel (builders walk a chain) |
| `any(items)` | an OR group's alternatives, shown under it |
| `actions(buttons)` | its buttons; their `action` gets `item` (its id) in its args, as list items' do |

The builder offers what your provider gives:

- `subjects(ctx, row)`: what conditions can read, each a `ConditionSubject.of(id, label)` with a `detail`, the `group`
  it is listed under (`This`, `Named`, `World`) and its `target` (shown where it is). With `pick(true)` the builder
  also offers to pick a thing in the world.
- `facts(ctx, row, subject, target)`: the facts of a subject (its id, or the target a builder picked, resolved by the
  providers): `ConditionFacts.of(subject, facts)`, the subject as conditions will name it (a thing without a name yet
  may be given one when the condition is added). A `ConditionFact` is `bool`, `number` (`range`), `text` or `choice`
  (its values: a state), with its value `now`, a `label` and a `description`; the builder offers the comparisons of its
  type (is true / is false; `== != < <= > >=`; `== !=`) and the values that fit.
- `check(ctx, row, condition)`: how you write the condition (`ConditionCheck.of(expression, holds, reads)`) or why it
  can't be read (`ConditionCheck.error(why)`): asked as the builder changes it, so the builder sees ✓ or ✗ before
  adding it, and what your syntax looks like.

Add sends the row's `add` button with `condition` in its args, a `Condition`'s map: `Condition.of(map)` reads it.
`condition.isTyped` with `expression` (typed in the builder's raw field, in your syntax), else its `terms` (one must
hold: one term, or an OR group), each `subject` (an id of yours; `target` when picked in the world), `fact`, `op`
(`Condition.Op`) and `value` (a Boolean, a Double or a String), and an optional `label`.

## Relations

`relations(world, box)` lists what links your things that reach into a part of a world: `Relation.of(id, from, to)`
with the points the arrow runs between (`.points(fromPoint, toPoint)`; without them Foreman takes the centres of the
entities, blocks and objects the targets name), `.label("powers")`, `.color(0x44AAFF)`, and `.state(state)`: whether
what it carries gets through now, which the mod shows over its colour: `ACTIVE` green with its chevrons flowing,
`BLOCKED` red and still (what reads it isn't met), `IDLE` grey and still (an event sent now and then). Give world
objects' relations their objects' targets: the mod's chain filter follows the relations from the selected object. An `id` is yours, unique among
your relations: what `unlink(ctx, id)` gets. Foreman asks for those within 64 blocks of each builder who shows
relations (the objects tool) once a second, and draws an arrow per relation; keep it cheap.

The objects tool's link mode picks a source, then a target: `link(ctx, from, to, null)` on the provider serving `from`
(`to` is resolved by the providers). Return `LinkResult.done("Linked")`, `refused("Too far")`, or
`choose("Which output?", LinkResult.Option.of("out_1", "Output 1"), …)`: the builder picks one and `link` is called
again with its id as `choice`, as many rounds as you need. Ask only when there is a choice to make: with one way to
link them, link at once and say which in the message. When no link fits as it is but a condition could say what one
needs of the other, `LinkResult.build(row, condition, message)` opens `from`'s panel on the tab of its conditions row
`row` with the builder filled in (`Condition.of(Condition.term(name, "on", Condition.Op.EQ, true, toTarget))`), for the
builder to finish and add.

### Changing a relation

Builders select a relation like a cable and change it with keys ([Editing a relation](../client/panels.md#editing-a-relation)):
unlink it, and the changes you offer on it, `.changes(Relation.Change.REVERSE, Relation.Change.FROM, Relation.Change.TO)`
(none by default: only unlinking). `.detail("lamp.power ← hall_switch")` is what its card says once selected (its label
by default): what reads what, in your words.

Each change calls `relink(ctx, relationId, change, to, choice)` on your provider, `ctx.target` the relation's `from`:

| `change` | `to` | Means |
|---|---|---|
| `REVERSE` | null | the other way round: what took from the other end gives to it now |
| `FROM` | the new `from` end (resolved by the providers) | another thing gives to its `to` end |
| `TO` | the new `to` end | its `from` end gives to another thing |

What these mean is yours (which of your things holds the link, a slot kept, a group's other members left alone): answer
as `link` does. `done`: you made the new link and removed the old one. `choose`: `relink` is called again with the
builder's `choice`. `build(row, condition, message).on(target)`: no link fits as it is, so the panel of `target` (the
thing that takes the condition: Foreman can't know which) opens on its conditions row with the builder filled in, and
Foreman calls your `unlink` for the old relation once the builder adds the condition (it stays if they don't): keep
your `unlink` harmless for a relation already gone. `refused`: nothing changed. The default refuses.

## Tools

| Tool | Does |
|---|---|
| `panel_get {target \| aimed: true, provider?, format?}` | The panel: text for the AI, or `format: json` `{aimed, target, provider, provider_label, panel}` (`target` and `panel` null: no panel) |
| `panel_edit {target, key, value, provider?, format?}` | Sets a field: `{ok, message?, errors?, panel?}` with `format: json` (a refusal is `ok: false`, not a tool error) |
| `panel_action {target, id, args?, provider?, format?}` | Presses a button: the same result. A conditions row's add button takes `args: {condition}` |
| `panel_facts {target, row, subject?, pick?, check?, provider?, format?}` | A conditions row's vocabulary: its subjects; with `subject` or `pick` (a target) its facts; with `check` (a condition) whether it holds now: `subjects`, `facts` and `check` above |
| `list_relations {world?, center?, radius?, format?}` | The relations near a point or the calling player: `{world, dim, relations: [relation]}` |
| `link_targets {from, to, choice?, format?}` | `{status: done\|refused\|choose\|build, message?, question?, options?: [{id, label, hint?}], row?, condition?, target?, provider}` |
| `unlink {target, relation, provider?, format?}` | `{ok, message?, errors?, panel?}` |
| `relink {target, relation, provider?, reverse \| from \| to, choice?, format?}` | As `link_targets`; with `build`, `replaces: {relation, provider, target}`: unlink it once the condition is added |

`aimed: true` is what the calling player aims at within 64 blocks (a world object's outline, an entity, a block). Edits,
actions, links and unlinks are tool calls: the caller's permissions and the [audit log](../security.md) come with
them. They need the `panels` feature. Foreman's undo doesn't cover them: what they change is your plugin's.

## Protocol (for the mod)

Over the bridge (`foreman:bridge`, JSON objects with a `type`):

- S2C `hello` has `panels` in `features` and `panels_version: 1`. The mod answers with its own
  `{"type":"hello","panels":1,"rows":["text","value","field","button","list","conditions"]}`: rows of other kinds are
  sent to it as `{"kind":"text","text":…}`. A mod without it gets every row.
- C2S `{"type":"panel_open","target":<target>}`: the panel of what the builder aims at. S2C
  `{"type":"panel","aimed":<the target as sent>,"target":<target or null>,"provider":"ns:name","provider_label":"…","panel":<panel or null>}`,
  again whenever its JSON changes (described every 10 ticks, at once after `refresh`); `target` null: no panel for it
  (or not any more), `error` when the provider failed. C2S `{"type":"panel_close"}`.
- C2S `{"type":"panel_resolve","id":"…","target":<target>}`: what serves a target, asked by the link mode, a target
  pick and the inspector for the things along the crosshair (after the aim rests on them, again every 5 seconds while
  it stays), and for the targets fields show (their provider's name for them, again every 5 seconds while shown). S2C
  `{"type":"panel_resolved","id":<as sent>,"aimed":<the target as sent>,"target":<target or null>,"provider"?,"provider_label"?,"title"?}`:
  `target` null when no provider serves it, `title` the provider's `name`. At most 24 a second per builder are
  answered; the mod asks again those left unanswered.
- C2S `subscribe` / `unsubscribe` layer `relations`: S2C `{"type":"relations","world":"…","dim":"…","relations":[…]}`,
  those within 64 blocks that have both points, once a second when they changed.
- Edits, actions, links, unlinks, relinks and a conditions row's vocabulary go through the MCP tools (the mod calls them
  like its other tools, `format: json`).

A panel's JSON:

```json
{"title": "…", "subtitle": "…", "status": {"level": "ok|off|blocked|problem", "word": "…", "detail": "…"},
 "sections": [{"title": "…", "id": "…", "tab": "…", "rows": [ROW, …]}]}
```

Every row has `kind` and `text` (its text form):

| `kind` | Fields |
|---|---|
| `text` | |
| `value` | `label`, `value`, `color?` (`"#rrggbb"`), `hint?` |
| `field` | `field` (as [layout types](layout-types.md) send it: `name`, `type`, `label`, `description`, `required`, `default?`, `values?`, `min?`, `max?`), `value` (current, null when unset; a target's JSON for `type: target`), `hint?` |
| `button` | `id`, `label`, `style` (`normal`, `primary`, `danger`, `ghost`), `confirm?`, `args?`, `enabled`, `hint?` |
| `list` | `items`: `[{id, label, detail?, color?, target?, actions: [button rows, their args with item]}]` |
| `conditions` | `id`, `label?`, `conditions: [{id, text, label?, holds?, reads?, error?, origin?, enabled, subjects?: [{id, label, detail?, group?, target?}], any?: [conditions], actions: [button rows]}]`, `add?` (a button row), `pick?`, `syntax?` |

A relation: `{"id", "provider", "from": <target>, "to": <target>, "from_point": [x, y, z], "to_point": [x, y, z],
"label"?, "color"?, "state"?, "detail"?, "changes"?}` (`state` `active`, `blocked` or `idle`; `changes` among
`reverse`, `from`, `to`); `unlink` takes `{target: its from, relation: its id, provider}`, `relink` the same and
`reverse: true`, `from` or `to` (a target). `link_targets` and `relink` answer `{status: "build", row, condition,
message?, target?}` for `LinkResult.build` (`target`: the panel to open, else the link's `from`).
