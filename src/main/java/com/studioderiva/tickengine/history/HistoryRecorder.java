package com.studioderiva.tickengine.history;

import com.studioderiva.tickengine.TickEngine;
import com.studioderiva.tickengine.config.TickEngineConfig;
import com.studioderiva.tickengine.profiler.ProfileSnapshot;
import com.studioderiva.tickengine.profiler.TickProfiler;
import com.studioderiva.tickengine.tuning.AutoTuner;
import com.studioderiva.tickengine.tuning.EmergencyMode;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * Writes the history: every tick is timed (two clock reads), and once per light profiler window (a
 * minute by default) a line with the average and longest tick, TPS, players, entities, chunks and
 * auto-tuning goes to the history file. A period is a peak when its average or its longest tick
 * passes a threshold, or auto-tuning went above a level; a peak also keeps the heaviest mods, types
 * and chunks of the light profiler window.
 *
 * <p>Optionally ({@code history.profilePeaks}, off by default) a peak starts a detailed session, which
 * measures every tick for a minute and is saved in the history as well. Server thread only.
 */
public final class HistoryRecorder {
	/** Mods, types and chunks kept per peak. */
	static final int TOP = 5;
	static final int AUTO_SESSION_SECONDS = 60;
	/** At most one automatic session in this time, so a long lag is not profiled from start to end. */
	static final long AUTO_SESSION_EVERY_MILLIS = 10 * 60_000L;
	/** Period length when the light profiler is off and gives no windows. */
	private static final long PERIOD_WITHOUT_PROFILER_MILLIS = 60_000L;
	private static final long PRUNE_EVERY_MILLIS = 60 * 60_000L;
	/**
	 * Time between two ticks after which the server is taken as stopped (a debugger, a suspended
	 * process) rather than lagging: a long single tick is not between ticks.
	 */
	private static final long PAUSE_MILLIS = 30_000L;

	private static long tickStartNanos;
	private static long tickStartMillis;
	private static long lastTickEndMillis;
	private static long periodStartMillis;
	private static long ticks;
	private static long tickNanos;
	private static long maxTickNanos;
	private static int maxTuningLevel;
	private static boolean emergency;
	private static ProfileSnapshot lastWindow;
	private static long lastPruneMillis;

	private static boolean autoSession;
	private static long autoSessionMaxNanos;
	private static List<String> autoSessionReasons = List.of();
	private static long nextAutoSessionMillis;

	private HistoryRecorder() {
	}

	public static void onTickStart() {
		tickStartNanos = System.nanoTime();
		tickStartMillis = System.currentTimeMillis();
	}

	public static void onTickEnd(MinecraftServer server) {
		long now = System.currentTimeMillis();
		if (!TickEngineConfig.historyEnabled) {
			startPeriod(now);
			return;
		}
		long nanos = System.nanoTime() - tickStartNanos;
		if (periodStartMillis == 0) {
			startPeriod(tickStartMillis);
		} else if (tickStartMillis - lastTickEndMillis >= PAUSE_MILLIS) {
			// The period ends where the pause began, so a paused server does not look like one at 0 TPS.
			closePeriod(server, null, lastTickEndMillis);
			startPeriod(tickStartMillis);
		}
		lastTickEndMillis = now;
		ticks++;
		tickNanos += nanos;
		maxTickNanos = Math.max(maxTickNanos, nanos);
		maxTuningLevel = Math.max(maxTuningLevel, AutoTuner.level());
		emergency |= EmergencyMode.isActive();
		if (autoSession) {
			autoSessionMaxNanos = Math.max(autoSessionMaxNanos, nanos);
		}
		maybeStartAutoSession(server, nanos, now);

		ProfileSnapshot window = TickProfiler.lastCompleteWindow();
		if (window != lastWindow) {
			lastWindow = window;
			if (window != null) {
				closePeriod(server, window, now);
			}
		} else if (now - periodStartMillis >= (TickEngineConfig.profilerEnabled
			? TickEngineConfig.windowSeconds * 1000L + 5000 : PERIOD_WITHOUT_PROFILER_MILLIS)) {
			closePeriod(server, null, now);
		}

		if (now - lastPruneMillis >= PRUNE_EVERY_MILLIS) {
			lastPruneMillis = now;
			HistoryStore.prune(TickEngineConfig.historyKeepDays);
		}
	}

	private static void startPeriod(long now) {
		periodStartMillis = now;
		ticks = 0;
		tickNanos = 0;
		maxTickNanos = 0;
		maxTuningLevel = AutoTuner.level();
		emergency = EmergencyMode.isActive();
	}

	/** {@code window}: the light profiler window that ended with this period, or null. */
	private static void closePeriod(MinecraftServer server, ProfileSnapshot window, long now) {
		int seconds = (int) Math.max(1, Math.round((now - periodStartMillis) / 1000.0));
		if (ticks > 0) {
			double avgMs = tickNanos / (double) ticks / 1_000_000.0;
			double maxMs = maxTickNanos / 1_000_000.0;
			List<String> peaks = peakReasons(avgMs, maxMs, maxTuningLevel);
			HistoryEntry.Detail detail = !peaks.isEmpty() && window != null && !window.isEmpty()
				? detail(window, "light profiler") : null;
			HistoryStore.append(entry(server, HistoryEntry.PERIOD, now, seconds, ticks, avgMs, maxMs, maxTuningLevel,
				emergency, peaks, detail));
		}
		startPeriod(now);
	}

