# Keys and commands

## Keys

Change any of them in the hub's Keys section (click the key, then press the new one: ++esc++ cancels, ++backspace++
unbinds) or in Minecraft's controls.

| Key | Does |
|---|---|
| ++h++ | The hub |
| ++b++ | [Editor mode](editor.md): the building tools and their keys |
| ++y++ | AI chats |
| ++f6++ / ++f7++ | Copy your exact / block position |
| unbound | Packet recording, input recording, ask the AI about the selection, take the client back from AI agents, overlays on/off, each overlay layer |

## Editor mode's keys

The building tools have no key bindings of their own: [editor mode](editor.md) (++b++) lists them, ++1++ to ++0++
pick one, and while it is on the current tool's keys are the editor's, before the game's (++f++ doesn't swap hands,
++1++ to ++0++ don't change the hotbar slot). ++esc++ steps back, then leaves. The hub's Keys section lists every
tool with its keys.

| # | Tool | Keys |
|---|---|---|
| 1 | [Selection](selection.md) | ++ctrl+c++ / ++ctrl+x++ [copy / cut it](selection.md#copy-and-paste), ++ctrl+v++ paste the clipboard, ++enter++ ask a chat about it, ++delete++ clear it, ++n++ the hub's Selection page |
| 2 | [Display editor](displays.md) | ++v++ mode, ++o++ axes, ++period++ pivot, ++n++ values, ++equal++ duplicate, ++delete++ delete, ++z++ undo, ++end++ drop, ++i++ select in the box, ++p++ move position |
| 3 | [Place model](models.md#placing) | ++r++ turn, ++enter++ place, ++shift+enter++ place and edit, ++n++ the model browser |
| 4 | [Place asset](library.md#placing) | ++r++ turn, ++enter++ paste, ++z++ undo, ++n++ the library |
| 5 | [Asset tool](library.md#the-asset-tool) | ++c++ new asset from the selection, ++n++ panel, ++r++ turn, ++f++ fit, ++u++ sync a copy, ++delete++ delete |
| 6 | [Areas and points](regions.md#the-tool) | ++n++ panel, ++m++ new area from the selection, ++p++ new point, ++delete++ delete, ++k++ check the layout |
| 7 | [Measure](editor.md#measuring) | ++backspace++ remove the last measure, ++delete++ clear them all |
| 8 | [Location editor](locations.md#editing) | ++k++ / ++u++ / ++j++ to your feet / eyes / block, ++h++ gizmo, ++n++ sources and categories |
| 9 | [WorldEdit selection](selection.md#the-worldedit-selection) | ++n++ the hub's Selection page |
| 0 | [World objects](objects.md) | ++c++ new object, ++n++ the [panel](objects.md#the-panel) of the selected object (else of what you aim at), ++v++ next anchor, ++p++ anchor where you aim, ++delete++ delete or [unlink](panels.md#relations), ++z++ undo, ++k++ [link mode](panels.md#linking), and the [keys plugins add](objects.md#keys-from-plugins) |
| any | [Snapping](editor.md#gizmos-and-snapping) of every gizmo | ++g++ the step of what the gizmo does now: moving (free, 1/16 to 1/2, block grid), turning or scaling; ++ctrl++ held while dragging: free |
| any | Overlays | ++x++ [hidden blocks](editor.md#hidden-blocks), ++l++ [displays per chunk](editor.md#displays-per-chunk) |

## Client commands

| Command | Does |
|---|---|
| `/packets [start [seconds] \| stop \| clear \| export \| ask]` | [Packet viewer](packets.md) |
| `/hud [guides [element]]` | [HUD inspector](hud-inspector.md) |
| `/fastreload [on \| off \| playing \| screen]` | Reload the resource packs; [fast reloads](resource-packs.md) on or off, playing through them or not |
| `/locations …` | [Plugin locations](locations.md); `/locations editor` opens editor mode on the location editor |
| `/local …` | [Local sets](locations.md#local-sets) |
| `/ai` | Open the chats |
| `/ai ask <prompt>` | Send a prompt in a new chat |
| `/ai new`, `/ai cancel` | A new chat; stop every running chat |
| `/ai tools` | Every tool the AI can use, by provider |
| `/ai selection clear` | Clear the selection |

The server plugin's `/foreman` command is described in [Setup and configuration](../server/setup.md#commands).
