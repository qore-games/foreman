# Selection

## Your selection

A box in the world, for the AI or your own commands. Press ++b++ for [editor mode](editor.md) and ++1++ for the
selection tool (or **Use** in the hub's Selection section), then left click and right click the two corners, and drag
the gizmo to adjust it (by whole blocks). ++enter++ asks a chat about it, ++delete++ clears it.

It is also the editor's one way of selecting things: the other tools make their new things from it (WorldEdit's
selection when you have no Foreman one). Select a box here, switch tool, and press its key: ++c++ in the
[asset tool](library.md#a-new-asset) makes an asset of it, ++m++ in [areas and points](regions.md#the-tool) an area.

The AI chats are told about it and about the WorldEdit selection: "here", "this" and "the selection" mean them. The
hub's *Ask a chat* button, ++enter++ with the selection tool, or the *Ask the AI about the selection* key (unbound by default), starts a chat about the
selected area. `/ai selection clear` clears it.

## The WorldEdit selection

On a server with WorldEdit or FastAsyncWorldEdit, the mod shows your WorldEdit selection in the world, whatever its
shape: cuboid, sphere, ellipsoid, cylinder, polygon, convex. It's what WorldEdit tells a CUI client, so it works
without the Foreman plugin. Turn it off in the hub's **World → Selection** (*Show the WorldEdit selection in the
world*).

![WorldEdit selections in four shapes](../assets/screenshots/worldedit-shapes.jpg)

The **WorldEdit tool** (editor mode's tool 9, or **Use** in the hub's Selection section) edits it:

- **Left / right click a block** for `//pos1` / `//pos2`, however far the block is. What they mean depends on the
  selector, as with the wand: two corners, a center and a radius, the points of a polygon.
- **Drag an arrow** to move the selection (`//shift`), **a knob** to resize it. A sphere, an ellipsoid or a cylinder
  grows on both sides of its center; a polygon or a convex shape is stretched. The mod selects it again with the
  commands that make it, paced so the server's spam check never kicks you.

Off when the WorldEdit CUI mod (or WorldEdit itself) is installed: it draws the selection then.
