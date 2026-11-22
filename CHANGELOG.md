# Changelog

One version for the mod, the plugin and the API artifacts, [semantic](https://semver.org): a release is the tag
`v<version>` of `mod_version` in `gradle.properties`, and its notes are its section here.

## [Unreleased]

### Building (mod and plugin)

- **Copy and paste.** The selection tool copies the selection (++ctrl+c++) or cuts it (++ctrl+x++, one undo step) into
  your clipboard, kept by the server, and ++ctrl+v++ places it with the asset placement tool: a ghost at the crosshair,
  turned with R, pasted with Enter or a right click, again and again, Z undoing the last change. It is the asset paste:
  blocks, block data, displays and furniture, the entities that stay (markers, item frames, paintings, armor stands,
  mobs that don't despawn: a cut takes them away too, and its undo puts them back), the points, areas and copies of
  assets lying in the box,
  each paste a detached copy of its own (`clipboard-<n>`), so plugins following copies keep its objects to itself (a
  pasted room's lamp reads its own switch). The AI gets `copy_region` (`cut`), then `paste_asset {name: "clipboard"}` and
  `asset_content {name: "clipboard"}`; one clipboard per player, shared with their AI chats, until the server restarts.
  The placement tool takes Z (undo) for assets too. `RegionCopyEvent.clipboard` tells plugins a paste is a clipboard's:
  its source may have changed, been cut or unloaded since (read what the paste carried, `source` for its coordinates).
- **Pastes carry marker entities** (where plugins keep data in the world, such as placed lights) besides displays and
  furniture.
- **World objects.** Plugins declare the kinds of things they draw with entities (`ForemanObjects` in
  `foreman-server-api`: a point placed by one anchor, or a line by its two ends, with typed fields like layout types and
  a handler that finds, makes, changes and removes them), and builders edit them without Foreman knowing what they
  are: the plugin's entities stay the truth. The AI gets `object_kinds`, `list_objects`, `place_objects`,
  `edit_objects` and `remove_objects`, each call one undo step that calls the plugin back. The mod's tenth editor tool,
  **World objects** (0), outlines those near you (a cable's curve as its plugin computes it), selects one with a
  click, moves its anchors with the gizmo, edits its fields in a form made from the declaration, and places new ones
  (C: pick a kind in its palette, cards drawn with the kind's icon model, by category, with a search; click its
  anchors, fill in its form). A kind can be placed like a furniture of the server (`ObjectKind.furniture`): the tool
  shows its model as it will stand on the face looked at, turned by the scroll wheel, and fills in its facing and
  surface fields.
- **Nested copies, sync everywhere.** An asset area holding pasted copies of other assets (apartments in a building)
  pastes them as copies nested in the new one: their own instances of their own assets (`<parent>/<child>`), placed
  with the parent, their entities, points and areas their own; `list_instances` and `instance_info` show the tree, the
  mod's copies layer too. `sync_asset` pastes a changed asset again over every copy of it in the loaded worlds, nested
  ones inside their own box only, as one undo step, and lists what it skipped and why; `sync_instance` works the same
  way. Pastes take a fingerprint of what they left (blocks, block data, entities; nested copies' boxes aside): syncs
  skip copies changed since unless `force`. What a copy's neighbours change (a fence or a pane joining a block placed
  beside it, stairs' shape, waterlogging) and entities settling after the paste (a boat, a minecart, an armor stand
  falling) don't count as changes. A sync over any number of worlds stays one undo step. `detach_instance` is now reversible (the copy stays recorded, flagged
  detached: syncs leave it, a parent's sync keeps its box as it is), `attach_instance` undoes it (`sync: true`), and
  `forget_instance` is the old detach. The mod's copy panel attaches a detached copy again, and draws detached copies
  grey and nested ones thinner.
- **Mirrored pastes.** `paste_asset {mirror: true}` mirrors an asset east–west before its turn (8 orientations):
  blocks flipped with their states as WorldEdit flips them, block data, entities' positions and yaws, displays'
  transformations and block displays' blocks (an asymmetric item model still shows its own side: models can't be
  mirrored), item frames' and paintings' facing, points and areas. Instances record it; syncs, `rotate_instance` and
  nesting compose it; `diff_regions` takes `target.mirror`. `RegionCopyEvent` gains `mirror` and `face(BlockFace)`,
  and its `block`, `position` and `yaw` follow the mirror.

### Server (Paper plugin)

- **Linked when given `foreman.client`.** A player made operator, or given the permission by a permission plugin, while
  online is linked at once (when the server sends them their commands again) instead of on their next join, and
  unlinked when they lose it. The mod of a player without it is told so (`denied` on the bridge): its hub and messages
  say they lack `foreman.client` instead of "No Foreman plugin on this server".
- **Panels and relations.** Plugins show panels for the things they manage and the relations between them
  (`ForemanPanels` in `foreman-server-api`: a `PanelProvider` resolves what a builder aims at (an entity, a block, a
  world object, or a `global` target for its settings) to its own target, describes its panel as sections of rows
  (text, values, fields of the layout field types plus the new `target` type, buttons, lists with their own buttons),
  applies edits checked against the panel's fields and runs its buttons, names its targets (`name`, by default its
  panel's title); optionally lists relations, links two targets (with questions and options) and unlinks). Foreman draws
  them: the mod's panels (`panel_open`, a `panel` message whenever it changes, `panel_resolve` for which provider serves
  what a builder aims at in link mode, `relations` within 64 blocks), and the AI's tools `panel_get` (also `aimed: true`), `panel_edit`,
  `panel_action`, `list_relations`, `link_targets` and `unlink`. A provider's exception fails its call only, logged once.
  Nothing runs while no panel is open and nobody shows relations. New feature `panels`: on in the `dev` preset, off in
  `production`.
- **Panels in tabs, statuses and conditions.** A section names its tab (`Section.tab`): the mod shows a tab bar, each
  tab its sections (those without a tab on every tab) and keeps the last tab per kind of thing; a panel without tabs
  is one page. `Panel.status(Status)` is a status line above every tab, `WorldObject.status(Status)` a badge the
  objects tool draws under the object's name (ok green, off grey, blocked red, problem orange), and `list_objects`
  gives it. A new row kind, `conditions` (`Row.conditions`): conditions with whether each holds now, what it read, the
  things it reads (a click opens their panel) and their own buttons, and a builder in the mod (a subject picked in the
  world or from the plugin's list, one of its typed facts, a comparison and a value, OR groups, or an expression typed
  in the plugin's syntax), checked live by the plugin (`PanelProvider.subjects`, `facts`, `check`) and added through the
  row's add button; Foreman parses nothing. New tool `panel_facts` (the vocabulary and checks, for the AI and the mod).
  Relations get a state (`Relation.state`: active green with flowing chevrons, blocked red, idle grey), link mode a
  `LinkResult.build` that opens the source's builder filled in, and the objects tool ++f++, the selected object's chain
  only (what it reads and what reads it).
- **Cloned worlds.** Worlds cloned from one map share their entities' UUIDs, so Foreman looks entities up in their own
  world: `PanelTarget.findEntity` (an entity target's equality takes its world in), the display, asset and move tools.
  A display or panel tool without `world` asks for it when several worlds hold that UUID, and `findEntity` on a target
  without a world finds the entity only in the one world that has it. **Breaking:** `FurnitureProvider.remove(world,
  handle)` takes the furniture's world (it was `remove(handle)`).
- **Changing links.** A relation says what may change of it (`Relation.changes(REVERSE, FROM, TO)`) and what it links
  for people once selected (`Relation.detail`, `lamp.power ← hall_switch`); `PanelProvider.relink(ctx, relationId,
  change, to, choice)` reverses it or moves one end to another thing, answering like `link` (done, refused, a question,
  or a condition to build, in the panel `LinkResult.on(target)` names: Foreman removes the old relation once the builder
  adds it). New tool `relink {target, relation, provider, reverse | from | to, choice?}`; `list_relations` gives each
  relation's detail and changes, `link_targets` a build's `target`.
- A test player elevated (or demoted) while online gets the mod's Foreman features at once (or loses them), without
  rejoining.
- World objects' data takes `target` fields (another thing, as a target's JSON): checked like the others, resolved by
  the panel providers when there are some (a furniture for its cell block) and stored as the target's JSON;
  `LayoutData.target(name)` reads one as a `PanelTarget`. Data a plugin gives back with a `PanelTarget` in it is sent as
  its JSON.
- **MCP clients hear of new tools.** Tools that plugins publish after a client listed them (a plugin enabling late,
  reloading, or a furniture plugin starting) and those withdrawn when a plugin disables used to stay unknown, or
  listed, until the client reconnected. The MCP server now gives sessions (`Mcp-Session-Id` from `initialize`),
  announces `tools.listChanged`, and opens a session's stream of server messages on `GET /mcp` (Server-Sent Events,
  a keep-alive comment every 25 s): each change to the tools sends one `notifications/tools/list_changed` on every open
  stream, a second after the last change (a plugin registering 20 tools sends one). `DELETE` ends a session and its
  stream; an unknown or ended session gets `404`, and the client starts again (the mod's own clients do, and send the
  call again). Requests without a session id are served as before. Nothing runs while no stream is open.
- A deadlock froze every tool (the mod's library renders waited forever): a model built for a render while a reload of
  the resources cleared the model caches. Models are now built outside the caches' locks.
- The first tool needing the game's models on a server without the vanilla client jar yet (`place_furniture`, a render)
  froze the server while it downloaded the jar from Mojang (half a minute), and tried again on every call when Mojang
  couldn't be reached. The jar is now downloaded in the background when the world tools start, checked against Mojang's
  SHA-1 and moved in place whole (a download cut short left a broken jar there for good); reading models waits for the
  first download, off the server thread, and a failed download is tried again after 10 minutes.
- What builders change themselves in the mod's pages (panels, forms) is no longer announced in chat as an AI's
  change; the audit log says `mod` for those calls. The mod's pages call with a token of their own (`editorToken` in
  the hello, kept in memory, never given to the AI), so no other client can pass its calls off as the builder's. An AI's change asked as JSON is announced with its message, not
  the raw answer.
- `list_furniture` takes `model` (the furniture showing an item model, the first the one `spawn_display` shows it as),
  `format: json` and `poses` (each one's display pose for every rotation step on a floor and on each face it attaches
  to); `place_furniture` against a face takes `yaw`/`facing` where the face leaves it free to turn (under a ceiling),
  and places as the player calling it when they are in that world. `FurnitureType.pose(face, yaw)` and
  `FurnitureProvider.place(type, player, against, face, yaw)` in `foreman-server-api` (default methods: providers
  that don't have them keep working).

### Client (Fabric mod, any server)

- **Panels and relations** in the world objects tool, for plugins giving panels (`ForemanPanels`): N with nothing
  selected opens the panel of what you aim at (an entity, a display, a block or a world object) beside the world, live as its plugin
  changes it, its fields sent as you leave them (target fields picked in the world), its buttons and lists' buttons
  run at once; the relations within 64 blocks show as arrows with their labels and chevrons flowing toward what each
  feeds, a click selects one and Delete unlinks it; K links a source to other things, picking among the links the
  plugin offers. In link mode, while picking a target and with N, the crosshair is on what the plugins serve: a block or hitbox of something a plugin manages (a
  piece of furniture's collision cell) picks that thing, outlined and named as its plugin names it, and something a
  plugin serves wins over a plain block just before or behind it (asked of the server for what you aim at, only
  then). The forms' controls are one `FieldInput` shared by the regions, objects and panels forms.
- **Editing links like cables.** A click on a relation's line selects it, in link mode too (when it is in front of what
  the crosshair is on): it turns white with a card saying what it links and its keys, Delete unlinks it, and where its
  plugin allows it, R reverses it and I / O move its source / destination to another thing (clicked in the world, or
  picked from a list of the objects near you with the same key again). The plugin makes the new link and removes the old
  one, or opens its condition builder filled in; then the old link goes once the condition is added. The changed link
  is selected again once the server sends it.
- **One panel for a world object.** N or a click on the selected object (N on an object aimed at) opens one panel
  beside the world: its Location (anchors and facing), then its kind's fields merged with the panel a plugin gives for
  that object (`{"type":"object", …}` resolved by its provider: live values, fields sent as you leave them, buttons,
  lists): a field's `group` (`LayoutField.group`) shows at the top of the panel's section of the same title, in one
  scrolling body. Without a provider for it, just the form. Save sends the anchors and the fields the builder changed
  (an `edit_objects` patch), and the fields left alone follow the object as the server sends it. A choice that
  `dependsOn` other fields offers what the kind's handler gives for the form's values (`ObjectHandler.choices`, also
  `object_kinds {kind, data, id?}` for the AI), asked again as those change. N on anything else opens the plugin's
  panel alone; a list item naming an object opens the object's panel. The side panels are wider (half the screen,
  between 300 and 520), with text cut short shown whole on hover.
- **Keys from plugins in the objects tool.** `ForemanObjects.addAction` adds a switch or an action to the world
  objects tool (`ObjectAction`: id, label, the letter it asks for, a handler giving its state and running it for the
  builder): the mod binds it to that letter or the first free one of its label, lists it with its state among the
  tool's keys, says on the status card while a switch is on, and shows its answer. The server sends them only while
  the tool is on.
- Object kinds may have `target` fields (`LayoutField.target`): the form picks one in the world (Pick, then click it)
  and shows the thing by the name its plugin gives (`panel_resolve`), as panels' target fields do.
- The move gizmo has a knob at its center that moves what it holds anywhere: across your view as you grabbed it,
  snapped on every axis, the scroll wheel pushing it farther or pulling it nearer while you drag (displays, locations,
  points, world objects' anchors, placements).
- Joining another server behind the same proxy forgets what the last one's Foreman said (its hello, object kinds,
  areas, panels, furniture): a server without Foreman no longer offers the last one's object kinds and tools.
- The world objects tool draws one shape per object: a point's marker only when its plugin gives no outline (a prop
  showed its box and a marker at its anchor).
- The Library page says what its renders are doing: rendering (with the seconds it has taken) or waiting, and how many;
  a render the server hasn't answered in 2 minutes fails with a message instead of loading forever.
- The Library page's scrollbar can be dragged again (the page took every drag to turn the opened asset's views).
- Ctrl held while dragging a box's face knob resizes both sides at once, around its center (selections, WorldEdit's,
  areas, assets).
