# AI

Foreman doesn't ship a model or an account. The AI runs on your computer with **your own** Claude Code, opencode or
OpenRouter account, and Foreman gives it tools:

- **The client's tools**, on any server: screenshots, commands as you, the camera, the open screen (clicks, typing),
  entities, items, resource packs, the client log, the packet viewer, the HUD inspector, and the folders you give it.
- **The server's tools**, where the Foreman plugin runs: [dev-server tools](../server/dev-tools.md),
  [world tools](../server/world-tools.md), and tools other plugins publish.

You can use them in two ways:

- [Chats](chats.md) in game (++y++): ask in plain words. Several chats can run at once.
- [Outside agents](outside-agents.md): Claude Code in a terminal connects to the client's tools through MCP, so it can
  implement a change in your editor, deploy it, and test it in the running game.

See the [tool reference](tools.md) for every tool.
