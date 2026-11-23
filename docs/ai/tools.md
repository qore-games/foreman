# Tool reference

Every tool the AI can use, by provider. In game, the hub's AI section (Tools) lists them with their full
descriptions; `/ai tools` prints them to chat, and `/foreman tools [filter]` on the server lists the server's.

## Client tools

Available on any server, to chats and to [outside agents](outside-agents.md).

### Seeing and acting

| Tool | Does |
|---|---|
| `client_context` | Where the player is and looks, the block or entity at the crosshair, their selection, dimension, the last chat lines |
| `client_screenshot` | What the player sees, as an image, optionally without the HUD (needs Vision) |
| `client_run_command` | Runs a command as the player and returns what the server printed (needs Commands) |
| `client_connect` | Joins a server (by default the last one joined) and waits until the player is in game, or leaves it (needs Commands) |
| `client_teleport` | Teleports the player: vanilla's `/minecraft:tp` as them (a plugin's `/tp` takes other arguments), else `/tp` where the server keeps namespaced commands from players; says when neither moved them |
| `client_look` | Turns the camera to a point, or a yaw and pitch |
| `client_input` | Holds key bindings across ticks, in steps, as the player holding keys: walking, sprinting, sneaking, a double jump, a dash, holding use or attack (needs Commands) |
| `client_wander` | Walks the player around like a bot, to random waypoints within a radius of where it starts, on safe ground, for a time; JSON: distance, waypoints reached, times stuck, jumps, final position (needs Commands). See [bots that move](testing.md#bots-that-move) |
| `client_path` | Walks the player through given points in order (or round in a loop), skipping one it can't get closer to; JSON: each point reached or skipped and how long it took (needs Commands) |
| `client_trace` | The client's own view, tick by tick: the player's position, velocity, ground and collision flags and pose, and nearby entities' boxes, optionally while holding keys; a summary line first (ticks on the ground and in the air, height range, each entity's feet range) (needs Commands) |
| `client_record`, `client_replay` | Record what the player does (keys, look, hotbar slot, commands, path), saved with a test that replays it from where it started and says where its path left the recorded one (needs Commands). Players record with `/foreman-record <name>` and `/foreman-record stop`, or a key |
| `client_stats` | A snapshot of the client: FPS, memory, graphics device, distances, rendered sections and entities, particles, ping, post effects, packs |
| `client_usage` | What the client process uses: heap and other JVM memory, resident memory, CPU time and CPU % over a few seconds, threads, the JVM's flags, frames drawn, [lean mode](lean-clients.md); `json` for scripts |
| `client_sounds` | The sounds the client plays over a few seconds: which, where, how far, volume and pitch |
| `client_pixels` | Exact colours of a rectangle of the world as rendered (post effects included, the HUD hidden), or a region's numbers (`stats`: mean colour, luminance spread, a grid of cell means) |
| `client_perf` | The client's frame times over a few seconds (average FPS, 1% and 0.1% lows, worst frame), optionally after a command: what a change costs |
| `client_frames` | Screenshots one after another as one labelled contact sheet, optionally after a command and turning the camera between frames (needs Vision) |
| `client_chat_log` | The last chat and system messages |
| `client_log` | The client's recent log lines, filtered by level and text |

### Tests

`client_test` runs a test in one call: steps calling client tools, the server's Foreman tools, commands, waits and
saved tests, each checked against what it returned, optionally under network conditions with the packets checked. It
answers a line a step, and the lines around a failure. A test is saved by `name` and runs again by its name. See
[Testing in game](testing.md).