- The selection tool picks its corners however far the block under the crosshair is (up to 256 blocks), like the
  WorldEdit tool, not only within reach.
- A box gizmo's knob on a +x, +y or +z face stays past that axis's move arrow: on a small box it sat on the arrow and
  was hard to grab.
- The selection tool makes its box your WorldEdit selection (P, or *Select in WorldEdit* in the hub): `//sel cuboid`,
  `//pos1` and `//pos2` sent as you, so WorldEdit's commands work on it.
- Clear the WorldEdit selection: Delete in the WorldEdit tool, or *Clear* beside it in the hub's Selection section.
- V in the WorldEdit tool hides or shows the WorldEdit selection, like the hub's switch.
- A point's gizmo tilts it up and down too (its pitch, a second ring around its right), besides turning it.
- A colour picker for colour fields in the areas-and-points forms (a light's colour): saturation and brightness in a
  square, the hue in a bar, common colours and the field's default; the `#rrggbb` box follows as you drag.
- The display editor leaves out displays riding a player (hats, held models) and those that only exist on the
  client (plugins' packet entities, which the server doesn't list and can't edit): they were picked, listed and
  selected by the box like a build's.
- `client_key`'s physical keys type their character too (`key.keyboard.i` types `i` into the focused field, as the
  window sends it after the press), take `alt`, and carry the key code the layout gives on 26.3 (Ctrl+A and the other
  shortcuts work): an AI tests editor mode's own keys (a tool's number, I, K, Delete) and screens like a player typing.
