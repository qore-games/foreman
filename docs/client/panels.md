# Panels and relations

Plugins of the server can show their own panels for the things they manage and draw how those things are linked: a
generator's switch and fuel beside the generator, the lamps it powers joined to it by arrows. Foreman knows nothing of
what they are: each plugin describes its panel ([Panels and relations](../developers/panels.md) for developers), the
mod draws it and sends back what you change. It all happens in the [world objects](objects.md) tool (++b++, then
++0++).

It needs the Foreman plugin on the server and a plugin there giving panels; without them the keys below aren't
offered.

| | |
|---|---|
| ++n++ | With no object selected: the panel of what you aim at, an entity, a display, a block or a world object (the nearest along the crosshair) |
| ++k++ | Link mode: click a source, then what to link it to |
| ++f++ | Only the selected object's chain: what it reads and what reads it, all the way |
| Left click | On an arrow: select that relation (in link mode too) |
| ++delete++ | With a relation selected: unlink them (press it twice) |
| ++r++ | With a relation selected: reverse it, when its plugin allows it |
| ++i++ / ++o++ | With a relation selected: move its source / its destination to another thing (click it; the same key again: pick it from a list) |
| ++esc++ | Back a step: a target pick, a relation's new end, link mode's source, the selected relation, link mode |

## Panels

++n++, with no object selected, asks the server's plugins which of them has a panel for what you aim at (a plugin may answer for the whole of
it: a piece of furniture for its hitbox or one of its blocks) and opens it beside the world, the game still in view.
It stays live while it is open: values the plugin changes show as they change. When no plugin has one, the panel says
so. A world object's panel is [the object's own](objects.md#the-panel): its form first, its plugin's sections under it
(the same opens with ++n++ on the selected object).

Panels take half the screen's width (between 300 and 520 of its units); text cut short shows whole under the mouse.
A plugin may give a panel a **status line** under its title, in its colour (green working, grey off, red blocked,
orange a setup problem: `Blocked: its switch is off`), and **tabs** (Status, Conditions, Links…): the line and the tabs
stay on top while the tab's content scrolls, and the last tab you picked is kept for that kind of thing.

A panel is made of sections of rows:

- **Text**: a paragraph from the plugin.
- **Values**: a label and a value in the plugin's colour; hover it for the plugin's hint (a **?** after the label).
- **Fields**: values you can change, the same controls as the forms of [typed points](regions.md#typed-points-and-areas)
  and [world objects](objects.md#the-panel): text, numbers with their range, switches, choices, colours, facings,
  areas, and **targets**, another thing of the world, shown by the name its plugin gives it: **Pick** closes the
  panel, you aim at it and click (right click or ++esc++ goes back without one), and the panel comes back with it. A
  field is sent when you leave it (++enter++, a click elsewhere, closing the panel); switches, choices and targets at
  once. The plugin's reason for refusing a value shows under the field (a switch goes back to what it was), the
  outcome on the panel's bottom line.
- **Buttons**: actions; one that asks first shows its question, with the button to confirm.
- **Lists**: items with a colour, a detail and buttons of their own; an item that is a thing of the world (its name
  lights up under the mouse) opens its panel with a click (a world object's own panel, with its form).

- **Conditions**: what a thing works by, a line each: ✓ when it holds now, ✗ when not (`!` when it can't be read, struck
  when turned off for this thing), what it is for and where it comes from, then what it read (`switch.on = false`). The
  things it reads are links: a click opens their panel, so you walk a chain to what is wrong. Its own buttons (turn a
  default off, remove one) sit on its right. See [below](#the-condition-builder) for adding one.

A row kind newer than your mod shows as its text. ++esc++ or **×** closes the panel; the server stops describing it.

### The condition builder

**Add a condition** under a conditions row opens the builder in place:

- **Build**: a **subject**, from the list its plugin gives (this thing, things with a name, world facts) or **Pick in
  the world…** (aim at it and click), then one of its **facts** (each with its type and its value now), a
  **comparison** for that type (is true / is false; `= ≠ < ≤ > ≥` for numbers; `= ≠` for text and states) and a
  **value** (typed, or picked among a state's). **+ or…** adds another way it may hold: it holds when one of them does.
- **Type it**: an expression in the plugin's own syntax (its hint says what it looks like).

Under it, what the plugin makes of it as you change it: how it writes it, whether it holds now and what it read, or why
it can't be read (in red). **Add** (++enter++) sends it; the row shows it with the others.

## Relations

While the world objects tool is on, the server sends the relations its plugins see within 64 blocks of you, refreshed
as they change: an arrow each, from the thing that gives to the thing that takes, in its plugin's colour, with
chevrons flowing along it toward the taker (drawn over what hides the line, so a link's way shows from anywhere), named when
you are near or aim at it. A plugin may say whether what a relation carries gets through: then it is green with its
chevrons flowing when it does, red and still when it doesn't (what reads it isn't met), grey and still when nothing
flows through it all the time (an event).

With an object selected, ++f++ narrows the tool to its chain: only what it reads, what those read, and so on, and what
reads it, all the way; the status card counts them. ++f++ again shows everything. Like the [objects](objects.md#the-tool) the tool doesn't select, they fade out with distance
(full within 12 blocks, gone by 40), but for the one selected or aimed at.

### Editing a relation

Click an arrow to select it, like a cable (in link mode too, when the arrow is in front of what the crosshair is on): it
turns white, drawn over everything, with a card saying what it links as its plugin puts it (`lamp.power ← hall_switch`)
and what its keys do; the status card names its two ends.

- ++delete++, pressed twice: its plugin unlinks them.
- ++r++: the other way round, the thing it came from now taking from the other.
- ++i++: another source. Click the new one in the world (a line follows the crosshair from the end that stays), or press
  ++i++ again to pick it from a list of the objects near you; right click or ++esc++ leaves it as it is.
- ++o++: another destination, the same way.

What these mean is the plugin's (a prop's slot kept, a group's other members left as they are): it makes the new link
and removes the old one, may ask which link (pick one in the list), or, when nothing links them as they are, opens the
panel of what takes the link with its condition builder filled in: the old link goes once you add the condition, and
stays if you don't. A plugin offers only the changes it knows how to make: ++r++, ++i++ and ++o++ show only for those.
Once the server sends the new link, it is selected.

## Linking

++k++ turns link mode on. Click the source (it is outlined, and a line follows the crosshair), then what to link it
to: its plugin links them, refuses with its reason, or asks which link when there are several (an output, a channel):
pick one in the list. When nothing links them as they are, the plugin may open the source's panel with its condition
builder filled in with the other thing (`the generator is working`): finish it and add it.

What the crosshair is on, in link mode, while picking a target and for ++n++, is what the server's plugins serve: aim
at a block or a hitbox of something a plugin manages (the invisible collision block of a piece of furniture) and that
thing is picked, outlined and named as its plugin names it (`floodlight 'hall'`, not a barrier block). Something a
plugin serves also wins over a plain block just in front of or behind it (the wall behind a switch). The mod asks the
server what serves what you aim at, and only in these modes. The source stays picked, so you can link it to several things in a row; right click or ++esc++
drops the source, then leaves link mode (so does ++k++). Without a source, a click on an arrow selects that link
instead ([Editing a relation](#editing-a-relation)).

The panels, edits, links and unlinks are tools of the AI too (`panel_get`, `panel_edit`, `panel_action`, `panel_facts`,
`list_relations`, `link_targets`, `unlink`, `relink`): your permissions, the server's audit log and your name come with
them. These changes go to the plugins, not to Foreman's undo: ++z++ doesn't take a link back (unlink it, or link
again).
