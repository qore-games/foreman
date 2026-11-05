# Testing in game

An AI that tests your plugin in the running game calls tools one by one: a command, a screenshot, the log, the
inventory, and reads each answer in full. **`client_test`** runs all of that as one test, from a list of steps, and
answers a line a step. A test is saved by name and runs again after every change, so a check made once keeps paying.

It runs on any server. With the Foreman plugin on the server, its steps also reach the server's tools (through the
[bridge](outside-agents.md)), the player can be put back as they were, and steps can run under network conditions.

## A first test

```json
{"name": "starter-kit", "keep_player": true, "steps": [
  {"command": "kit starter", "expect": "received"},
  {"client": "client_item", "args": {"slot": 0}, "expect": {"contains": "diamond_sword", "regex": "sharpness"}},
  {"client": "client_screenshot", "args": {"hide_hud": true}, "show": true}
]}
```

The first run saves it (in the Foreman config folder, `tests/starter-kit.json`); afterwards `{"name": "starter-kit"}`
runs it:

```text
test starter-kit: passed 3/3 in 1.9 s; player restored
✓ 1 /kit starter → Received kit starter. (811 ms)
✓ 2 client_item slot=0 → 0: minecraft:diamond_sword ×1 … (9 ms)
✓ 3 client_screenshot hide_hud=true → (1 image) (240 ms)
```

A failure says what failed and shows the lines of the output around it, not all of it.

## Steps

| Step | Does |
|---|---|
| `client`, `args` | Calls a client tool |
| `server`, `args` | Calls one of the server's Foreman tools (needs Foreman on the server) |
| `command` | A command as the player; `wait_ms` collects the replies (default 800) |
| `console` | A server command as the console; `wait_for` a text it prints |
| `wait` | Waits that many ms |
| `run` | Runs a saved test's steps here |
| `parallel` | Steps run at the same time: the client's and the server's traces of the same ticks, a trace while a command fires `delay_ms` later |

On any step:

