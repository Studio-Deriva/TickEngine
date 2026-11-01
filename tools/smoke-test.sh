#!/usr/bin/env bash
# Starts the dev server of one Stonecutter node, runs TickEngine's commands from the console and checks
# the log: the mod loads, every mixin applies, throttling and the profiler work, nothing throws.
# It does not replace the tests that need a player (AI skipping, clickable chat, permissions): see
# TEST_PLAN.md.
#
# Usage: tools/smoke-test.sh <node>     for example: tools/smoke-test.sh 1.21.2
# Needs versions/<node>/run/eula.txt with eula=true (the Minecraft EULA, accepted by a person).
# Output: build/smoke/<node>/server.log and a PASS/FAIL line per check. Exit code 0 only if all pass.
set -euo pipefail

node="${1:?usage: tools/smoke-test.sh <node>, for example 1.21.2}"
root="$(cd "$(dirname "$0")/.." && pwd)"
run="$root/versions/$node/run"
out="$root/build/smoke/$node"

if [ ! -f "$root/versions/$node/gradle.properties" ]; then
	echo "No node $node. Nodes: $(ls "$root/versions" | tr '\n' ' ')" >&2
	exit 2
fi
if ! grep -qx 'eula=true' "$run/eula.txt" 2>/dev/null; then
	echo "The Minecraft EULA is not accepted for $node. A person must read it" >&2
	echo "(https://aka.ms/MinecraftEULA) and write eula=true in $run/eula.txt." >&2
	exit 2
fi

mkdir -p "$out"
log="$out/server.log"

# 1.21.2+ pauses an empty server after pause-when-empty-seconds (60 by default), and the checks take
# longer than that with no player online. Turn pausing off for the test and put the file back after.
properties="$run/server.properties"
if [ -f "$properties" ] && grep -q '^pause-when-empty-seconds=' "$properties"; then
	cp "$properties" "$out/server.properties.saved"
	sed -i.tmp 's/^pause-when-empty-seconds=.*/pause-when-empty-seconds=0/' "$properties" && rm -f "$properties.tmp"
fi
restore_properties() {
	if [ -f "$out/server.properties.saved" ]; then
		mv "$out/server.properties.saved" "$properties"
	fi
}
stdin="$out/stdin"
[ -p "$stdin" ] || mkfifo "$stdin"

"$root/gradlew" -p "$root" ":$node:runServer" --args=nogui --console=plain < "$stdin" > "$log" 2>&1 &
server=$!
# Hold the pipe open, so the server does not see the end of its input between commands.
exec 3>"$stdin"

stop_server() {
	echo stop >&3 2>/dev/null || true
	exec 3>&- || true
	wait "$server" 2>/dev/null || true
	restore_properties
}
trap stop_server EXIT

wait_for() { # pattern, seconds
	local i
	for ((i = 0; i < $2; i++)); do
		grep -qE "$1" "$log" && return 0
		kill -0 "$server" 2>/dev/null || return 1
		sleep 1
	done
	return 1
}

send() {
	echo "$1" >&3
	sleep "${2:-1}"
}

echo "Starting the $node dev server (log: $log)..."
if ! wait_for 'Done \(' 900; then
	echo "FAIL server did not start; last lines of the log:"
	tail -30 "$log"
	exit 1
fi

