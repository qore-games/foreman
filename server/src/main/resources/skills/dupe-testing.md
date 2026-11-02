---
name: dupe-testing
description: Test a plugin for item duplication — the ledger, snapshot, inventory bursts and audit, and the dupe classes to try. Read when asked to find, reproduce or confirm a dupe, or to check a plugin that touches items or inventories.
---

# Finding item dupes

A dupe ends with more of an item than there was. So every test is: **count everything, do the suspect action, count
again.** A bigger total is a dupe; the breakdown and the audit say where and how.

The server tools need `foreman.test` (they refuse without it). The client tools (`inventory_*`) are the player's own
inventory clicks, so they need the player's game linked to this server and the chat's commands allowed; their `network`
and `drop` options need `foreman.test` too.

## The loop

1. **Baseline.** `item_ledger` with the item to test (`held` matches the exact item incl. components, or a material like
   `DIAMOND`) and `reset: true`. Note the total and the per-place breakdown (players / containers / entities / ground).
   Scope it with `from`/`to` to a test area when the world is big (a box counts only what's inside it, players by where
   they stand — omit it to count the whole world, the usual choice when the tester carries the item).
2. **Save state** so you can repeat cleanly: `test_restore` `save` with a `label` (the tester, or named `players`).
3. **Watch** while you act: start `inventory_watch` (`seconds`), then do the action during the window. It lists each
   inventory event, whether it ended cancelled, and the plugins whose listeners are in the chain.
4. **Act** — see the dupe classes below.
5. **Count again.** `item_ledger` with the same item (no `reset`): it reports the change since the baseline. `+n` is
   duplication; the per-place line says where the copies landed.
6. **Reset** between attempts: `test_restore` `restore` the label.

## Dupe classes to try

Anything that touches items or inventories can dupe. For the plugin under test, find its item paths (its GUIs/menus,
shops, `/kit`, auctions, backpacks, claims storage) and try, for each:

- **Same-tick in/out.** `inventory_actions` with two `pickup` on the same slot and `per_tick: 2` (grab and replace in
  one tick). Also pickup then `quick_move`, or pickup then close.
- **Shift-click** (`quick_move`) repeatedly and fast into a plugin menu that moves or sells items.
- **Number-key / offhand swap** (`swap`, `button` 0–8 or 40) inside a plugin menu — a common gap, menus often guard
  normal clicks but not swaps.
- **Double-click collect** (`pickup_all`) to pull matching items from slots a menu meant to lock.
- **Drop while a menu reacts** (`throw`), or drop + close in one burst.
- **Entities with inventories:** put the item in a mule, chest/hopper minecart or item frame, then break or unload it
  (the ledger counts these; `server_command` can `kill`/`summon`/unload).
- **Reconnect / world change with an item on the cursor:** `client_run_command` a server-change or kick, or disconnect,
  then recount.
- **Sell or deposit twice:** two plugin commands or clicks in one tick.

## Desync (lag) dupes

The strongest dupes need the client and server to disagree for a tick — an attacker's lag switch. The mod ships two
network profiles: `burst` holds the client's sends and sends them together when the burst ends, and `ping_<n>`
(`ping_100`, `ping_300`, `ping_500`, `ping_1000`) adds n ms to each. Pass the name as `network` on `inventory_actions`
and it is held around the burst. A `drop` list ignores chosen server packets during it, so the client acts on the slots
it has while the server has already changed them.

## Confirm and report

- A real dupe repeats: restore, do it again, see the same `+n`.
- Say the exact steps (the `inventory_actions` list, the menu, the timing), the item, how many it made, and from the
  audit which plugin's listener let it through.
- Fixing is the plugin's job: cancel the event, re-check the inventory after the action, or use an atomic transaction.
  `learn` the finding so later sessions know this path dupes until it's patched.
