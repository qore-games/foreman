# Backends

![The hub's Agents page](../assets/screenshots/agents.png)

Pick the backend of new chats in the hub's **AI → Agents**, with its settings under it. Each chat keeps the backend it
started with.

=== "Claude Code"

    Runs `claude` on your computer with your own login, in print mode, with two MCP servers: `client` (this client's
    tools) and `minecraft` (the Foreman server, when there is one). Only their tools are allowed, plus Claude Code's
    own file tools on the chat's folders.

    Settings: the command (default `claude`) and the model (empty: Claude Code's default).

=== "opencode"

    Runs `opencode run` on your computer with the same two MCP servers, and its file tools on the chat's folders.

    Settings: the command (default `opencode`) and the model.

=== "OpenRouter"

    Talks to OpenRouter from the game, with your API key. No program to install, and no Flatpak permission needed.

    Settings: the API key and the model (default `anthropic/claude-sonnet-4.5`).

## Other settings

- **Instructions:** added to every prompt (house rules, language…).
- **Server MCP URL:** the address of the Foreman plugin's MCP endpoint, when the one the server announces is wrong for
  your computer (see the server's `mcp.public-url`). Empty: the server's.

All of them are stored in `config/foreman/settings.json`, readable by you only.
