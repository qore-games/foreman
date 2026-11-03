# Changelog

One version for the mod, the plugin and the API artifacts, [semantic](https://semver.org): a release is the tag
`v<version>` of `mod_version` in `gradle.properties`, and its notes are its section here.

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
