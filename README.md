# TickEngine

Server-side tick profiler and throttling for Fabric 1.21.11, by [Studio Deriva](https://discord.gg/pQPWr7vfHu).

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
- **Fully configurable, live.** A commented `config/tickengine.json5`, every option also settable by
  command and saved to the file.
- **ServerCore aware.** If ServerCore already throttles entities or manages distances, TickEngine steps
  back from that part and tells admins why.

## Requirements

| | |
|---|---|
| Minecraft | 1.21.11 |
| Loader | Fabric 0.19.5+ |
| Java | 21 |
| Required | Fabric API |
| Optional | LuckPerms (or any fabric-permissions-api provider) for permission nodes |

Install it on the **server only**. Players don't need it, and it does nothing on a client.

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

## How block entity throttling works

- Block entities within `blockEntities.activeRadiusChunks` chunks (default 4) of any player tick normally.
- Everything else ticks once every N ticks (`blockEntities.defaultInterval`, default 4).
- Rules override the default. The most specific wins: dimension + type, type, dimension + mod, mod,
  dimension `*`, default. Moving pistons (`minecraft:piston`) are never throttled by default.
- Force-loaded chunks without players tick at least every `blockEntities.forcedChunkMaxInterval` ticks
  (default 2; 1 = never throttle them). Mod chunk loaders that use their own tickets count as far chunks.
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
- Force-loaded chunks without players are never slower than `entities.forcedChunkMaxInterval` (2).
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
at once; tell us which one it was. Turning it back on goes through `/tickengine spread enable`, which
explains it and asks you to confirm with `/tickengine spread confirm I understand the risks`.
`/tickengine config set` cannot change it.

A config file written by an earlier version keeps the value it has (`"spreadTicks": false` from
1.0.0-beta.2 or older): turn it on with `/tickengine spread enable`.

## How auto-tuning works

Every `autoTuning.decisionSeconds` (10) TickEngine looks at the average MSPT over that time. Above
`raiseAboveMspt` (45 ms) it tightens one level; below `lowerBelowMspt` (30 ms) it relaxes one level.
`lowerBelowMspt` must be below `raiseAboveMspt`; commands reject values that break this.
It relaxes only after the MSPT has stayed low for `relaxAfterSeconds` (60). If the load comes straight
back after relaxing, that wait doubles (up to 10 minutes), so a load right at the threshold does not
flip the level back and forth.

Levels tighten in this order, least visible first, and relax in reverse:

1. default throttle intervals, through safe values only: 4, 5, 10, 20 (explicit rules are never changed)
2. throttle radii, one chunk per level (block entities down to 2, entity full radius to 1, AI radius to 2)
3. simulation distance, one per level, down to 4
4. view distance, one per level, down to 6

If the load sits close to players, the first levels can't reduce it and auto-tuning goes on to the
distances. Turn those steps off with `autoTuning.tuneSimulationDistance` and `tuneViewDistance` if you
would rather keep them.

Every limit and step is configurable. Auto-tuning starts at level 0 after a restart and puts the
original distances back when it relaxes or the server stops. Each level change is logged and, with
`autoTuning.notifyAdmins`, sent to admins.

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
                     "relaxAfterSeconds": 60 }
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

## Permissions

Without a permissions mod, commands need operator level `permissions.commandOpLevel` (default 3).
With LuckPerms (tested with 5.5.21) or any fabric-permissions-api provider, use these nodes:
`tickengine.status`, `tickengine.top`, `tickengine.profile`, `tickengine.config`, and
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
- **C2ME**: tested with 0.4.0-alpha.0.28 on Java 25 (every C2ME build for 1.21.11 needs Java 25),
  together with Lithium and FerriteCore: no conflicts, new chunks generated while throttling, throttled
  entities kept across chunk unloads and reloads. TickEngine is built for Java 21 and runs on Java 25.
- **Modded block entities and entities** are throttled like vanilla ones. If a mod's machine or mob
  misbehaves when far away, give it a rule with interval 1 and tell us which one it is.

## Building from source

```
./gradlew build          # jar in build/libs/ (also runs the tests)
./gradlew test           # unit tests only
./gradlew runServer      # dev server in run/
```

Java 21 or newer, and a first run with network access to fetch the dependencies.

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
  time. Raising `entities.forcedChunkMaxInterval` to 4 saved about 10-30% on the same world. Far areas
  without force-loading use interval 4 by default.

## Contributing

Ideas, bug reports and pull requests are all welcome. Found a bug? Open a ticket on
[our Discord](https://discord.gg/pQPWr7vfHu). If an idea of yours ships, your name goes in the
credits and in the release notes. See [CONTRIBUTING.md](CONTRIBUTING.md).

## Licence

MIT. See [LICENSE](LICENSE). © 2026 Studio Deriva.