# Every history period is a peak while the threshold is 1 ms, so peaks get written.
send 'tickengine config set history.peakAverageMspt 1' 0
# Something to throttle: a force-loaded chunk with mobs, an item and a furnace.
send 'forceload add 0 0'
send 'summon minecraft:zombie 8 80 8' 0
send 'summon minecraft:villager 9 80 9' 0
send 'summon minecraft:cow 10 80 10' 0
send 'summon minecraft:item 11 80 11 {Item:{id:"minecraft:stone",count:1}}' 0
send 'summon minecraft:experience_orb 12 80 12' 0
send 'setblock 5 80 5 minecraft:furnace' 3
send 'tickengine status' 2
send 'tickengine profile start 5' 8
send 'tickengine top blockentities' 1
send 'tickengine top entities' 1
send 'tickengine top chunks' 1
send 'tickengine top worldgen' 1
send 'tickengine config reload' 1
send 'tickengine tuning status' 1
send 'tickengine rules entities set-in minecraft:the_nether 2 2 minecraft:zombie' 1
send 'tickengine rules entities list' 1
send 'tickengine rules entities remove-in minecraft:the_nether minecraft:zombie' 1
send 'tickengine spread' 1
# Emergency mode by hand: auto-tuning jumps to the top and comes back.
send 'tickengine emergency start' 2
send 'tickengine emergency' 1
send 'tickengine emergency stop' 1
# Stacking: three cows on one spot become one stack; /damage takes one mob, /kill the rest.
send 'kill @e[type=minecraft:cow]' 1
send 'tickengine stacking mode always' 1
send 'tickengine stacking confirm I understand the risks' 1
# On a platform high up: at y 80 some dev worlds have terrain, and cows inside it suffocate.
send 'fill 9 300 9 15 300 15 minecraft:stone' 0
send 'fill 9 301 9 15 303 15 minecraft:air' 0
send 'summon minecraft:cow 12 301 12' 0
send 'summon minecraft:cow 12 301 12' 0
send 'summon minecraft:cow 12 301 12' 6
send 'tickengine stacking' 1
send 'data get entity @e[type=minecraft:cow,tag=tickengine.stack.3,limit=1] CustomName' 1
send 'damage @e[type=minecraft:cow,tag=tickengine.stack.3,limit=1] 100 minecraft:generic' 2
send 'data get entity @e[type=minecraft:cow,tag=tickengine.stack.2,limit=1] CustomName' 1
send 'kill @e[type=minecraft:cow]' 1
send 'tickengine stacking mode off' 1
# Entity limit: with a limit of 2 cows and 1 chicken per chunk, two cows in love make no calf and
# thrown eggs hatch no chicks. Spawners are placed so their classes (and mixins) load; without a
# player nearby they never spawn.
send 'setblock 1 79 1 minecraft:spawner' 0
send 'setblock 2 79 2 minecraft:trial_spawner' 0
send 'fill 0 300 0 7 300 7 minecraft:stone' 0
send 'fill 0 301 0 7 303 7 minecraft:air' 0
send 'tickengine limit rules set 2 minecraft:cow' 0
send 'tickengine limit rules set 1 minecraft:chicken' 0
send 'tickengine limit enable' 0
send 'summon minecraft:cow 3 301 3 {InLove:600}' 0
send 'summon minecraft:cow 3 301 3 {InLove:600}' 10
send 'tickengine limit' 1
send 'summon minecraft:chicken 5 301 5' 0
for _ in $(seq 1 40); do
	echo 'summon minecraft:egg 4 305 4' >&3
done
sleep 4
send 'tickengine limit' 1
send 'execute if entity @e[type=minecraft:cow,x=0,y=290,z=0,dx=16,dy=20,dz=16]' 0
send 'kill @e[type=minecraft:cow]' 0
send 'kill @e[type=minecraft:chicken]' 0
send 'tickengine limit disable' 0
send 'tickengine limit rules remove minecraft:cow' 0
send 'tickengine limit rules remove minecraft:chicken' 1
# History: a peak starts a detailed session when history.profilePeaks is on; stopping it saves it.
# Auto-tuning above level 5 is a peak, and the dev server is too light to reach the MSPT thresholds.
sleep 10
send 'tickengine tuning level 6' 0
send 'tickengine config set history.profilePeaks true' 3
send 'tickengine profile stop' 2
send 'tickengine tuning level 0' 0
send 'tickengine tuning resume' 0
send 'tickengine config set history.profilePeaks false' 0
send 'tickengine config set history.peakAverageMspt 50' 0
send 'tickengine history 1' 2
send 'tickengine history report 1' 2
# Network profiler: server list pings are real connections, so packets get encoded and measured.
send 'tickengine network start 5' 1
# Asked while the session runs: it must say so instead of showing an old result as if it were new.
send 'tickengine network top' 1
for _ in 1 2 3; do
	python3 -I "$root/tools/status-ping.py" localhost 25565 >> "$out/ping.log" 2>&1 || echo "ping failed" >> "$out/ping.log"
done
sleep 6
send 'tickengine network' 1
send 'tickengine network top packets' 1
send 'tickengine network top chunks' 1
send 'tickengine status' 2
stop_server
trap - EXIT

failures=0
check() { # description, pattern that must be in the log
	if grep -qE "$2" "$log"; then
		echo "PASS $1"
	else
		echo "FAIL $1   (expected: $2)"
		failures=$((failures + 1))
	fi
}
check_absent() { # description, pattern that must not be in the log, optional pattern of lines allowed anyway
	local found
	found="$(grep -nE "$2" "$log" | grep -vE "${3:-^$}" | head -3 || true)"
	if [ -z "$found" ]; then
		echo "PASS $1"
	else
		echo "FAIL $1"
		echo "$found"
		failures=$((failures + 1))
	fi
}

