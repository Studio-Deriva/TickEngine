# Changelog

## 1.0.0-beta.3

- Tick spreading is now **on by default**, so it gets tested in the beta before 1.0.0. It is still
  experimental: if a modded mob or machine misbehaves, `/tickengine spread disable` turns it off at once.
  A config file from 1.0.0-beta.2 or older keeps its `"spreadTicks": false`; turn it on with
  `/tickengine spread enable`.
- Tick spreading only moves tick counters while entity throttling is running (before, also when entity
  throttling was off or paused because of ServerCore).
- The startup log line says whether tick spreading is on.
- Checked since beta.2: C2ME 0.4.0-alpha.0.28 on Java 25 with Lithium and FerriteCore, the Nether and
  the End on a real Fabric server, and the cost of the mod itself (within the measurement noise).
- Documented: in dense farms in force-loaded chunks the default limit (never slower than one tick in
  two) may not lower the average MSPT; `entities.forcedChunkMaxInterval` 4 did on our test world.

## 1.0.0-beta.2

- New, experimental and off by default: tick spreading (`/tickengine spread`). Entities loaded together
  (after a restart, in spawn or force-loaded chunks) share their throttled turn, which makes one tick in a
  few heavier than a normal tick. When turned on, after a warning in chat and the typed phrase
  `/tickengine spread confirm I understand the risks`, each entity's tick counter is moved once by 0-19
  ticks after it is loaded, which spreads the work. In a test with 1100 mobs right after a restart, the
  slowest 5% of ticks went from 35 to 11 ms and the slowest tick from 55 to 21 ms; the average did not
  change. `/tickengine config set` cannot turn it on.
- Checked since beta.1: LuckPerms 5.5.21 (granted and denied nodes, fall back to the operator level,
  notices only with `tickengine.notify`).

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
- Unit tests for auto-tuning, run inside Fabric Loader: `./gradlew test`.

The full account of the audit behind this release, the performance measurements and what has not been
verified are in `HOW_WE_VERIFIED_AND_FIXED_THE_BUGS.txt`.
