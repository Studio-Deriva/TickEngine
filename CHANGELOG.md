# Changelog

## 1.0.0

First stable release: the fixes from a full audit of the mod.


- **Fix: stacking and farms that kill by falling or leave mobs at half a heart.** The mob that takes a
  dead one's place got full health and lost its fall, so in a fall-damage farm the rest of a stack
  landed alive, and in a one-hit farm it needed a full fight. Now what hit every mob of the stack (a
  fall, lava, cramming, an explosion) stays on the rest, and only an attack is charged to the mob that
  died.
- **Fix: stacking merged horses, donkeys and llamas with different stats** (speed, jump, health), and
  the copies took the stats of one of them. Attribute base values must now match (a zombie's random
  reinforcement chance is left out, so zombies still stack).
- **Fix: stacking ignored scoreboard tags and `Invulnerable`.** Mobs tagged by a datapack or a command,
  or made invulnerable, now only stack with mobs that have the same.
- **Fix: chickens never stacked**, since each one has its own random egg timer, which was compared. Now
  they stack, and a stack lays as many eggs as its chickens would.
- **Fix: a dispenser with shears sheared a whole stack for the wool of one mob.** It now shears one,
  like a player.
- **Fix: the entity limit did not stop frogs, sniffers, turtles and foxes from breeding.** Frogs,
  sniffers and turtles no longer make eggs, and foxes no cubs, in a chunk at the limit.
- **Fix: one inconsistent value in the config file blocked every `/tickengine config set`**, and with
  two of them neither could be fixed from the game. Now only a change that brings a new inconsistency is
  refused; the ones left are shown after each change.
- **Fix: `/tickengine report` showed `output.webPublicAddress` and `output.webBindAddress`**, despite
  saying it holds no addresses. Once set, they show as "(set, hidden)".
- **Changed: the report web server (`output.webServer`) is off by default**, since it opens a port. Config
  files that already exist keep their value.
- Dying mobs are no longer throttled, so they no longer lie there longer than 20 ticks far from players.
- Tested with `tools/smoke-test.sh` on every version (1.21.1, 1.21.2, 1.21.5, 1.21.6, 1.21.9, 1.21.11),
  unit tests on every version, and each fix in game from the dev server console on every version. Player
  actions on stacks (sword and axe kills, shears, a name tag, feeding) tested on 1.21.11 with a fake
  player.

## 1.0.0-beta.10-pre-release

- **Profiler: heaviest chunks.** Block entities, entities and blocks also count for the chunk they tick
  in, so `/tickengine top chunks` ranks chunks by tick time (block entities, entities and blocks apart,
  the three types that took most of it, click to teleport to the slowest call there). Also in the HTML
  report and, without coordinates, in `/tickengine report`.
- **Changed: `/tickengine top chunks` is now the chunk ranking.** Chunk loading, generation and worldgen
  moved to `/tickengine top worldgen`, matching `/tickengine network top chunks`.
- **Entity limit per chunk** (`/tickengine limit`, off by default). Once a chunk holds `entityLimit.perChunk`
  (48) mobs of one type, natural spawning, spawners, trial spawners and breeding (animals, villagers,
  chicks from thrown eggs) add no more of that type there. Each source can be turned off; rules per type,
  mod and dimension; an exclude list. Nothing is removed, a stack counts as one mob, spawn eggs and
  commands are never stopped.
- **History of the last 7 days** (`history.*`, on by default). Once a minute (one light profiler window)
  the average and longest tick, TPS, players, entities, chunks, auto-tuning level and emergency state go to
  `tickengine/history/<date>.jsonl`; files older than `history.keepDays` are deleted. Peaks (average from
  50 ms, a tick from 500 ms, or auto-tuning above level 5) also keep the heaviest mods, types and chunks.
  `/tickengine history [hours]` in chat (click a peak for its details), `/tickengine history report` as
  an HTML chart. `history.profilePeaks` (off by default) starts a 60 s detailed session during a peak, at
  most once every 10 minutes.
- **Fix: answers lost over RCON.** `/tickengine history`, `history report`, `history peak`, `profile report`, `network report` and `report` read or write files off the
  server thread and answered a moment later, which RCON drops: it sends a command's output when the
  command returns. Commands from the console or RCON now wait for the disk (at most 10 s) before
  returning; players still get the answer without any wait.
