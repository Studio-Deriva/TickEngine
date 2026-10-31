# Changelog

## 1.0.0-beta.7+1.20.1-forge

- **Network profiler** (`/tickengine network start [seconds]`), as in the Fabric version's 1.0.0-beta.7.
  Measures every packet the server sends during a session and ranks mods, sources (entity types, blocks,
  block entities, mod channels, sounds, particles), packet types and chunks by upload, before and after
  compression. Results in chat (click a chunk to teleport) and in an HTML report. Needs
  `tickengine.profile`. No cost when no session runs. On 1.20.1 packet types are named after their class,
  and Forge's login handshake counts as a channel.
- Tested on a Forge 1.20.1 dev server with a client: entities, blocks, block entities, sounds,
  particles, chunks and Forge's login handshake (`fml:loginwrapper`).

## 1.0.0-beta.6+1.20.1-forge

As in the Fabric version's 1.0.0-beta.6 (without `/tickengine report`, which stays Fabric only):

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
  separates the mob you clicked (hooked on the player's interaction, so shearing through Forge's
  `IForgeShearable` counts too); natural spawning counts every mob in a stack. `/kill` removes whole
  stacks. Never stacked: named, tamed, owned or leashed mobs, babies, animals in love, mobs in combat,
  riders and mounts, bosses, villagers and wandering traders (`stacking.exclude`). `stacking.mode`:
  `off`, `emergency`, `autoTuning` (a new last auto-tuning level) or `always`; anything but off needs the
  confirmation in `/tickengine stacking mode`. Run `/tickengine stacking unstack` before removing
  TickEngine, or each stack stays one mob.
- GameTests for both (12 new, 58 in all) and JUnit tests for the emergency timing and stack data (19 in
  all). The config file path is now looked up when it is read, so JUnit tests can load the config class.
- Same fixes as the Fabric version: turning tick spreading on also moves the entities exactly at the
  spreading tick, and `/tickengine config set entities.spreadTicks false` turns spreading off.
- Auto-tuning raises the force-loaded chunk limit from 2 to 4 under load, as in the Fabric version.
- GameTests for all of it (46 in all).

## 1.0.0-beta.4+1.20.1-forge

Made for personal use; support and updates are not guaranteed.

- As in the Fabric version's 1.0.0-beta.4: tick spreading no longer moves entity types that are never
  throttled (a rule with `ai` 1 and `tick` 1, bosses and projectiles while excluded from throttling), and
  `entities.spreadExclude` with `/tickengine spread exclude add|remove|list` keeps chosen types or mods out
  of it while they stay throttled.
- GameTests: 6 new ones for spreading exclusions (44 in all); every test now starts from the rules loaded
  at startup, so a failing test cannot leave a rule behind for the next one.

## 1.0.0-beta.3+1.20.1-forge

Made for personal use; support and updates are not guaranteed.

- Tick spreading is now **on by default**, as in the Fabric version's 1.0.0-beta.3. Still experimental:
  if a modded mob or machine misbehaves, `/tickengine spread disable` turns it off at once. A config file
  from 1.0.0-beta.2 or older keeps its `"spreadTicks": false`; turn it on with `/tickengine spread enable`.
- Tick spreading only moves tick counters while entity throttling is running.
- The jar now declares only what has been tested: Minecraft 1.20.1 and Forge 47 (`[1.20.1,1.20.2)`,
  `[47,48)`). Before, it also accepted 1.20.2-1.20.6 and any later Forge, none of them tried.

## 1.0.0-beta.2+1.20.1-forge

Made for personal use; support and updates are not guaranteed.

- New, experimental and off by default: tick spreading (`/tickengine spread`), as in the Fabric
  version's 1.0.0-beta.2. Turned on only after a warning in chat and the typed phrase
  `/tickengine spread confirm I understand the risks`; it moves each entity's tick counter once by 0-19
  ticks after loading, so entities loaded together stop running their throttled turn on the same tick.
  In a test with 1100 mobs right after a restart, the slowest 5% of ticks went from 35 to 11 ms.
- Checked since beta.1: LuckPerms for Forge 5.4.102, ServerCore for Forge 1.5.2, Forge 47.2.0 to 47.4.8,
  a client install and singleplayer, the Nether and the End, several players at once, and a 252-mod pack
  with ModernFix, FerriteCore and Sinytra Connector. Installed but idle, TickEngine costs nothing
  measurable.
- More in-game tests (36 GameTests).

## 1.0.0-beta.1+1.20.1-forge — Forge 1.20.1 port (personal project)

Made for personal use; support and updates are not guaranteed.

- Port of 1.0.0-beta.1 (below) to Minecraft 1.20.1 and Forge 47 or newer, Java 17. Same features.
- Differences from the Fabric version: permission nodes go through Forge's PermissionAPI instead of
  fabric-permissions-api; bosses are read from the `forge:bosses` tag instead of `c:bosses`; MixinExtras
  is bundled in the jar; clients without TickEngine can join.
- Tests: JUnit (`./gradlew test`) and in-game GameTests (`./gradlew runGameTestServer`).

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
