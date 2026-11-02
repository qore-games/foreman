---
description: Find and fix what is wrong in an area, checking again after each round, until it is clean
arguments:
  - {name: place, description: "A mark name or a box [[x,y,z],[x,y,z]]", required: true}
  - {name: rounds, description: "Most check-fix rounds (default 5)", required: false}
---
Repair this area of the Minecraft world: {{place}}
Rounds at most: {{rounds}} (5 if empty).

Each round:
1. Run repair_terrain on the area (foundations, cliffs, holes, floating blocks), then check with fix=true (it fixes
   double-door hinges and missing steps by itself). connect_doors when entrances have no path.
2. Fix the remaining errors, the most serious first, with the right tool:
   - unreachable rooms or containers: a door (edit op door, width 2), stairs (edit op stairs), a path (build_path);
   - steps that need a jump: slabs or stairs (build_path slabs, edit op stairs);
   - furniture blocking a door: place_furniture relocate;
   - cliffs and steep roads: grade, smooth_terrain, build_path with a lower max_grade;
   - floating blocks: remove them; sunk or floating builds: grade or foundation;
   - trap pits and basements with no way out: stairs or a ladder.
   Keep each fix local: don't rebuild what works.
3. Run check again. Stop when there are no errors, or when a round fixed nothing (then say what is left and why).

At the end, list what you changed with coordinates and what is left. Record with learn each kind of error that came
back after a fix, or that you had to fix by hand in a way a tool could have done.

Lessons learned on this server:
{{lessons}}
