package com.studioderiva.tickengine.network;

import net.minecraft.resources.Identifier;

/**
 * What a sent packet is about: an entity type, a block, a mod's network channel and so on.
 * Instances are cached per registry object, so equal sources are usually the same object.
 */
public record NetworkSource(Kind kind, String id, String namespace) {
	public enum Kind {
		ENTITY("Entity"),
		BLOCK_ENTITY("Block entity"),
		BLOCK("Block"),
		CHANNEL("Mod channel"),
		SOUND("Sound"),
		PARTICLE("Particle"),
		WORLD("Chunks and light"),
		OTHER("Other packet");

		public final String displayName;

		Kind(String displayName) {
			this.displayName = displayName;
		}
	}

	static NetworkSource of(Kind kind, Identifier id) {
		// Unregistered objects (rare, broken mods) still show up instead of crashing the profiler.
		return id == null ? new NetworkSource(kind, "unregistered", "unknown")
			: new NetworkSource(kind, id.toString(), id.getNamespace());
	}

	public String label() {
		return kind.displayName + " " + id;
	}
}
