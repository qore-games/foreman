# Ask your AI to use Foreman

Your AI (Claude Code, opencode, Cursor, any agent with MCP) can set Foreman up and build on it for you. These
prompts are written for it: paste one into a session in your plugin's or server's folder.

The documentation is available for AIs in one file, <https://qore-games.github.io/foreman/llms-full.txt>, and as an
index, <https://qore-games.github.io/foreman/llms.txt>. The repository's
[AGENTS.md](https://github.com/qore-games/foreman/blob/main/AGENTS.md) explains the code to an AI working on Foreman
itself.

## Connect to your server and your game

```text
Read https://qore-games.github.io/foreman/llms-full.txt to learn Foreman.
Then connect yourself to it:
- my dev server runs the Foreman plugin; its MCP endpoint is http://127.0.0.1:25599/mcp (mcp.port in
  plugins/Foreman/config.yml), with the token in plugins/Foreman/mcp-token.txt (or mcp.token) sent as
  "Authorization: Bearer <token>": add it as an MCP server named "minecraft".
- my game runs the Foreman mod: I allowed outside agents in the hub (AI → Agents); the address and token are in
  config/foreman/client-mcp.json of my Minecraft instance: add it as an MCP server named "foreman-client".
List the tools and skills you get, and tell me what you can now do for me.
```

## Add Foreman support to my plugin

```text
Read https://qore-games.github.io/foreman/llms-full.txt, sections "Skills for your plugin", "ForemanTools" and
"Add Foreman to your plugin".
Then make this plugin easy for an AI to use through Foreman:
1. Write skills in src/main/resources/foreman/skills/ for its main tasks (its commands, config files, how to test a
   change, pitfalls), one per task, each with a description that says when to read it. Only commands that exist in
   this code.
2. Where a command can't give what an AI needs (structured data, an action without a command), add a tool with
   ForemanTools, in a class loaded only when Foreman is enabled (softdepend: Foreman).
3. Build, then on my dev server (Foreman's MCP) reload the plugin, check that /foreman skills and /foreman tools list
   them, and try each tool once.
```

## Write a skill for my server

```text
Using Foreman's tools on my server, work out how we <do the task> (look at the plugins' configs and commands involved,
try them), then save it as a skill with save_skill so later sessions know it: a short description that says when to
read it, the exact commands and steps, and the pitfalls you hit.
```

## Test my plugin end to end

```text
I changed <feature> in this plugin. Build it, deploy the jar to my dev server's plugins folder, then with Foreman:
mark the server log, reload the plugin (server_reload_plugin), check server_plugins says it runs the new jar, and
read the log since the mark. Then in my game (foreman-client tools): <steps a player would do>, take screenshots,
and tell me what works and what doesn't, with the log lines and screenshots that show it.
```

## Tips

- Give the AI the folders it may edit (a chat's workspace in game, the session's folder outside).
- Ask it to `learn` rules and `save_skill` workflows that worked: the next session starts from there.
- The tool reference ([Tools](tools.md)) and the skills of your server (`/foreman skills`) are what it can do here.
