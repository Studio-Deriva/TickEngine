# TickEngine

Server-side Fabric mod by **Studio Deriva** that helps heavy modpacks run on weak hardware.

TickEngine measures where server tick time goes (per mod, per block entity type, per entity type)
and throttles block entities (machines, hoppers, furnaces) and entities (mobs, animals, items),
vanilla or modded, that are far from every player. When the server struggles, auto-tuning tightens
the throttles and distances step by step, and relaxes them again when load drops. It is designed to run **alongside**
Lithium, C2ME, FerriteCore and ServerCore, not to replace them.

- Minecraft 1.21.11, Fabric Loader 0.19.5+, Fabric API
- Server only: players do not need it
- Fully configurable (`config/tickengine.json5` and live commands)

## Status

| Version | Feature | State |
|---------|---------|-------|
| 0.1 | Hybrid tick profiler (chat ranking + HTML report) | done |
| 0.2 | Block entity throttling | done |
| 0.3 | Entity and AI throttling | done |
| 0.4 | MSPT-based auto-tuning | done |

## Commands

| Command | Description |
|---------|-------------|
| `/tickengine status` | MSPT, TPS, profiler state |
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

## Permissions

Without a permissions mod, commands need operator level `permissions.commandOpLevel` (default 3).
With LuckPerms (or any fabric-permissions-api provider) use these nodes:
`tickengine.status`, `tickengine.top`, `tickengine.profile`, `tickengine.config`, and
`tickengine.notify` for admin notices (for example the ServerCore conflict below).

## How the profiler works

- **Light mode** (always on): measures one tick every `profiler.sampleEveryTicks` ticks (default 20,
  one per second). On ticks that are not sampled the cost is a single boolean check per tick call.
- **Detailed sessions** (on demand): measure every tick for a limited time.
- Reports are written to disk off the server thread, so slow disks do not cause lag spikes.

## How block entity throttling works

- Block entities within `blockEntities.activeRadiusChunks` chunks (default 4) of any player tick normally.
- Everything else ticks once every N ticks (`blockEntities.defaultInterval`, default 4): a far furnace
  smelts at 1/4 speed and costs about 1/4 of the CPU.
- Rules override the default. The most specific wins: dimension + type, type, dimension + mod, mod,
  dimension `*`, default. Moving pistons (`minecraft:piston`) are never throttled by default.
- Force-loaded chunks without players tick at least every `blockEntities.forcedChunkMaxInterval` ticks
  (default 2; 1 = never throttle them). Mod chunk loaders that use their own tickets count as far chunks.
- Throttled ticks happen on game times divisible by the interval, so blocks that act on
  `gameTime % 20` or `% 80` (beacons, vaults, trial spawners, and any modded block that does the same) keep working with
  intervals 2, 4, 5, 10 or 20. `blockEntities.stagger` spreads the work over ticks instead, at the cost
  of that guarantee.

## How entity throttling works

Players are never throttled. Around the nearest player:

| Distance | What runs |
|----------|-----------|
| up to `entities.fullRadiusChunks` (2) | everything, normally |
| up to `entities.aiRadiusChunks` (4) | movement and physics every tick; AI, goals, pathfinding and brains once every `ai` ticks (default 4) |
| beyond | the full tick once every `tick` ticks (default 4); in between only timers advance (item and XP despawn, mob idle time, portal cooldown, burning time) |

- Throttled ticks follow each entity's own tick counter, so logic like "every 40 ticks" (item merging)
  keeps working with intervals 2, 4, 5, 10 or 20, and the work is spread over different ticks.
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

## How auto-tuning works

Every `autoTuning.decisionSeconds` (10) TickEngine looks at the average MSPT over that time. Above
`raiseAboveMspt` (45 ms) it tightens one level; below `lowerBelowMspt` (30 ms) it relaxes one level.
`lowerBelowMspt` must be below `raiseAboveMspt`; commands reject values that break this.
Levels tighten in this order, least visible first, and relax in reverse:

1. default throttle intervals, through safe values only: 4, 5, 10, 20 (explicit rules are never changed)
2. throttle radii, one chunk per level (block entities down to 2, entity full radius to 1, AI radius to 2)
3. simulation distance, one per level, down to 4
4. view distance, one per level, down to 6

Every limit and step is configurable. Auto-tuning is runtime only: it never writes the config file or
server.properties, starts at level 0 after a restart, and puts the original distances back when it
relaxes or the server stops. Each level change is logged and, with `autoTuning.notifyAdmins`, sent to admins.

## ServerCore

- **Entity activation range:** if it is enabled, TickEngine pauses its own entity throttling (profiler
  and block entity throttling keep running), so entities are not throttled twice.
- **Dynamic settings:** if they are enabled, ServerCore manages simulation and view distance and
  TickEngine's auto-tuning only adjusts its own throttles.

Admins get a notice on join and every `notices.intervalMinutes` (30) while either applies. Turn this off
with `compatibility.disableEntityThrottleWithServerCore`, `autoTuning.tuneSimulationDistance` and
`tuneViewDistance`, or `notices.enabled`.

## Building

Requires JDK 21.

```bash
./gradlew build
```

The jar is in `build/libs/`.

## License

MIT, see [LICENSE](LICENSE).
