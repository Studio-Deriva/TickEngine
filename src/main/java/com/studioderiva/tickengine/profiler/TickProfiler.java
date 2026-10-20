package com.studioderiva.tickengine.profiler;

import com.studioderiva.tickengine.config.TickEngineConfig;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Hybrid tick profiler.
 *
 * <ul>
 *   <li><b>Light mode</b>: always on, measures one tick every {@code sampleEveryTicks} ticks and
 *   keeps rolling windows of {@code windowSeconds}.</li>
 *   <li><b>Sessions</b>: started on demand, measure every tick until stopped or timed out.</li>
 * </ul>
 *
 * <p>Everything here runs on the server thread, so no synchronization is used. On ticks that
 * are not sampled the mixins only read {@link #sampling}, which keeps the overhead near zero.
 */
public final class TickProfiler {
	/** True while the current tick is being measured. Read by the mixins on every call. */
	public static boolean sampling;

	private static boolean lightSample;
	private static long tickCounter;
	private static long tickStartNanos;

	private static ProfileWindow lightWindow = new ProfileWindow();
	private static ProfileSnapshot lastLightSnapshot;

	private static ProfileWindow session;
	private static long sessionEndMillis;
	private static Consumer<ProfileSnapshot> sessionListener;

	private TickProfiler() {
	}

	public static void onTickStart(MinecraftServer server) {
		tickCounter++;
		lightSample = TickEngineConfig.profilerEnabled && tickCounter % TickEngineConfig.sampleEveryTicks == 0;
		sampling = lightSample || session != null;
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

		if (System.currentTimeMillis() - lightWindow.startMillis() >= TickEngineConfig.windowSeconds * 1000L) {
			rotateLightWindow();
		}
		if (session != null && System.currentTimeMillis() >= sessionEndMillis) {
			stopSession();
		}
	}

	private static void rotateLightWindow() {
		if (lightWindow.sampledTicks() > 0) {
			lastLightSnapshot = lightWindow.snapshot("Light profiler, last " + TickEngineConfig.windowSeconds + " s");
		}
		lightWindow = new ProfileWindow();
	}

	public static void recordBlockEntity(BlockEntity blockEntity, long nanos) {
		if (lightSample) {
			lightWindow.addBlockEntity(blockEntity.getType(), nanos, blockEntity.getLevel(), blockEntity.getBlockPos());
		}
		if (session != null) {
			session.addBlockEntity(blockEntity.getType(), nanos, blockEntity.getLevel(), blockEntity.getBlockPos());
		}
	}

	public static void recordEntity(Entity entity, long nanos) {
		if (lightSample) {
			lightWindow.addEntity(entity.getType(), nanos, entity.level(), entity.blockPosition());
		}
		if (session != null) {
			session.addEntity(entity.getType(), nanos, entity.level(), entity.blockPosition());
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
		return lightWindow.sampledTicks() > 0 ? lightWindow.snapshot("Light profiler, current window") : null;
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
		sessionEndMillis = System.currentTimeMillis() + seconds * 1000L;
		sessionListener = listener;
		return true;
	}

	/** Stops the running session and hands the result to its listener. */
	public static boolean stopSession() {
		if (session == null) {
			return false;
		}
		ProfileSnapshot result = session.snapshot("Detailed session");
		Consumer<ProfileSnapshot> listener = sessionListener;
		session = null;
		sessionListener = null;
		listener.accept(result);
		return true;
	}

	/** Drops all data, e.g. when the server stops. */
	public static void reset() {
		sampling = false;
		lightSample = false;
		lightWindow = new ProfileWindow();
		lastLightSnapshot = null;
		session = null;
		sessionListener = null;
	}
}