- The model placement tool's ghost shows the model exactly as it will be placed. Furniture kept its neutral rotation
  until placed (lying on its back, facing the player): it now takes the pose its plugin gives (`list_furniture`'s
  `poses`), on a floor at the furniture's rotation step (the scroll wheel turns it a step), attached to the wall or
  ceiling looked at when it goes there, and is placed against that face; a model some furniture shows, placed as a
  display, stands as that furniture. The ghost stays visible and lit as the placed one against walls (entity culling
  hid it, a painting's middle in the wall darkened it).
- The world objects tool draws the objects it doesn't select lighter and fades them out with distance (full within 12
  blocks, gone by 40), their outlines fading away from the line through their anchors too: a street of spot lights no
  longer hides the build under their cones (a wide one shows its axis and the start of its cone, a cable stays whole).
  The selected one and the one under the crosshair are drawn whole; what has faded out can't be picked. The panels'
  relation arrows fade the same way.

## [1.3.0] - 2026-11-09

### Building (mod and plugin)

- **Editor mode.** One key (B, `key.foreman.editor`) turns the building tools on: a panel at the side of the screen
  lists them (the number keys pick one: selection, displays, models, library, assets, areas and points, measure,
  locations, WorldEdit) and the keys of the one in use, which the mod takes before the game's own bindings while it is
  on (F doesn't swap hands, the numbers don't change the hotbar; movement, chat and F3 go through). A card at the top
  left says what is selected (an asset area or a pasted copy of it, a display, a furniture, an area, a point…), what a
  click does and what is waiting for a confirmation. Esc steps back in the tool, then leaves. A crosshair in spectator.
  The tools' own global key bindings are gone: their keys live in editor mode.
