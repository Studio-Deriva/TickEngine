package com.studioderiva.tickengine.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.studioderiva.tickengine.TickEngine;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import net.fabricmc.loader.api.FabricLoader;

/**
 * TickEngine configuration.
 *
 * <p>Stored as a commented JSON5 file in {@code config/tickengine.json5}. Every option is
 * registered once in {@link #OPTIONS}, which drives loading, saving and the
 * {@code /tickengine config} commands, so a new option only needs to be added here.
 */
public final class TickEngineConfig {
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("tickengine.json5");

	// --- profiler ---
	public static boolean profilerEnabled = true;
	public static int sampleEveryTicks = 20;
	public static int windowSeconds = 60;
	public static int maxSessionSeconds = 600;

	// --- output ---
	public static int chatTopEntries = 10;
	public static String reportDirectory = "tickengine/reports";

	// --- permissions ---
	public static int commandOpLevel = 3;

	// --- block entity throttling ---
	public static boolean blockEntityThrottle = true;
	public static int activeRadiusChunks = 4;
	public static int defaultInterval = 4;
	public static boolean stagger = false;
	public static int forcedChunkMaxInterval = 2;
	public static boolean dimensionRulesEnabled = false;
	public static final ThrottleRules<Integer> BLOCK_ENTITY_RULES = new ThrottleRules<>(ThrottleRules.INTERVAL,
		new String[] {
			"Interval per type or per mod: \"minecraft:hopper\": 2, \"create:*\": 8. 1 = never throttle.",
			"The most specific rule wins (type before mod). Change live with '/tickengine rules blockentities'."},
		new String[] {
			"Same as 'rules' but only in one dimension; \"*\" matches every type there. Used only when",
			"'dimensionRulesEnabled' is true. Example: \"minecraft:the_nether\": { \"*\": 10, \"create:*\": 2 }"});

	// --- entity throttling ---
	public static boolean entityThrottle = true;
	public static int entityFullRadiusChunks = 2;
	public static int entityAiRadiusChunks = 4;
	public static int defaultAiInterval = 4;
	public static int defaultTickInterval = 4;
	public static int entityForcedChunkMaxInterval = 2;
	public static boolean entityDimensionRulesEnabled = false;
	public static boolean excludeProjectiles = true;
	public static boolean excludeVehicles = true;
	public static boolean excludeBosses = true;
	public static boolean excludeCombat = true;
	public static int combatSeconds = 10;
	public static boolean wakeFallingOrInFluid = true;
	public static boolean wakeNewEntities = true;
	public static int newEntitySeconds = 10;
	public static boolean wakeBreeding = true;
	public static final ThrottleRules<ThrottleRules.EntityIntervals> ENTITY_RULES = new ThrottleRules<>(ThrottleRules.ENTITY_INTERVALS,
		new String[] {
			"Intervals per type or per mod: \"minecraft:villager\": { \"ai\": 2, \"tick\": 8 }.",
			"\"ai\" applies between fullRadiusChunks and aiRadiusChunks, \"tick\" beyond. 1 = never throttle.",
			"The most specific rule wins (type before mod). Change live with '/tickengine rules entities'."},
		new String[] {
			"Same as 'rules' but only in one dimension; \"*\" matches every type there. Used only when",
			"'dimensionRulesEnabled' is true."});

	// --- auto-tuning ---
	public static boolean autoTuning = true;
	public static int raiseAboveMspt = 45;
	public static int lowerBelowMspt = 30;
	public static int decisionSeconds = 10;
	public static boolean tuneIntervals = true;
	public static boolean tuneRadii = true;
	public static boolean tuneSimulationDistance = true;
	public static boolean tuneViewDistance = true;
	public static int maxTunedInterval = 20;
	public static int minBlockEntityRadius = 2;
	public static int minEntityFullRadius = 1;
	public static int minEntityAiRadius = 2;
	public static int minSimulationDistance = 4;
	public static int minViewDistance = 6;
	public static boolean notifyTuningChanges = true;

