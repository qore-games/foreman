---
description: Test a plugin (or an item path) for item duplication and report what dupes, with the exact steps
arguments:
  - {name: target, description: "What to test: a plugin name, a menu/command, or an item path to probe", required: true}
  - {name: item, description: "The item to test with (default: whatever the tester holds)", required: false}
---
Read the `dupe-testing` skill, then test this for item duplication: {{target}}
Test item: {{item}}

Work like this:
1. Make sure you have foreman.test (the tools say if not). Find the target's item paths: its menus, commands, shops,
   storage. Check `server_plugins` and the plugin's config if you need to.
2. For each path, run the loop from the skill: item_ledger baseline (reset), test_restore save, inventory_watch on,
   do the suspect action (try the same-tick, shift, swap, collect, drop and reconnect classes), item_ledger again.
3. When the total grows, confirm it repeats: restore and redo it, see the same gain. Note the exact inventory_actions
   and timing, and from the watch which plugin's listener let it through.
4. Report each confirmed dupe with its steps, how many it made per run, and the likely fix (cancel the event, re-check
   the inventory, make the transaction atomic). `learn` each finding. Keep what you tried and found clear and short.

Known findings on this server:
{{lessons}}
