# World context

Each world carries its own settings, saved in the world itself (its persistent data), so they travel with the world
file. The AI reads them with `get_context`; your plugins read them with [`ForemanWorlds`](../developers/foreman-worlds.md).

## Profile

The look and rules the AI follows in a world, and the defaults of generated buildings. Without one it builds neutrally.
Set it with the `world_profile` tool (ask the AI), which merges fields in:

| Field | What |
|---|---|
| `theme` | Text: the look, palette, era, level-design rules |
| `build` | Defaults of `build_structure` (`style`, `roof`, `decay`, `clutter`, `dark`, `furnish`, `room_width`, `floor_height`, `basement`…) |
| `styles`, `uses`, `rooms` | [Building templates](building-templates.md) for this world only |

```yaml
theme: |-
  A coastal trading town, late medieval. Timber-framed houses on stone bases, narrow streets that follow the
  relief, a market square by the harbor. Read the style guide `harbor` before building near the water.
build:
  style: timber
  roof: gable
```

## Areas

Named places ("town_hall", "east_gate"), made with `set_mark`, a tool's `mark` argument or `/foreman mark <name>`:

- **Claims** reserve space for a build, a road or a yard (`claim`, or the `claim` argument of the tools that build), so
  later edits keep off it.
- **Zones** are areas with a `theme`: their own building rules, on top of the world's profile.

The client draws them as the *Areas* overlay, and can tell you when you enter or leave one.

## Points

Gameplay spots that generated buildings leave behind, from the `point` entries of the
[room templates](building-templates.md#rooms): containers, spawns… with free type and kind names. The `points` tool
lists, exports (JSON), adds and removes them; the client draws them as the *Points* overlay; your plugins read them
(fill containers, spawn NPCs) with `ForemanWorlds`.

## Export and import

`/foreman world export [world]` writes the profile, areas and points to `plugins/Foreman/worlds/` as YAML, and
`/foreman world import [world]` loads them back. `/foreman world show` prints the profile.
