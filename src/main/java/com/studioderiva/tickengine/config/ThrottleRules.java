package com.studioderiva.tickengine.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.studioderiva.tickengine.TickEngine;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.resources.Identifier;

/**
 * Per-mod and per-type throttle intervals, optionally per dimension.
 *
 * <p>Targets are either a type id ({@code minecraft:hopper}) or every type of a mod
 * ({@code create:*}). Inside a dimension, {@code *} matches every type. The most specific
 * match wins:
 * <ol>
 *   <li>dimension + type</li>
 *   <li>type</li>
 *   <li>dimension + mod</li>
 *   <li>mod</li>
 *   <li>dimension + {@code *}</li>
 *   <li>the default interval</li>
 * </ol>
 */
public final class ThrottleRules {
	public static final int MIN_INTERVAL = 1;
	public static final int MAX_INTERVAL = 100;
	public static final String ALL = "*";

	private final Map<String, Integer> rules = new LinkedHashMap<>();
	private final Map<String, Map<String, Integer>> dimensionRules = new LinkedHashMap<>();

	static ThrottleRules blockEntityDefaults() {
		ThrottleRules defaults = new ThrottleRules();
		// Moving pistons must finish their animation on time, or flying machines and door
		// mechanisms break when nobody is nearby.
		defaults.rules.put("minecraft:piston", 1);
		return defaults;
	}

	public Map<String, Integer> rules() {
		return Collections.unmodifiableMap(rules);
	}

	public Map<String, Map<String, Integer>> dimensionRules() {
		return Collections.unmodifiableMap(dimensionRules);
	}

	public int resolve(Identifier type, Identifier dimension, boolean useDimensionRules, int defaultInterval) {
		String typeKey = type.toString();
		String modKey = type.getNamespace() + ":" + ALL;
		Map<String, Integer> inDimension = useDimensionRules ? dimensionRules.get(dimension.toString()) : null;
		Integer interval;
		if (inDimension != null && (interval = inDimension.get(typeKey)) != null) {
			return interval;
		}
		if ((interval = rules.get(typeKey)) != null) {
			return interval;
		}
		if (inDimension != null && (interval = inDimension.get(modKey)) != null) {
			return interval;
		}
		if ((interval = rules.get(modKey)) != null) {
			return interval;
		}
		if (inDimension != null && (interval = inDimension.get(ALL)) != null) {
			return interval;
		}
		return defaultInterval;
	}

	/** Adds or replaces a rule. Returns null on success, otherwise an error message. */
	public String set(String target, int interval, String dimension) {
		String error = validate(target, dimension != null);
		if (error == null && dimension != null && Identifier.tryParse(dimension) == null) {
			error = "'" + dimension + "' is not a valid dimension id.";
		}
		if (error == null && (interval < MIN_INTERVAL || interval > MAX_INTERVAL)) {
			error = "interval must be between " + MIN_INTERVAL + " and " + MAX_INTERVAL + ".";
		}
		if (error != null) {
			return error;
		}
		if (dimension == null) {
			rules.put(target, interval);
		} else {
			dimensionRules.computeIfAbsent(dimension, key -> new LinkedHashMap<>()).put(target, interval);
		}
		return null;
	}

	public boolean remove(String target, String dimension) {
		if (dimension == null) {
			return rules.remove(target) != null;
		}
		Map<String, Integer> inDimension = dimensionRules.get(dimension);
		if (inDimension == null || inDimension.remove(target) == null) {
			return false;
		}
		if (inDimension.isEmpty()) {
			dimensionRules.remove(dimension);
		}
		return true;
	}

	/** Returns null when the target is valid, otherwise an error message. */
	static String validate(String target, boolean inDimension) {
		if (inDimension && target.equals(ALL)) {
			return null;
		}
		if (target.endsWith(":" + ALL)) {
			String namespace = target.substring(0, target.length() - 2);
			return Identifier.isValidNamespace(namespace) && !namespace.isEmpty() ? null
				: "'" + namespace + "' is not a valid mod id.";
		}
		return target.contains(":") && Identifier.tryParse(target) != null ? null
			: "'" + target + "' is not a valid target. Use a type id like minecraft:hopper or a mod like create:*"
				+ (inDimension ? " (or * for every type in the dimension)." : ".");
	}

	void read(JsonObject section) {
		JsonElement rulesJson = section.get("rules");
		if (rulesJson != null && rulesJson.isJsonObject()) {
			rules.clear();
			readMap(rulesJson.getAsJsonObject(), null);
		}
		JsonElement dimensionsJson = section.get("dimensionRules");
		if (dimensionsJson != null && dimensionsJson.isJsonObject()) {
			dimensionRules.clear();
			for (Map.Entry<String, JsonElement> dimension : dimensionsJson.getAsJsonObject().entrySet()) {
				if (dimension.getValue().isJsonObject()) {
					readMap(dimension.getValue().getAsJsonObject(), dimension.getKey());
				} else {
					TickEngine.LOGGER.warn("Config 'dimensionRules.{}' must be an object, ignored.", dimension.getKey());
				}
			}
		}
	}

	private void readMap(JsonObject json, String dimension) {
		for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
			String error;
			try {
				error = set(entry.getKey(), entry.getValue().getAsInt(), dimension);
			} catch (RuntimeException e) {
				error = "the interval must be a whole number.";
			}
			if (error != null) {
				TickEngine.LOGGER.warn("Config rule '{}'{}: {} Ignored.", entry.getKey(),
					dimension == null ? "" : " in " + dimension, error);
			}
		}
	}

	void write(StringBuilder out) {
		out.append("\t\t// Interval per type or per mod: \"minecraft:hopper\": 2, \"create:*\": 8. 1 = never throttle.\n");
		out.append("\t\t// The most specific rule wins (type before mod). Change live with '/tickengine rules'.\n");
		out.append("\t\t\"rules\": ");
		writeMap(out, rules, "\t\t");
		out.append(",\n");
		out.append("\t\t// Same as 'rules' but only in one dimension; \"*\" matches every type there. Used only when\n");
		out.append("\t\t// 'dimensionRulesEnabled' is true. Example: \"minecraft:the_nether\": { \"*\": 10, \"create:*\": 2 }\n");
		out.append("\t\t\"dimensionRules\": {");
		boolean first = true;
		for (Map.Entry<String, Map<String, Integer>> dimension : dimensionRules.entrySet()) {
			out.append(first ? "\n" : ",\n");
			first = false;
			out.append("\t\t\t\"").append(dimension.getKey()).append("\": ");
			writeMap(out, dimension.getValue(), "\t\t\t");
		}
		out.append(first ? "}" : "\n\t\t}");
	}

	private static void writeMap(StringBuilder out, Map<String, Integer> map, String indent) {
		if (map.isEmpty()) {
			out.append("{}");
			return;
		}
		out.append("{\n");
		boolean first = true;
		for (Map.Entry<String, Integer> entry : map.entrySet()) {
			if (!first) {
				out.append(",\n");
			}
			first = false;
			out.append(indent).append("\t\"").append(entry.getKey()).append("\": ").append(entry.getValue());
		}
		out.append('\n').append(indent).append('}');
	}
}
