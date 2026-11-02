---
description: Build something at a place, end to end, until check reports no errors
arguments:
  - {name: place, description: "Where: an area name, a box [[x,y,z],[x,y,z]] or 'where I look'", required: true}
  - {name: request, description: "What to build (a town hall, a market square, a forest clearing…)", required: true}
---
Build this in the Minecraft world: {{request}}
Place: {{place}}

Work like this:
1. get_context: follow the world's building profile and the zone's theme it shows. Look at the place: render view=top
   and view=slope around it, scan_area if the terrain matters, list the claims nearby. Keep off other claims.
2. Plan in a few lines: footprint, floor y (measure_site), entrances and which road they face, materials.
3. Prefer the generators over block-by-block edits:
   - a building (house, apartment block, office, school, hotel, shop, hall…): build_structure (list_styles first); it
     grades the site, connects the doors and furnishes (wear and clutter when the profile or the request asks);
   - roads and paths: build_path with slabs (no step needs a jump), max_grade 0.2-0.3, route auto for hills;
   - vegetation and props: scatter_assets (category:rock, category:ruin, trees);
   - library schematics: list_assets and preview_asset before paste_asset.
4. After each step, use the render argument of the tool to see it, and fix what looks wrong (undo and redo rather than
   patching on top).
5. Run repair_terrain on the place and connect_doors for any entrance not on a path, then run check on the whole place. Fix every error, then check again, until there are none. Then look from where players
   arrive and inside the main rooms.
6. Name and claim what you built (an area) and sum it up with coordinates.
7. If you hit a mistake the checks did not catch, or the user corrects you, record it with learn.

Lessons learned on this server:
{{lessons}}