	// --- compatibility and notices ---
	public static boolean disableEntityThrottleWithServerCore = true;
	public static boolean noticesEnabled = true;
	public static int noticeIntervalMinutes = 30;

	/** Incremented on every change, so caches derived from the config know when to rebuild. */
	public static int version;

	private static final Map<String, Option<?>> OPTIONS = new LinkedHashMap<>();

	static {
		register(Option.bool("profiler.enabled",
			"Light profiler: always on, samples one server tick every 'sampleEveryTicks' ticks.\n"
				+ "Feeds '/tickengine top'. Detailed sessions work even when this is false.",
			() -> profilerEnabled, v -> profilerEnabled = v));
		register(Option.integer("profiler.sampleEveryTicks",
			"How often the light profiler samples a tick. 20 = one tick per second (lowest overhead).\n"
				+ "Lower values give more precise data but cost more CPU. Range: 1-1200.",
			1, 1200, () -> sampleEveryTicks, v -> sampleEveryTicks = v));
		register(Option.integer("profiler.windowSeconds",
			"Length of the light profiler window in seconds. '/tickengine top' shows the last full window.\n"
				+ "Range: 10-3600.",
			10, 3600, () -> windowSeconds, v -> windowSeconds = v));
		register(Option.integer("profiler.maxSessionSeconds",
			"Maximum length of a detailed session ('/tickengine profile start'). Range: 10-3600.",
			10, 3600, () -> maxSessionSeconds, v -> maxSessionSeconds = v));
		register(Option.integer("output.chatTopEntries",
			"How many entries '/tickengine top' prints per list. Range: 1-50.",
			1, 50, () -> chatTopEntries, v -> chatTopEntries = v));
		register(Option.string("output.reportDirectory",
			"Folder for HTML reports, relative to the server folder.",
			() -> reportDirectory, v -> reportDirectory = v));
		register(Option.integer("permissions.commandOpLevel",
			"Operator level required for /tickengine when no permissions mod (e.g. LuckPerms) is installed.\n"
				+ "With LuckPerms use the nodes tickengine.status, tickengine.top, tickengine.profile,\n"
				+ "tickengine.config. Range: 0-4.",
			0, 4, () -> commandOpLevel, v -> commandOpLevel = v));
		register(Option.bool("blockEntities.enabled",
			"Block entity throttling: machines, hoppers, furnaces etc. (vanilla and modded) that are far\n"
				+ "from every player tick only once every N ticks, so they run slower but cost less CPU.",
			() -> blockEntityThrottle, v -> blockEntityThrottle = v));
		register(Option.integer("blockEntities.activeRadiusChunks",
			"Block entities within this many chunks of a player always tick normally. Range: 1-32.",
			1, 32, () -> activeRadiusChunks, v -> activeRadiusChunks = v));
		register(Option.integer("blockEntities.defaultInterval",
			"Far block entities tick once every N ticks (1 = no throttling). Rules below override this.\n"
				+ "Prefer 2, 4, 5, 10 or 20: some blocks (beacons, vaults, trial spawners, possibly modded\n"
				+ "blocks) only act on game times divisible by 20 or 80, and other values can skip them.\n"
				+ "Range: 1-100.",
			1, 100, () -> defaultInterval, v -> defaultInterval = v));
		register(Option.bool("blockEntities.stagger",
			"false: all throttled block entities tick on the same game ticks (safe for blocks that act\n"
				+ "on game times divisible by 20 or 80). true: spread them over different ticks for a smoother\n"
				+ "MSPT, but such blocks may stop acting while throttled.",
			() -> stagger, v -> stagger = v));
		register(Option.integer("blockEntities.forcedChunkMaxInterval",
			"Force-loaded chunks without players (/forceload, chunk loaders that use it) never tick slower\n"
				+ "than once every N ticks. 1 = never throttle them. Note: mod chunk loaders that use their\n"
				+ "own loading tickets are treated like any other far chunk. Range: 1-100.",
			1, 100, () -> forcedChunkMaxInterval, v -> forcedChunkMaxInterval = v));
		register(Option.bool("blockEntities.dimensionRulesEnabled",
			"Use 'dimensionRules' below. When false, only 'rules' and 'defaultInterval' apply.",
			() -> dimensionRulesEnabled, v -> dimensionRulesEnabled = v));

		register(Option.bool("entities.enabled",
			"Entity throttling (players are never throttled). Three zones around the nearest player:\n"
				+ "- within fullRadiusChunks: everything normal\n"
				+ "- up to aiRadiusChunks: movement normal, AI and pathfinding once every 'ai' ticks\n"
				+ "- beyond: full tick once every 'tick' ticks; in between only timers advance (item and\n"
				+ "  mob despawn, portal cooldown, burning time), so items still vanish after 5 minutes.",
			() -> entityThrottle, v -> entityThrottle = v));
		register(Option.integer("entities.fullRadiusChunks",
			"Entities within this many chunks of a player are never throttled. Range: 0-32.",
			0, 32, () -> entityFullRadiusChunks, v -> entityFullRadiusChunks = v));
		register(Option.integer("entities.aiRadiusChunks",
			"Up to this many chunks only AI is throttled; beyond it the whole tick is. Entities only tick\n"
				+ "within the simulation distance, so keep this below it. Range: 0-32.",
			0, 32, () -> entityAiRadiusChunks, v -> entityAiRadiusChunks = v));
		register(Option.integer("entities.defaultAiInterval",
			"AI runs once every N ticks in the AI zone (1 = no throttling). Range: 1-100.",
			1, 100, () -> defaultAiInterval, v -> defaultAiInterval = v));
		register(Option.integer("entities.defaultTickInterval",
			"Full ticks happen once every N ticks beyond aiRadiusChunks (1 = no throttling).\n"
				+ "Aligned to each entity's own tick counter, so logic like 'every 40 ticks' keeps working\n"
				+ "with 2, 4, 5, 10 or 20. Range: 1-100.",
			1, 100, () -> defaultTickInterval, v -> defaultTickInterval = v));
		register(Option.integer("entities.forcedChunkMaxInterval",
			"In force-loaded chunks without players, AI and ticks are never slower than once every N\n"
				+ "ticks. 1 = never throttle them. Range: 1-100.",
			1, 100, () -> entityForcedChunkMaxInterval, v -> entityForcedChunkMaxInterval = v));
		register(Option.bool("entities.dimensionRulesEnabled",
			"Use 'dimensionRules' below. When false, only 'rules' and the defaults apply.",
			() -> entityDimensionRulesEnabled, v -> entityDimensionRulesEnabled = v));
		register(Option.bool("entities.excludeProjectiles",
			"Never throttle arrows, tridents, fireballs and other projectiles.",
			() -> excludeProjectiles, v -> excludeProjectiles = v));
		register(Option.bool("entities.excludeVehicles",
			"Never throttle entities that carry passengers (minecarts, boats, ridden mobs).",
			() -> excludeVehicles, v -> excludeVehicles = v));
		register(Option.bool("entities.excludeBosses",
			"Never throttle bosses: entity types in the 'c:bosses' tag (Ender Dragon, Wither and modded\n"
				+ "bosses that use the tag).",
			() -> excludeBosses, v -> excludeBosses = v));
		register(Option.bool("entities.excludeCombat",
			"Never throttle mobs in combat: hit by a player or mob (also with projectiles) in the last\n"
				+ "'combatSeconds', or with an attack target. Environmental damage (fire, cactus) does not count.",
			() -> excludeCombat, v -> excludeCombat = v));
		register(Option.integer("entities.combatSeconds",
			"How long a mob counts as in combat after being hit. Range: 1-120.",
			1, 120, () -> combatSeconds, v -> combatSeconds = v));
		register(Option.bool("entities.wakeFallingOrInFluid",
			"Tick normally while falling, in lava, or in water (water mobs like fish and drowned are not\n"
				+ "woken by water, since they live there).",
			() -> wakeFallingOrInFluid, v -> wakeFallingOrInFluid = v));
		register(Option.bool("entities.wakeNewEntities",
			"Tick normally for the first 'newEntitySeconds' after spawning or loading.",
			() -> wakeNewEntities, v -> wakeNewEntities = v));
		register(Option.integer("entities.newEntitySeconds",
			"See wakeNewEntities. Range: 1-120.",
			1, 120, () -> newEntitySeconds, v -> newEntitySeconds = v));
		register(Option.bool("entities.wakeBreeding",
			"Tick normally while an animal is in love or is a baby, so far animal farms breed and grow\n"
				+ "at normal speed.",
			() -> wakeBreeding, v -> wakeBreeding = v));

		register(Option.bool("autoTuning.enabled",
			"Adjusts throttling to the server load. Every 'decisionSeconds' it looks at the average MSPT:\n"
				+ "above 'raiseAboveMspt' it tightens one level, below 'lowerBelowMspt' it relaxes one level.\n"
				+ "Levels tighten, in order: throttle intervals, throttle radii, simulation distance, view distance.\n"
				+ "Runtime only: never written to this file or server.properties; restarts at level 0.",
			() -> autoTuning, v -> autoTuning = v));
		register(Option.integer("autoTuning.raiseAboveMspt",
			"Tighten one level when the average MSPT is above this (50 = below 20 TPS). Range: 10-200.",
			10, 200, () -> raiseAboveMspt, v -> raiseAboveMspt = v));
		register(Option.integer("autoTuning.lowerBelowMspt",
			"Relax one level when the average MSPT is below this. Keep it well below raiseAboveMspt to avoid\n"
				+ "flip-flopping. Range: 1-199.",
			1, 199, () -> lowerBelowMspt, v -> lowerBelowMspt = v));
		register(Option.integer("autoTuning.decisionSeconds",
			"Seconds between decisions; the average MSPT is taken over the same period. Range: 2-300.",
			2, 300, () -> decisionSeconds, v -> decisionSeconds = v));
		register(Option.bool("autoTuning.tuneIntervals",
			"Step 1: raise the default throttle intervals (block entities and entities) through safe values\n"
				+ "only: 2, 4, 5, 10, 20. Explicit rules are never changed.",
			() -> tuneIntervals, v -> tuneIntervals = v));
		register(Option.bool("autoTuning.tuneRadii",
			"Step 2: shrink the throttle radii around players by one chunk per level.",
			() -> tuneRadii, v -> tuneRadii = v));
		register(Option.bool("autoTuning.tuneSimulationDistance",
			"Step 3: lower the simulation distance by one per level. Skipped while ServerCore's dynamic\n"
				+ "settings are on (ServerCore manages the distances then).",
			() -> tuneSimulationDistance, v -> tuneSimulationDistance = v));
		register(Option.bool("autoTuning.tuneViewDistance",
			"Step 4: lower the view distance by one per level. Helps RAM and network more than CPU.\n"
				+ "Skipped while ServerCore's dynamic settings are on.",
			() -> tuneViewDistance, v -> tuneViewDistance = v));
		register(Option.integer("autoTuning.maxInterval",
			"Highest throttle interval auto-tuning may reach. Range: 2-100.",
			2, 100, () -> maxTunedInterval, v -> maxTunedInterval = v));
		register(Option.integer("autoTuning.minBlockEntityRadius",
			"Smallest block entity radius (chunks) auto-tuning may reach. Range: 0-32.",
			0, 32, () -> minBlockEntityRadius, v -> minBlockEntityRadius = v));
		register(Option.integer("autoTuning.minEntityFullRadius",
			"Smallest entity full-tick radius (chunks) auto-tuning may reach. Range: 0-32.",
			0, 32, () -> minEntityFullRadius, v -> minEntityFullRadius = v));
		register(Option.integer("autoTuning.minEntityAiRadius",
			"Smallest entity AI radius (chunks) auto-tuning may reach. Range: 0-32.",
			0, 32, () -> minEntityAiRadius, v -> minEntityAiRadius = v));
		register(Option.integer("autoTuning.minSimulationDistance",
			"Lowest simulation distance auto-tuning may set. Range: 2-32.",
			2, 32, () -> minSimulationDistance, v -> minSimulationDistance = v));
		register(Option.integer("autoTuning.minViewDistance",
			"Lowest view distance auto-tuning may set. Range: 2-32.",
			2, 32, () -> minViewDistance, v -> minViewDistance = v));
		register(Option.bool("autoTuning.notifyAdmins",
			"Tell admins in chat on every level change (always logged). Also needs notices.enabled.",
			() -> notifyTuningChanges, v -> notifyTuningChanges = v));

		register(Option.bool("compatibility.disableEntityThrottleWithServerCore",
			"Turn off TickEngine's entity throttling while ServerCore's entity activation range is on, so\n"
				+ "entities are not throttled twice. The profiler and block entity throttling keep running.",
			() -> disableEntityThrottleWithServerCore, v -> disableEntityThrottleWithServerCore = v));
		register(Option.bool("notices.enabled",
			"Tell admins about problems (like the ServerCore conflict) when they join and every\n"
				+ "'intervalMinutes'. Admins: operators at permissions.commandOpLevel or the tickengine.notify node.",
			() -> noticesEnabled, v -> noticesEnabled = v));
		register(Option.integer("notices.intervalMinutes",
			"Minutes between repeated notices. Range: 1-1440.",
			1, 1440, () -> noticeIntervalMinutes, v -> noticeIntervalMinutes = v));
	}

