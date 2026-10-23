package com.studioderiva.tickengine.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.studioderiva.tickengine.TickEngine;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.resources.Identifier;

/**
 * A set of targets, written as a JSON array: type ids ({@code minecraft:villager}) and whole
 * mods ({@code somemod:*}), checked like rule targets.
 */
public final class TargetList {
	private final String key;
	private final String[] comment;
	private final Set<String> targets = new LinkedHashSet<>();

	TargetList(String key, String[] comment) {
		this.key = key;
		this.comment = comment;
	}

	public Set<String> targets() {
		return Collections.unmodifiableSet(targets);
	}

	/** True when the type itself or its whole mod is in the list. */
	public boolean matches(Identifier typeId) {
		return targets.contains(typeId.toString()) || targets.contains(typeId.getNamespace() + ":" + ThrottleRules.ALL);
	}

	/** Adds a target. Returns null on success (also when it was already there), otherwise an error message. */
	public String add(String target) {
		String error = ThrottleRules.validateTarget(target, false);
		if (error == null) {
			targets.add(target);
		}
		return error;
	}

	public boolean remove(String target) {
		return targets.remove(target);
	}

	void read(JsonObject section) {
		JsonElement json = section.get(key);
		if (json == null) {
			return;
		}
		if (!json.isJsonArray()) {
			TickEngine.LOGGER.warn("Config '{}' must be a list like [\"minecraft:villager\", \"somemod:*\"], ignored.", key);
			return;
		}
		targets.clear();
		for (JsonElement element : json.getAsJsonArray()) {
			String error = element.isJsonPrimitive() ? add(element.getAsString()) : "expected a text value.";
			if (error != null) {
				TickEngine.LOGGER.warn("Config '{}' entry {}: {} Ignored.", key, element, error);
			}
		}
	}

	void write(StringBuilder out) {
		for (String line : comment) {
			out.append("\t\t// ").append(line).append('\n');
		}
		out.append("\t\t\"").append(key).append("\": [");
		boolean first = true;
		for (String target : targets) {
			out.append(first ? "" : ", ").append('"').append(target).append('"');
			first = false;
		}
		out.append(']');
	}
}
