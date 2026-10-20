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
	public static final ThrottleRules BLOCK_ENTITY_RULES = ThrottleRules.blockEntityDefaults();

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