- **Snapping, one key for every gizmo:** G cycles the step of what the gizmo does now (move: free, 1/16, 1/8, 1/4,
  1/2 or a whole block, the block grid itself; turn: free, 5°, 15°, 22.5°, 45°, 90°; scale: free, 1/16, 1/4, 1/2), Ctrl
  drags freely. One transform gizmo (arrows, planes, rings, scale handles) serves every tool.
- **Asset library from the world.** An area tagged `asset` is a library asset (`area:<world>/<name>`): `list_assets`
  lists it (`format: json` for programs), `preview_asset` renders it alone with its furniture and displays (`views`
  images around it, `size`), `paste_asset` pastes it into any loaded world, turned, as one undo step. Everything inside
  travels: blocks, their block data (the per-block entries plugins keep in the chunk with the CustomBlockData layout),
  display and furniture entities with their data, the points and areas inside. A display belongs to the asset where its
  model shows, not only where the entity stands. The mod's Library page browses them (thumbnails, 8 views to turn,
  tags, search) and places them with a ghost of the asset at the paste spot (`asset_content`).
- **Making and editing assets:** `define_asset` (`fit` to the content, `plot` on the next free plot of the library
  world), `fit_asset`, `resize_asset`, `move_asset` (everything inside moves, entities keep their UUIDs, players
  standing on it go along; `rotate`), `/foreman asset new|fit`. In the mod's asset tool, an asset is made from the
  selection.
- **Pasted copies (instances):** each paste is recorded in the target world (asset, origin, turn, the blocks it wrote,
  its entities, points and areas) and tagged on its entities; `list_instances` (also where an asset is used),
  `instance_info`, `rotate_instance`, `sync_instance` (pasted again from the asset as it is now), `delete_instance`
  (only what the paste wrote), `detach_instance`, `open_asset_source` (to the asset where it's built, loading its world
  through a `ForemanWorldLoader` when one is registered; on the ground in front of it, or flying). The mod outlines the
  copies and opens a panel for each.
- **`diff_regions`:** compares a box with its copy or its move, turned: blocks, block data, entities (pose, data),
  furniture as its plugin sees it, points and areas; how pastes and moves are checked.
- **Display entities:** `list_displays`, `edit_displays` (position, yaw, pitch, any part of the transformation,
  billboard; a furniture's root display keeps its root block, whole-block moves place it again through its plugin; the
  answer gives each display as it ended), `spawn_display` (a model, a block or a text; `copy_of` copies a display with
  its item and data), `remove_displays`, `list_models` (the item models of the server's resources and the furniture
  showing each), `/foreman model spawn|list`. The mod's display editor selects a display by clicking its model (its real
  shape), several with Shift or the selection box, shows each entity's position (and moves it while the model stays),
  and moves, turns, scales and stretches them, previewed at once and saved on release; a panel for exact values,
  duplicate, delete, drop onto the surface. The Models page shows every item model of the loaded packs in 3D, with
  search, and places them (as furniture when they are one).