	static {
		// Moving pistons must finish their animation on time, or flying machines and door
		// mechanisms break when nobody is nearby.
		BLOCK_ENTITY_RULES.set("minecraft:piston", 1, null);
	}

	private TickEngineConfig() {
	}

	private static void register(Option<?> option) {
		OPTIONS.put(option.key, option);
	}

	public static Map<String, Option<?>> options() {
		return Collections.unmodifiableMap(OPTIONS);
	}

	/** Call after changing a value outside {@link #load()} (commands do this). */
	public static void changed() {
		version++;
		save();
	}

	/** Rebuilds caches derived from the config without saving, e.g. after an auto-tuning step. */
	public static void invalidateCaches() {
		version++;
	}

	/** Loads the file, keeping defaults for missing or invalid values, then rewrites it. */
	public static void load() {
		if (Files.exists(FILE)) {
			try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
				JsonReader json = new JsonReader(reader);
				json.setStrictness(Strictness.LENIENT);
				JsonObject root = JsonParser.parseReader(json).getAsJsonObject();
				for (Option<?> option : OPTIONS.values()) {
					JsonElement value = find(root, option.key);
					if (value != null) {
						String error = option.setFromJson(value);
						if (error != null) {
							TickEngine.LOGGER.warn("Config '{}': {} Using {}.", option.key, error, option.format());
						}
					}
				}
				JsonElement blockEntities = root.get("blockEntities");
				if (blockEntities != null && blockEntities.isJsonObject()) {
					BLOCK_ENTITY_RULES.read(blockEntities.getAsJsonObject());
				}
				JsonElement entities = root.get("entities");
				if (entities != null && entities.isJsonObject()) {
					ENTITY_RULES.read(entities.getAsJsonObject());
				}
			} catch (Exception e) {
				TickEngine.LOGGER.error("Could not read {}, using defaults for unreadable values", FILE, e);
			}
		}
		version++;
		save();
	}

	public static void save() {
		StringBuilder out = new StringBuilder();
		out.append("// TickEngine configuration (JSON5).\n");
		out.append("// Edit and run '/tickengine config reload', or change values live with '/tickengine config set'.\n");
		out.append("{\n");
		String currentSection = null;
		boolean first = true;
		for (Option<?> option : OPTIONS.values()) {
			String section = option.key.substring(0, option.key.indexOf('.'));
			String name = option.key.substring(option.key.indexOf('.') + 1);
			if (!section.equals(currentSection)) {
				if (currentSection != null) {
					writeSectionExtras(out, currentSection);
					out.append("\n\t},\n");
				}
				out.append("\t\"").append(section).append("\": {\n");
				currentSection = section;
				first = true;
			}
			if (!first) {
				out.append(",\n");
			}
			first = false;
			for (String line : option.comment.split("\n")) {
				out.append("\t\t// ").append(line).append('\n');
			}
			out.append("\t\t\"").append(name).append("\": ").append(option.toJson());
		}
		if (currentSection != null) {
			writeSectionExtras(out, currentSection);
			out.append("\n\t}\n");
		}
		out.append("}\n");
		try {
			Files.createDirectories(FILE.getParent());
			Files.writeString(FILE, out.toString(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			TickEngine.LOGGER.error("Could not write {}", FILE, e);
		}
	}

	/** Non-scalar settings written at the end of their section. */
	private static void writeSectionExtras(StringBuilder out, String section) {
		if (section.equals("blockEntities")) {
			out.append(",\n");
			BLOCK_ENTITY_RULES.write(out);
		} else if (section.equals("entities")) {
			out.append(",\n");
			ENTITY_RULES.write(out);
		}
	}

	private static JsonElement find(JsonObject root, String key) {
		String[] parts = key.split("\\.", 2);
		JsonElement section = root.get(parts[0]);
		if (section == null || !section.isJsonObject()) {
			return null;
		}
		return section.getAsJsonObject().get(parts[1]);
	}

	public static final class Option<T> {
		public final String key;
		public final String comment;
		private final Supplier<T> getter;
		private final Consumer<T> setter;
		/** Parses text into a value, or returns null when the text is not valid. */
		private final Function<String, T> parser;
		/** Returns an error message for an out-of-range value, or null when it is valid. */
		private final Function<T, String> validator;

		private Option(String key, String comment, Supplier<T> getter, Consumer<T> setter,
			Function<String, T> parser, Function<T, String> validator) {
			this.key = key;
			this.comment = comment;
			this.getter = getter;
			this.setter = setter;
			this.parser = parser;
			this.validator = validator;
		}

		static Option<Boolean> bool(String key, String comment, Supplier<Boolean> getter, Consumer<Boolean> setter) {
			return new Option<>(key, comment, getter, setter, text -> switch (text.toLowerCase()) {
				case "true", "on", "yes" -> true;
				case "false", "off", "no" -> false;
				default -> null;
			}, value -> null);
		}

		static Option<Integer> integer(String key, String comment, int min, int max,
			Supplier<Integer> getter, Consumer<Integer> setter) {
			return new Option<>(key, comment, getter, setter, text -> {
				try {
					return Integer.parseInt(text.trim());
				} catch (NumberFormatException e) {
					return null;
				}
			}, value -> value < min || value > max ? "must be between " + min + " and " + max + "." : null);
		}

		static Option<String> string(String key, String comment, Supplier<String> getter, Consumer<String> setter) {
			return new Option<>(key, comment, getter, setter, text -> text,
				value -> value.isBlank() ? "must not be empty." : null);
		}

		public String format() {
			return String.valueOf(getter.get());
		}

		String toJson() {
			T value = getter.get();
			if (value instanceof String text) {
				return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
			}
			return String.valueOf(value);
		}

		String setFromJson(JsonElement element) {
			if (!element.isJsonPrimitive()) {
				return "expected a single value.";
			}
			return set(element.getAsString());
		}

		/** Sets the value from text. Returns null on success, otherwise an error message. */
		public String set(String text) {
			T value = parser.apply(text);
			if (value == null) {
				return "'" + text + "' is not a valid value.";
			}
			String error = validator.apply(value);
			if (error != null) {
				return error;
			}
			setter.accept(value);
			return null;
		}
	}
}
