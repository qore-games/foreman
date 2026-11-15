# World objects

Plugins of the server can let builders edit things they draw with entities: a cable hanging between two walls, a
zipline, a sign post, a prop. Each plugin declares its **kinds** of objects ([World objects](../developers/world-objects.md)
for developers): a shape (a **point**, placed by one anchor, or a **line**, by its two ends) and the fields its
settings hold. The **world objects** tool of editor mode shows them where they are and edits them, whatever they are:
Foreman knows only their anchors, their fields and the line they draw.

It needs the Foreman plugin on the server and a plugin declaring object kinds; without the Foreman plugin the tool is
greyed out.

## The tool

Turn on editor mode (++b++) and pick **World objects** with ++0++ (the tenth tool). While it is on, the server sends
the objects within 64 blocks of you, refreshed as they change: each shows as one outline in its kind's colour (a
line's curve as its plugin computes it, a prop's box; a marker for a point whose plugin gives no outline), named when
you are near or aim at it. An object whose plugin says how it is doing shows a badge under its name: a word in green
(working), grey (off), red (blocked) or orange (a setup problem); the status card says why for the selected one. The HUD says what the
clicks and keys do.

So that a street of lights and cables doesn't hide the build, the objects you haven't selected are drawn lighter and
fade out with their distance from you: full within 12 blocks, gone by 40 (what has faded out can't be clicked either).
Their outlines also fade away from the line through their anchors: a cable's curve stays whole, a spot's wide cone
shows its axis and the start of its cone. The one under the crosshair and the selected one are drawn whole.

| | |
|---|---|
| Left click | Select the object under the crosshair; on the selected one, open its panel; on one of its knobs, make that anchor the active one; on nothing, deselect |
| ++c++ | A new object: pick its kind in the palette (straight to placing when there is one kind) |
| ++n++ | The panel of the selected object (its form, and its plugin's panel), the form of the new one, else the [panel](panels.md#panels) of what you aim at |
| ++v++ | The next anchor of the selected object |
| ++p++ | Put the active anchor where you aim |
| ++delete++ | Delete the selected object (press it twice) |
| ++z++ | Undo your last change on the server |
| ++esc++ | Cancel a drag or a new object, deselect |
| ++k++ | Link mode (with plugins giving [panels](panels.md)) |
| ++r++, ++i++, ++o++ | With a relation selected: reverse it, a new source, a new destination ([editing a relation](panels.md#editing-a-relation)) |
| ++f++ | Only the selected object's chain: what it reads and what reads it ([relations](panels.md#relations)) |
| Others | Keys the server's plugins add ([below](#keys-from-plugins)) |

### Selecting and moving

A selected object is drawn in blue with a knob on each anchor, the active one white, under the gizmo's arrows and
squares: drag them to move that anchor, as the editor's [snapping](editor.md#gizmos-and-snapping) says (free, by a
step, or with **block grid** onto block centres for a line's ends, the middle of a block's floor for a point). While you
drag, and until the server has the change, the object shows straight between its anchors, where it was in grey: its
plugin draws its real shape (a cable's sag) once it has moved it. Click another knob, or press ++v++, to move the other
end; ++p++ puts the active anchor where the crosshair meets a block.

### The panel

Click the selected object again, or press ++n++: a panel beside the world, the game still in view, as wide as half
the screen (between 300 and 520 of its units), scrolling as one. At the top, what its plugin says of it now (live
values, a preview, its setup). Then **Location**: its anchors (exact x, y, z, typed) and its facing (the kind's yaw
fields, and those its plugin groups there). Then the form made from its kind's other fields, like the forms of
[typed points and areas](regions.md#typed-points-and-areas): text, numbers with their range, switches, choices,
colours, facings, and **targets**, another thing of the world (an object linked to another): **Pick** closes the
panel, you aim at it and click (right click or ++esc++ goes back without one), and the panel comes back with it, shown
by the name its plugin gives it. A field left empty takes its default. A choice that depends on another field (the
output of the thing a target field picked) offers what fits that value, asked again of the server as you change it;
a value it doesn't offer any more goes back to its default.

When a plugin of the server gives a panel for the object (its live state, values, buttons, lists: see
[Panels](panels.md#panels)), its sections are part of the same panel, live while it is open: a group of the form's
fields shows at the top of the section of the same title (a machine's start state above its live state, a link above
what it carries), the others under their own headings. The section's own fields are sent as you leave them (++enter++
in one sends it), its buttons run at once. When that panel has [tabs](panels.md#panels), the form's parts go with them:
each group of fields at the top of the section of the same title (wherever its tab), the anchors with a `Location`
section, the rest on the first tab. Without such a plugin, the panel is the form alone. Text cut short shows whole
under the mouse.

**Save** (++enter++) sends the anchors and the fields you changed, nothing else: values that change by themselves
meanwhile (a machine's state) are left as the server has them, and the fields you didn't touch follow them. The server
refuses values that don't fit their field and the plugin refuses what its own rules don't allow (a cable too long),
shown on the fields they name or under the form. **Delete it** removes the object.

### New objects

Press ++c++: the palette shows the kinds the server's plugins declare as cards, each drawn with its icon model in 3D
(like the [Models page](models.md)) or, without one, a drawing of its shape in its colour, under its category. Type to
search (name, id, category, description), click a category chip to see only that one (again: every category); the
search and the category stay for the next time. Click a card, or press ++enter++ for the first one (with one kind,
++c++ goes straight to placing it).

Then click where its anchors go, as many as its shape has (a line: its start, then its end): where the crosshair meets
a block, on the centre of the face with **block grid** snapping (a point: on the floor of the block before it), else
right there to the move step. A kind its plugin places like furniture (a prop) shows first as it will stand: its model
in the block before the face you look at, on that face when it goes there (against the wall, under the ceiling, on the
floor), in a box with an arrow for its front; the scroll wheel turns it in its rotation steps. On a face it doesn't go
on, the box is red and says where it goes. Its form then has its facing (and its surface) filled in from there. The line follows the crosshair to the
next anchor; right click takes back the last one (or cancels). With all its anchors, its form opens with the kind's
defaults: **Place it** (++enter++) asks its plugin to make it, and the new object is selected (its plugin's panel shows
once it exists: ++n++).

Plugins can also show panels for what they manage and draw how things are linked: the same tool opens them (++n++
with nothing selected; an object's is [its panel](#the-panel)), draws the links as arrows, makes new ones (++k++) and
changes them (click an arrow: unlink, reverse, move an end): [Panels and relations](panels.md).

### Keys from plugins

A plugin of the server may add keys of its own to the tool, for what you do while editing its objects: a switch (a
preview of its machines running) or an action. They show in the tool's list of keys with their state (`Preview
machines: on`), on the key the plugin asks for unless the tool already uses it (then the first free letter of its
name); a switch that is on is also said on the status card, and the plugin's answer when you press one. The server
sends them, and their state for you, only while the tool is on.

Every change (placing, moving, a form saved, a deletion) is one undo step of yours on the server: ++z++, the
[History page](hub.md#history), or `undo` from your AI take it back.