- **Areas and points in game:** the areas-and-points tool outlines areas (claims, zones, tags) and points, makes areas
  from the selection, resizes and moves their boxes, edits their name, note, tags, zone rules and claim kind, and places
  points. Points are precise now: decimal positions with an optional yaw and pitch (`points` `move`).
- **Layout types:** plugins declare the point types and area types they read, with typed fields (`ForemanLayout`);
  points and areas carry `data` for them, checked when written; `layout_types`, `check_layout` (unknown types, missing
  or invalid fields, the plugins' own checks). The mod fills them in with forms and lists the check's findings.
- **Working together:** the undo history is per player (`undo` takes back your own changes and refuses one that someone
  changed over since, unless `force`; `player` for admins; `history`, `/foreman history`; the mod's History page).
  Areas can be locked (`lock_area`, `unlock_area`, `/foreman lock|unlock`): others' edits inside are refused, and with
  `locks.protect-blocks` their block placing and breaking too; `foreman.lock.bypass`.
- Overlays: hidden blocks (barriers, light blocks, structure voids, blocks the resource packs draw as nothing), display
  entities per chunk; a measure tool.

### Server (Paper plugin)

- `look` and the model previews drew no block textures on Java 25 (every block was invisible).
- Area tags (`/foreman tag|untag`, `set_mark` `tags`), shown in listings and to the mod.
- Yaws stay between -180 and 180 through turns and moves; an undo takes back the furniture a change placed before
  restoring what it removed.

### Client (Fabric mod, any server)

- The hub opens on the page it was closed on.
- `client_key` presses a physical key (`key.keyboard.f`, with `shift` / `ctrl`) through the game's keyboard handler.

### API

- `ForemanLayout` (point and area types with fields, a Bukkit service), `RegionCopyEvent` (what a paste or a move did
  to a region: its transform and the entity UUIDs it replaced), `ForemanWorldLoader` (load a world on demand).
- **Breaking:** `WorldPoint`'s position is decimal (`x`, `y`, `z` doubles, `yaw`, `pitch`, `location()`, `block()`) and it has
  `data`; `WorldArea` has `tags`, `data` and its lock.

## [1.2.0] - 2026-11-05

### Server (Paper plugin)

- **Features and a production preset** for the plugin: `preset: dev` (everything, as before) or `preset: production`
  (only the read-only server tools, for `foreman.admin`; no MCP port, no console tools, world edits, dupe testing,
  plugins' tools or learning), and `features:` to turn single ones on or off over it: `mcp`, `diagnostics`, `console`,
  `world`, `world-edit`, `testing`, `plugin-tools`, `learning`. A tool whose feature is off isn't published, whoever
  registers it; the log says what's on at start, and `/foreman status` lists each feature, whether the MCP server
  listens and where, and the tools published. The mod gets the server's `features` in `hello`. A warning when the MCP
  server listens beyond the machine with `console` on.
- **The relay:** the mod's AI reaches the server's tools through the player's game connection (`foreman:mcp`), for
  servers that open no MCP port, and behind proxies. For players with `foreman.client`, as themselves; a player's
  requests run one at a time, `relay.calls-per-minute` tool calls a minute (default 120). The `relay` feature, on in
  both presets. The mod uses it on a server with no MCP address for the player, or one whose address doesn't answer
  from their computer (the hub says *through the game*); outside agents through `/relay/external` (`relay` in
  `client-mcp.json`). The `hello` has the MCP address and token only with the MCP server, and `relay`.
- `plugins/Foreman/audit.log`: every tool call, a JSON line each (when, `relay` or `http`, the player, the tool, its
  arguments cut short, the result, the time), readable by the server's user only; relayed calls also log a line.
- **Test players** (the `test-players` feature, on in `dev`): names registered with `test_players` or
  `/foreman testplayer` get in through the whitelist and are plain players, to test what a real player can and can't
  do; elevated on demand, they have every permission (and `*`) on a permission attachment until demoted, nothing
  written to `ops.json`. Kept in `test-players.yml`; a name counts only from the server's own machine
  (`test-players.local-only`).
- `server_packets`: what the server sends its players and gets from them, recorded on its side, every player at
  once: packets and bytes per player and packet type (before compression and on the wire), bytes per second, and what
  the packets are about (entities grouped by type, the plugins whose data they carry and what they ride; plugin
  channels, teams, objectives). Handlers on the players' connections for the recording only.
- `server_reload_plugin` checks the reload: `clean`, or `restart before trusting it` with what the old instance left
  (listeners, commands, scheduled tasks, services, plugin channels), threads still running its code, the plugins that
  depend on it, and whether its old classes were unloaded (up to two full collections). Bukkit's `/help` keeps the
  commands of a plugin's first instance, and with them its classes: said, and clean (memory only). With the `console`
  feature, reloads typed in the console or made by `/reload` are checked too, a line in the log each.
- A `spark` skill for the profiler Paper bundles: the commands whose text the AI reads (`tps`, `health show`, `gc`,
  `tickmonitor` with the slow ticks read back from the log), and profiles and heap summaries saved to files for
  spark's viewer, uploading only when the player asks. Skills can be offered by command (`command: spark`), for what
  the server bundles without a plugin.
- The plugin's MCP server no longer fails when `mcp.port` is taken (two servers on one machine with the same config):
  it listens on the next free port up to `port + 20`, else any free one, and says so in the log. `mcp.strict-port:
  true` fails as before; `{port}` in `mcp.public-url` is the port it took, and the default URL the mod gets follows it.
- World renders and model previews are made in memory: the image library no longer caches to a temporary file.
- `plugins/Foreman/mcp.json`, written while the plugin's MCP server runs, for scripts, launchers and agents on the
  server's machine: `url`, `token`, `port`, `pid`, `server` (its folder's name), `version`, `started_at`. Readable by
  the server's user only (it holds the server's token). `/foreman status` gives the port it took.
- Each tool says what it's for in `tools/list`, `_meta.group`: the client's (`Player`, `Screens`, `Movement`,
  `Testing`, `Client`, `Packets`, `HUD`, `Files`) and the server's (the groups of `ForemanTool`, or the plugin that
  publishes it).