- `tools/smoke-test.sh` turns off `pause-when-empty-seconds` while it runs (and restores the file): the
  checks now last longer than the 60 s after which an empty 1.21.2+ server stops ticking.
- Tested with `tools/smoke-test.sh` on the dev servers of 1.21.1, 1.21.9 and 1.21.11 (chunk ranking,
  entity limit on breeding and eggs, history with a peak and a detailed session); unit tests on every
  version. In game on 1.21.11 with a player: the limit on spawners, trial spawners and natural spawning,
  the chunk ranking and the history with real lag. Villager breeding is not verified in game yet.

## 1.0.0-beta.9

- **HTML reports open in the browser with a click.** The "HTML report saved" line in chat now has an
  `[open in browser]` link, served by a small built-in web server (`output.webServer`, on by default,
  port `output.webPort` 8765). It serves only the HTML reports, each behind its own secret link (an HMAC
  of the file name with a key in `config/tickengine-web.secret`), so a shared link opens one report and
  links keep working after a restart. The link uses `output.webPublicAddress`, or by default
  `server-ip`, or else the address the player typed to join. On a hosting panel, add the port as an
  extra port or set `output.webServer` to false; when the port cannot be opened the server logs a
  warning and chat shows the path as before.
- Tested in game on 1.21.11 and Forge 1.20.1 with a player: the link opens the report; wrong links,
  other files and `../` paths get 404.

## 1.0.0-beta.8

- **Profiler: redstone, fluids, random ticks and block events.** The tick profiler now also times
  scheduled block ticks (repeaters, comparators, observers, falling blocks: most redstone, including the
  dust they power), fluid ticks (water and lava flowing), random ticks (crops, leaves, lava) and block
  events (pistons, note blocks), per block or fluid, with the slowest place to teleport to. They count
  toward their mod. `/tickengine top blocks`, and a Blocks section in the HTML report.
- **Profiler: chunks and worldgen.** Server-thread time on loading, finishing, waiting for, unloading and
  saving chunks (rows that add up, per sampled tick), chunk tasks between ticks, chunks loaded from disk,
  started and finished generating, generated chunks by nearest player, and the time each feature and
  structure takes on the worldgen threads, by mod. `/tickengine top chunks`, a Chunks section in the HTML
  report and a line in `/tickengine report`.
- Tested on the dev servers of 1.21.1, 1.21.5 and 1.21.11, with a player on 1.21.1 and 1.21.11.

## 1.0.0-beta.7

- **Network profiler** (`/tickengine network start [seconds]`). Measures every packet the server sends
  during a session and ranks mods, sources (entity types, blocks, block entities, mod channels, sounds,
  particles), packet types and chunks by upload, before and after compression. Results in chat (click a
  chunk to teleport) and in an HTML report. Needs `tickengine.profile`. No cost when no session runs.
- Tested in game on 1.21.1, 1.21.6, 1.21.9 and 1.21.11 and with a Fabric modpack; the sent size matches
  what the server process really sent within about 1%. `tools/smoke-test.sh` checks it on every version.
- **Fix: entity stacking duplicated items.** An item a stack picked up (a zombie's sword, a piglin's gold,
  a fox's item, an enderman's block) came back with every mob of the stack: killed one by one, a stack of
  5 that picked up one diamond sword dropped 5, and `/tickengine stacking unstack` gave all 4 mobs of a
  stack the sword. Now only one mob holds it, and mobs holding something they picked up never stack.
- **Fix: a bucket took a whole stack.** Picking up a stack of fish or axolotls with a bucket removed every
  mob in it; now the bucket takes one and the rest stays. The same for shearing a stack of mooshrooms:
  one turns into a cow.
- **Fix: dyeing a stack of sheep dyed all of them** with one dye. Using a stack is now caught where the
  player uses an entity, so items that act on a mob themselves (dye) separate one mob too.
- **Fix: a stack that converted kept its old name.** A drowned stack was named "Zombie x3", and when one
  mob was left it kept that name for good and never stacked again. Now it reads "Drowned x3"; on 1.21.1,
  where conversions do not copy tags, the converted stack also kept only one mob, and now keeps them all.
- **Fix: network profiler, chunks.** A packet that changes several kinds of blocks in a chunk was counted
  once per kind of block in the chunk's packets per second.

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
