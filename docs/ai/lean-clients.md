# Lean clients for automated tests

An AI that tests your plugin needs a real client: one that ticks, talks to the server, opens screens, holds items and
shows the HUD. Your own game is one, but you play in it. Test clients started next to it, one per agent, each cost RAM
and CPU, mostly to draw frames nobody looks at.

**Lean mode** makes a Foreman client as cheap as it can be while staying a real client: everything runs as in any
client, and every [client tool](tools.md#client-tools) works, but it draws a frame only when a tool needs one, opens no
window and no sound device, and lightens its settings in memory.

## Turning it on

Add `-Dforeman.lean=true` to the game's JVM arguments (where your launcher or script sets the memory), or set
`"lean": {"enabled": true}` in `config/foreman/settings.json`. It's read when the game starts. It isn't in the hub:
a hidden window couldn't turn it off again (`-Dforeman.lean=false` wins over the setting).

| System property | Setting (`lean.…`) | Default | Does |
|---|---|---|---|
| `foreman.lean` | `enabled` | `false` | Lean mode |
| `foreman.lean.fps` | `fps` | `1` | Frames per second drawn besides the ones tools ask for; `0`: only those |
| `foreman.lean.window` | `window` | `hidden` | `hidden`: no window at all; `minimized` or `shown`, to watch it |
| `foreman.lean.renderDistance` | `renderDistance` | `2` | In chunks (the server sends no farther); more to take screenshots of a build |
| `foreman.lean.packs` | `packs` | `load` | `skip`: the server's resource packs are answered as loaded without being downloaded |

## What it does

- **No window.** It's created hidden: nothing on screen or in the taskbar. Frames are drawn into the game's own image
  (screenshots read it) and never shown, so nothing waits on the desktop. With `minimized` or `shown` the frames are
  shown as they're drawn.
- **Frames on demand.** A loop of the game without a frame still does what a frame updates besides drawing: what the
  player looks at (the crosshair's block or entity), the camera, toasts, light, the screenshots the GPU finished. Then
  it sleeps until the next tick or a tool's call. Frames are drawn:
    - for pixels: `client_screenshot`, `client_pixels`, `client_frames`, `ui_capture`'s image, until the world around
      the player is built (a screenshot after a while without frames would miss chunks);
    - for a screen's layout: `client_screen`, `client_click`, `client_hover`, `client_scroll`, `client_key`,
      `client_drag`, `client_dialog_preview` (option lists place their buttons while drawing, containers find the slot
      under the pointer), and `client_post_effects` (post effects are applied by a frame);
    - every frame while `client_perf` measures, and during loading screens: resource reloads, and joining a world
      (the game lets the player in once the chunk under them is built, which takes frames);
    - `fps` per second besides, or a live stream's rate while one is open.
- **No sound.** OpenAL isn't asked for devices nor opened, and the sound engine doesn't tick. `client_sounds` still
  lists what the game plays, as not started.
- **Light settings, in memory only.** Render distance 2 (`renderDistance`), simulation distance 5, minimal particles,
  no clouds, no mipmaps, 30 FPS at most, no VSync, windowed, no entity shadows, no biome blend, chunks built at once
  near the player and without fade-in, and the game doesn't pause when its window isn't focused. options.txt keeps the
  player's own values (they're put back while it's written), so the same game directory runs lean or normal.

- **Server resource packs skipped, on request.** With `foreman.lean.packs=skip`, each pack the server sends is answered
  accepted, downloaded and successfully loaded at once, without downloading or applying anything, so a server that
  requires its packs still lets the client in; the log says which packs were skipped. A bot that only moves and
  records packets never draws them, and a server's pack set of a hundred MB doesn't fit a 512 MB heap. Leave it at
  `load` for screenshots and the HUD inspector: without the packs, custom items, glyphs and fonts aren't there.

Ticks, networking, input, screens, inventories, chat and the HUD's texts (the [HUD inspector](../client/hud-inspector.md)
reads them every tick) are those of any client.

## Watching

A lean client has no window, but you can watch it live: its MCP server streams what the window would show, screens
and HUD included, at the rate you ask (`/stream`, see [watching the game live](outside-agents.md#watching-the-game-live)).
While a stream is open, frames are drawn at its rate at least (10 per second by default, instead of `fps`): that's what
watching costs, about a sixth of a core at the defaults on the machine below (3 % idle). Once the last stream closes,
the client draws as before. Frames stay in memory, never on disk.

## Starting test clients from a script or launcher

1. **A game directory per client**: its own `options.txt`, logs and `config/foreman`, so its own port and token.
2. **options.txt**: `onboardAccessibility:false` (a new directory opens the accessibility screen first, and quick play
   waits behind it: `state` stays `menu`), and the graphics API you want (`preferredGraphicsBackend`). The server in
   `servers.dat` with `acceptTextures` when it sends a resource pack (not needed with `-Dforeman.lean.packs=skip`).
3. **JVM arguments**: `-Dforeman.lean=true -Dforeman.mcp.external=true -Dforeman.mcp.port=0`, and the
   [flags below](#what-it-costs). Program arguments: `--quickPlayMultiplayer <host:port>` to join at once, an offline
   `--username` for an offline-mode dev server, and `--width` and `--height`: the size of the screenshots.
4. **Wait for it**: delete `config/foreman/client-mcp.json` before the launch, then read it until `state` is `in_game`
   ([the file](outside-agents.md#without-the-hub): `url`, `token`, `state`, `pid`). Agents connect to `url` with
   the token, as any [outside agent](outside-agents.md); `client_usage` says what the client costs.
5. **Stop it** as any process (`SIGTERM`, or quitting): the file then says `stopped`. After a crash it keeps the last
   state: check `pid`.

## What it costs

Measured on a desktop (16-core CPU, discrete GPU on Vulkan, Linux), each client alone and idle in a world on a local
Paper server (render distance of the server 10), an 854×480 window, over 60 s after 30 s in game. RSS is the resident
memory, CPU a share of one core, GPU its busy time.

| Client | JVM flags | RSS | CPU | GPU | VRAM | Threads |
|---|---|---|---|---|---|---|
| Normal | `-Xmx2G` | 1390 MB | 17.6 % | | | 92 |
| Lean | `-Xmx2G` | 1200 MB | 3.3 % | | | 91 |
| Normal | `-Xmx768M`, the two flags recommended below | 966 MB | 18.4 % | 3.7 % | 725 MB | 36 |
| Lean | the same | 881 MB | 2.6 % | 0 % | 719 MB | 29 |
| Lean, `fps` 0 | the same | 868 MB | 2.1 % | 0 % | 463 MB | 29 |

Lean mode itself takes the CPU down to a seventh and the GPU to nothing; most of the memory is the game itself (its
classes, textures and models), which only JVM flags reduce. Each flag alone, on a lean client with `-Xmx2G`:

| Flag | RSS | CPU | Threads | Note |
|---|---|---|---|---|
| none | 1200 MB | 3.3 % | 91 | |
| `-XX:TieredStopAtLevel=1` | 946 MB | 2.4 % | 55 | The quick compiler only: the biggest saving |
| `-XX:ActiveProcessorCount=2` | 1078 MB | 3.0 % | 30 | Fewer GC, compiler and worker threads |
| `-XX:+UseSerialGC` | 1295 MB | 2.9 % | 25 | Fewer threads, but it keeps nearly all of `-Xmx`: G1 (the default) stays smaller |
| `MALLOC_ARENA_MAX=2` (environment) | | | | 12 MB less with the other flags: not worth it |

**Recommended:** `-Xmx512M -XX:TieredStopAtLevel=1 -XX:ActiveProcessorCount=2`, G1 as by default. With G1 the heap
grows only as needed, so a lean client idles at the same RSS with `-Xmx768M` (881 MB) as with `-Xmx384M` (868 MB):
`-Xmx` is the ceiling for the heavy moments. 384 MB went through a resource reload, eight screenshots after teleports
far away and a contact sheet, with one full GC; 256 MB ran out of memory in the reload. 512 MB leaves room for a
server's resource pack.

**A shared CDS archive** (`-XX:+AutoCreateSharedArchive -XX:SharedArchiveFile=<file>`, one file per mod set, written
by the first launch and used by the next ones) took a client from launch to in game in 8.5 s instead of 11.5 s, and its
class data out of each process's memory (non-heap 178 → 77 MB): the archive is a file mapped by every client started
with it, so they share those pages. Keep the same JVM arguments between the launch that writes it and the ones that
use it, and only jars on the classpath (classes from folders aren't archived).

## Limits

- What happens between frames isn't drawn: a screenshot shows the moment it's taken, animations jump from one frame
  to the next.
- `client_perf` times lean's frames (30 FPS at most), not what the game would draw; `client_stats`' FPS and section
  counts are the last frame's.
- Packets are handled at least once a tick, as a client at 20 FPS would, rather than every frame.
- Pixel tools take longer when the view just changed (far teleports, the camera turning): they wait, 3 s at most, for
  the chunks in view to be built, with the fewer threads of `ActiveProcessorCount=2` too.
- At render distance 2 the player sees about two chunks around them: raise `renderDistance` to look at a build.
- There's no sound, and the hidden window never has the focus (the game doesn't pause for it).
