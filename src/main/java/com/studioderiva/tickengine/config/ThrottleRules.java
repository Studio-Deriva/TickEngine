package com.studioderiva.tickengine.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.studioderiva.tickengine.TickEngine;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.resources.Identifier;

/**
 * Per-mod and per-type throttle settings, optionally per dimension.
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
 *   <li>the default value</li>
 * </ol>
 *
 * @param <V> the value of a rule: one interval for block entities, an AI and a tick
 *            interval for entities
 */
public final class ThrottleRules<V> {
	public static final int MIN_INTERVAL = 1;
	public static final int MAX_INTERVAL = 100;
	public static final String ALL = "*";

	/** How rule values are read, written, checked and shown. */
	public interface ValueType<V> {
		/** Parses a config value. Throws on malformed input. */
		V fromJson(JsonElement json);

		String toJson(V value);

		/** Returns an error message, or null when the value is valid. */
		String validate(V value);

		/** Human-readable form for chat. */
		String describe(V value);
	}

	public static final ValueType<Integer> INTERVAL = new ValueType<>() {
		@Override
		public Integer fromJson(JsonElement json) {
			return json.getAsInt();
		}

		@Override
		public String toJson(Integer value) {
			return value.toString();
		}

		@Override
		public String validate(Integer value) {
			return validInterval(value) ? null : "interval must be between " + MIN_INTERVAL + " and " + MAX_INTERVAL + ".";
		}

		@Override
		public String describe(Integer value) {
			return describeInterval(value);
		}
	};

	/** AI and full-tick intervals of an entity rule. */
	public record EntityIntervals(int ai, int tick) {
	}

	public static final ValueType<EntityIntervals> ENTITY_INTERVALS = new ValueType<>() {
		@Override
		public EntityIntervals fromJson(JsonElement json) {
			JsonObject object = json.getAsJsonObject();
			return new EntityIntervals(object.get("ai").getAsInt(), object.get("tick").getAsInt());
		}

		@Override
		public String toJson(EntityIntervals value) {
			return "{ \"ai\": " + value.ai() + ", \"tick\": " + value.tick() + " }";
		}

		@Override
		public String validate(EntityIntervals value) {
			return validInterval(value.ai()) && validInterval(value.tick()) ? null
				: "ai and tick must be between " + MIN_INTERVAL + " and " + MAX_INTERVAL + ".";
		}

		@Override
		public String describe(EntityIntervals value) {
			return "AI " + describeInterval(value.ai()) + ", far tick " + describeInterval(value.tick());
		}
	};

	private final ValueType<V> type;
	private final String[] rulesComment;
	private final String[] dimensionRulesComment;
	private final Map<String, V> rules = new LinkedHashMap<>();
	private final Map<String, Map<String, V>> dimensionRules = new LinkedHashMap<>();

	ThrottleRules(ValueType<V> type, String[] rulesComment, String[] dimensionRulesComment) {
		this.type = type;
		this.rulesComment = rulesComment;
		this.dimensionRulesComment = dimensionRulesComment;
	}

	public ValueType<V> type() {
		return type;
	}

	public Map<String, V> rules() {
		return Collections.unmodifiableMap(rules);
	}

	public Map<String, Map<String, V>> dimensionRules() {
		return Collections.unmodifiableMap(dimensionRules);
	}

	public V resolve(Identifier typeId, Identifier dimension, boolean useDimensionRules, V defaultValue) {
		String typeKey = typeId.toString();
		String modKey = typeId.getNamespace() + ":" + ALL;
		Map<String, V> inDimension = useDimensionRules ? dimensionRules.get(dimension.toString()) : null;
		V value;
		if (inDimension != null && (value = inDimension.get(typeKey)) != null) {
			return value;
		}
		if ((value = rules.get(typeKey)) != null) {
			return value;
		}
		if (inDimension != null && (value = inDimension.get(modKey)) != null) {
			return value;
		}
		if ((value = rules.get(modKey)) != null) {
			return value;
		}
		if (inDimension != null && (value = inDimension.get(ALL)) != null) {
			return value;
		}
		return defaultValue;
	}

	/** Adds or replaces a rule. Returns null on success, otherwise an error message. */
	public String set(String target, V value, String dimension) {
		String error = validateTarget(target, dimension != null);
		if (error == null && dimension != null && Identifier.tryParse(dimension) == null) {
			error = "'" + dimension + "' is not a valid dimension id.";
		}
		if (error == null) {
			error = type.validate(value);
		}
		if (error != null) {
			return error;
		}
		if (dimension == null) {
			rules.put(target, value);
		} else {
			dimensionRules.computeIfAbsent(dimension, key -> new LinkedHashMap<>()).put(target, value);
		}
		return null;
	}

	public boolean remove(String target, String dimension) {
		if (dimension == null) {
			return rules.remove(target) != null;
		}
		Map<String, V> inDimension = dimensionRules.get(dimension);
		if (inDimension == null || inDimension.remove(target) == null) {
			return false;
		}
		if (inDimension.isEmpty()) {
			dimensionRules.remove(dimension);
		}
		return true;
	}

	/** Returns null when the target is valid, otherwise an error message. */
	static String validateTarget(String target, boolean inDimension) {
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

	private static boolean validInterval(int interval) {
		return interval >= MIN_INTERVAL && interval <= MAX_INTERVAL;
	}

	private static String describeInterval(int interval) {
		return interval == 1 ? "never throttled" : "every " + interval + " ticks";
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
				error = set(entry.getKey(), type.fromJson(entry.getValue()), dimension);
			} catch (RuntimeException e) {
				error = "malformed value " + entry.getValue() + ".";
			}
			if (error != null) {
				TickEngine.LOGGER.warn("Config rule '{}'{}: {} Ignored.", entry.getKey(),
					dimension == null ? "" : " in " + dimension, error);
			}
		}
	}

	void write(StringBuilder out) {
		for (String line : rulesComment) {
			out.append("\t\t// ").append(line).append('\n');
		}
		out.append("\t\t\"rules\": ");
		writeMap(out, rules, "\t\t");
		out.append(",\n");
		for (String line : dimensionRulesComment) {
			out.append("\t\t// ").append(line).append('\n');
		}
		out.append("\t\t\"dimensionRules\": {");
		boolean first = true;
		for (Map.Entry<String, Map<String, V>> dimension : dimensionRules.entrySet()) {
			out.append(first ? "\n" : ",\n");
			first = false;
			out.append("\t\t\t\"").append(dimension.getKey()).append("\": ");
			writeMap(out, dimension.getValue(), "\t\t\t");
		}
		out.append(first ? "}" : "\n\t\t}");
	}

	private void writeMap(StringBuilder out, Map<String, V> map, String indent) {
		if (map.isEmpty()) {
			out.append("{}");
			return;
		}
		out.append("{\n");
		boolean first = true;
		for (Map.Entry<String, V> entry : map.entrySet()) {
			if (!first) {
				out.append(",\n");
			}
			first = false;
			out.append(indent).append("\t\"").append(entry.getKey()).append("\": ").append(type.toJson(entry.getValue()));
		}
		out.append('\n').append(indent).append('}');
	}
}
