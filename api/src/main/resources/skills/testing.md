---
name: testing
description: Write and run automated in-game tests with client_test — steps over the client's and the server's tools, checks, the player kept, traces, recordings, network conditions and packets. Read when asked to test a feature end to end, reproduce a bug, check a fix, or before a check you will make more than once.
---

# Testing in game with client_test

A check you make twice is a test. `client_test` runs its steps in one call and answers a line a step, with the lines
around what failed: a saved test costs a few lines each run, where calling the tools yourself costs every output.

1. Write the steps once with a `name`: they are saved (the Foreman config's `tests/<name>.json`).
2. After each change: `client_test` with that `name` alone. `repeat: 3` when a result may be flaky.
3. Keep it: a fixed bug's test stays, and runs again after the next change.

## A step

One of: `client` or `server` (a tool's name, with `args`), `command` (as the player; `wait_ms` collects its replies,
default 800), `console` (a server command; `wait_for` a text it prints), `wait` (ms), `run` (a saved test),
`parallel` (steps at the same time). Then, optionally:

- `expect`: a text the output contains, a list of them, or `{contains, not_contains, regex, not_regex, number, all,
  min, max, error}`. `number` reads the first match of a regex (its group 1), `all` every match: `{"all": "feet
  ([-+][0-9.]+)\\.\\.", "min": -0.3}`. Bounds can use captured values: `"min": "${before} + 0.5"`.
- `capture`: `{var: regex}`, for `${var}` in the next steps (`${player}` is the player's name).
- `until_ms` (retried every `every_ms` until `expect` holds), `delay_ms`, `label`, `show` (the whole output and
  images in the answer: for a screenshot), `optional`.
- `network` (`burst`, `ping_300`…), `drop` (inbound packet types ignored, `set_slot`), `packets` (`{"sent": [...],
  "received": [...], "not_sent": [...], "not_received": [...]}`, or `{type, min, max}`), `after_ms` (drops and counting
  go on that long after, default 400). These also go on a `parallel` or `run` group. `network` and `drop` need Foreman on
  the server and `foreman.test` for the player.

A test has `steps`, `cleanup` (run whatever happened), `vars` (defaults, overridden by the run's), `keep_player`,
`description`.

## Make it hold

- **`keep_player: true`** for anything that moves, hurts or kills the player: their position, health, effects and
  inventory are put back at the end, and they take no damage meanwhile. Repeated small falls killed players before.
- **Absolute positions** (`minecraft:tp @s x y z yaw pitch`), not `~`: after a fall, `~` is somewhere else.
- **Check summaries, not rows.** `client_trace` and `server_trace` start with a summary line (ground and air ticks,
  height range, each entity's feet range); check it with `all`, and keep the rows for the failure's excerpt.
- **The server's answer comes later**: `wait_ms` on commands, `until_ms` on checks of what follows.
- **A death or a disconnect stops the test** and says so (a dead player is respawned for the cleanup). Out of the
  game, `client_connect` rejoins.

## Seeing more than the output

- `client_trace` (the client's view, tick by tick; `steps` holds keys meanwhile) with `server_trace` in a `parallel`:
  what each side has for the same ticks. Plugins can add their values to the server's rows.
- `client_pixels` `stats` (a region's mean colour and brightness, a grid of cells): whether an effect shows, without a
  screenshot. `client_perf`: what a change costs in frames.
- `client_record` then `client_replay`: what the player did, played back; recording saves a test that replays it from
  where it started and says where its path left the recorded one. Ask the player to `/foreman-record <name>` a
  bug they can show, then `/foreman-record stop`.
- A dupe test: `server item_ledger` with `reset`, the burst (`client inventory_actions`, `per_tick`) under `network` and
  `drop` with a `packets` check, then `item_ledger` again expecting "No change". The **dupe-testing** skill has the
  dupe classes.

## One agent at a time

The client is driven by one session at a time: a tool that acts takes it until 2 minutes after your last call, and
another session's acting calls are refused, saying who has it. `client_lock acquire` (a `label`, `minutes`) before a
long session, `release` when done, `wait` instead of retrying. If the player took it back (`/foreman-lock take`), ask
them. Don't launch a second game client to get around it: two clients and the dev servers can fill the machine's
memory.
