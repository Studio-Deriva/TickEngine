package com.studioderiva.tickengine.profiler;

/**
 * Chunk work the server thread does, timed as exclusive time: each kind excludes the other kinds
 * nested inside it, so the rows add up. Generation itself runs on worker threads and is counted
 * apart, in {@link ChunkActivity}.
 */
public enum ChunkWork {
	/** Turning saved chunk data into a chunk (blocks, block entities, ticks), on the server thread. */
	LOAD("Loading saved chunks"),
	/** Other chunk tasks: mostly turning loaded or generated chunks into full chunks (entities, block entities). */
	TASKS("Finishing chunks and other chunk tasks"),
	/** The server thread stopped until a chunk it asked for was loaded or generated. */
	WAIT("Waiting for chunks to load or generate"),
	/** Unloading chunks no longer in use and saving them. */
	UNLOAD("Unloading and saving chunks");

	public final String displayName;

	ChunkWork(String displayName) {
		this.displayName = displayName;
	}
}
