# Changelog

One version for the mod, the plugin and the API artifacts, [semantic](https://semver.org): a release is the tag
`v<version>` of `mod_version` in `gradle.properties`, and its notes are its section here.

## [Unreleased]

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

### Client (Fabric mod, any server)

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
