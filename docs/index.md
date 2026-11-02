# Foreman

A multi-tool for Minecraft server owners, admins and plugin developers. See what your server sends and draws, debug
your plugins on a running server, and work with an AI that can do the same: in game, or from your terminal.

Foreman is a **Fabric client mod** and an optional **Paper plugin** for Minecraft 26.3.

<video src="assets/videos/ai-debug.mp4" controls muted loop playsinline preload="metadata" width="100%"></video>

*A plugin's bug, found and fixed from inside the game: the chat reads the server's log, fixes the config, reloads
the plugin and tries the command again. More in [Examples](ai/examples.md).*

<div class="grid cards" markdown>

-   **Packet viewer**

    Record what the server and your client send each other, then find what floods the connection.
    [→ Packet viewer](client/packets.md)

-   **HUD inspector**

    See how the client lays out action bars, titles, boss bars and the tab list, glyph by glyph.
    [→ HUD inspector](client/hud-inspector.md)

-   **Plugin locations**

    Plugins send their NPCs, spawns and regions, and the mod draws them. Move them with a gizmo.
    [→ Locations](client/locations.md)

-   **Dev-server tools**

    Commands with everything they printed, the log since the last reload, stale jars, a profiler, event listeners.
    [→ Dev-server tools](server/dev-tools.md)

-   **AI chats**

    Your own Claude Code, opencode or OpenRouter, in game, with your position, selection and plugin folders.
    [→ Chats](ai/chats.md)

-   **Outside agents**

    Claude Code in a terminal uses the client through MCP to test your plugin end to end in the running game.
    [→ Outside agents](ai/outside-agents.md)

-   **Your plugins, understood**

    Skills teach the AI WorldEdit, WorldGuard, MythicMobs, LuckPerms and more; add yours as a markdown file.
    [→ Plugin integrations](server/integrations.md)

-   **Dupe testing**

    Count every item, attack a plugin's menus and shops, and catch what duplicates — before your players do.
    [→ Dupe testing](server/dupe-testing.md)

-   **For your AI**

    Prompts to connect your agent to Foreman or add Foreman support to your plugin; the docs in one file.
    [→ Ask your AI](ai/ask-your-ai.md)

</div>

## What runs where

| | Client mod alone | With the server plugin |
|---|---|---|
| Packet viewer, HUD inspector, local location sets | ✓ | ✓ |
| Plugin locations | when a plugin sends them (`foreman-paper`) | when a plugin sends them |
| AI chats with the client's tools (screenshots, commands, files, screens) | ✓ | ✓ |
| Dev-server tools (`server_command`, `server_log`, `server_plugins`…) | | ✓ |
| World tools (render, edit, terrain, buildings, check, undo) | | ✓ |
| Dupe testing (item ledger, inventory watch, action bursts) | | ✓ |
| Tools other plugins publish (`ForemanTools`) | | ✓ |

## Cheap when unused

Foreman is meant to stay installed. A packet recording runs only while you record, overlays are drawn only when
they're on, and the client's MCP server only answers outside agents when you allow them.

!!! note "Server requirements"
    The server plugin runs on Paper alone. Add FastAsyncWorldEdit for the world tools: they're built on it, and they're
    off without it.
