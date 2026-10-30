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
check "config file reloads (Gson lenient parsing of JSON5)" 'TickEngine config reloaded'
check "auto-tuning status" 'Force-loaded chunks: never slower than'
check "default entity rules (Identifier/ResourceLocation)" 'minecraft:warden: AI never throttled'
check "per-dimension rule (ResourceKey identifier/location)" 'Rule saved: minecraft:zombie: .* in minecraft:the_nether'
check "tick spreading command" 'Tick spreading \(experimental\)'
check "emergency mode starts and raises auto-tuning" 'Auto-tuning level 0 -> [1-9][0-9]* \(emergency mode\)'
check "emergency mode ends and auto-tuning comes back" 'Auto-tuning level [1-9][0-9]* -> 0 \(emergency mode over\)'
check "entity stacking merges cows (NBT save, tags, name)" 'Cow x3 has the following entity data'
check "a stack loses one mob when it dies (die mixin, NBT load)" 'Cow x2 has the following entity data'
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
