# Keys and commands

## Keys

Change any of them in the hub's Keys section (click the key, then press the new one: ++esc++ cancels, ++backspace++
unbinds) or in Minecraft's controls.

| Key | Does |
|---|---|
| ++h++ | The hub |
| ++y++ | AI chats |
| ++b++ | Selection tool |
| ++l++ | Plugin locations |
| ++k++ / ++u++ / ++j++ | Location editor: move the selected location to your feet / eyes / block |
| ++f6++ / ++f7++ | Copy your exact / block position |
| unbound | Location editor, WorldEdit tool, packet recording, ask the AI about the selection, overlays on/off, each overlay layer |

## Client commands

| Command | Does |
|---|---|
| `/packets [start [seconds] \| stop \| clear \| export \| ask]` | [Packet viewer](packets.md) |
| `/hud [guides [element]]` | [HUD inspector](hud-inspector.md) |
| `/locations …` | [Plugin locations](locations.md) and the location editor |
| `/local …` | [Local sets](locations.md#local-sets) |
| `/ai` | Open the chats |
| `/ai ask <prompt>` | Send a prompt in a new chat |
| `/ai new`, `/ai cancel` | A new chat; stop every running chat |
| `/ai tools` | Every tool the AI can use, by provider |
| `/ai selection clear` | Clear the selection |

The server plugin's `/foreman` command is described in [Setup and configuration](../server/setup.md#commands).