### Client (Fabric mod, any server)

- AI chats on Claude Code saw the game context of their first prompt in every later one: where the player stood,
  looked and had selected, and the server they were on (a resumed session keeps the system prompt of its first run).
  The context now comes with each prompt, and the chat's instructions (yours, its workspaces', the server tools
  missing here) with its first prompt and again when they change, on every backend.
- AI chats on Claude Code cost about a third of the tokens per request (7.7k instead of 24.8k with no folder, 10.5k
  with folders): Claude Code gets only the tools a chat uses (tool search and, on its folders, the file tools), not
  the definitions of all its others.
- AI chats on OpenRouter send about 6k tokens per request instead of 47k: the model starts with the tools of the
  `Player`, `Files` and `Skills` groups and the names of the others by group, and loads the groups a task needs
  (`load_tools`), which the chat keeps. A new prompt cuts down what the earlier ones got back (images dropped, long
  tool results to their first 1,800 characters), once each, so the cached part of the conversation stays the same. A
  chat stays on one OpenRouter provider (`session_id`), and Anthropic's models cache (`cache_control`). The default
  model is `anthropic/claude-sonnet-5.5`.
- **Model and effort per chat:** the button beside Workspaces says what a chat runs on (its own choice, the
  default, or the model the backend said it ran) and picks the chat's model and effort, or makes them the defaults of
  the backend's new chats. Defaults per backend in AI → Agents, with a new effort setting each: Claude Code's
  `--effort`, opencode's model variant, OpenRouter's reasoning effort. Each run's last line names the model and
  effort.