check "loads the $node jar" "tickengine [0-9]+\.[0-9]+\.[0-9]+[^ ]*\+$node"
check "TickEngine initializes" 'TickEngine initialized'
check "block entity throttling skips ticks (Level/BoundTickingBlockEntity mixins)" 'Block entity throttle: on, skipped [1-9]'
check "entity throttling gives minimal ticks (ServerLevel mixin, accessors)" 'last second: [0-9]+ full ticks, [0-9]+ without AI, [1-9][0-9]* minimal'
check "detailed profiling times entities (WrapOperation on Entity.tick)" 'minecraft:(villager|zombie) x'
check "detailed profiling times block entities (WrapOperation on the ticker)" 'minecraft:furnace x'
check "HTML report is written (Util.ioPool)" 'HTML report saved'
check "chunks ranked by tick time" '1\. minecraft:overworld -?[0-9]+ -?[0-9]+ minecraft:[a-z_]+  [0-9.]+ ms/tick'
check "chunk loading and worldgen view" 'Chunk loading and worldgen'
check "entity limit stops breeding (Animal mixin)" 'Stopped breeding: [0-9]+ times, last minecraft:cow'
check "entity limit stops chicks from eggs (ThrownEgg mixin)" 'Stopped breeding: [0-9]+ times, last minecraft:chicken'
check "no calf was born" 'Test passed[.,] [Cc]ount: 2'
check "history records periods" 'Recorded: [1-9][0-9]* periods'
check "history keeps peaks" 'Peaks: [1-9]'
check "a peak starts a detailed session (history.profilePeaks)" 'History: peak \(.*auto-tuning\), detailed profiler started'
check "the detailed session is kept" 'Detailed sessions during peaks: [1-9]'
check "history HTML report is written" 'HTML report saved: .*tickengine-history-'
check "config file reloads (Gson lenient parsing of JSON5)" 'TickEngine config reloaded'
check "auto-tuning status" 'Force-loaded chunks: never slower than'
check "default entity rules (Identifier/ResourceLocation)" 'minecraft:warden: AI never throttled'
check "per-dimension rule (ResourceKey identifier/location)" 'Rule saved: minecraft:zombie: .* in minecraft:the_nether'
check "tick spreading command" 'Tick spreading \(experimental\)'
check "emergency mode starts and raises auto-tuning" 'Auto-tuning level 0 -> [1-9][0-9]* \(emergency mode\)'
check "emergency mode ends and auto-tuning comes back" 'Auto-tuning level [1-9][0-9]* -> 0 \(emergency mode over\)'
check "entity stacking merges cows (NBT save, tags, name)" 'Cow x3 has the following entity data'
check "a stack loses one mob when it dies (die mixin, NBT load)" 'Cow x2 has the following entity data'
check "network profiler measures packets (PacketEncoder mixin)" 'minecraft:status_response'
check "network HTML report is written" 'HTML report saved: .*tickengine-network-'
check "network top during a session says it is running" 'A network session is running \([0-9]+ s left\)'
check "network status command" 'Last result.*/s over'
check "server stops cleanly" 'Stopping server|All dimensions are saved'
check_absent "no mixin errors" 'InvalidInjectionException|InjectionError|MixinApplyError|Mixin apply .*failed|Critical injection failure|@Shadow field .* was not located'
check_absent "no missing classes or members" 'NoSuchMethodError|NoSuchFieldError|NoClassDefFoundError|ClassNotFoundException: (net\.minecraft|com\.studioderiva|me\.lucko)'
check_absent "no exceptions in the server tick" 'Exception in server tick|Encountered an unexpected exception|---- Minecraft Crash Report ----'
check_absent "no TickEngine errors or warnings" '\((TickEngine)\).*(ERROR|WARN)|/(ERROR|WARN)\] \(TickEngine\)' 'Emergency mode ON'
check_absent "no command errors" 'Unknown or incomplete command|An unexpected error occurred trying to execute that command'

echo
if [ "$failures" -eq 0 ]; then
	echo "$node: all checks passed"
else
	echo "$node: $failures check(s) failed, see $log"
fi
[ "$failures" -eq 0 ]