| Tool | Does |
|---|---|
| `client_test` | Runs a test, saves it, lists, shows or deletes saved ones; `action: guide` gives the AI the testing skill (needs Commands) |
| `client_lock` | Who drives the client: one session at a time (see [Outside agents](outside-agents.md#one-agent-at-a-time)) |

### Screens

| Tool | Does |
|---|---|
| `client_screen` | The open screen: its class and title, the GUI size, each widget with its text and bounds |
| `client_click`, `client_hover`, `client_scroll` | Use the open screen like the mouse |
| `client_drag` | Hold a mouse button and move: across a screen (scrollbars, sliders), or turning the camera in the world (gizmos, mining) |
| `client_type`, `client_key` | Type into the focused field; press a key binding, or hold it (`hold_ms`); a physical key (`key.keyboard.i`, `key.keyboard.0`, `key.keyboard.delete`, with `shift` / `ctrl` / `alt`) goes through the game's keyboard handler like a real keyboard, with the character it types: mods' raw keys (the [editor](../client/editor.md)'s tool keys) and the open screen get it as from a player |
| `client_dialog_preview` | Opens a dialog on the client alone from its JSON, as the server would send it |
| `inventory_snapshot`, `inventory_actions` | Read the open container, and click it like a player (bursts in one tick, optionally under a network profile or inbound packet drop, which need `foreman.test`) — [dupe testing](../server/dupe-testing.md) |

### Entities, items, resources

| Tool | Does |
|---|---|
| `client_entities` | The entities around the player or along the look, with their synced data |
| `client_item` | An item the player has, with its components, as the client sees it |
| `client_resource_packs` | The loaded resource packs, bottom to top; `reload` reloads them and returns how long each step took, and what was logged |
| `client_post_effects` | The post effects (full-screen shaders) in use |

### Packets

| Tool | Does |
|---|---|
| `packets_record` | Starts or stops a recording |
| `packets_summary` | Totals each way, the busiest second, the types, entities and chunks that get the most |
| `packets_suspects` | The traffic grouped by what likely sends it, with clues to search for |
| `packets_list` | Kept packets one by one, filtered |
| `packets_timeline` | Packets and bytes per time bucket, with the peaks |
| `packets_stats` | The recording's numbers as compact JSON for reports: packets and bytes each way and per type, rates per second over time, silences from the server, keep-alives and the ping the server measured |
| `packets_export` | Writes the recording to a file |

### HUD

`ui_capture`, `ui_watch`, `ui_measure`, `ui_glyphs`, `ui_check_widths`: see [HUD inspector](../client/hud-inspector.md).

### Files (chats only)

`client_list_files`, `client_read_file`, `client_write_file`, `client_search_files`: the chat's workspace folders.
Claude Code and opencode also have their own file tools on the same folders; OpenRouter chats rely on these.

## Server tools

Available where the Foreman plugin runs.

### Dev server

`server_command`, `server_log`, `server_log_mark`, `server_plugins`, `server_status`, `server_profile`,
`server_listeners`, `server_packets` (what the server sends and receives, every player at once),
`server_reload_plugin` (with PlugMan or PlugManX). See [Dev-server tools](../server/dev-tools.md).

### Skills

`read_skill` (one skill, or the list), `save_skill` (a skill for the server, from a chat). See
[Skills](../server/skills.md).

### Testing

`item_ledger` (count an item everywhere, with the change since last time), `inventory_watch` (the inventory events a
test fires and who handles them), `test_restore` (save/restore inventories): see [Dupe testing](../server/dupe-testing.md).
`test_player` saves and restores players as a test found them (position, game mode, health, hunger, effects,
experience, inventory; optionally unhurt meanwhile), `server_trace` records the server's view of a player tick by tick,
with the values plugins add ([your values in server traces](../developers/foreman-tools.md#your-values-in-server-traces)),
to compare with `client_trace`. Need `foreman.test`. `test_players` registers [test players](testing.md#test-players)
(through the whitelist, plain players until elevated) and elevates or demotes them; it needs `foreman.admin`.

### Panels

What the server's plugins show and let builders change about their things, and the relations between them
([panels and relations](../developers/panels.md); the `panels` feature):

| Tool | Does |
|---|---|
| `panel_get` | The panel of a target, or of what the calling player aims at (`aimed: true`): its values, fields (type, range, choices) and buttons |
| `panel_edit` | Sets a field of a panel, checked against it; the plugin applies or refuses it |
| `panel_action` | Presses a button of a panel (a list item's by its `item` arg; a conditions row's add with `condition`) |
| `panel_facts` | What a panel's conditions row can read: its subjects, a subject's facts (type, values, now), whether a condition holds now |
| `list_relations` | What links the plugins' things near a point or the player: ids, providers, both ends |
| `link_targets` | Links two things through the plugin serving the first: done, refused, or a question with options to answer with `choice` |
| `unlink` | Removes a relation through its plugin |
| `relink` | Changes a relation through its plugin: reverses it, or moves its from or to end to another thing (as `link_targets` answers) |

### Other plugins

| Plugin | Tools |
|---|---|
| ModelEngine | `modelengine_models`: the loaded models, their bones, hitboxes and animations |

Most plugins need no tool of their own: a [skill](../server/integrations.md) teaches the AI their commands, which it
runs with `server_command` (or `worldedit`).

### World

| Group | Tools |
|---|---|
| Inspect | `get_context`, `read_style_guide`, `scan_area`, `read_blocks`, `find_blocks`, `render`, `world_profile`, `set_mark`, `diff_regions` (a box against its copy: [checking a copy](../server/assets.md#checking-a-copy)) |
| Quality | `check`, `look`, `measure_site`, `claim`, `check_layout` (points and areas against the types plugins declare: [layout types](../server/world-context.md#layout-types)), `lock_area` and `unlock_area` (others' edits refused inside an area while you build in it: [locks](../server/world-context.md#locks)) |
| Edit | `edit`, `worldedit` (any WorldEdit command on a box, undoable), `transform_region`, `undo` (your own changes), `history` (`format: json` for programs) |
| Terrain | `foundation`, `blend_terrain`, `smooth_terrain`, `make_ground`, `grade`, `build_path`, `make_fields` |
| Buildings | `build_structure`, `demolish`, `list_styles`, `points` (with their `data`), `layout_types` (the point and area types plugins declare, with their fields) |
| Repair | `connect_doors`, `repair_terrain` |
| Assets | `list_assets`, `index_assets`, `preview_asset`, `asset_content` (what one holds, as data), `scatter_assets`, `paste_asset`, `save_asset`, `copy_region` (a box into your clipboard, which `paste_asset {name: "clipboard"}` pastes: [clipboards](../server/assets.md#clipboards)); asset areas: `define_asset`, `fit_asset`, `resize_asset`, `move_asset` (with everything in it), `open_asset_source` (takes the player to an asset's source); pastes: `list_instances`, `instance_info`, `sync_asset` (every copy of an asset, nested ones included), `sync_instance`, `detach_instance`, `attach_instance`, `forget_instance`, `delete_instance`, `rotate_instance`: [assets](../server/assets.md#instances) |
| Models | `preview_model`, `list_models` (the item models of the server's resources) |
| Displays | `list_displays`, `edit_displays` (move, turn, scale, stretch item, block and text displays, furniture included; their view range), `spawn_display` (a model, block or text, or a copy of a display), `remove_displays`: [displays and models](../server/displays.md) |
| Entities | `list_entities` (any entity in a box, filtered by type, model, item or block, or by uuid: name, tags, persistent data, a display's view range, a text display's text, a mob's health, with `nbt` its whole NBT as SNBT), `edit_entities` (in bulk, by uuid or box: view range, tags, typed persistent data keys, custom name and text (MiniMessage), persistence, AI, attribute base values and health, NBT paths removed and an SNBT merged as `/data` does, one undo step), `remove_entities` (any entity but a player, by uuid or box; undo brings them back with their uuid), `copy_entities` (with all their data, new uuids, in place or moved): [entities](../server/displays.md#entities) |
| Furniture | `list_furniture`, `place_furniture`, `remove_furniture` (with a [furniture provider](../developers/furniture.md)) |
| Objects | `object_kinds` (what plugins let you edit: cables, props, with their shape and fields; with `kind` and `data`, the choices of fields that depend on others for that data), `list_objects`, `place_objects`, `edit_objects` (anchors and fields), `remove_objects`: [world objects](../developers/world-objects.md) |
| Lessons | `learn`, `lessons` |

See [World tools](../server/world-tools.md).

### Your plugins' tools

Other plugins publish their own with [`ForemanTools`](../developers/foreman-tools.md). They're listed with their
plugin as the provider.