- `client_teleport` (and the hub's teleport to a location) failed on servers where a plugin owns `/tp` with its own
  arguments: it runs vanilla's `/minecraft:tp` now, then `/tp` when the server keeps namespaced commands from players,
  checks that the player got there (a fall below the target counts), and says when neither moved them. Coordinates are
  written in full (`-12000000.0000`, not `-1.2E7`, which the command parser refused).
- With ModernFix, a resource reload after the game's start (F3+T, the options' packs, a server's pack) never ended
  while Foreman timed it (fast reloads or the chat report on): the packs were prepared, then nothing applied and the
  loading screen stayed. To time the steps Foreman switched the reload to vanilla's profiled one, whose listeners
  ModernFix wraps; on 26.x the reload then waits for the unwrapped ones to be prepared, for good. Foreman now times
  each step itself and leaves the profiled reload to vanilla's debug logging.
- Fast font selection also covers providers of other kinds than vanilla's, asked about the codepoints still free at
  their turn as vanilla asks them (`FontProviders.select(supported, has)`), and font sets reloaded outside a resource
  reload. With ModernFix, which wraps the unifont provider, every font that includes the default one went vanilla's
  quadratic way (seconds for icon fonts), and ImmediatelyFast's font reload after a server pack froze the game as long.
- Lean mode for test clients (`-Dforeman.lean=true`, or the `lean` setting): the window hidden, frames drawn only when
  a tool needs pixels or a screen's layout (and once a second), no sound device, light settings in memory only
  (options.txt keeps the player's); ticks, networking, screens and every client tool work as usual. See
  [lean clients](docs/ai/lean-clients.md).
- Lean clients can skip the server's resource packs: `-Dforeman.lean.packs=skip` (or the `lean.packs` setting) answers
  each pack the server sends as accepted, downloaded and loaded without downloading or applying it, so a server that
  requires its packs still lets the client in; the log says which were skipped. Default `load`.
- Outside access from a launch's system properties, for scripts and launchers: `foreman.mcp.external`,
  `foreman.mcp.port` (`0`: any free port), `foreman.mcp.allow`. `client-mcp.json` says where the game is (`state`:
  `starting`, `menu`, `joining`, `in_game`, `stopped`) and its `pid`.
- `client-mcp.json` gains `version` (the mod's) and `started_at`.
- `GET /stream` on the client's MCP server (with the outside agents' token): what the game shows, screens and HUD
  included, live, as Motion JPEG (`multipart/x-mixed-replace`), for a launcher or a dashboard to watch a client, a
  lean one included. `fps` 1 to 30 (default 10), `width` 160 to 1920 (default 640, frames only scaled down),
  `quality` 30 to 95 (default 70). Only the newest frame is sent, so a slow reader skips frames instead of falling
  behind; 4 streams at most (`503`), `403` when outside agents may not take screenshots, a reader idle for 60 s is
  dropped. A lean client draws frames at the stream's rate while it's watched (about 15 % of a core at 10 FPS against
  3 % idle), and nothing more otherwise. See [watching the game live](docs/ai/outside-agents.md#watching-the-game-live).
- Screenshots no longer touch the disk: `client_screenshot`, `ui_capture` and `client_frames` encode their images in
  memory (they wrote and deleted a temporary file).
- `client_usage`: what the client process uses (heap, resident memory, CPU, threads, JVM flags, frames drawn).
- Bots that move, for load and network tests: `client_wander` walks the player to random waypoints within a radius of
  where it starts, on safe ground a straight walk reaches (no lava, water, cliff or void; a block up at most), jumping
  onto single blocks and taking another waypoint when stuck, the same ones again with a `seed`; `client_path` follows
  given points, in a loop if asked, skipping one it can't reach. Both steer with the movement keys and the camera as
  `client_input` does, need no frames (lean clients), and answer JSON (distance, waypoints reached or points
  reached and skipped with their times, times stuck, jumps, final position). See
  [bots that move](docs/ai/testing.md#bots-that-move).
- `packets_stats`: the packet recording as compact JSON for reports: packets and bytes each way and per type, rates
  per second and a series over time, the silences from the server (longest first, with when), keep-alives (the
  client's answer time, the interval, the ping the server measured), optionally from `since_ms` on. The recorder now
  keeps those silences, keep-alives and pings while it records.

### fastload

- **fastload**, Foreman's fast reload algorithms as a library of their own (`fastload/`, `games.qore:fastload`, plain
  Java 21, no dependencies, MIT; `fastload/API.md`): `Mipmaps` (vanilla's mip levels on pixel arrays, to the bit, and
  an atlas's sprites in parallel), `FontProviders` (font provider selection in linear time), `ZipIndex` (a zip's
  folders by lookup), and `Claims`: who speeds up each feature (`loading-screen`, `title-fade`, `mipmaps`, `fonts`,
  `zip-index`), first come first served, in system properties every class loader sees. The mod bundles it and steps
  aside on the features a launcher or another mod claimed first; alone, it does what it did, as fast.

## [1.1.1] - 2026-11-04

- Minecraft 26.2: the mod is a jar per Minecraft version, `foreman-1.1.1+26.2.jar` (Fabric API 0.152.2 or later)
  and `foreman-1.1.1+26.3.jar`, and the plugin runs on Paper 26.2 and 26.3. On 26.2, `client_post_effects` shows
  the one post effect it has (a spectated entity's).
- Building: a project per Minecraft version, `client/versions/<minecraft>`, over the shared sources, with what differs
  in the package `games.qore.foreman.client.mc`; `./gradlew :client:26.3:runClient`.

## [1.1.0] - 2026-11-04

### Client (Fabric mod, any server)

- `client_input` holds key bindings across ticks, in steps (walking, sprinting, sneaking, double jumps, dashes, holding
  use or attack), and `client_key` holds one with `hold_ms`: the server gets them as from real keys.
- `client_trace` records what the client has every tick (the player's position, velocity, flags and pose, nearby
  entities' boxes), keys held meanwhile, with a summary line first (ground and air ticks, height range, each entity's
  feet range); `client_frames` takes screenshots in a row as one contact sheet, after a command, the camera turning
  between frames; `client_perf` times the frames over a few seconds (FPS, lows, worst frame), after a command, to see
  what a change costs; `client_stats` a snapshot of what the client renders and holds; `client_sounds` the sounds
  played over a few seconds; `client_pixels` exact colours of the world without the HUD, or a region's mean colour,
  luminance spread and grid of cell means (`stats`); `client_screenshot` `hide_hud`.
- `client_connect` joins a server (the last one by default) and waits until the player is in game, or leaves it.
- `client_test` runs tests: steps over the client's tools, the server's Foreman tools, commands and waits, checked
  against what they return (texts, regexes, every number of a regex against bounds, arithmetic of captured values) and
  saved by name to run again, answering a line a step and the lines around a failure. `keep_player` puts the player
  back as they were; a death or a disconnect stops a test and says so. Any step, or group of them, can hold a network
  profile (`network`), ignore inbound packets (`drop`, both behind the server's `foreman.test`) and check the packets
  sent and received meanwhile (`packets`). The `testing` skill (`action: guide`, and a skill on servers with Foreman)
  says how to write tests that hold.
- One agent drives the client at a time: the first acting call takes a lease that lasts 2 minutes past its last call
  (`client_lock` acquires it for longer, releases it, waits for it), other sessions' acting calls are refused
  meanwhile; `/foreman-lock take|free` for the player. MCP sessions get ids, and after the game restarts agents are
  told to start a new session, listing the tools again.
- `client_record` and `client_replay` (and `/foreman-record`, a key): what the player does, recorded and saved with a test that
  replays it from where it started and says where its path left the recorded one.
- Fast resource reloads (on by default, Debug → Resource packs): mipmaps for every sprite at once and on arrays,
  fonts' providers picked in linear time off the main thread, zip packs listed from an index, and no loading screen
  minimum or fade-out. A 70 MB pack reloads in about a second instead of ten, with the same resources.
- Keep playing through reloads: the game stays on screen and playable under a thin progress bar; only the last step
  freezes it, without drawing it half done.
- Each reload's time in chat, its steps in the hub, and in `client_resource_packs` with `reload`; `/fastreload`.
- The last reload's warnings and errors in the hub, a row per kind, and a chat that finds their cause in the pack's
  sources and fixes it.

### Server (Paper plugin)

- `test_player` saves and restores players as a test found them, unhurt meanwhile; `server_trace` records the
  server's view of a player tick by tick, with the values plugins add through `ForemanTools.registerTraceValues`.

## [1.0.0] - 2026-11-02

The first public release.

### Client (Fabric mod, any server)

- The hub (H): every tool in a rail grouped by AI, World and Debug, each feature's settings on its page.
- Packet viewer: records both directions, groups the traffic by what likely sends it (Suspects), types, entities,
  chunks, every packet with its fields; export, and a chat that looks for the culprit in your plugins.
- HUD inspector: the action bar, titles, boss bars and tab list laid out glyph by glyph, guides over the HUD, a watch
  for what moves, and a check of declared glyph widths.
- Plugin locations drawn in the world, by source and category, the location editor's gizmo, local location sets.
- The WorldEdit selection drawn in any shape (on any server with WorldEdit), and a tool that sets its positions from
  afar and moves or resizes it with a gizmo.
- A log of plugin messages both ways: a colour per channel, click to expand a decoded + hex/ASCII view, pause and filter;
  printed to chat as the hub's rows (hover for the whole text, click to copy it) or to the game's log.
- A selection tool of its own, keys to copy your position.
- `client_drag` holds a mouse button and moves (gizmos, scrollbars, mining); dupe-testing inventory tools (gated).
- The hub and chats fit small windows: they draw at a smaller GUI scale when the game's is too big for their layout.
- AI chats with your own Claude Code, opencode or OpenRouter, in workspaces of folders; outside agents (Claude Code in
  a terminal) can use the client's tools through MCP.

### Server (Paper plugin)

- Dev-server tools: commands with everything they printed, the log since the last reload, stale plugin jars, reloads
  through PlugMan, status, a profile of the server thread by plugin, an event's listeners.
- World tools for the AI: renders, edits, terrain, roads, schematics, buildings from editable templates
  (`catalog/buildings.yml`, `catalog/rooms.yml`), checks that walk a build like a player, undo. They need
  FastAsyncWorldEdit; the rest of the plugin runs without it.

- Skills: markdown that teaches the AI a plugin or a workflow, read only when a request needs it (`read_skill`,
  `save_skill`, `/foreman skills`). Bundled for FastAsyncWorldEdit, WorldGuard, MythicMobs, ModelEngine, LuckPerms,
  PlaceholderAPI, CoreProtect, EssentialsX, Citizens, TAB and Skript, each offered
  while its plugin runs; plugins ship their own in `foreman/skills/`, servers in `plugins/Foreman/skills/`.
- `worldedit`: any WorldEdit/FastAsyncWorldEdit command on a box in one call, undoable with `undo`.
- `modelengine_models`: ModelEngine's models with their bones, hitboxes and animations.
- Dupe testing (needs `foreman.test`): `item_ledger` counts an item everywhere and reports the change since last time,
  `inventory_watch` logs the inventory events a test fires and who handles them, `test_restore` saves and restores
  inventories; the client's `inventory_snapshot` and `inventory_actions` (bursts in one tick) replay a player's clicks,
  with network profiles `burst` (hold the sends and send them together) and `ping_<n>` (added latency), and inbound
  `drop` of chosen server packets, for the desync classes (these two only where the server gives the player
  `foreman.test`, which the bridge's hello says). A `dupe-testing` skill and `find_dupes` prompt.
- Every MCP request needs a token, on localhost too (`mcp-token.txt`, made at the first start, when `mcp.token` is
  empty); requests from web pages are refused. Tools that act as the console (`server_*`, `learn`, WorldGuard and
  non-editing WorldEdit commands, plugins' console command tools) need `foreman.admin` with a personal token.

### For AIs

- `llms.txt` and `llms-full.txt` on the docs site, `AGENTS.md` in the repository, and prompts to paste into an agent
  ("Ask your AI to use Foreman").

### API

- `foreman-server-api`: `ForemanTools` (tools, commands and skills), `ForemanWorlds`, `FurnitureProvider`.
- `foreman-paper` and `foreman-api`: the location API, usable from Java and Kotlin.
