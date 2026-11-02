# Workspaces

A workspace is a named set of folders that chats work in: a plugin's configs, its sources, a datapack, a resource
pack. Make them in the hub's Workspaces section:

1. Name a workspace and create it.
2. Add its folders (absolute paths).
3. Optionally, write its instructions: how the AI should work there (conventions, what to edit, what not to touch).
   They're added to the prompt of every chat using it.
4. Mark it **default** to give it to every new chat.

A chat can use several workspaces, and can have extra folders of its own (in the chat's folder panel).

!!! tip
    For the packet viewer's *Ask the AI*, make a default workspace with your plugins' sources and your server's
    configs and language files. That's where the AI searches for what sends the traffic.

!!! note "Sandboxed launchers"
    A folder the game can't see (Flatpak launcher) is shown in yellow. Claude Code and opencode still use it, because
    they run outside the sandbox. See [Flatpak launchers](../getting-started/flatpak.md).
