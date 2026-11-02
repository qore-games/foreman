# Lessons and prompts

## Lessons

`plugins/Foreman/lessons.md` holds what the AI learned on your server, one `- ` line each: a mistake the checks
missed, a rule of a zone, a tool trick. The AI records them with `learn` when you correct it, reads them at the start
of every session, and `lessons` lists or removes them. Edit the file by hand too.

Every later session follows them, the console's too, so writing and removing lessons needs `foreman.admin` (or the
server's token). A lesson is one sentence of at most 300 characters, and a server keeps at most 100.

## Prompts

MCP prompts are ready-made tasks with arguments. Foreman ships `build_place` (build something at a place, end to end,
until `check` reports no errors), `review_area` (review an area like a player and a level designer, changing nothing)
and `repair_area` (find and fix what's wrong, checking again after each round).

Add yours, or replace these, as `.md` files in `plugins/Foreman/prompts/`: a YAML header between `---` lines, then the
text. `{{argument}}` is replaced by the argument, `{{lessons}}` by the server's lessons.

```markdown title="plugins/Foreman/prompts/test_command.md"
---
description: Test a plugin command end to end and report what broke
arguments:
  - {name: command, description: "The command to test, without the slash", required: true}
---
Mark the server log, run `{{command}}` with server_command, then read the log since the mark.
Report the replies, every warning and error with the plugin and line it came from, and a fix.

Lessons learned on this server:
{{lessons}}
```