| Option | Does |
|---|---|
| `expect` | A text the output contains, a list of them, or `{contains, not_contains, regex, not_regex, number, all, min, max, error}` |
| `capture` | `{var: regex}`: what it finds, for `${var}` in the next steps (`${player}` is the player's name) |
| `until_ms`, `every_ms` | Retried until `expect` holds |
| `label`, `delay_ms`, `show`, `optional` | Its name in the report; a wait first; its whole output and images in the answer; a failure that doesn't fail the test |
| `network` | A [network profile](../developers/network-profiles.md) held around it: `burst`, `ping_100`…`ping_1000` |
| `drop` | Inbound packet types ignored meanwhile (`set_slot`) |
| `packets` | The packets sent and received meanwhile, checked: `{"sent": ["container_click"], "not_received": ["container_set_slot"]}`, or `{"type": …, "min": …, "max": …}` |
| `after_ms` | How long drops and counting go on after the step, for the server's answer (default 400) |

`number` reads the first match of a regex (its group 1), `all` every match, against `min` and `max`. Bounds can be
arithmetic of captured values: `"min": "${before} + 0.5"`. `network`, `drop` and `packets` also go on a `parallel` or
`run`, around all of it. `network` and `drop` change what the server sees, so they need its opt-in: Foreman on the server
and `foreman.test` for the player.

A test also has `cleanup` (steps run whatever happened), `vars` (defaults a run can override), `description` and
`keep_player`. `repeat` runs it several times and reports how many runs passed, for flaky behaviour; `action` lists,
shows or deletes saved tests.

## The player

`keep_player: true` saves the player as the test found them (position, look, game mode, health, hunger, effects,
experience, inventory and ender chest, with the server's `test_player`), keeps them from any damage, and puts them back
at the end. A death or a disconnect during a step stops the test and says so; a dead player is respawned for the
cleanup.

## Seeing what happens

- `client_trace` records what the client has each tick (position, velocity, flags, nearby entities' boxes), keys held
  meanwhile, and starts with a summary line a test can check (ticks on the ground and in the air, height range, how
  far the player's feet went under each entity's top). `server_trace` is the server's view of the same ticks, with
  values [plugins add](../developers/foreman-tools.md#your-values-in-server-traces). In a `parallel`, they show where the
  two sides disagree.
- `client_pixels` `stats`: a region's mean colour and brightness and a grid of cells, to check that an effect shows
  without a screenshot. `client_perf`: the frame times a change costs.
- `client_record` and `client_replay`: what the player does (keys, look, hotbar slot, commands), saved with a test that
  replays it from where it started and says where its path left the recorded one. You record a bug you can show with
  `/foreman-record <name>` and `/foreman-record stop` (or the key in Foreman's controls), and the AI keeps it as a test.
  Login commands (`/login`, `/register`…) are never recorded.

## Bots that move

`client_wander` and `client_path` walk the player as a player would: they hold the movement keys and turn the camera
each tick (what `client_input` does), so the server sees ordinary movement, and they need no frames, so they run on
[lean clients](lean-clients.md).

- `client_wander` (`radius`, `duration_ms`, `seed`, `speed`: `walk` or `sprint`, `jump`, `stuck_ms`): random
  waypoints within `radius` blocks of where it starts. A waypoint is a block the player can stand on (solid below, two
  blocks of room, no liquid, fire, cactus…) that a straight walk reaches on safe ground from where they are: a block up
  at most (it jumps), three down at most, no lava, water, cliff or void. It takes the next one on arrival, or after
  `stuck_ms` (2 s) without getting closer; when none looks reachable it waits half a second and tries again
  (`no_waypoint`). The same `seed` from the same place picks the same waypoints; how long each walk takes depends on
  the server.
- `client_path` (`points`, `loop`, `speed`, `jump`, `duration_ms`, `stuck_ms`): the points in order, exact positions;
  one is skipped after `stuck_ms` without getting closer, and `loop` goes round until `duration_ms`.

Both answer JSON, the outcome first (`distance` in blocks, `waypoints_reached` or `reached` and `skipped`, `stuck`,
`jumps`), then the time, `blocks_per_s`, the `start` and `final` positions; `teleports` counts the ticks the server
moved the player more than two blocks (a teleport, a setback). `client_input` without steps stops them early.

```json
{"name": "walk-around", "steps": [
  {"client": "packets_record", "args": {"action": "start", "contents": false}},
  {"client": "client_wander", "args": {"radius": 16, "duration_ms": 60000, "seed": 1, "speed": "sprint"},
   "expect": {"number": "\"waypoints_reached\":(\\d+)", "min": 5}},
  {"client": "packets_record", "args": {"action": "stop"}},
  {"client": "packets_stats", "args": {"gap_ms": 500},
   "expect": {"number": "\"gaps\":\\{\"over_ms\":\\d+,\"count\":(\\d+)", "max": 0}}
]}
```

Several bots are several clients, each running its own test at the same time: a script or launcher starts them.

### What the network numbers mean

`packets_stats` reads the recording as JSON: packets and bytes on the wire each way and per type, per-second rates and
a series over time, the silences from the server of `gap_ms` or more (with `at_ms`, since the recording's start, and
`started_at`, its epoch ms, to line them up with the server's own timeline), and keep-alives: `answer_ms`, how long
this client took to answer each one, `interval_ms` between them, and `server_ping_ms`, the round trip the server
measured with them and sends with the tab list.

The connection is TCP: nothing sent is lost on the way. A bad link resends, and its packets arrive late and together,
so "drops" show as silences (`gaps`) followed by a burst in the series, keep-alives further apart than the server's
interval, a higher `server_ping_ms`, and at worst a timeout. A silence is a stall only when the server would have sent
something: near other players or mobs it sends their moves every tick, so silences are rare there, while in an empty
spot they're normal. The client can't tell a stalled server from a held link: compare with the server's tick times for
the same seconds.

## A dupe test

```json
{"name": "shulker-dupe", "keep_player": true, "steps": [
  {"server": "item_ledger", "args": {"item": "SHULKER_BOX", "reset": true}},
  {"client": "inventory_actions", "args": {"actions": [{"slot": 0, "input": "quick_move"}, {"slot": 0, "input": "throw"}], "per_tick": 2},
   "network": "burst", "drop": ["container_set_slot"], "packets": {"sent": [{"type": "container_click", "min": 2}]}},
  {"server": "item_ledger", "args": {"item": "SHULKER_BOX"}, "expect": "No change"}
]}
```

See [dupe testing](../server/dupe-testing.md) for the dupes worth a test.

## Test clients

A test can run in your own game, or in clients started for it next to yours: [lean clients](lean-clients.md) draw
frames only when a tool needs them and open no sound device, so several cost less than one normal client.

## Test players

A server's own players are what a test should behave like: a test client with an op's powers can't tell you whether a
real player can open that menu or run that command. With the `test-players` [feature](../server/setup.md#features) (on
in `dev`), names registered as **test players** get in through the whitelist like the players on it, and are plain
players otherwise. When a step needs more (a command to set the test up, a check only an op can make), `test_players`
elevates them: every permission the server knows, plus `*` for plugins that check it as such, for as long as it lasts,
on a permission attachment (nothing is written to `ops.json`); `demote` takes it back, at once. A permission plugin
that answers from its own data only, ignoring Bukkit's permission attachments, leaves elevated players plain:
`test_players` says so for each one it affects.

```json
{"action": "add", "names": ["Tester1", "Tester2"]}
{"action": "elevate", "names": ["Tester1"]}
{"action": "demote", "names": ["Tester1"]}
```

`/foreman testplayer list | add <name> [elevated] | remove | elevate | demote <name>` does the same in game (changes
need `foreman.admin`). They're kept in `plugins/Foreman/test-players.yml`. A name counts only when it connects from the
server's own machine: on an offline-mode server anyone may join with any name (`test-players.local-only: false` for
bots on other machines). A launcher that starts test clients for a server registers them when it starts them, and
removes them when they stop.

```yaml
test-players:
  local-only: true
  # What an elevated test player gets besides every registered permission.
  grant: ["*"]
```

## For the AI

The `testing` skill tells an AI how to write tests that hold (state kept, absolute positions, summaries checked rather
than rows): `client_test` with `action: guide` gives it on any server, and on a server with Foreman it's one of its
[skills](../server/skills.md). Only one session drives the client at a time: see
[one agent at a time](outside-agents.md#one-agent-at-a-time).
