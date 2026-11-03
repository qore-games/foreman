# Resource packs

Vanilla holds the game behind its loading screen for the whole of a resource pack reload (++f3+t++, the options'
**Resource Packs**, a server's pack), which takes several seconds with a big pack. The screen then stays up at least a
second and fades out for two more, taking no input. Foreman makes reloads much faster and lets you play through them.

## Fast reloads

On by default, in the hub's **Debug → Resource packs**. The same resources, sooner:

- **Mipmaps** for every sprite at once, the biggest first, on arrays of the whole image. Vanilla makes an atlas's one
  by one on a single thread; a 2048×2048 cutout texture alone took it half a second.
- **Fonts** pick their glyph providers in linear time, off the main thread. Vanilla tries every provider for every
  codepoint while the game is frozen, which is quadratic for fonts made of one provider per icon.
- **Zip packs** list a folder from an index, instead of going through every entry of the zip for each folder.
- **The loading screen** closes as soon as the packs are loaded.

A 70 MB pack of 58,000 files went from 9–11 seconds to about 1, and its longest freeze from 1.3 seconds to 0.2. The
mipmaps match vanilla's to the bit: every level of 20,000 sprites was checked.

## Keep playing

On by default. The game stays on screen and playable while the packs load, under a thin progress bar: screens work,
singleplayer doesn't pause. Only the last step, swapping in the new resources, freezes it (the 0.2 seconds above), and
no frame is drawn during it, so you never see it half done. The game's start keeps its loading screen.

## Timings

Each reload prints its time and longest freeze in chat. The hub's page (**Performance**) lists the last one's steps
(textures, models, fonts, shaders…): when each was prepared, and its time preparing off the main thread and applying on
it. The last step prepared is the one the others waited for. Timing turns on vanilla's own reload profiler; with fast
reloads and the chat report both off, Foreman leaves reloads alone.

## Issues

The page's **Issues** tab lists the warnings and errors of the last reload: missing textures and models, shaders that
don't compile, fonts, sounds, textures that limit mipmaps. Lines saying the same thing about different resources are
one row with their count, errors first; click a row for its lines. **Ask the AI** opens a chat with them (or **Ask
about these** with one row's): it explains each kind, finds the file that causes it in your pack's sources (add them
as a [workspace](../ai/workspaces.md)), fixes it and reloads to check.

## Commands

| Command | Does |
|---|---|
| `/fastreload` | Reload, like ++f3+t++ |
| `/fastreload on` / `off` | Fast or vanilla reloads, to compare |
| `/fastreload playing` / `screen` | Play through reloads, or keep the loading screen |

For an AI, [`client_resource_packs`](../ai/tools.md#entities-items-resources) with `reload` reloads, then returns the
same timings and the warnings and errors logged meanwhile.
