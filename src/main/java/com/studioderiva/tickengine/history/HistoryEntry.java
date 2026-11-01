package com.studioderiva.tickengine.history;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * One line of the history file: a period of server ticks (one light profiler window, a minute by
 * default) or a detailed session started by a peak. Peaks also carry the heaviest mods, types and
 * chunks of the period, so the history says why there was lag, not only that there was.
 *
 * @param endMillis when the period ended
 * @param seconds length of the period
 * @param avgMs average tick length, every tick measured
 * @param maxMs longest single tick
 * @param tuningLevel highest auto-tuning level reached in the period
 * @param peaks why the period counts as a peak ({@link #AVERAGE}, {@link #SINGLE_TICK}, {@link #TUNING}); empty when it does not
 * @param detail the heaviest things of a peak, or null
 */
public record HistoryEntry(String kind, long endMillis, int seconds, long ticks, double avgMs, double maxMs,
	double tps, int players, int entities, int chunks, int tuningLevel, boolean emergency, List<String> peaks,
	Detail detail) {

	public static final String PERIOD = "period";
	public static final String SESSION = "session";
	public static final String AVERAGE = "average";
	public static final String SINGLE_TICK = "single tick";
	public static final String TUNING = "auto-tuning";

	/**
	 * What took the tick time.
	 *
	 * @param source "light profiler" (one tick in 20) or "detailed session" (every tick)
	 */
	public record Detail(String source, List<Mod> mods, List<Type> types, List<Chunk> chunks) {
	}

	public record Mod(String name, double msPerTick) {
	}

	/** A type with its slowest place; {@code dimension} is null for work without a place. */
	public record Type(String category, String id, double msPerTick, String dimension, int x, int y, int z) {
	}

	/** A chunk with the place of its slowest call and the type that took most of its time. */
	public record Chunk(String dimension, int chunkX, int chunkZ, int x, int y, int z, boolean onTop, double msPerTick,
		String top) {
	}

	public boolean isPeak() {
		return !peaks.isEmpty();
	}

	public long startMillis() {
		return endMillis - seconds * 1000L;
	}

	JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.addProperty("kind", kind);
		json.addProperty("end", endMillis);
		json.addProperty("seconds", seconds);
		json.addProperty("ticks", ticks);
		json.addProperty("avgMs", round(avgMs));
		json.addProperty("maxMs", round(maxMs));
		json.addProperty("tps", round(tps));
		json.addProperty("players", players);
		json.addProperty("entities", entities);
		json.addProperty("chunks", chunks);
		json.addProperty("tuning", tuningLevel);
		json.addProperty("emergency", emergency);
		JsonArray peakArray = new JsonArray();
		peaks.forEach(peakArray::add);
		json.add("peaks", peakArray);
		if (detail != null) {
			JsonObject detailJson = new JsonObject();
			detailJson.addProperty("source", detail.source());
			JsonArray mods = new JsonArray();
			for (Mod mod : detail.mods()) {
				JsonObject modJson = new JsonObject();
				modJson.addProperty("name", mod.name());
				modJson.addProperty("ms", round(mod.msPerTick()));
				mods.add(modJson);
			}
			detailJson.add("mods", mods);
			JsonArray types = new JsonArray();
			for (Type type : detail.types()) {
				JsonObject typeJson = new JsonObject();
				typeJson.addProperty("category", type.category());
				typeJson.addProperty("id", type.id());
				typeJson.addProperty("ms", round(type.msPerTick()));
				if (type.dimension() != null) {
					typeJson.addProperty("dimension", type.dimension());
					typeJson.addProperty("x", type.x());
					typeJson.addProperty("y", type.y());
					typeJson.addProperty("z", type.z());
				}
				types.add(typeJson);
			}
			detailJson.add("types", types);
			JsonArray chunkArray = new JsonArray();
			for (Chunk chunk : detail.chunks()) {
				JsonObject chunkJson = new JsonObject();
				chunkJson.addProperty("dimension", chunk.dimension());
				chunkJson.addProperty("chunkX", chunk.chunkX());
				chunkJson.addProperty("chunkZ", chunk.chunkZ());
				chunkJson.addProperty("x", chunk.x());
				chunkJson.addProperty("y", chunk.y());
				chunkJson.addProperty("z", chunk.z());
				chunkJson.addProperty("onTop", chunk.onTop());
				chunkJson.addProperty("ms", round(chunk.msPerTick()));
				chunkJson.addProperty("top", chunk.top());
				chunkArray.add(chunkJson);
			}
			detailJson.add("chunks", chunkArray);
			json.add("detail", detailJson);
		}
		return json;
	}

	/** Reads a line written by {@link #toJson}. Throws on malformed input. */
	static HistoryEntry fromJson(JsonObject json) {
		List<String> peaks = new ArrayList<>();
		json.getAsJsonArray("peaks").forEach(element -> peaks.add(element.getAsString()));
		Detail detail = null;
		JsonElement detailElement = json.get("detail");
		if (detailElement != null && detailElement.isJsonObject()) {
			JsonObject detailJson = detailElement.getAsJsonObject();
			List<Mod> mods = new ArrayList<>();
			for (JsonElement element : detailJson.getAsJsonArray("mods")) {
				JsonObject mod = element.getAsJsonObject();
				mods.add(new Mod(mod.get("name").getAsString(), mod.get("ms").getAsDouble()));
			}
			List<Type> types = new ArrayList<>();
			for (JsonElement element : detailJson.getAsJsonArray("types")) {
				JsonObject type = element.getAsJsonObject();
				boolean placed = type.has("dimension");
				types.add(new Type(type.get("category").getAsString(), type.get("id").getAsString(), type.get("ms").getAsDouble(),
					placed ? type.get("dimension").getAsString() : null, placed ? type.get("x").getAsInt() : 0,
					placed ? type.get("y").getAsInt() : 0, placed ? type.get("z").getAsInt() : 0));
			}
			List<Chunk> chunks = new ArrayList<>();
			for (JsonElement element : detailJson.getAsJsonArray("chunks")) {
				JsonObject chunk = element.getAsJsonObject();
				chunks.add(new Chunk(chunk.get("dimension").getAsString(), chunk.get("chunkX").getAsInt(),
					chunk.get("chunkZ").getAsInt(), chunk.get("x").getAsInt(), chunk.get("y").getAsInt(), chunk.get("z").getAsInt(),
					chunk.get("onTop").getAsBoolean(), chunk.get("ms").getAsDouble(), chunk.get("top").getAsString()));
			}
			detail = new Detail(detailJson.get("source").getAsString(), mods, types, chunks);
		}
		return new HistoryEntry(json.get("kind").getAsString(), json.get("end").getAsLong(), json.get("seconds").getAsInt(),
			json.get("ticks").getAsLong(), json.get("avgMs").getAsDouble(), json.get("maxMs").getAsDouble(),
			json.get("tps").getAsDouble(), json.get("players").getAsInt(), json.get("entities").getAsInt(),
			json.get("chunks").getAsInt(), json.get("tuning").getAsInt(), json.get("emergency").getAsBoolean(), peaks, detail);
	}

	/** Two decimals are plenty and keep the file small. */
	private static double round(double value) {
		return Math.round(value * 100) / 100.0;
	}
}
