# Dupe testing

Item duplication is the bug that wrecks an economy, and it almost always comes from a plugin mishandling an inventory.
Foreman turns a dupe hunt into something you run on purpose: **count every item on the server, do the suspect action,
count again.** A bigger total is a dupe, and the tools tell you which action and which plugin caused it.

It's the same method an attacker uses to find a dupe — run on your own server, before they do.

## The tools

| Tool | Where | Does |
|---|---|---|
| `item_ledger` | server | Counts an item everywhere — every player's inventory, ender chest and cursor, containers, item frames, armor stands, dropped items and entities with inventories (mules, chest/hopper minecarts) — and reports the total, a per-place breakdown, and the change since you last ran it |
| `inventory_watch` | server | Records the inventory events over a few seconds and whether each ended cancelled, with the plugins whose listeners are in the chain |
| `test_restore` | server | Saves and restores players' inventories, so each attempt starts the same |
| `inventory_snapshot` | client | The open container exactly as the client holds it: slots, cursor, sync state id |
| `inventory_actions` | client | Performs a sequence of inventory clicks like a player — shift-click, number/offhand swap, middle-click clone, drop, double-click collect — including several in the same client tick |

## The loop

An AI chat with the `dupe-testing` skill does this for you; the prompt **find_dupes** kicks it off (`/foreman` → prompts,
or ask a chat "find dupes in `<plugin>`"). By hand:

1. `item_ledger` the item (`held`, or a material), `reset: true` — the baseline.
2. `test_restore` `save` a label.
3. `inventory_watch` on, then do the suspect action with `inventory_actions` (e.g. two `pickup` on one slot,
   `per_tick: 2` — grab and replace in one tick).
4. `item_ledger` again: `+n` is duplication, and the breakdown says where the copies went.
5. `test_restore` `restore` and repeat to confirm.

The dupe classes to try — same-tick in/out, shift-spam, number/offhand swaps in menus, double-click collect, drop-while-
reacting, mules and minecarts, reconnect with a cursor item, sell-twice — are in the skill.

## Desync (lag) dupes

The strongest dupes need the client and server to disagree for a tick, the way added latency does. The mod ships two
kinds of [network profile](../developers/network-profiles.md): `burst` holds the client's sends and sends them together
when the burst ends, and `ping_<n>` (`ping_100`, `ping_300`, `ping_500`, `ping_1000`) adds n ms to each. Name one as
`network` on `inventory_actions` and it is held around the burst; without one, the bursts still run at normal timing and
catch every logic dupe above.

A `drop` list on `inventory_actions` ignores those server packets for the burst's duration (`setslot`,
`containerset`): the client keeps acting on the slots it has while the server has already changed them. The drop stays
armed for `drop_ms` after the last click (default 400) so the server's reply, which arrives a round-trip later, is
caught.

`network` and `drop` need the server's opt-in, like the server tools: the Foreman plugin and `foreman.test`. Elsewhere
`inventory_actions` refuses them, and its plain bursts still run.

## Scope

- The server tools need the Foreman plugin and `foreman.test` (ops by default); they read and restore inventories and
  count items.
- The client tools replay the clicks a player already makes, through the normal click path, on any server. Their
  packet options (`network`, `drop`) only run where the Foreman plugin gives you `foreman.test`.
- The desync layer is a [`NetworkProfile`](../developers/network-profiles.md): the mod ships `burst` and `ping_<n>`,
  and a dev build can register its own.

## Keep the wins

Each dupe you patch becomes a test you keep: save the steps as a chat prompt or a note, and re-run them after every
plugin update. The ledger is a regression guard, not a one-off.
