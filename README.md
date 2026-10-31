# TickEngine

Server-side tick profiler and throttling for Fabric 1.21 to 1.21.11, by [Studio Deriva](https://discord.gg/pQPWr7vfHu).

TickEngine shows where your server's tick time goes — per mod, per block entity type, per entity
type — and makes the things far from every player cost less: machines, hoppers and furnaces tick less
often, far mobs think less often. When the server struggles, auto-tuning tightens the throttles step by
step, and relaxes them again when the load is gone. It runs **alongside** Lithium, FerriteCore and
ServerCore, not instead of them.

**Free and open source**, like everything we make: MIT, source included, no premium version.

> **Beta.** TickEngine changes how the game runs far from players. It has been tested on a dev server,
> on a real Fabric server with a mod set and with a real modpack (see
> [How this mod was checked](#how-this-mod-was-checked)), but not yet on many live servers. Keep a
> backup of your world and tell us what you see.

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
- **Tick spreading.** Entities loaded together are spread over different ticks, so throttling does
  not trade a lower average for heavier single ticks. Experimental, on by default in the beta.
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
| Minecraft | 1.21 to 1.21.11, one jar per range (below) |
| Loader | Fabric 0.19.5+ |
| Java | 21 |
| Required | Fabric API |
| Optional | LuckPerms (or any fabric-permissions-api provider) for permission nodes |

Install it on the **server only**. Players don't need it, and it does nothing on a client.

### Which jar

Minecraft changed its code several times during 1.21.x, so there is one jar per range. Pick the one
that covers your server's version; the others refuse to load on it.

| Server version | Jar |
|---|---|
| 1.21, 1.21.1 | `tickengine-<version>+1.21.1.jar` |
| 1.21.2 to 1.21.4 | `tickengine-<version>+1.21.2.jar` |
| 1.21.5 | `tickengine-<version>+1.21.5.jar` |
| 1.21.6 to 1.21.8 | `tickengine-<version>+1.21.6.jar` |
| 1.21.9, 1.21.10 | `tickengine-<version>+1.21.9.jar` |
| 1.21.11 | `tickengine-<version>+1.21.11.jar` |

Every jar has the same features and config. Each one bundles the fabric-permissions-api build made for
its range. The tests in "How this mod was checked" were run on 1.21.11 unless they say otherwise; on
1.21.1 the dev server was checked too (all mixins applied, throttling, profiler and commands working).
The other jars are built and unit-tested, and their mixin targets checked against each version's
bytecode, but not yet run on a server.

## Getting started

1. Drop the jar in the server's `mods/` folder, next to Fabric API, and start the server. The config is
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
- Never throttled by default: projectiles, entities carrying passengers, bosses (`c:bosses` tag), mobs in
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
every few ticks one tick is heavier than a normal tick would be.

Tick spreading moves each entity's counter forward once, by 0–19 ticks, 5 seconds after it is loaded,
so the turns spread out. It only acts while entity throttling is running. Measured right after a
restart:

| | Average | Slowest 5% of ticks | Slowest tick |
|---|---|---|---|
| Forge port, 1100 mobs, spreading off | 9.2 ms | 35 ms | 55 ms |
| Forge port, 1100 mobs, spreading on | 9.3 ms | 10.8 ms | 21 ms |
| Fabric, 1500 mobs, spreading off | 12.2–12.8 ms | 17.8–20.0 ms | not measured |
| Fabric, 1500 mobs, spreading on | 12.2–12.4 ms | 16.5 ms | not measured |

On Fabric, "slowest 5%" is the median P95 of `/tick query` over 180 s, two runs each; the same server
without TickEngine had 15.7–15.9 ms.

The risk: logic that waits for one exact counter value in that short range can be skipped once per
load, and counter-based timers such as "hurt recently" can end up to 19 ticks early once. Vanilla does
not wait for exact values there; some mods might. It is on by default in the beta so that it gets
tested before 1.0.0. If a modded mob or machine misbehaves, `/tickengine spread disable` turns it off
at once; tell us which one it was.
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

**When it starts.** Every 20 ticks TickEngine compares the average tick time of those ticks with
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

Measured on the 1.21.11 dev server with 2500 pigs packed in a glass box (`stacking.mode emergency`,
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
| `/tickengine report` | Report a problem: click what it is about (general, a block entity, an entity, a mod), then see the next section |
| `/tickengine report general\|blockentity <type>\|entity <type>\|mod <mod id>` | Collect a diagnostic report and get a link that opens it as a GitHub issue (Tab completes the type or mod) |

### Reporting a problem

`/tickengine report entity minecraft:villager` (or `blockentity`, `mod`, `general`) collects what we need
to look at a problem: versions, the settings you changed, the rule that applies to that target and
why, profiler numbers for it, the state of the throttles and auto-tuning, and the mod list. It gives you
a link: click it, your browser opens a GitHub issue with all of that filled in, you describe what is
going wrong and press Submit. A file with the full report (the link has to stay short and can leave out
part of the mod list) is written to `tickengine/reports/` to attach to the issue.

TickEngine sends nothing itself, so you can read everything before it is posted. The report has no
player names, IP addresses or coordinates; the only path in it is the `output.reportDirectory` setting,
relative to the server folder. From the server console the command prints the address of the new-issue
page and the file instead of a clickable link; there the file is the whole report, so attach or paste it.

## Permissions

Without a permissions mod, commands need operator level `permissions.commandOpLevel` (default 3).
With LuckPerms (tested with 5.5.21) or any fabric-permissions-api provider, use these nodes:
`tickengine.status`, `tickengine.top`, `tickengine.profile` (also the network profiler), `tickengine.config`, `tickengine.report`, and
`tickengine.notify` for admin notices (auto-tuning changes, the ServerCore notices below).

## Compatibility

- **Lithium, FerriteCore**: tested together, no conflicts. Hoppers and furnaces that Lithium puts to
  sleep don't tick at all, and TickEngine throttles the rest.
- **ServerCore**:
  - *Entity activation range:* if it is enabled, TickEngine pauses its own entity throttling (profiler
    and block entity throttling keep running), so entities are not throttled twice.
  - *Dynamic settings:* if they are enabled, ServerCore manages simulation and view distance and
    TickEngine's auto-tuning only adjusts its own throttles.

  Admins get a notice on join and every `notices.intervalMinutes` (30) while either applies.
- **C2ME**: tested on 1.21.11 with 0.4.0-alpha.0.28 on Java 25 (every C2ME build for 1.21.11 needs Java 25),
  together with Lithium and FerriteCore: no conflicts, new chunks generated while throttling, throttled
  entities kept across chunk unloads and reloads. TickEngine is built for Java 21 and runs on Java 25.
- **Modded block entities and entities** are throttled like vanilla ones. If a mod's machine or mob
  misbehaves when far away, give it a rule with interval 1 and tell us which one it is.

## Building from source

```
./gradlew build                  # all six jars, in versions/<minecraft>/build/libs/ (also runs the tests)
./gradlew :1.21.1:build          # one jar
./gradlew test                   # unit tests only, every version
./gradlew :1.21.11:runServer     # dev server in versions/1.21.11/run/
```

Java 21 or newer, and a first run with network access to fetch the dependencies.

The versions are handled by [Stonecutter](https://stonecutter.kikugie.dev/): one source tree in
`src/`, with `//? if` comments where Minecraft versions differ (mostly in
`compat/MinecraftCompat.java`), and renames such as `Identifier`/`ResourceLocation` done by
replacements in `build.gradle`. Each version's Fabric API and fabric-permissions-api versions are in
`versions/<minecraft>/gradle.properties`. `src/` is kept in the 1.21.11 state; to edit it as another
version, run `./gradlew "Set active project to 1.21.1"`, and `./gradlew "Reset active project"` before
committing.

## How this mod was checked

[`HOW_WE_VERIFIED_AND_FIXED_THE_BUGS.txt`](HOW_WE_VERIFIED_AND_FIXED_THE_BUGS.txt) is the full account
of the audit behind this beta: 7 findings — mobs that never chose a goal while throttled, a config typo
that wiped every setting, report paths outside the server folder and more — what each one was and what
was changed to close it. It also has the performance measurements and says plainly what has **not**
been verified yet.

Checked after the audit:

- **LuckPerms 5.5.21**: granted and denied nodes, the fall back to the operator level when a node is
  not set, and admin notices only for players with `tickengine.notify`.
- **Tick spikes.** The audit saw the slowest ticks get worse with throttling and guessed at block
  entity stagger. Measured since: the cause is entities loaded together sharing their throttled turn.
  Tick spreading (above) fixes it; measured on the Forge port, which runs the same code, and again on
  Fabric (the slowest 5% of ticks from 18-20 ms to 16.5 ms).
- **C2ME, the Nether and the End** on a real Fabric server, and the cost of the mod itself: installed
  with everything off, or with only the light profiler, it is within the measurement noise.
- **Dense farms in force-loaded chunks.** On a test world with 1500 mobs packed in one pen in
  force-loaded chunks, the defaults (never slower than one tick in two there) did not lower the average
  MSPT: a mob's throttled turn costs more than a normal tick, and much of what mobs do is paced by game
  time. Raising `entities.forcedChunkMaxInterval` to 4 saved about 10-30% on the same world (about 44%
  on a less crowded modded world, against 25% with 2). The default stays 2, so farms kept loaded keep
  half their speed, and auto-tuning raises the limit to 4 when the server goes above 45 ms per
  tick, right after the intervals, and lowers it again when the load is gone. Far areas without
  force-loading use interval 4 by default.

Emergency mode and entity stacking (beta.6) were checked on the 1.21.11 dev server, with a test player
joined through the server's normal login code (a `ServerPlayer` without a network client, whose clicks
go through the same packet handler a client's do):

- stacks form and keep sheep colours, sheared sheep, babies and named mobs apart, and survive a
  restart; a kill takes one mob with its loot until none is left; `/tickengine stacking unstack`
  splits them;
- the player's wheat, shears and lead separate one mob; two cows separated with wheat bred a calf;
- a player kill drops XP (3 points from a cow of a stack of 3);
- mob cap: at night around the player, 56-63 monster entities held 70-73 monsters, the vanilla cap of
  70, so stacking does not let more monsters spawn;
- emergency mode paused natural spawning (no monster in 45 s at night, 71 again 45 s after it ended);
- a zombie stack more than 128 blocks from the player despawned whole, as single zombies do;
- the memory trigger: on a 600 MB heap filled with force-loaded chunks, emergency mode started when
  the old generation was 91% full after collection, and saved the world. The server still ran out of
  memory later, since the test kept loading chunks: emergency mode lowers the work per tick, it cannot
  unload chunks;
- the 2500-pig emergency above; `tools/smoke-test.sh` passes on all six jars.

Not checked: how the count looks on a real client ("Cow x7" is a normal custom name, so vanilla draws it).

## Contributing

Ideas, bug reports and pull requests are all welcome. Found a bug? Run `/tickengine report` in
game or on the console and open the GitHub issue it prepares, or open a ticket on
[our Discord](https://discord.gg/pQPWr7vfHu). If an idea of yours ships, your name goes in the
credits and in the release notes. See [CONTRIBUTING.md](CONTRIBUTING.md).

## Licence

MIT. See [LICENSE](LICENSE). © 2026 Studio Deriva.
