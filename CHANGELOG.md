# Changelog

## 1.0.0-beta.7

- **Network profiler** (`/tickengine network start [seconds]`). Measures every packet the server sends
  during a session and ranks mods, sources (entity types, blocks, block entities, mod channels, sounds,
  particles), packet types and chunks by upload, before and after compression. Results in chat (click a
  chunk to teleport) and in an HTML report. Needs `tickengine.profile`. No cost when no session runs.
- Tested in game on 1.21.1, 1.21.6, 1.21.9 and 1.21.11 and with a Fabric modpack; the sent size matches
  what the server process really sent within about 1%. `tools/smoke-test.sh` checks it on every version.

## 1.0.0-beta.6

- **Emergency mode** (`/tickengine emergency`), off by default. When turned on and the average MSPT stays
  above `emergency.triggerMspt` (150 ms) for `triggerSeconds` (30 s), or the old generation of the Java
  heap stays 90% full after garbage collection, it throttles as hard as it can: auto-tuning to its
  highest level at once, far entities and block entities at most every `emergency.interval` (20) ticks,
  natural mob spawning paused, the world saved once. It ends after the MSPT stays below `exitMspt`
  (60 ms) for `exitSeconds` (60 s); auto-tuning then goes straight back to its level from before.
  `/tickengine emergency start` and `stop` run it by hand.
- **Entity stacking** (`/tickengine stacking`), experimental and off by default. Mobs of the same kind
  with the same data standing close together become one mob that counts for up to 10 ("Cow x7"). Killing
  a stack kills one mob at a time with its own loot and XP; using it (food, shears, name tag, lead)
  separates the mob you clicked; natural spawning counts every mob in a stack. `/kill` removes whole
  stacks. Never stacked: named, tamed, owned or leashed mobs, babies, animals in love, mobs in combat,
  riders and mounts, bosses, villagers and wandering traders (`stacking.exclude`). `stacking.mode`:
  `off`, `emergency`, `autoTuning` (a new last auto-tuning level) or `always`; anything but off needs the
  confirmation in `/tickengine stacking mode`. Run `/tickengine stacking unstack` before removing
  TickEngine, or each stack stays one mob.
- `tools/smoke-test.sh` also checks both on every version.
- Tested on the 1.21.11 dev server with a test player (XP, breeding, shears, lead, mob cap, spawning
  pause, despawn, the memory trigger), see README, "How this mod was checked".

## 1.0.0-beta.5

- **Minecraft 1.21 to 1.21.11.** TickEngine now ships six jars, one per range: 1.21-1.21.1,
  1.21.2-1.21.4, 1.21.5, 1.21.6-1.21.8, 1.21.9-1.21.10 and 1.21.11 (the jar name ends in the first
  version of its range, for example `+1.21.2`). Same features and config in all of them; each bundles
  the fabric-permissions-api build for its range. Checked on a 1.21.1 dev server; the 1.21.2, 1.21.5,
  1.21.6 and 1.21.9 jars are built, unit-tested and their mixin targets checked, but not yet run on a
  server.
- Fixed: turning tick spreading on skipped the entities that were exactly at the spreading tick at that
  moment; they are moved too now.
- `/tickengine config set entities.spreadTicks false` now turns spreading off. Turning it on still goes
  through `/tickengine spread enable` and its confirmation.
- Auto-tuning has a new step, right after the intervals: it raises the limit for force-loaded chunks
  without players (`forcedChunkMaxInterval`, block entities and entities) from 2 to 4 while the server is
  above 45 ms per tick, and puts it back when the load is gone. Before, those areas never went below one
  tick in two however heavy the load. `autoTuning.tuneForcedChunks` turns the step off,
  `autoTuning.maxForcedChunkInterval` (4) sets how far it goes; a limit of 1 is never raised. The default
  limit stays 2. One more auto-tuning level is available as a result.
- New `/tickengine report`: collects a diagnostic report for a problem (general, or about one block
  entity type, entity type or mod) and gives a link that opens it as a GitHub issue to review and
  submit: versions, the settings changed from the defaults, the rule that applies to the target and
  where it comes from, profiler numbers, throttle and auto-tuning state, and the mod list. The full
  report is written to `tickengine/reports/diagnostic-<time>.txt` to attach, since the link has to stay
  short. TickEngine sends nothing itself, and the report has no player names, addresses or coordinates;
  the only path in it is the `output.reportDirectory` setting, relative to the server folder. New
  permission node `tickengine.report`.
- A mini-audit of every change since 1.0.0-beta.1 is in HOW_WE_VERIFIED_AND_FIXED_THE_BUGS.txt (part 6).

## 1.0.0-beta.4

- Tick spreading no longer moves entity types that are never throttled: a rule with `ai` 1 and `tick` 1
  (Elder Guardians and Wardens by default), and bosses and projectiles while they are excluded from
  throttling. They have no throttled turn to spread, so moving them only added risk.
- New `entities.spreadExclude`: entity types (`minecraft:villager`) or whole mods (`somemod:*`) that tick
  spreading never moves, while they stay throttled. Change it live with
  `/tickengine spread exclude add|remove <target>` and `/tickengine spread exclude list`; `/tickengine spread`
  shows it.
- Turning spreading on with entities already loaded skips the same entities, and moves nothing while
  entity throttling is off.

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
