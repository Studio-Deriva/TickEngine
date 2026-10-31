package com.studioderiva.tickengine.profiler;

import com.studioderiva.tickengine.config.TickEngineConfig;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * Hybrid tick profiler.
 *
 * <ul>
 *   <li><b>Light mode</b>: always on, measures one tick every {@code sampleEveryTicks} ticks and
 *   keeps rolling windows of {@code windowSeconds}.</li>
 *   <li><b>Sessions</b>: started on demand, measure every tick until stopped or timed out.</li>
 * </ul>
 *
 * <p>Tick timings run on the server thread, so no synchronization is used for them. On ticks that
 * are not sampled the mixins only read {@link #sampling}, which keeps the overhead near zero.
 * Chunk counts are kept over the whole window (chunks load between ticks too), and feature and
 * structure placement is timed on the worldgen threads into a synchronized {@link WorldgenWindow}.
 */
public final class TickProfiler {
	/** True while the current tick is being measured. Read by the mixins on every call. */
	public static boolean sampling;
	/**
	 * True while there is a window to count chunks and worldgen into (light profiler on, or a session).
	 * Volatile: worldgen threads read it.
	 */
	public static volatile boolean collecting;

	private static boolean lightSample;
	private static boolean inTick;
	private static long tickCounter;
	private static long tickStartNanos;
	private static Thread serverThread;
	private static MinecraftServer server;
	/** Time already counted by chunk work nested in the chunk work running now (server thread). */
	private static long chunkInnerNanos;

	private static ProfileWindow lightWindow = new ProfileWindow();
	private static ProfileSnapshot lastLightSnapshot;

	private static ProfileWindow session;
	private static long sessionEndMillis;
	private static Consumer<ProfileSnapshot> sessionListener;

	// Read by worldgen threads; the server thread swaps them when windows rotate or sessions end.
	private static volatile WorldgenWindow lightWorldgen = lightWindow.worldgen;
	private static volatile WorldgenWindow sessionWorldgen;

	private TickProfiler() {
	}

	public static void onTickStart(MinecraftServer server) {
		TickProfiler.server = server;
		serverThread = Thread.currentThread();
		tickCounter++;
		inTick = true;
		lightSample = TickEngineConfig.profilerEnabled && tickCounter % TickEngineConfig.sampleEveryTicks == 0;
		sampling = lightSample || session != null;
		collecting = TickEngineConfig.profilerEnabled || session != null;
		if (sampling) {
			tickStartNanos = System.nanoTime();
		}
	}

	public static void onTickEnd(MinecraftServer server) {
		if (sampling) {
			long nanos = System.nanoTime() - tickStartNanos;
			if (lightSample) {
				lightWindow.addTick(nanos);
			}
			if (session != null) {
				session.addTick(nanos);
			}
			sampling = false;
		}
		inTick = false;

		if (System.currentTimeMillis() - lightWindow.startMillis() >= TickEngineConfig.windowSeconds * 1000L) {
			rotateLightWindow();
		}
		if (session != null && System.currentTimeMillis() >= sessionEndMillis) {
			stopSession();
		}
	}

	private static void rotateLightWindow() {
		if (lightWindow.sampledTicks() > 0) {
			lastLightSnapshot = lightWindow.snapshot("Light profiler, last " + TickEngineConfig.windowSeconds + " s", registries());
		}
		lightWindow = new ProfileWindow();
		lightWorldgen = lightWindow.worldgen;
	}

	private static RegistryAccess registries() {
		return server == null ? null : server.registryAccess();
	}

	public static void recordBlockEntity(BlockEntity blockEntity, long nanos) {
		record(ProfileSnapshot.Category.BLOCK_ENTITY, blockEntity.getType(), nanos, blockEntity.getLevel(), blockEntity.getBlockPos());
	}

	public static void recordEntity(Entity entity, long nanos) {
		record(ProfileSnapshot.Category.ENTITY, entity.getType(), nanos, entity.level(), entity.blockPosition());
	}

	/**
	 * A timed call of a block, fluid or type while a tick is sampled. {@code key} is the registry
	 * object: a Block for block ticks, random ticks and block events, a Fluid for fluid ticks and
	 * fluid random ticks.
	 */
	public static void record(ProfileSnapshot.Category category, Object key, long nanos, Level level, BlockPos pos) {
		if (lightSample) {
			lightWindow.add(category, key, nanos, level, pos);
		}
		if (session != null) {
			session.add(category, key, nanos, level, pos);
		}
	}

	// --- chunks ---

	/** Chunk counts and chunk work are recorded on the server thread only (mods like C2ME move some of it off). */
	public static boolean isServerThread() {
		return Thread.currentThread() == serverThread;
	}

	/** Pass the result to {@link #endChunkWork} together with the start time. */
	public static long chunkWorkInner() {
		return chunkInnerNanos;
	}

	/**
	 * Ends a timed piece of chunk work on the server thread. Records its exclusive time (minus chunk
	 * work nested inside it) in the sampled tick, or as time between ticks when no tick is running.
	 */
	public static void endChunkWork(ChunkWork work, long startNanos, long innerBefore) {
		long elapsed = System.nanoTime() - startNanos;
		long self = Math.max(0, elapsed - (chunkInnerNanos - innerBefore));
		chunkInnerNanos = innerBefore + elapsed;
		if (sampling) {
			record(ProfileSnapshot.Category.CHUNK, work, self, null, null);
		} else if (!inTick && collecting) {
			if (TickEngineConfig.profilerEnabled) {
				lightWindow.addBetweenTicks(self);
			}
			if (session != null) {
				session.addBetweenTicks(self);
			}
		}
	}

	public static void chunkLoaded() {
		if (!collecting || !isServerThread()) {
			return;
		}
		if (TickEngineConfig.profilerEnabled) {
			lightWindow.chunkLoaded();
		}
		if (session != null) {
			session.chunkLoaded();
		}
	}

	public static void chunkCreated() {
		if (!collecting || !isServerThread()) {
			return;
		}
		if (TickEngineConfig.profilerEnabled) {
			lightWindow.chunkCreated();
		}
		if (session != null) {
			session.chunkCreated();
		}
	}

	/** A chunk finished generating, counted for the player nearest to it in its dimension. */
	public static void chunkGenerated(ServerLevel level, ChunkPos pos) {
		if (!collecting || !isServerThread()) {
			return;
		}
		String nearest = nearestPlayer(level, pos);
		if (TickEngineConfig.profilerEnabled) {
			lightWindow.chunkGenerated(nearest);
		}
		if (session != null) {
			session.chunkGenerated(nearest);
		}
	}

	private static String nearestPlayer(ServerLevel level, ChunkPos pos) {
		double x = pos.getMiddleBlockX();
		double z = pos.getMiddleBlockZ();
		ServerPlayer nearest = null;
		double best = Double.MAX_VALUE;
		for (ServerPlayer player : level.players()) {
			double dx = player.getX() - x;
			double dz = player.getZ() - z;
			double distance = dx * dx + dz * dz;
			if (distance < best) {
				best = distance;
				nearest = player;
			}
		}
		return nearest == null ? "nobody" : nearest.getScoreboardName();
	}

	/** Worldgen threads: a placed feature (one per feature per chunk). */
	public static void recordFeature(PlacedFeature feature, long nanos, Level level, BlockPos pos) {
		WorldgenWindow light = lightWorldgen;
		WorldgenWindow detailed = sessionWorldgen;
		if (TickEngineConfig.profilerEnabled && light != null) {
			light.addFeature(feature, nanos, level, pos);
		}
		if (detailed != null) {
			detailed.addFeature(feature, nanos, level, pos);
		}
	}

	/** Worldgen threads: the part of a structure that falls in one chunk. */
	public static void recordStructure(Structure structure, long nanos, Level level, BlockPos pos) {
		WorldgenWindow light = lightWorldgen;
		WorldgenWindow detailed = sessionWorldgen;
		if (TickEngineConfig.profilerEnabled && light != null) {
			light.addStructure(structure, nanos, level, pos);
		}
		if (detailed != null) {
			detailed.addStructure(structure, nanos, level, pos);
		}
	}

	/**
	 * Latest light profiler data: the last complete window, or the window in progress when
	 * no window has completed yet. Null when nothing has been sampled.
	 */
	public static ProfileSnapshot lightSnapshot() {
		if (lastLightSnapshot != null) {
			return lastLightSnapshot;
		}
		return lightWindow.sampledTicks() > 0 ? lightWindow.snapshot("Light profiler, current window", registries()) : null;
	}

	public static boolean isSessionRunning() {
		return session != null;
	}

	public static long sessionSecondsLeft() {
		return session == null ? 0 : Math.max(0, (sessionEndMillis - System.currentTimeMillis()) / 1000);
	}

	/** Starts a detailed session. The listener receives the result when it stops. */
	public static boolean startSession(int seconds, Consumer<ProfileSnapshot> listener) {
		if (session != null) {
			return false;
		}
		session = new ProfileWindow();
		sessionWorldgen = session.worldgen;
		sessionEndMillis = System.currentTimeMillis() + seconds * 1000L;
		sessionListener = listener;
		collecting = true;
		return true;
	}

	/** Stops the running session and hands the result to its listener. */
	public static boolean stopSession() {
		if (session == null) {
			return false;
		}
		ProfileSnapshot result = session.snapshot("Detailed session", registries());
		Consumer<ProfileSnapshot> listener = sessionListener;
		session = null;
		sessionWorldgen = null;
		sessionListener = null;
		listener.accept(result);
		return true;
	}

	/** Drops all data, e.g. when the server stops. */
	public static void reset() {
		sampling = false;
		collecting = false;
		lightSample = false;
		inTick = false;
		chunkInnerNanos = 0;
		server = null;
		lightWindow = new ProfileWindow();
		lightWorldgen = lightWindow.worldgen;
		lastLightSnapshot = null;
		session = null;
		sessionWorldgen = null;
		sessionListener = null;
	}
}
