# First run

## Open the hub

Press ++h++. The hub has everything on one screen: a rail of sections on the left, grouped by what they're for
(AI, World, Debug), with Keys and Settings at the bottom. ++l++ opens it on Locations.
See [The hub](../client/hub.md).

## Record the traffic

1. Stand where the game stutters and run `/packets start 20`.
2. When it stops, run `/packets` (or open the hub's Packets section).
3. Start with *Suspects*: the traffic grouped by what probably sends it. See [Packet viewer](../client/packets.md).

## Look at the HUD

`/hud` opens the HUD inspector, and `/hud guides action_bar` draws guides over the action bar. See
[HUD inspector](../client/hud-inspector.md).

## Ask the AI

Press ++y++, start a chat and ask. By default the chat uses Claude Code with your own login; pick another backend in
the hub's AI section. Give the chat your plugin's folder through a [workspace](../ai/workspaces.md) so it can read and
fix the code. See [Chats](../ai/chats.md).

## Let Claude Code in a terminal drive the game

In the hub's AI section, allow outside sessions and copy the `claude mcp add` command. Run it once in the folder
Claude Code works in. See [Outside agents](../ai/outside-agents.md).