	static List<String> peakReasons(double avgMs, double maxMs, int tuningLevel) {
		List<String> reasons = new ArrayList<>(3);
		if (avgMs >= TickEngineConfig.historyPeakAverageMspt) {
			reasons.add(HistoryEntry.AVERAGE);
		}
		if (maxMs >= TickEngineConfig.historyPeakTickMs) {
			reasons.add(HistoryEntry.SINGLE_TICK);
		}
		if (tuningLevel > TickEngineConfig.historyPeakTuningLevel) {
			reasons.add(HistoryEntry.TUNING);
		}
		return reasons;
	}

	private static void maybeStartAutoSession(MinecraftServer server, long tickNanos, long now) {
		if (!TickEngineConfig.historyProfilePeaks || autoSession || now < nextAutoSessionMillis
			|| TickProfiler.isSessionRunning()) {
			return;
		}
		// The server's average of the last 100 ticks: a peak now, not over the whole period.
		List<String> reasons = peakReasons((double) server.getAverageTickTime(), tickNanos / 1_000_000.0,
			AutoTuner.level());
		if (reasons.isEmpty()) {
			return;
		}
		int seconds = Math.min(AUTO_SESSION_SECONDS, TickEngineConfig.maxSessionSeconds);
		if (!TickProfiler.startSession(seconds, result -> finishAutoSession(server, result))) {
			return;
		}
		autoSession = true;
		autoSessionMaxNanos = tickNanos;
		autoSessionReasons = reasons;
		nextAutoSessionMillis = now + AUTO_SESSION_EVERY_MILLIS;
		TickEngine.LOGGER.info("History: peak ({}), detailed profiler started for {} s (history.profilePeaks)",
			String.join(", ", reasons), seconds);
	}

	private static void finishAutoSession(MinecraftServer server, ProfileSnapshot result) {
		autoSession = false;
		if (result.isEmpty()) {
			return;
		}
		long end = result.startMillis + result.durationMillis;
		HistoryStore.append(entry(server, HistoryEntry.SESSION, end, (int) Math.max(1, result.durationMillis / 1000),
			result.sampledTicks, result.averageTickMs(), autoSessionMaxNanos / 1_000_000.0, AutoTuner.level(),
			EmergencyMode.isActive(), autoSessionReasons, detail(result, "detailed session")));
	}

	private static HistoryEntry entry(MinecraftServer server, String kind, long end, int seconds, long tickCount,
		double avgMs, double maxMs, int tuningLevel, boolean emergencyActive, List<String> peaks, HistoryEntry.Detail detail) {
		int entities = 0;
		int chunks = 0;
		for (ServerLevel level : server.getAllLevels()) {
			for (Entity ignored : level.getAllEntities()) {
				entities++;
			}
			chunks += level.getChunkSource().getLoadedChunksCount();
		}
		return new HistoryEntry(kind, end, seconds, tickCount, avgMs, maxMs, tickCount / (double) seconds,
			server.getPlayerCount(), entities, chunks, tuningLevel, emergencyActive, List.copyOf(peaks), detail);
	}

	static HistoryEntry.Detail detail(ProfileSnapshot snapshot, String source) {
		List<HistoryEntry.Mod> mods = snapshot.mods().stream().limit(TOP)
			.map(mod -> new HistoryEntry.Mod(mod.displayName(), snapshot.msPerTick(mod.totalNanos()))).toList();
		List<HistoryEntry.Type> types = snapshot.entries.stream()
			.filter(entry -> entry.category() != ProfileSnapshot.Category.CHUNK).limit(TOP)
			.map(entry -> {
				BlockPos pos = entry.worstPos();
				boolean placed = pos != null && entry.worstDimension() != null;
				return new HistoryEntry.Type(entry.category().displayName, entry.id(), snapshot.msPerTick(entry.totalNanos()),
					placed ? entry.worstDimension().location().toString() : null, placed ? pos.getX() : 0,
					placed ? pos.getY() : 0, placed ? pos.getZ() : 0);
			}).toList();
		List<HistoryEntry.Chunk> chunks = snapshot.chunkTimes.stream().limit(TOP)
			.map(chunk -> new HistoryEntry.Chunk(chunk.dimension().location().toString(), chunk.x(), chunk.z(),
				chunk.worstPos().getX(), chunk.worstPos().getY(), chunk.worstPos().getZ(),
				chunk.worstCategory() != ProfileSnapshot.Category.ENTITY, snapshot.msPerTick(chunk.totalNanos()),
				chunk.topTypes().isEmpty() ? "" : chunk.topTypes().get(0).id()))
			.toList();
		return new HistoryEntry.Detail(source, mods, types, chunks);
	}

	public static void reset() {
		periodStartMillis = 0;
		lastTickEndMillis = 0;
		ticks = 0;
		tickNanos = 0;
		maxTickNanos = 0;
		lastWindow = null;
		lastPruneMillis = 0;
		autoSession = false;
		autoSessionReasons = List.of();
		nextAutoSessionMillis = 0;
	}
}
