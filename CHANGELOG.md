# Changelog

## Unreleased

- Fixed: lowering `autoTuning.relaxAfterSeconds` while the server runs (by command or config reload)
  now applies at the next decision. Before, the old wait stayed in force for up to 10 minutes and
  `/tickengine tuning status` kept showing it.
- Unit tests for auto-tuning, run inside Fabric Loader: `./gradlew test`.

## 1.0.0-beta.1 — first public beta

- Tick profiler: an always-on light mode (one tick sampled per second) and detailed sessions, ranked by
  mod, block entity type and entity type, with the slowest instance of each. `/tickengine top` in chat,
  click to teleport; self-contained HTML reports written off the server thread.
- Block entity throttling: block entities far from every player tick once every N ticks (default 4),
  aligned to the game time so beacons, vaults and trial spawners keep working. Force-loaded chunks
  without players are never slower than once every 2 ticks. Moving pistons are never throttled.
- Entity and AI throttling in three zones around the nearest player: normal, AI less often, whole tick
  less often with only timers advancing in between. Items and mobs despawn on time. Never throttled:
  players, projectiles, vehicles with passengers, bosses, mobs in combat, Elder Guardians and Wardens;
  falling, burning in lava, newborn, breeding and baby mobs tick normally.
- Throttled mobs run a full goal selection on their AI turn, so far mobs still eat, pick targets and
  flee whatever their entity id.
- Rules per type, per mod and per dimension, with live commands.
- MSPT-based auto-tuning: tightens intervals, then radii, then simulation and view distance above 45 ms;
  relaxes below 30 ms after 60 s, with a longer wait if the load bounces back. Runtime only.
- Commented JSON5 config, every option settable by command. A file with a syntax error is never
  overwritten. The report folder must stay inside the server folder.
- Permission nodes through fabric-permissions-api (bundled): `tickengine.status`, `.top`, `.profile`,
  `.config`, `.notify`. Bare `/tickengine` shows the status only with `tickengine.status`.
- ServerCore: entity throttling pauses while its activation range is on; auto-tuning leaves the
  distances to its dynamic settings. Admins are told on join and every 30 minutes.

The full account of the audit behind this release, the performance measurements and what has not been
verified are in `HOW_WE_VERIFIED_AND_FIXED_THE_BUGS.txt`.
