# TickEngine

Server-side Fabric mod by **Studio Deriva** that helps heavy modpacks run on weak hardware.

TickEngine measures where server tick time goes (per mod, per block entity type, per entity type)
and throttles block entities (machines, hoppers, furnaces, vanilla or modded) that are far from
every player. It is designed to run **alongside**
Lithium, C2ME, FerriteCore and ServerCore, not to replace them.

- Minecraft 1.21.11, Fabric Loader 0.19.5+, Fabric API
- Server only: players do not need it
- Fully configurable (`config/tickengine.json5` and live commands)

## Status

| Version | Feature | State |
|---------|---------|-------|
| 0.1 | Hybrid tick profiler (chat ranking + HTML report) | done |
| 0.2 | Block entity throttling | done |
| 0.3 | Entity and AI throttling | planned |
| 0.4 | MSPT-based auto-tuning | planned |

## Commands

| Command | Description |
|---------|-------------|
| `/tickengine status` | MSPT, TPS, profiler state |
| `/tickengine top [mods\|blockentities\|entities]` | Most expensive mods and types from the light profiler. Click a type to teleport to its slowest instance. |
| `/tickengine profile start [seconds]` | Detailed session: measures every tick, then prints results and writes an HTML report |
| `/tickengine profile stop` | Stop the session early |
| `/tickengine profile report` | HTML report from the light profiler |
| `/tickengine config list\|get\|set\|reload` | Read and change settings live; changes are saved to the file |
| `/tickengine rules list` | Show block entity throttle rules |
| `/tickengine rules set <interval> <target>` | Rule for a type (`minecraft:hopper`) or a whole mod (`create:*`); interval 1 = never throttle |
| `/tickengine rules set-in <dimension> <interval> <target>` | Same, only in one dimension (`*` = every type there); needs `blockEntities.dimensionRulesEnabled` |
| `/tickengine rules remove <target>` / `remove-in <dimension> <target>` | Delete a rule |

## Permissions

Without a permissions mod, commands need operator level `permissions.commandOpLevel` (default 3).
With LuckPerms (or any fabric-permissions-api provider) use these nodes:
`tickengine.status`, `tickengine.top`, `tickengine.profile`, `tickengine.config`.

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

## Building

Requires JDK 21.

```bash
./gradlew build
```

The jar is in `build/libs/`.

## License

MIT, see [LICENSE](LICENSE).
