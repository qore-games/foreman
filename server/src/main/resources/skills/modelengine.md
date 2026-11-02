---
name: modelengine
description: Work with ModelEngine models (Blockbench blueprints, bones and their behaviors, animations, the generated resource pack) and put them on MythicMobs mobs. Read before adding or changing a model or when one doesn't show or animate.
plugin: ModelEngine
---

# ModelEngine

Blueprints are Blockbench files in `plugins/ModelEngine/blueprints/` (any subfolder). `/meg reload` (with
`server_command`) reads them again and regenerates the resource pack; its errors are in the log (`server_log`).

- **What's loaded:** the `modelengine_models` tool lists the models with their animations, and one model's bones,
  hitbox and animations (length, loop mode). Use it instead of guessing ids and animation names.
- **The resource pack:** after `/meg reload`, the generated pack must reach the players (merged into the server's pack,
  or sent as is). A model that shows as nothing or as a plain item usually means the client doesn't have the new pack.
- **Bone names carry behaviors** through prefixes in Blockbench (head, hitboxes, seats, nametags, ghost bones…):
  `modelengine_models` shows each bone's behaviors. Check ModelEngine's documentation for the prefixes of your version.

## On a MythicMobs mob

```yaml
Golem:
  Type: IRON_GOLEM
  Options:
    Silent: true
  Skills:
  - model{mid=golem} @self ~onSpawn
  - defaultstate{mid=golem;t=idle;s=idle_alt} @self ~onSpawn
  - state{mid=golem;s=slam} @self ~onAttack
```

`mid` is the model id (optional when the mob has one model); `state{s=<animation>}` plays an animation by its name in
the blueprint (`li`/`lo`: ticks to blend in and out, `remove=true` stops it); `defaultstate{t=walk;s=walk_heavy}`
replaces a built-in state (`idle`, `walk`, `death`…). Then `/mm reload` and spawn it (see the mythicmobs skill).

## Testing

- Players can summon a model on themselves with `/meg summon <model>` (run it as the player with
  `client_run_command`, or ask them); on a mob, spawn the MythicMobs mob.
- Look at it: `client_screenshot` from the player, or `look` from the server. Animations play in game only.
- Packet cost: every bone is a display entity: the client's packet viewer shows a model's traffic.
