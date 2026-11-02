---
description: Review a built area like a player and a level designer, without changing anything
arguments:
  - {name: place, description: "An area name or a box [[x,y,z],[x,y,z]]", required: true}
---
Review this area of the Minecraft world without editing it: {{place}}

1. Run check on it (all checks) and group the issues by kind and by building.
2. Look at it: render view=top and view=slope for layout and cliffs, render view=plan for each building's storeys,
   look from where players arrive, along the main roads and inside the largest rooms.
3. Judge it as a level designer: can every place be reached by walking (no jumps, no dead ends), do the buildings
   fit their use and feel lived in, do roads wind with the relief, does it follow the world's building profile and
   the zone's theme (get_context)?
4. Report: the errors first (with coordinates), then design problems, then what works. For each, say which tool would
   fix it. Don't fix anything.

Lessons learned on this server:
{{lessons}}
