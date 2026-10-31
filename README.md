# FORGE 1.20.1 PORT: PERSONAL PROJECT, NO SUPPORT GUARANTEED

> **This Forge 1.20.1 version of TickEngine was made for personal use.**
> **Support and updates are not guaranteed:** it may lag behind the Fabric version, fixes may never
> be ported, and it may be dropped at any time. Use it at your own risk and keep a backup of your
> world. The maintained version is TickEngine for Fabric 1.21.11, on the `main` branch.
>
> Differences in this port: Forge 47.x instead of Fabric; permission nodes go through Forge's
> PermissionAPI (tested with LuckPerms for Forge); the boss tag is `forge:bosses` instead of
> `c:bosses`. Tests: `./gradlew test` and `./gradlew runGameTestServer`. How the port was tested and
> what it saves on a modded Forge server: [Forge 1.20.1 port: tests and benchmark](#forge-1201-port-tests-and-benchmark).

---

# TickEngine

Server-side tick profiler and throttling for Fabric 1.21.11 (this branch: Forge 1.20.1), by [Studio Deriva](https://discord.gg/pQPWr7vfHu).

TickEngine shows where your server's tick time goes — per mod, per block entity type, per entity
type — and makes the things far from every player cost less: machines, hoppers and furnaces tick less
often, far mobs think less often. When the server struggles, auto-tuning tightens the throttles step by
step, and relaxes them again when the load is gone. It is meant to run **alongside** other performance
mods, not instead of them (on Forge, combinations with other performance mods have not been tested;
see [Compatibility](#compatibility)).

**Free and open source**, like everything we make: MIT, source included, no premium version.

> **Beta.** TickEngine changes how the game runs far from players. The Fabric version has been tested
> on a dev server, on a real Fabric server with a mod set and with a real modpack; this Forge port on a
> Forge GameTest server, a real Forge server and a Forge modpack with a player (see
> [How this mod was checked](#how-this-mod-was-checked)). Neither has run on many live servers yet.
> Keep a backup of your world.

---

## Features

- **Tick profiler, always on.** A light mode samples one tick per second at almost no cost and feeds
  `/tickengine top`: the most expensive mods and types, with the slowest instance of each. Click one to
  teleport there.
- **Detailed sessions and HTML reports.** `/tickengine profile start` measures every tick for a while,
  then prints the ranking and writes a self-contained HTML report (light and dark theme). Reports are
  written off the server thread, so a slow disk never causes lag.
- **Network profiler.** `/tickengine network start` measures every packet the server sends for a while
  and shows which mods, entities, blocks, block entities, mod channels, sounds, particles and chunks
  send the most, with sizes before and after compression. Useful to find the mod or the farm that eats
  the upload of a home server.
- **Block entity throttling.** Block entities far from every player — vanilla or modded — tick once
  every N ticks: a far furnace smelts at 1/4 speed and costs about 1/4 of the CPU.
- **Entity and AI throttling.** Three zones around the nearest player: everything normal close by, AI
  and pathfinding less often a bit farther, the whole tick less often beyond. Items and mobs still
  despawn on time, and far mobs still eat, pick targets and flee.
- **Sensible exceptions.** Never throttled: players, projectiles, vehicles with passengers, bosses,
  mobs in combat, Elder Guardians, Wardens and moving pistons. Ticked normally while falling, in lava,
  just spawned, in love or growing up.
- **Rules per type, per mod and per dimension.** `minecraft:hopper` at 2, `create:*` at 8, villagers
  untouched — the most specific rule wins.
- **MSPT-based auto-tuning.** Above 45 ms it tightens one level, below 30 ms it relaxes one, least
  visible step first: intervals, radii, simulation distance, view distance. Runtime only: it never
  writes your config or server.properties.
- **Emergency mode.** Off by default. When turned on and the server is about to fall over (MSPT above
  150 ms for 30 s, or memory almost full), it throttles as hard as it can until the load is gone, then
  puts everything back.
- **Entity stacking.** Experimental, off by default. Mobs of the same kind standing together become one
  mob that counts for up to 10: always, only in emergencies, or as the last auto-tuning level.
- **Fully configurable, live.** A commented `config/tickengine.json5`, every option also settable by
  command and saved to the file.
- **ServerCore aware.** If ServerCore already throttles entities or manages distances, TickEngine steps
  back from that part and tells admins why.

## Requirements

| | |
|---|---|
| Minecraft | 1.20.1 |
| Loader | Forge 47 (tested on 47.2.0, 47.4.0 and 47.4.8) |
| Java | 17 or newer |
| Required | nothing else (MixinExtras is bundled in the jar) |
| Optional | a permissions mod that uses Forge's PermissionAPI, such as LuckPerms (tested with 5.4.102) |

Install it on the **server only**. Players don't need it, and it does nothing on a client.

## Getting started

1. Drop the jar in the server's `mods/` folder and start the server. The config is
   created at `config/tickengine.json5`.
2. Play for a minute, then run `/tickengine top` to see what costs the most.
3. Throttling and auto-tuning are on by default. `/tickengine status` shows what they are doing right
   now: ticks skipped, mobs without AI, the auto-tuning level.
4. Something must keep running at full speed? Add a rule:
   `/tickengine rules blockentities set 1 somemod:important_machine` or
   `/tickengine rules entities set 1 1 minecraft:villager`.

## How the profiler works

- **Light mode** (always on): measures one tick every `profiler.sampleEveryTicks` ticks (default 20,
  one per second). On ticks that are not sampled the cost is a single boolean check per tick call.
- **Detailed sessions** (on demand): measure every tick for a limited time
  (`profiler.maxSessionSeconds`, default 600).
- Time is grouped by mod (the namespace of the type) and by type. Work a mod adds to vanilla code
  through mixins is counted under the vanilla type it runs in.

## How the network profiler works

`/tickengine network start [seconds]` (default 60, at most `profiler.maxSessionSeconds`) measures every
packet the server sends until the time is up or `/tickengine network stop`, then prints the ranking and
writes an HTML report (`tickengine-network-<time>.html`). When no session runs it costs one boolean
check per packet.

- **Sizes.** Each packet is measured where vanilla encodes it and again after compression, so you see
  both the raw size and what actually goes on the wire (with the length in front of each packet; small
  packets are not compressed, so they show a byte or two more sent than raw). Behind a proxy that turns compression off on the
  backend (`network-compression-threshold=-1`), both are the same. With a mod that replaces vanilla
  compression with its own the compressed size cannot be seen: the results say so, and show
  the raw size for those packets.
- **Sources.** A packet is charged to what it is about: the entity type of an entity packet (movement,
  data, equipment, spawn), the block of a block update (a packet changing several blocks is split by how
  many of each it carries), the block entity type, the channel of a mod's own packet, the sound or the
  particle. Chunk and light data count as "Chunks and light"; everything else (player movement, chat,
  inventory...) by its packet type.
- **Mods.** A source belongs to the mod that registered it (its namespace). A vanilla mob spawned by a
  modded farm counts as Minecraft; the **chunk** list shows where the traffic comes from instead, with a
  click to teleport there.
- **On 1.20.1** packets have no names of their own, so packet types are shown by class name
  (`ClientboundMoveEntityPacket.Pos`). Forge's own login handshake (`fml:handshake`) and mods that
  ask the client something while it logs in count as channels too.
- **Per player.** Sizes add up what all players received: a cow seen by three players counts three
  times, because it costs three times the upload.

## How block entity throttling works

- Block entities within `blockEntities.activeRadiusChunks` chunks (default 4) of any player tick normally.
- Everything else ticks once every N ticks (`blockEntities.defaultInterval`, default 4).
- Rules override the default. The most specific wins: dimension + type, type, dimension + mod, mod,
  dimension `*`, default. Moving pistons (`minecraft:piston`) are never throttled by default.
- Force-loaded chunks without players tick at least every `blockEntities.forcedChunkMaxInterval` ticks
  (default 2; 1 = never throttle them). Under load, auto-tuning may raise this to 4 (see below). Mod chunk
  loaders that use their own tickets count as far chunks.
- Throttled ticks happen on game times divisible by the interval, so blocks that act on
  `gameTime % 20` or `% 80` (beacons, vaults, trial spawners, and any modded block that does the same)
  keep working with intervals 2, 4, 5, 10 or 20. `blockEntities.stagger` spreads the work over ticks
  instead, at the cost of that guarantee.

## How entity throttling works

Players are never throttled. Around the nearest player:

| Distance | What runs |
|----------|-----------|
| up to `entities.fullRadiusChunks` (2) | everything, normally |
| up to `entities.aiRadiusChunks` (4) | movement and physics every tick; AI, goals, pathfinding and brains once every `ai` ticks (default 4) |
| beyond | the full tick once every `tick` ticks (default 4); in between only timers advance (item and XP despawn, mob idle time, portal cooldown, burning time) |

- Throttled ticks follow each entity's own tick counter, so logic like "every 40 ticks" (item merging)
  keeps working with intervals 2, 4, 5, 10 or 20. Entities loaded at different times run their turns
  on different ticks; entities loaded together share a counter and so a turn (see Tick spreading).
- When a throttled mob gets its AI turn, it always runs a full goal selection, so far mobs still start
  and stop behaviours (eating grass, picking targets, fleeing).
- Never throttled by default: projectiles, entities carrying passengers, bosses (`forge:bosses` tag), mobs in
  combat (hit by a player or mob in the last `combatSeconds`, or with an attack target).
- Default rules: Elder Guardians and Wardens are never throttled, because their area effects (mining
  fatigue, darkness) only fire on specific ticks that a throttled AI turn would miss.
- Ticked normally while it lasts: falling, in lava, in water (except water mobs that breathe there),
  the first `newEntitySeconds` after spawning or loading, animals in love and babies.
- Force-loaded chunks without players are never slower than `entities.forcedChunkMaxInterval` (2, raised
  to 4 by auto-tuning under load).
- Entities only tick within the server's simulation distance, so keep `aiRadiusChunks` below it.

### Tick spreading (experimental, on by default since 1.0.0-beta.3)

Entities loaded together (after a restart, in spawn chunks, in force-loaded chunks) start with the
same tick counter, so all their throttled turns land on the same tick: the average MSPT drops, but
every few ticks one tick is heavier than a normal tick would be. Measured on a test server right
after a restart, with 1100 mobs: average 9.2 ms, but 5% of ticks over 35 ms and the slowest at 55 ms.

Tick spreading moves each entity's counter forward once, by 0–19 ticks, 5 seconds after it is loaded,
so the turns spread out. It only acts while entity throttling is running. Same test with it on:
average 9.3 ms, 5% of ticks over 10.8 ms, slowest 21 ms.

The risk: logic that waits for one exact counter value in that short range can be skipped once per
load, and counter-based timers such as "hurt recently" can end up to 19 ticks early once. Vanilla does
not wait for exact values there; some mods might. It is on by default in the beta so that it gets
tested before 1.0.0. If a modded mob or machine misbehaves, `/tickengine spread disable` turns it off
at once.
In game, turning it back on goes through `/tickengine spread enable`, which explains it and asks you
to confirm with `/tickengine spread confirm I understand the risks`; `/tickengine config set` can only
turn it off. Setting `"spreadTicks": true` in the file and running `/tickengine config reload` also
turns it on, for whoever can edit the file.

A config file written by an earlier version keeps the value it has (`"spreadTicks": false` from
1.0.0-beta.2 or older): turn it on with `/tickengine spread enable`.

**What is never spread.** Since 1.0.0-beta.4, tick spreading leaves alone:

- entity types that are never throttled, since they have no throttled turn to spread: a rule with
  `ai` 1 and `tick` 1 (Elder Guardians and Wardens by default, and whatever you exclude from
  throttling), and bosses and projectiles while they are excluded from throttling;
- what you list in `entities.spreadExclude`: types (`minecraft:villager`) or whole mods (`somemod:*`),
  for things you want throttled but not spread. Change it live with
  `/tickengine spread exclude add <target>`, `remove <target>` and `list`.

A type excluded while the server runs keeps the counters already moved until those entities are loaded
again; that is harmless.

## How auto-tuning works

Every `autoTuning.decisionSeconds` (10) TickEngine looks at the average MSPT over that time. Above
`raiseAboveMspt` (45 ms) it tightens one level; below `lowerBelowMspt` (30 ms) it relaxes one level.
`lowerBelowMspt` must be below `raiseAboveMspt`; commands reject values that break this.
It relaxes only after the MSPT has stayed low for `relaxAfterSeconds` (60). If the load comes straight
back after relaxing, that wait doubles (up to 10 minutes), so a load right at the threshold does not
flip the level back and forth.

Levels tighten in this order, least visible first, and relax in reverse:

1. default throttle intervals, through safe values only: 4, 5, 10, 20 (explicit rules are never changed)
2. the limit for force-loaded chunks without players, 2 -> 4 (`autoTuning.maxForcedChunkInterval`):
   farms kept loaded with nobody nearby run at a quarter of the speed while the server struggles
3. throttle radii, one chunk per level (block entities down to 2, entity full radius to 1, AI radius to 2)
4. simulation distance, one per level, down to 4
5. view distance, one per level, down to 6
6. entity stacking, only with `stacking.mode` set to `autoTuning` (see Entity stacking)

If the load sits close to players, the first levels can't reduce it and auto-tuning goes on to the
distances. Turn those steps off with `autoTuning.tuneSimulationDistance` and `tuneViewDistance` if you
would rather keep them.

Every limit and step is configurable. Auto-tuning starts at level 0 after a restart and puts the
original distances back when it relaxes or the server stops. Each level change is logged and, with
`autoTuning.notifyAdmins`, sent to admins.

## Emergency mode

Off by default; turn it on with `/tickengine emergency enable`. It is for the moment a server is about
to fall over, not for ordinary lag (auto-tuning handles that).

**When it starts.** Every 20 ticks (or every second, when ticks are slower) TickEngine compares the average tick time of those ticks with
`emergency.triggerMspt` (150 ms, about 6.7 TPS) and, when `emergency.memoryPercent` is not 0, how full
the old generation of the Java heap still is after a garbage collection (90%: close to an
out-of-memory crash). If either stays critical for `triggerSeconds` (30 s of real time), the mode
starts. `/tickengine emergency start` starts it by hand.

**What it does.**

- auto-tuning jumps to its highest level at once (if auto-tuning is on);
- entities and block entities away from players tick at most once every `emergency.interval` ticks
  (20), force-loaded chunks included; rules with 1 (pistons, Elder Guardians, Wardens) still never
  throttle;
- natural mob spawning pauses (`emergency.pauseMobSpawning`); spawners, raids and commands still work;
- with `stacking.mode` set to `emergency`, `autoTuning` or `always`, mobs are stacked;
- the world is saved when it starts (`emergency.saveOnEnter`, at most once every 10 minutes), so a
  crash that follows loses less.

**When it ends.** After the average stays below `exitMspt` (60 ms) for `exitSeconds` (60 s) with memory
fine, or with `/tickengine emergency stop`. Auto-tuning goes straight back to the level it had before;
stacks made during the emergency stay.

What it cannot do: save a server from one single tick that freezes it (the watchdog stops the server
when one tick takes longer than `max-tick-time`, 60 s by default). It acts between ticks.

Measured on the Fabric version, 1.21.11 dev server, with 2500 pigs packed in a glass box (`stacking.mode emergency`,
trigger lowered to 50 ms for 5 s for the test): about 220 ms per tick before, emergency started after
5 s, the pigs became 257 stacks within 5 s, and the server was back at 2–4 ms per tick (20 TPS).

## Entity stacking (experimental)

Off by default. Mobs of the same kind standing close together become one mob that counts for several
(up to `stacking.maxSize`, 10), shown as "Cow x7" when you look at it (`stacking.alwaysShowCount` to show
it all the time). One mob ticks instead of ten.

**When.** `stacking.mode`: `off`, `emergency` (only during emergency mode), `autoTuning` (as the last
auto-tuning level, and during emergencies) or `always`. Only `/tickengine stacking mode <mode>` turns it
on, after explaining the risks and asking for `/tickengine stacking confirm I understand the risks`;
`/tickengine config set` can only set it to `off`.

**What stacks.** Once a second the mobs are grouped by type and by cube of `stacking.areaBlocks` (5)
blocks per side (the grid moves by half a cube every other second, so mobs on a cube edge meet too);
inside a cube, mobs whose saved data is the same stack together. Position, health,
timers and similar values are ignored; everything else must match, modded data included: a red and a
white sheep, a sheared and a woolly one, two horses with different stats never stack. The work is
spread over several ticks with a time limit per tick, so even thousands of mobs never make one long
tick.

Never stacked: mobs with a name from a player, tamed or owned mobs, leashed mobs, babies, animals in
love, mobs in combat, riders and their mounts, bosses, and what is in `stacking.exclude` (villagers and
wandering traders by default, for their trades). Change the list with `/tickengine stacking exclude`.

**How a stack behaves.**

- Killing it kills one mob at a time, with its own loot and XP; the rest stays as a stack. Farms give
  the same as without stacking. `/kill` removes the whole stack.
- Using it in a way that does something (feeding, shearing, a name tag, a lead, a spawn egg) separates
  the mob you clicked; the rest stays a stack. So feeding a stack of cows breeds them as usual.
- Natural spawning counts every mob in a stack, so the mob cap stays as without stacking.
- The count is kept in a tag, `tickengine.stack.<n>` (usable in selectors: `@e[tag=tickengine.stack.10]`),
  and survives restarts.

**The risks.** A stack moves, thinks and despawns as one mob: ten zombies become one zombie with ten
lives, and mobs that produce something over time (wool regrowth, for example) produce for one. Mods
that keep their own data about single mobs may not expect the copies made when a stack is killed or
used. If you remove TickEngine, each stack stays one single mob: run `/tickengine stacking unstack`
first, which splits every loaded stack back into single mobs.

## Configuration

`config/tickengine.json5`, created on first start. Every option has a comment in the file; the main
ones:

```jsonc
{
  "profiler":      { "enabled": true, "sampleEveryTicks": 20, "windowSeconds": 60 },
  "blockEntities": { "enabled": true, "activeRadiusChunks": 4, "defaultInterval": 4,
                     "forcedChunkMaxInterval": 2, "rules": { "minecraft:piston": 1 } },
  "entities":      { "enabled": true, "fullRadiusChunks": 2, "aiRadiusChunks": 4,
                     "defaultAiInterval": 4, "defaultTickInterval": 4,
                     "rules": { "minecraft:elder_guardian": { "ai": 1, "tick": 1 } } },
  "autoTuning":    { "enabled": true, "raiseAboveMspt": 45, "lowerBelowMspt": 30,
                     "relaxAfterSeconds": 60 },
  "emergency":     { "enabled": false, "triggerMspt": 150, "triggerSeconds": 30, "memoryPercent": 90,
                     "exitMspt": 60, "exitSeconds": 60, "interval": 20, "pauseMobSpawning": true },
  "stacking":      { "mode": "off", "maxSize": 10, "areaBlocks": 5,
                     "exclude": ["minecraft:villager", "minecraft:wandering_trader"] }
}
```

Change values live with `/tickengine config set <key> <value>`: they are checked and saved to the file.
After editing the file by hand, run `/tickengine config reload`. A file with a syntax error is never
overwritten: the settings in use are kept and the command tells you.

## Commands

| Command | What |
|---------|------|
| `/tickengine status` | MSPT, TPS, what the throttles and auto-tuning are doing |
| `/tickengine top [mods\|blockentities\|entities]` | Most expensive mods and types from the light profiler. Click a type to teleport to its slowest instance. |
| `/tickengine profile start [seconds]` | Detailed session: measures every tick, then prints results and writes an HTML report |
| `/tickengine profile stop` | Stop the session early |
| `/tickengine profile report` | HTML report from the light profiler |
| `/tickengine network` | Network profiler: whether a session runs, the last result |
| `/tickengine network start [seconds]` / `stop` | Measure every packet sent for a while, then print results and write an HTML report |
| `/tickengine network top [mods\|sources\|packets\|chunks]` / `report` | Show the last network result again, or write its HTML report again. Click a chunk to teleport there. |
| `/tickengine config list\|get\|set\|reload` | Read and change settings live; changes are saved to the file |
| `/tickengine rules blockentities list` | Show block entity rules |
| `/tickengine rules blockentities set <interval> <target>` | Rule for a type (`minecraft:hopper`) or a whole mod (`create:*`); interval 1 = never throttle |
| `/tickengine rules entities list` | Show entity rules |
| `/tickengine rules entities set <ai> <tick> <target>` | Entity rule: AI interval and far-tick interval (`minecraft:villager` or a whole mod like `somemod:*`) |
| `/tickengine rules <blockentities\|entities> set-in <dimension> ... <target>` | Same, only in one dimension (`*` = every type there); needs `dimensionRulesEnabled` in that section |
| `/tickengine rules <blockentities\|entities> remove <target>` / `remove-in <dimension> <target>` | Delete a rule |
| `/tickengine tuning status` | Auto-tuning level and the values in use right now |
| `/tickengine tuning pause` / `resume` | Stop or restart automatic level changes |
| `/tickengine tuning level <n>` | Force a level (pauses automatic changes until `resume`) |
| `/tickengine spread` | Tick spreading: state, what it does, gain and risk |
| `/tickengine spread enable` / `confirm <phrase>` / `disable` | Turn tick spreading on (after reading the warning and typing the phrase) or off |
| `/tickengine spread exclude add\|remove <target>` / `list` | Types or mods that tick spreading never moves (`minecraft:villager`, `somemod:*`) |
| `/tickengine emergency` | Emergency mode: state, load, thresholds and what it changes |
| `/tickengine emergency enable` / `disable` | Let it start by itself, or not |
| `/tickengine emergency start` / `stop` | Start or end it now |
| `/tickengine stacking` | Entity stacking: mode, loaded stacks, what it does, gain and risk |
| `/tickengine stacking mode <off\|emergency\|autoTuning\|always>` / `confirm <phrase>` | When stacking runs (anything but off after reading the warning and typing the phrase) |
| `/tickengine stacking unstack` | Split every loaded stack back into single mobs |
| `/tickengine stacking exclude add\|remove <target>` / `list` | Types or mods that are never stacked |

## Permissions

Without a permissions mod, commands need operator level `permissions.commandOpLevel` (default 3).
With a permissions mod that uses Forge's PermissionAPI (tested with LuckPerms for Forge 5.4.102,
which registers itself as the permission handler) use these nodes:
`tickengine.status`, `tickengine.top`, `tickengine.profile` (also the network profiler), `tickengine.config`, and
`tickengine.notify` for admin notices (auto-tuning changes, the ServerCore notices below).

## Compatibility

- **Other performance mods**: runs alongside ModernFix, FerriteCore and Sinytra Connector (in the
  252-mod pack below) without mixin conflicts. Canary, Radium and others have not been tested.
- **ServerCore** (verified with ServerCore for Forge 1.5.2+1.20.1; if a future version renames its
  config classes, TickEngine assumes the activation range is on and pauses its entity throttling,
  with an admin notice):
  - *Entity activation range:* if it is enabled, TickEngine pauses its own entity throttling (profiler
    and block entity throttling keep running), so entities are not throttled twice.
  - *Dynamic settings:* if they are enabled, ServerCore manages simulation and view distance and
    TickEngine's auto-tuning only adjusts its own throttles.

  Admins get a notice on join and every `notices.intervalMinutes` (30) while either applies.
- **Tested modpack**: Iron's Spells 'n Spellbooks, Spartan Weaponry and Shields, JCraft, Curios,
  GeckoLib, Watut and their libraries, with a player connected. See the benchmark below.
- **Modded block entities and entities** are throttled like vanilla ones. If a mod's machine or mob
  misbehaves when far away, give it a rule with interval 1 and tell us which one it is.

## Building from source

```
./gradlew build              # jar in build/libs/ (also runs the unit tests)
./gradlew test               # unit tests only
./gradlew runGameTestServer  # in-game tests on a Forge GameTest server
./gradlew runServer          # dev server in run/
```

Run Gradle with Java 17 to 21; the mod is compiled for Java 17, and Gradle downloads a JDK 17 for it
when none is installed. The first run needs network access to fetch Forge and the dependencies.

## How this mod was checked

[`HOW_WE_VERIFIED_AND_FIXED_THE_BUGS.txt`](HOW_WE_VERIFIED_AND_FIXED_THE_BUGS.txt) is the full account
of the audit behind this beta: 7 findings — mobs that never chose a goal while throttled, a config typo
that wiped every setting, report paths outside the server folder and more — what each one was and what
was changed to close it. It also has the performance measurements and says plainly what has **not**
been verified yet.

### Forge 1.20.1 port: tests and benchmark

The audit above was done on the Fabric version. The Forge port was checked separately:

- **Automated tests.** 8 JUnit tests (throttle rules) and 36 GameTests run inside a real Forge server
  (`./gradlew runGameTestServer`). They measure throttling on real furnaces, items and mobs, and also
  cover exemptions, zones, several players at once, rules, the goal-selection fix, the profiler and
  HTML reports, commands, permissions, auto-tuning under load, config file handling and tick spreading
  with its confirmation. To check that the tests can fail,
  three throttling mechanisms were broken on purpose; 10 tests failed, at least one per break.
- **Production jar on a real server.** Forge 1.20.1-47.4.0 server from the official installer: mixins
  applied, MixinExtras loaded from the bundled jar, throttling rates as expected, clean shutdown.
- **Modpack with a real client.** A 16-mod Forge 1.20.1 pack (Iron's Spells 'n Spellbooks, Spartan
  Weaponry and Shields, JCraft, Curios, GeckoLib, Watut and libraries) with a player connected: zones
  follow the player, `/tickengine top` teleport, combat and mod content behaved normally.
- **On a client.** With TickEngine installed in a client's mods folder, the client joins servers and
  opens singleplayer worlds without errors; the mod does nothing there.
- **LuckPerms for Forge 5.4.102 and ServerCore for Forge 1.5.2.** Nodes granted and denied, the fall
  back to the operator level, notices only for `tickengine.notify`; ServerCore's activation range and
  dynamic settings read correctly, with entity throttling paused and the admin notices shown.
- **Forge 47.2.0**, the Nether and the End. Throttling and per-dimension rules behave as in the
  overworld.
- **Modded machines and vehicles** in the 252-mod pack below: Immersive Engineering capacitors, pumps
  and batchers tick at 1/4 without errors; Superb Warfare vehicles are unaffected while parked. Airdrop
  planes (dyairdrop) fly at a quarter of their speed far from players, so drops arrive later; to keep
  them at full speed: `/tickengine rules entities set 1 1 dyairdrop:*`.
- **A heavy modpack.** ZOMBIEMANIA V2.2 C2: 252 server-side mods on Forge 1.20.1-47.4.8, including
  Create, Alex's Mobs, Mutant Monsters, Immersive Engineering, Superb Warfare, Ad Astra, Sinytra
  Connector, ModernFix and FerriteCore. TickEngine loaded with no mixin conflicts and logged no errors.
  See the second benchmark below.

**Benchmark 1: vanilla load.** The 16-mod pack server, one player standing still, the load 6–8
chunks away from them (inside the spawn chunks, not force-loaded), fixed daytime, natural spawning
off. Each line is one `/tickengine profile` session of 20–30 s that measures every tick.

| Load far from the player | TickEngine | ms per tick | TPS | Tick time saved |
|---|---|---|---|---|
| 1536 cows, 320 villagers, 400 furnaces, 400 hoppers | throttling off | 94.5 | ~10.6 | — |
| same | throttling on | 38.1 | 20 | **60%** |
| same | throttling + auto-tuning | 34.1 | 20 | **64%** |
| 3072 cows, 640 villagers, 400 furnaces, 400 hoppers | throttling off | 253 | 3.96 | — |
| same | throttling + auto-tuning | 51.3 | 19.5 | **80%** (TPS ×4.9) |
| same, player standing inside the load | throttling on | 224 | 4.35 | 11% |

With the first load, cows went from 47 to 10 ms per tick (−79%), villagers from 23 to 7.5 ms (−68%)
and hoppers from 0.76 to 0.18 ms (−76%); 75% of block entity ticks were skipped. With the double load,
95% of block entity ticks were skipped and 95% of entity ticks were minimal. No mob was lost: all
cows and villagers were still there at the end.

**Benchmark 2: modded mobs in a 252-mod pack.** The ZOMBIEMANIA server, one player standing still,
the load 7–9 chunks away from them, fixed night, natural spawning off. The load is 30 modded mob
types (Alex's Mobs, Mutants and Zombies, Mutant Monsters, Zombie Extincion, Butcher, Guard Villagers,
Miner Villager, OutpostZero) plus 200 furnaces and a Create kinetic network. Same method as above.

| Load far from the player | TickEngine | ms per tick | TPS | Tick time saved |
|---|---|---|---|---|
| 480 modded mobs (16 per type) | throttling off | 26.0 | 20 | — |
| same | throttling on | 9.15 | 20 | **65%** |
| ~2100 modded mobs (64 per type) | throttling off | 88.0 | 11.3 | — |
| same | throttling on | 27.5 | 20 | **69%** (TPS +77%) |

Per mod, with ~2100 mobs: Alex's Mobs −66%, Mutants and Zombies −77%, Mutant Monsters −76%, Zombie
Extincion −78%, Butcher −78%. Auto-tuning, turned on afterwards, stayed at level 0: the server was
already below 45 ms per tick, so there was nothing to tighten.

Modded content stayed correct while throttled:

- **Multipart mobs.** Each anaconda and centipede segment is an entity of its own and is throttled on
  its own. The segments stayed together: 100 anaconda segments were on average 0.83 blocks from the
  one before, 0.95 at most.
- **Create.** A millstone driven by a creative motor kept working far from the player, at a quarter
  of the speed (1 wheat in 616 ticks, against 4 in 617 with throttling off). It slows down in
  proportion; it does not stall.
- **Predators and prey.** Anacondas hunting gazelles and emus kept all three at full speed most of
  the time, because mobs in combat are never throttled. That is why Alex's Mobs saved less than the
  zombie mods.

With the player inside the pen, nothing near them is throttled, and the server ran at 65–80 ms per
tick while auto-tuning tightened (values read during the flight, not a profile session): far-away
throttling cannot help with a load the player is standing in.

What these numbers do and do not show:

- One run per line on one Mac; the same "off" setup measured twice gave 98.3
  and 90.7 ms, so allow about ±8%. In benchmark 2 predators also ate some of the load during the runs
  (2157 mobs down to 2085).
- Trust the total ms per tick. Per-type times understate the throttled cost, because minimal ticks
  are not timed.
- Lowering the simulation distance did not help here: spawn chunks stay loaded whatever it is set
  to. On a server whose load is outside the spawn chunks, that auto-tuning step can save more.
- TickEngine only saves time far from players. With the player inside the load, almost nothing was
  throttled, and that is by design.

**What TickEngine itself costs.** Same load (1024 hoppers passing items, 280 furnaces, 512 cows and
512 villagers in force-loaded chunks), Forge 47.2.0, mean tick time from `/forge tps` over 60 s:

| Setup | ms per tick |
|---|---|
| TickEngine not installed | 24.3 |
| Installed, throttling and profiler off | 24.3 |
| Installed, only the light profiler on | 24.2 |
| Installed, throttling on (force-loaded chunks: interval 2) | 13.8 (−43%) |
| Same, the JVM limited to 2 cores and 3 GB like an old PC | 13.7–13.9, against 23.7–24.2 off (−43%) |

Installed but idle, TickEngine costs nothing measurable (the runs differ by about ±0.5 ms). The 2-core
run limits threads and memory, not the speed of each core, so an old PC would show higher numbers.

**Tick spikes and tick spreading.** Same server, measured tick by tick right after a restart, with
the furnaces and mobs throttled at interval 4:

| Setup | mean | median | slowest 5% | slowest 1% | slowest tick |
|---|---|---|---|---|---|
| Throttling off | 25.0 | 24.8 | 27.3 | 29.6 | 33.4 ms |
| Throttling on | 9.2 | 1.0 | 35.0 | 38.1 | 54.8 ms |
| Throttling on + tick spreading | 9.3 | 9.2 | 10.8 | 12.2 | 21.1 ms |

Without spreading, mobs loaded together run their throttled turn on the same tick: three light ticks,
then one heavier than a normal tick. Block entity `stagger` barely changes this; the entities cause it.
Tick spreading removes the spikes. It moves entity tick counters (see Tick spreading above); since
1.0.0-beta.3 it is on by default.

Emergency mode and entity stacking (1.0.0-beta.6) were first checked on the Fabric version, with a
test player on a 1.21.11 server: breeding, XP, the mob cap with natural spawning, the spawning pause,
despawning and the memory trigger. On Forge 1.20.1 they have 12 GameTests of their own (58 GameTests in
all, plus 19 JUnit tests):

- three cows become one "Cow x3"; a kill leaves a full-health "x2"; `kill` removes the whole stack;
- a red and a white sheep, a baby and a named cow never stack, and nothing stacks with the mode off;
- shears and wheat used by a player apply to one mob of the stack (the other two stay woolly, or stay a
  stack); natural spawning counts a stack of 5 as 5 mobs; `/tickengine stacking unstack` splits stacks;
- `/tickengine config set` cannot turn stacking on, the mode changes only after the confirmation;
- `/tickengine emergency start` raises auto-tuning to its highest level, pauses spawning and stretches
  intervals to 20; `stop` puts the level from before back; with `stacking.mode emergency` stacking runs
  only during an emergency;
- 200 ms ticks start the mode on their own after `triggerSeconds`, normal ticks end it after
  `exitSeconds`.

Found by the Forge tests and fixed: on Forge, shears act through the item (`IForgeShearable`), not through
the mob's own interaction, so the first port sheared a whole stack and dropped the wool of one sheep. The
Forge version hooks the player's interaction instead, which covers both.

Not checked on Forge yet: the spawning pause and the mob cap with real natural spawning around a
player, and the memory trigger (the same code passed those tests on Fabric).

## Contributing

Ideas, bug reports and pull requests are all welcome. Found a bug? Open a ticket on
[our Discord](https://discord.gg/pQPWr7vfHu). If an idea of yours ships, your name goes in the
credits and in the release notes. See [CONTRIBUTING.md](CONTRIBUTING.md).

## Licence

MIT. See [LICENSE](LICENSE). © 2026 Studio Deriva.
